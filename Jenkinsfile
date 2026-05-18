pipeline {
    agent any

    environment {
        // You can define default environment variables here or in Jenkins global properties
        // CREDENTIAL_NAME = env.CREDENTIAL_NAME ?: 'my-git-credentials-id'
        DOCKER_REGISTRY = 'jeshupatelg'
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
                    sh 'cp $SECRET_ENV_FILE .env'
                    sh 'cp $SECRET_ENV_FILE docker/postgres/.env'
                    sh 'cp $SECRET_ENV_FILE docker/keycloak/.env'
                    sh 'cp $SECRET_ENV_FILE docker/jenkins/.env'
                    sh 'cp $SECRET_ENV_FILE docker/apigw/.env'
                }
            }
        }

        stage('Build api-gateway') {
            when {
                anyOf {
                    changeset "app/**"
                    changeset "pom.xml"
                    changeset "docker/apigw/**"
                }
            }
            steps {
                // Assuming Maven is installed in the agent or available in PATH.
                // Note: Change 'sh' to 'bat' if running on a Windows Jenkins agent.
                sh 'mvn -B clean install'
            }
        }

        stage('Build api-gateway image') {
            when {
                anyOf {
                    changeset "app/**"
                    changeset "pom.xml"
                    changeset "docker/apigw/**"
                }
            }
            steps {
                script {
                    def fullImageName = "${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                    
                    // Build the Docker image
                    sh "docker build -t ${fullImageName} -f docker/apigw/Dockerfile ."
                    
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
                dir('docker/network') {
                    // Run the network sh script
                    sh 'bash network-compose.sh'
                }
            }
        }

        stage('Deploy postgres') {
            steps {
                dir('docker/postgres') {
                    sh 'docker compose up -d'
                    sh '''
                        # Disable command tracing to prevent printing secrets
                        set +x
                        if [ -f .env ]; then
                            export $(cat .env | grep -v '^#' | xargs)
                        fi
                        # Re-enable command tracing
                        set -x
                        
                        DB_USER="${POSTGRES_USER}"
                        echo "Waiting for PostgreSQL to be ready on homeserver-pg (timeout 60s)..."
                        TIMEOUT=60
                        COUNTER=0
                        until docker exec homeserver-pg pg_isready -U "$DB_USER" >/dev/null 2>&1; do
                            if [ $COUNTER -ge $TIMEOUT ]; then
                                echo "ERROR: Timeout of ${TIMEOUT}s reached waiting for PostgreSQL to start!"
                                exit 1
                            fi
                            sleep 2
                            COUNTER=$((COUNTER + 2))
                        done
                        echo "PostgreSQL is ready!"
                        
                        # Execute SQL initialization scripts
                        bash scripts/run-sql.sh
                    '''
                }
            }
        }

        stage('Deploy api-gateway') {
            when {
                anyOf {
                    changeset "app/**"
                    changeset "pom.xml"
                    changeset "docker/apigw/**"
                }
            }
            steps {
                dir('docker/apigw') {
                    // Configuration files are now baked into the image in the 'Build api-gateway image' stage.
                    // Export APIGW_VERSION so docker compose knows to use the newly built image.
                    sh "APIGW_VERSION=${IMAGE_TAG} docker compose up -d"
                }
            }
        }

        stage('Deploy keycloak') {
            steps {
                dir('docker/keycloak') {
                    sh 'docker compose up -d'
                }
            }
        }

        stage('Deploy jenkins') {
            steps {
                dir('docker/jenkins') {
                    sh 'docker compose up -d'
                }
            }
        }
    }
}
