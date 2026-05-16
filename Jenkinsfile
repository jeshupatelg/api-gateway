pipeline {
    agent any

    environment {
        // You can define default environment variables here or in Jenkins global properties
        // CREDENTIAL_NAME = env.CREDENTIAL_NAME ?: 'my-git-credentials-id'
        DOCKER_REGISTRY = env.DOCKER_REGISTRY ?: 'jeshupatelg'
        IMAGE_NAME = 'apigw-homeserver'
        IMAGE_TAG = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                // For Multibranch Pipelines, 'checkout scm' automatically uses the credentials configured for the branch source.
                checkout scm
                
                // If you explicitly need to perform a custom checkout using the credential name from env:
                // git credentialsId: env.CREDENTIAL_NAME, url: 'your-repo-url.git', branch: env.BRANCH_NAME
            }
        }

        stage('Set env') {
            steps {
                // Fetch the .env file from Jenkins secret file credentials.
                // Replace 'api-gateway-env-secret' with your actual Secret File credential ID.
                withCredentials([file(credentialsId: 'apigw-env', variable: 'SECRET_ENV_FILE')]) {
                    // Copy to docker directory for docker-compose during deployment
                    sh 'cp $SECRET_ENV_FILE docker/.env'
                    
                    // Also copy to root directory in case Maven tests need these env vars to load the Spring context successfully
                    sh 'cp $SECRET_ENV_FILE .env'
                }
            }
        }

        stage('Build api-gateway') {
            steps {
                // Assuming Maven is installed in the agent or available in PATH.
                // Note: Change 'sh' to 'bat' if running on a Windows Jenkins agent.
                sh 'mvn clean install'
            }
        }

        stage('Build api-gateway image') {
            steps {
                script {
                    def fullImageName = "${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                    
                    // Build the Docker image
                    sh "docker build -t ${fullImageName} -f docker/Dockerfile ."
                    
                    // Push the Docker image
                    // Replace 'dockerhub-creds' with your actual Jenkins Username/Password credential ID for Docker Hub
                    withCredentials([usernamePassword(credentialsId: 'dockerhub-pat', passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {
                        sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                        sh "docker push ${fullImageName}"
                        //dckr_pat_6HctLyDzbdRRj8rGCHFwkfz7sNw
                    }
                }
            }
        }

        stage('Deploy network') {
            steps {
                dir('docker') {
                    // Run the network sh script
                    sh 'bash network-compose.sh'
                }
            }
        }

        stage('Deploy api-gateway') {
            steps {
                dir('docker') {
                    // Export APIGW_VERSION so docker compose knows to use the newly built image.
                    // Because the image tag changes, 'up -d' will automatically detect the change,
                    // stop the old container, and start the new one without needing 'down'.
                    sh "APIGW_VERSION=${IMAGE_TAG} docker compose -f apigw-compose.yaml up -d"
                }
            }
        }

        stage('Deploy keycloak') {
            steps {
                dir('docker') {
                    sh 'docker compose -f keycloak-compose.yaml up -d'
                }
            }
        }

        stage('Deploy jenkins') {
            steps {
                dir('docker') {
                    sh 'docker compose -f jenkins-compose.yaml up -d'
                }
            }
        }
    }
}
