// vars/createTagAndDeploy.groovy
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
        def buildArgs = []
        def team = config.team?: "sos"
        // Flag to control deployment
        def deploy = config.deploy ?: true
        def fieldPath = config.fieldpath ?: 'spec.template.spec.containers'
        def containerName = config.containerName ?: classname
        def subfolder = config.subfolder ?: '.'
        def applicationName = config.applicationName ?: classname

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
                (registryUrl, serviceAcc) = determineRegistry(tag, team)
                echo "Using registry: ${registryUrl}"
            }

            stage('Build Docker Image') {
                image = buildDockerImage(registryUrl, classname, dockerTag, buildArgs, config, team, subfolder)
                echo "Docker image built with tag: ${dockerTag}"
            }

            stage('Push Docker Image') {
                pushDockerImage(image, dockerTag, registryUrl, serviceAcc)
            }

            stage('Deploy via Argo') {
                if (config.get('deploy', true)) { // Default is true
                    echo "Deploying via ArgoCD..."
                    deployViaGitopsHelper(classname, registryUrl, dockerTag, repositoryUrl, filename, team, containerName, subfolder, applicationName)
                } else {
                    echo "Skipping deployment stage as deploy flag is set to false."
                }
            }

        // Cleanup Stage: remove old images from registry, keeping max 5 artifacts per patch version
        stage('Cleanup Docker Images') {
            script {
                // Determine repository name based on subfolder
                def repoName = (subfolder == '.') ? "${registryUrl}/cloudogu-backend/team-${team}/${classname}" :
                                                     "${registryUrl}/cloudogu-backend/team-${team}/${classname}/${subfolder}"
                echo "Cleaning up repository: ${repoName}"
                
                def listCmd = "gcloud container images list-tags ${repoName} --format=json"
                // Authenticate with gcloud using the service account key
                withCredentials([file(credentialsId: "ar-${team}-sf", variable: "GCLOUD_KEY_FILE")]) {
                    sh "gcloud auth activate-service-account --key-file=${GCLOUD_KEY_FILE}"
                    def jsonOutput = sh(script: listCmd, returnStdout: true).trim()
                    def artifacts = readJSON text: jsonOutput
                    
                    // Initialize groups for tagged images and list for untagged images
                    def groups = [:]
                    def untagged = []
                    
                    artifacts.each { artifact ->
                        def tagList = artifact.tag ?: []
                        if (tagList.size() == 0) {
                            // Artifact is untagged; add its digest for deletion
                            if (artifact.digest) {
                                untagged.add(artifact.digest)
                            }
                        } else {
                            tagList.each { t ->
                                if (t.contains('-')) {
                                    def tokens = t.split('-')
                                    if (tokens.size() >= 3) {
                                        // Group by the semantic version part (e.g. "3.2.10")
                                        def semver = tokens[0]
                                        groups[semver] = groups.get(semver, []) + [t]
                                    }
                                }
                            }
                        }
                    }
                    
                    // Delete untagged artifacts
                    if (untagged) {
                        echo "Deleting untagged artifacts with digests: ${untagged}"
                        untagged.each { digest ->
                            def deleteCmd = ""
                            if (registryUrl.contains("gcr.io")) {
                                deleteCmd = "gcloud container images delete ${repoName}@${digest} --quiet"
                            } else if (registryUrl.contains("pkg.dev")) {
                                deleteCmd = "gcloud artifacts docker images delete ${repoName}@${digest} --quiet"
                            }
                            echo "Deleting untagged image ${repoName}@${digest}"
                            sh(script: deleteCmd)
                        }
                    }
                    
                    // For each semantic version, sort by timestamp (second token) descending and delete excess tags
                    groups.each { semver, tagList ->
                        tagList = tagList.sort { a, b ->
                            def aTimestamp = a.split('-')[1]
                            def bTimestamp = b.split('-')[1]
                            return bTimestamp <=> aTimestamp
                        }
                        if (tagList.size() > 5) {
                            def tagsToDelete = tagList.drop(5)
                            echo "For semantic version ${semver}, deleting tags: ${tagsToDelete}"
                            tagsToDelete.each { t ->
                                def deleteCmd = ""
                                if (registryUrl.contains("gcr.io")) {
                                    deleteCmd = "gcloud container images delete ${repoName}:${t} --quiet"
                                } else if (registryUrl.contains("pkg.dev")) {
                                    deleteCmd = "gcloud artifacts docker images delete ${repoName}:${t} --quiet"
                                }
                                echo "Deleting image ${repoName}:${t}"
                                sh(script: deleteCmd)
                            }
                        }
                    }
                }
            }
        }

        } catch (Exception e) {
            echo "Pipeline failed: ${e.getMessage()}"
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            if (currentBuild.result == 'FAILURE') { 
                notifyBuildResult(dockerTag, registryUrl, webhookUrl)
            }
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

def determineRegistry(String tag, String team) {
    def registryUrl = tag.contains("+gcr") ? "eu.gcr.io" : "europe-docker.pkg.dev"
    def serviceAcc = tag.contains("+gcr") ? "gcloud-docker" : "ar-${team}"
    return [registryUrl, serviceAcc]
}

def buildDockerImage(String registryUrl, String classname, String dockerTag, List buildArgs, Map config , String team, String subfolder) {
    withCredentials([string(credentialsId: 'chatbot-github-pat', variable: 'GIT_API_KEY')]) {

        for (arg in config.buildArgs) {
            buildArgs.add("--build-arg ${arg}")
        }
        buildArgs.add("--build-arg GIT_API_KEY=${GIT_API_KEY}")
        def argsString = buildArgs.join(' ')
        echo "ARG STRING: " + argsString
        def uri = "${registryUrl}/cloudogu-backend/team-${team}/${classname}/${subfolder}:${dockerTag}"
        if (subfolder == '.') {
            uri = "${registryUrl}/cloudogu-backend/team-${team}/${classname}:${dockerTag}"
        }
        return docker.build(uri, "--no-cache ${argsString} .")
    }
}

def pushDockerImage(def image, String dockerTag, String registryUrl, String serviceAcc) {
    docker.withRegistry("https://${registryUrl}", "${serviceAcc}") {
        image.push(dockerTag)
        image.push(dockerTag.split('-')[0]) // Push the version without timestamp
        image.push('latest')
    }
}

def deployViaGitopsHelper(String classname, String registryUrl, String dockerTag, String repositoryUrl, String filename, String team, String containerName, String subfolder, String applicationName) {
    def imageName = "${registryUrl}/cloudogu-backend/team-${team}/${classname}/${subfolder}:${dockerTag}"
    if (subfolder == '.') {
            imageName = "${registryUrl}/cloudogu-backend/team-${team}/${classname}:${dockerTag}"
    }
        
    def gitopsConfig = [
        k8sVersion: "${env.K8S_VERSION_BC2}",
        scm: [
            provider: 'SCMManager',
            credentialsId: 'SCM-Manager',
            baseUrl: env.SCMM_URL,
            repositoryUrl: repositoryUrl,
        ],
        application: applicationName,
        gitopsTool: 'ARGO',
        folderStructureStrategy: 'ENV_PER_APP',
        deployments: [
            sourcePath: "apps/${applicationName}",
            destinationRootPath: 'apps',
            plain: [
                updateImages: [
                    [
                        filename: filename,
                        containerName: containerName,
                        imageName: imageName,
                    ]
                ]
            ]
        ],
        stages: [
            production: [
                namespace: '${team}',
                deployDirectly: true
            ]
        ],
    ]
    deployViaGitops(gitopsConfig)
}


def notifyBuildResult(String dockerTag, String registryUrl, String webhookUrl) {
    def messageText = "Pipeline ${env.JOB_NAME} #${env.BUILD_NUMBER} completed successfully. Docker image ${dockerTag} was pushed to ${registryUrl} and deployed with ArgoCD."
    if (currentBuild.result == 'FAILURE') {
        messageText = "🚨🚨🚨 *PIPELINE FAILURE* 🚨🚨🚨\n\nPipeline *${env.JOB_NAME}* #${env.BUILD_NUMBER} failed.\n<${env.BUILD_URL}console|View Jenkins Error Console Output>"
        messageTextclean = "🚨🚨🚨 *PIPELINE FAILURE* 🚨🚨🚨\n\nPipeline *${env.JOB_NAME}* #${env.BUILD_NUMBER} failed.\n<${env.BUILD_URL}console|View Jenkins Error Console Output>"
    }

    def message = [
        text: messageTextclean,
        formattedText: messageText
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
