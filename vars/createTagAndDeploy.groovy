// vars/createTagAndDeploy.groovy
//@Library('cloudogu/gitops-build-lib@0.6.0')
import com.cloudogu.gitops.gitopsbuildlib.*
import com.cloudogu.ces.cesbuildlib.*
import java.util.Collections

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

                // Cleanup Stage: Remove old images from registry, keeping max 5 detailed artifacts per patch version,
                // and deleting only artifacts that have no valid version tag (neither detailed nor simple).
                stage('Cleanup Docker Images') {
                    script {
                        // Determine repository name based on subfolder
                        def repoName = (subfolder == '.') ? "${registryUrl}/cloudogu-backend/team-${team}/${classname}" :
                                                             "${registryUrl}/cloudogu-backend/team-${team}/${classname}/${subfolder}"
                        echo "Cleaning up repository: ${repoName}"

                        def listCmd = "gcloud container images list-tags ${repoName} --format=json"

                        // Use credentials to authenticate with gcloud
                        withCredentials([file(credentialsId: "ar-${team}-sf", variable: "GCLOUD_KEY_FILE")]) {
                            sh "gcloud auth activate-service-account --key-file=${GCLOUD_KEY_FILE}"
                            // sh "gcloud auth activate-service-account --key-file=${GCLOUD_KEY_FILE}"
                            def jsonOutput = sh(script: listCmd, returnStdout: true).trim()
                            def artifacts = readJSON text: jsonOutput

                            // Define regex patterns
                            def detailedTagPattern = ~/^[0-9]+\.[0-9]+\.[0-9]+-\d{12}-[0-9a-f]+$/  // E.g., "1.0.0-202502281019-6160697"
                            def simpleTagPattern = ~/^[0-9]+\.[0-9]+\.[0-9]+$/  // E.g., "1.0.0"

                            // Build a map: digest -> list of detailed & simple tags
                            echo "Build a map: digest -> list of detailed & simple tags"
                            def artifactsByDigest = [:]
                            artifacts.each { artifact ->
                                def digest = artifact.digest
                                def tagList = artifact.tags ?: []
                                def validTags = tagList.findAll { it ==~ detailedTagPattern || it ==~ simpleTagPattern }
                                artifactsByDigest[digest] = validTags
                            }

                            // Prepare groups for artifacts with detailed tags
                            echo "Prepare groups for artifacts with detailed tags"
                            def groups = [:]
                            def invalidArtifacts = []

                            artifactsByDigest.each { digest, validTags ->
                                if (validTags.isEmpty()) {
                                    // Artifact has no valid version tag (neither detailed nor simple), delete it
                                    invalidArtifacts.add(digest)
                                } else {
                                    // Group artifacts by patch version from detailed tags
                                    validTags.findAll { it ==~ detailedTagPattern }.each { t ->
                                        def semver = t.split('-')[0]  // Extract patch version (e.g. "1.0.0" from "1.0.0-202502281019-6160697")
                                        groups[semver] = groups.get(semver, []) + [t]
                                    }
                                }
                            }

                            // Delete truly invalid artifacts
                            echo "Delete truly invalid artifacts"
                            if (!invalidArtifacts.isEmpty()) {
                                echo "Deleting invalid artifacts (no valid version tag found): ${invalidArtifacts}"
                                invalidArtifacts.each { digest ->
                                    def deleteCmd = "gcloud container images delete ${repoName}@${digest} --quiet --force-delete-tags"
                                    echo "Deleting untagged/invalid image ${repoName}@${digest}"
                                    sh(script: deleteCmd)
                                }
                            }

                            // Prune detailed artifacts per patch version
                            echo "Prune detailed artifacts per patch version"
                                groups.each { semver, tagList ->
                                    tagList = tagList as List

                                    // Filter out invalid tags
                                    tagList = tagList.findAll { it.contains('-') }

                                    // Call sorting function (Jenkins CPS-safe!)
                                    tagList = sortTags(tagList)

                                    // Prevent the .size() call on non-list values
                                    if (tagList instanceof List && tagList.size() > 5) {
                                        def tagsToDelete = tagList.drop(5)
                                        echo "For semantic version ${semver}, deleting older tags: ${tagsToDelete}"
                                        tagsToDelete.each { t ->
                                            def deleteCmd = "gcloud container images delete ${repoName}:${t} --quiet --force-delete-tags"
                                            echo "Deleting older image ${repoName}:${t}"
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
    def remoteHost = sh(
        script: "echo \"\$(git config --get remote.origin.url)\" | sed -E 's#^(https?://|git@)([^/:]+).*#\\2#'",
        returnStdout: true
    ).trim()
    echo "Remote host is: ${remoteHost}"
    if (remoteHost.equals("github.com")) {
        withCredentials([usernamePassword(credentialsId: 'github-pat-read-all-repos', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
            echo "Using sos-automat PAT (github-pat-read-all-repos) to fetch tags"
            sh(
                script: 'git -c http.extraheader="Authorization: Basic $(printf "%s:%s" "$GITHUB_USER" "$GITHUB_TOKEN" | base64 -w0)" fetch --tags',
                returnStdout: false
            )
        }
    }
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

// Define a helper method outside of the CPS-transformed block
@NonCPS
def sortTags(List<String> tagList) {
    return tagList
        .findAll { it.contains('-') } // Filter out invalid tags
        .sort { a, b ->
            def aTimestamp = a.split('-')[1]
            def bTimestamp = b.split('-')[1]
            return bTimestamp <=> aTimestamp // Sort descending
        }
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

/**
 * Very small, self-contained GitOps deploy function.
 * No metaClass, no overwriting foreign libs, no magic.
 * 100% predictable / safe.
 */
def deployViaGitopsSafe(Map cfg) {
    echo "🚀 Running deployViaGitopsSafe()"

    // --- Basic validation ---
    if (!cfg.scm?.repositoryUrl) error "Missing scm.repositoryUrl"
    if (!cfg.application)       error "Missing application name"
    if (!cfg.stages)            error "Missing stages"
    if (!cfg.deployments?.plain?.updateImages)
        error "Only plain mode supported (no updateImages)"

    // --- Fix GitOps repo URL format ---
    def repoUrl = cfg.scm.repositoryUrl.contains("://")
        ? cfg.scm.repositoryUrl
        : "${cfg.scm.baseUrl}/repo/${cfg.scm.repositoryUrl}.git"

    def branch = cfg.mainBranch ?: "main"

    // --- Create Git instance (REAL methods work here) ---
    def cesBuildLib = initCesBuildLib('https://github.com/cloudogu/ces-build-lib', '4.4.0', cfg.scm.credentialsId ?: '')
    // def git = new cesBuildLib.Git(this, cfg.scm.credentialsId ?: '')

    //def git = new com.cloudogu.ces.cesbuildlib.Git(this, )
    def git = new com.cloudogu.ces.cesbuildlib.Git(this, cfg.scm.credentialsId ?: '')

    git.committerName  = "Jenkins"
    git.committerEmail = "jenkins@cloudogu.com"

    // -----------------------------------------------------------------
    // SAFE WRAPPERS (NEVER override Git methods — just wrap them!)
    // -----------------------------------------------------------------
    def safePull = { String refSpec = '' ->
        echo "🔧 SAFE pull (no-rebase) ${refSpec}"
        git.executeGitWithCredentials("pull --no-rebase ${refSpec}")
    }

    def safePush = { String refSpec = '' ->
        echo "⬆️  SAFE push (${refSpec})"

        try {
            git.executeGitWithCredentials("push ${refSpec}")
        } catch (Exception e) {
            echo "⚠️ Push failed → fallback pull+retry"
            git.executeGitWithCredentials("pull --no-rebase ${refSpec}")
            git.executeGitWithCredentials("push ${refSpec}")
        }
    }

    // --- Create workspace ---
    def tempDir = ".gitops-tmp"
    sh "rm -rf ${tempDir}"
    sh "mkdir -p ${tempDir}"

    dir(tempDir) {

        // --- Clone repo ---
        echo "📥 Cloning GitOps repo: ${repoUrl}"
        git url: repoUrl, branch: branch, changelog: false, poll: false
        git.fetch()

        def changes = []

        // -----------------------------------------------------------------
        // PROCESS EACH STAGE
        // -----------------------------------------------------------------
        cfg.stages.each { stageName, stageCfg ->

            echo "=== 🔹 Stage: ${stageName} ==="

            // Ensure correct branch
            git.checkoutOrCreate(branch)
            safePull("origin ${branch}")

            // Update image references under k8s/<stage>/
            cfg.deployments.plain.updateImages.each { upd ->

                def yamlPath = "${cfg.deployments.sourcePath}/${stageName}/${upd.filename}"
                echo "📄 Loading YAML: ${yamlPath}"

                if (!fileExists(yamlPath)) {
                    error "YAML file not found: ${yamlPath}"
                }

                def yaml = readYaml(file: yamlPath)

                def container = yaml.spec.template.spec.containers.find { it.name == upd.containerName }
                if (!container) {
                    echo "⚠️ Container ${upd.containerName} not found → skipped"
                } else {
                    echo "📝 Updating: ${upd.containerName} → ${upd.imageName}"
                    container.image = upd.imageName
                    writeYaml(file: yamlPath, data: yaml, overwrite: true)
                }
            }

            // COMMIT & PUSH IF NEEDED
            git.add('.')

            if (!git.areChangesStagedForCommit()) {
                echo "ℹ️ No changes for stage '${stageName}'"
                return
            }

            git.commit(
                "[${stageName}] Update images for ${cfg.application}",
                "jenkins@cloudogu.com",
                "Jenkins"
            )

            safePush("origin ${branch}")

            changes << "${stageName}:${git.commitHashShort}"
        }

        echo "🎉 GitOps changes: ${changes ?: 'none'}"
        currentBuild.description = "GitOps: ${changes.join(', ')}"
    }

    sh "rm -rf ${tempDir}"
}

protected initCesBuildLib(cesBuildLibRepo, cesBuildLibVersion, credentialsId) {
    Map retrieverParams = [$class: 'GitSCMSource', remote: cesBuildLibRepo]
    if (credentialsId?.trim()) {
        retrieverParams << [credentialsId: credentialsId]
    }

    return library(identifier: "ces-build-lib@${cesBuildLibVersion}",
        retriever: modernSCM(retrieverParams)
    ).com.cloudogu.ces.cesbuildlib
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
    deployViaGitopsSafe(gitopsConfig)
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
