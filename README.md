# jenkins-docker-image-updater


### Use Example
```groovy
@Library('github.com/cloudogu/gitops-build-lib@0.6.0')
import com.cloudogu.gitops.gitopsbuildlib.*
@Library('github.com/cloudogu/jenkins-deploy-helper-lib@main') _

node('docker') {
    properties([
        buildDiscarder(logRotator(numToKeepStr: '5')),
        disableConcurrentBuilds(),
        pipelineTriggers([cron('H H(2-4) * * *')])
    ])
    
    // Load credentials for the webhook URL from Jenkins credentials store
    withCredentials([string(credentialsId: 'jenkins-pipeline-notifier-webhookurl', variable: 'WEBHOOK')]) {
        // Execute the common pipeline function from the shared library
        createTagAndDeploy(
            classname: 'esti-mate', // Define the classname for your specific application
            webhook: "${WEBHOOK}", // Use the loaded webhook URL for notifications
            repositoryUrl: 'sos/gitops', // Set the GitOps repository URL
            filename: 'deployment.yaml', // Specify the filename used in the deployment step
            buildArgs: '',
            team: 'sos'
        )
    }
}
