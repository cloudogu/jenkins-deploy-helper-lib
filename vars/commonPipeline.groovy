// vars/commonPipeline.groovy
//@Library('cloudogu/gitops-build-lib@0.6.0')
import com.cloudogu.gitops.gitopsbuildlib.*

// Define a function that encapsulates the shared pipeline logic
def call(Map config) {

        def image
        def tag
        def dockerTag
        def dockerTagWithoutTimestamp
        def registry
        def registryUrl
        def classname = config.classname ?: "default-classname"
        def serviceAcc
        def version
        def webhookUrl = config.webhook ?: 'default-webhook-url'
        def repositoryUrl = config.repositoryUrl ?: 'default-repository-url'
        def filename = config.filename ?: 'deployment.yaml'
        def buildArgs = ["--build-arg ", config.buildArgs ?: '']

        try {
            
            stage('Initialize Workspace') {
                deleteDir()
            }

            stage('Checkout Code') {
                checkout scm
            }

            stage('Retrieve Latest Tag') {
                tag = getLatestTag()
                echo "Found latest tag: ${tag}"
            }

            stage('Checkout Tag') {
                checkoutTag(tag)
                echo "Checked out tag: ${tag}"
            }

            stage('Generate Docker Tag') {
                (dockerTag, version) = generateDockerTag(tag)
                echo "Generated Docker tag: ${dockerTag}"
            }

            stage('Determine Registry') {
                (registryUrl, serviceAcc) = determineRegistry(tag)
                echo "Using registry: ${registryUrl}"
            }

            stage('Build Docker Image') {
                image = buildDockerImage(registryUrl, classname, dockerTag, buildArgs)
                echo "Docker image built with tag: ${dockerTag}"
            }

            stage('Push Docker Image') {
                pushDockerImage(image, dockerTag, registryUrl, serviceAcc)
            }

            stage('Deploy via Argo') {
                deployViaGitopsHelper(classname, registryUrl, dockerTag, repositoryUrl, filename)
            }
            
        } catch (Exception e) {
            echo "Pipeline failed: ${e.getMessage()}"
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            notifyBuildResult(dockerTag, registryUrl, webhookUrl)
        }
    }

// Helper functions to handle stages and reusable logic
def getLatestTag() {
    def gitTags = sh(
        script: "git tag -l --sort=-v:refname",
        returnStdout: true
    ).trim().split("\n")
    def latestTag = gitTags.find { it ==~ /^v[0-9]+\.[0-9]+\.[0-9]+(\+.+)?$/ }
    if (!latestTag) {
        error("No valid semver tag found")
    }
    return latestTag
}

def checkoutTag(String tag) {
    checkout([
        $class: 'GitSCM',
        branches: [[name: "refs/tags/${tag}"]],
        userRemoteConfigs: scm.userRemoteConfigs
    ])
}

def generateDockerTag(String tag) {
    def dockerTagWithoutTimestamp = tag.replaceAll(/\+.*$/, '').replaceFirst(/^v/, '')
    def version = computeVersion(tag)
    def dockerTag = "${dockerTagWithoutTimestamp}-${version}"
    return [dockerTag, version]
}

def determineRegistry(String tag) {
    def registryUrl = tag.contains("+gar") ? "europe-docker.pkg.dev" : "eu.gcr.io"
    def serviceAcc = tag.contains("+gar") ? "ar-sos" : "gcloud-docker"
    return [registryUrl, serviceAcc]
}

def buildDockerImage(String registryUrl, String classname, String dockerTag, List buildArgs) {
    withCredentials([string(credentialsId: 'chatbot-github-pat', variable: 'GIT_API_KEY')]) {

        buildArgs.add("GIT_API_KEY=${GIT_API_KEY}")
        def argsString = "[" + buildArgs.join(', ') + "]"
        echo "ARG STRING:" + argsString
        return docker.build("${registryUrl}/cloudogu-backend/team-sos/${classname}:${dockerTag}", "--no-cache ${argsString} .")
    }
}

def pushDockerImage(def image, String dockerTag, String registryUrl, String serviceAcc) {
    docker.withRegistry("https://${registryUrl}", "${serviceAcc}") {
        image.push(dockerTag)
        image.push(dockerTag.split('-')[0]) // Push the version without timestamp
        image.push('latest')
    }
}

def deployViaGitopsHelper(String classname, String registryUrl, String dockerTag, String repositoryUrl, String filename) {
    def gitopsConfig = [
        k8sVersion: "${env.K8S_VERSION_BC2}",
        scm: [
            provider: 'SCMManager',
            credentialsId: 'SCM-Manager',
            baseUrl: env.SCMM_URL,
            repositoryUrl: repositoryUrl,
        ],
        application: classname,
        gitopsTool: 'ARGO',
        folderStructureStrategy: 'ENV_PER_APP',
        deployments: [
            sourcePath: "apps/${classname}",
            destinationRootPath: 'apps',
            plain: [
                updateImages: [
                    [
                        filename: filename,
                        containerName: classname,
                        imageName: "${registryUrl}/cloudogu-backend/team-sos/${classname}:${dockerTag}"
                    ]
                ]
            ]
        ],
        stages: [
            production: [
                namespace: 'sos',
                deployDirectly: true
            ]
        ],
    ]
    deployViaGitops(gitopsConfig)
}

def notifyBuildResult(String dockerTag, String registryUrl, String webhookUrl) {
    def messageText = "Pipeline ${env.JOB_NAME} #${env.BUILD_NUMBER} completed successfully. Docker image ${dockerTag} was pushed to ${registryUrl} and deployed with ArgoCD."
    if (currentBuild.result == 'FAILURE') {
        messageText = "*🚨🚨🚨 **PIPELINE FAILURE** 🚨🚨🚨*\n\nPipeline ${env.JOB_NAME} #${env.BUILD_NUMBER} failed.\n[View Console Output](${env.BUILD_URL}console)."
    }

    def message = [
        text: messageText
    ]

    try {
        def response = httpRequest(
            httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: groovy.json.JsonOutput.toJson(message),
            url: webhookUrl
        )
        echo "Notification sent to Google Chat: ${response.status} ${response.content}"
    } catch (Exception notifyError) {
        echo "Failed to send notification to Google Chat: ${notifyError.getMessage()}"
    }
}

def computeVersion(String tag) {
    def commitHashShort = sh(returnStdout: true, script: "git rev-parse --short ${tag}").trim()
    return "${new Date().format('yyyyMMddHHmm')}-${commitHashShort}"
}
