/**
 * Shared pipeline for every iCollect service: build -> test -> (SonarQube) -> Docker image -> Trivy -> push to ECR
 * -> (optional) commit the new image tag to the GitOps repository.
 *
 * Usage in a service's Jenkinsfile:
 *     @Library('icollect-shared-lib') _
 *     icollectPipeline(service: 'customer-service', language: 'java')
 *
 * language: java | node | python | static
 *
 * Settings come from Jenkins global environment variables (Manage Jenkins > System):
 *     AWS_ACCOUNT_ID       required
 *     AWS_REGION           optional, default ap-south-1
 *     AWS_CREDENTIALS_ID   optional id of a "Username with password" credential
 *                          (username = access key id, password = secret access key).
 *                          Leave unset when Jenkins runs on EC2 with an instance role.
 *     GITOPS_REPO_URL      optional, e.g. https://github.com/<user>/icollect-gitops.git
 *                          When set, the pipeline commits the new image tag there (Update GitOps stage).
 *     GITOPS_CREDENTIALS_ID  id of a "Username with password" credential
 *                          (username = GitHub user, password = personal access token with write access)
 */
def call(Map cfg) {
    // ---- fixed settings --------------------------------------------------------------
    final String TRIVY_VERSION = '0.57.1'
    final String service = cfg.service
    final String language = cfg.language
    final List pushBranches = cfg.pushBranches ?: ['main']

    // Language-specific settings for the SonarQube scanner
    final Map sonarProps = [
        java  : '-Dsonar.sources=src/main/java -Dsonar.tests=src/test/java -Dsonar.java.binaries=target/classes',
        node  : '-Dsonar.sources=server.js -Dsonar.tests=test',
        python: '-Dsonar.sources=main.py -Dsonar.tests=tests',
        static: '-Dsonar.sources=index.html'
    ]

    pipeline {
        agent any

        options {
            timestamps()
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds()
        }

        parameters {
            booleanParam(name: 'RUN_SONAR', defaultValue: false,
                         description: 'Run SonarQube analysis and enforce the quality gate (needs a SonarQube server)')
            booleanParam(name: 'PUSH_IMAGE', defaultValue: true,
                         description: 'Push the image to ECR (uncheck for a build-and-scan dry run)')
        }

        environment {
            AWS_REGION_EFFECTIVE = "${env.AWS_REGION ?: 'ap-south-1'}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }
            stage('Prepare') {
                steps {
                    script {
                        if (!env.AWS_ACCOUNT_ID) {
                            error 'AWS_ACCOUNT_ID is not set. Add it under Manage Jenkins > System > Global properties > Environment variables.'
                        }
                        env.GIT_SHORT = sh(returnStdout: true, script: 'git rev-parse --short=8 HEAD').trim()
                        env.ECR_REGISTRY = "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION_EFFECTIVE}.amazonaws.com"
                        env.ECR_REPO = "icollect/${service}"
                        env.IMAGE = "${env.ECR_REGISTRY}/${env.ECR_REPO}:${env.GIT_SHORT}"
                        currentBuild.displayName = "#${env.BUILD_NUMBER} ${service}:${env.GIT_SHORT}"
                        echo "Service: ${service} (${language})"
                        echo "Image  : ${env.IMAGE}"
                    }
                }
            }

            stage('Build and test') {
                steps {
                    dir(service) {
                        script {
                            switch (language) {
                                case 'java':
                                    sh 'mvn -B -ntp clean verify'
                                    break
                                case 'node':
                                    sh 'npm ci && npm test'
                                    break
                                case 'python':
                                    sh '''
                                        python3 -m venv .venv
                                        . .venv/bin/activate
                                        pip install -q -r requirements-dev.txt
                                        pytest -q --junitxml=test-results.xml
                                    '''
                                    break
                                case 'static':
                                    echo 'Static site: nothing to compile or test'
                                    break
                                default:
                                    error "Unsupported language: ${language}"
                            }
                        }
                    }
                }
                post {
                    always {
                        junit testResults: "${service}/target/surefire-reports/*.xml,${service}/test-results.xml",
                              allowEmptyResults: true
                    }
                }
            }

            stage('SonarQube analysis') {
                when { expression { params.RUN_SONAR } }
                steps {
                    dir(service) {
                        script {
                            def scannerHome = tool 'sonar-scanner'
                            withSonarQubeEnv('sonarqube') {
                                sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=icollect-${service} ${sonarProps[language]}"
                            }
                        }
                    }
                }
            }

            stage('Quality gate') {
                when { expression { params.RUN_SONAR } }
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Docker build') {
                steps {
                    sh "docker build -t ${env.IMAGE} ${service}"
                }
            }

            stage('Trivy scan') {
                steps {
                    sh """
                        # Report HIGH findings (does not fail the build)
                        docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/ \
                            aquasec/trivy:${TRIVY_VERSION} image --no-progress --severity HIGH --exit-code 0 ${env.IMAGE}
                        # Fail the build on CRITICAL findings that have a fix available
                        docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/ \
                            aquasec/trivy:${TRIVY_VERSION} image --no-progress --severity CRITICAL --ignore-unfixed --exit-code 1 ${env.IMAGE}
                    """
                }
            }

            stage('Push to ECR') {
                when {
                    expression {
                        return params.PUSH_IMAGE && (env.BRANCH_NAME == null || pushBranches.contains(env.BRANCH_NAME))
                    }
                }
                steps {
                    script {
                        withAwsCredentials {
                            // The repository must already exist (created by Terraform or jenkins/create-ecr-repos.sh)
                            int repoStatus = sh(returnStatus: true, script:
                                "aws ecr describe-repositories --repository-names ${env.ECR_REPO} --region ${env.AWS_REGION_EFFECTIVE} > /dev/null 2>&1")
                            if (repoStatus != 0) {
                                error "ECR repository ${env.ECR_REPO} not found in ${env.AWS_REGION_EFFECTIVE}. Create it first (jenkins/create-ecr-repos.sh)."
                            }

                            // Tags are immutable, so re-running the same commit must not try to overwrite the tag
                            int tagStatus = sh(returnStatus: true, script:
                                "aws ecr describe-images --repository-name ${env.ECR_REPO} --image-ids imageTag=${env.GIT_SHORT} --region ${env.AWS_REGION_EFFECTIVE} > /dev/null 2>&1")
                            if (tagStatus == 0) {
                                echo "Tag ${env.GIT_SHORT} already exists in ECR. Skipping push (same commit, same image)."
                            } else {
                                sh """
                                    aws ecr get-login-password --region ${env.AWS_REGION_EFFECTIVE} \
                                        | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                                    docker push ${env.IMAGE}
                                """
                            }
                            sh "aws ecr describe-images --repository-name ${env.ECR_REPO} --image-ids imageTag=${env.GIT_SHORT} --region ${env.AWS_REGION_EFFECTIVE} --query 'imageDetails[0].[imageDigest,imagePushedAt]' --output text"
                        }
                        // Record what was built; the GitOps step (next stage of the project) reads this
                        writeFile file: "image-${service}.txt", text: "${env.IMAGE}\n"
                        archiveArtifacts artifacts: "image-${service}.txt"
                    }
                }
            }

            stage('Update GitOps') {
                when {
                    expression {
                        return params.PUSH_IMAGE && env.GITOPS_REPO_URL &&
                               (env.BRANCH_NAME == null || pushBranches.contains(env.BRANCH_NAME))
                    }
                }
                steps {
                    script {
                        def update = { sh "bash jenkins/update-gitops.sh ${service} ${env.GIT_SHORT} dev" }
                        if (env.GITOPS_CREDENTIALS_ID) {
                            withCredentials([usernamePassword(credentialsId: env.GITOPS_CREDENTIALS_ID,
                                                              usernameVariable: 'GIT_USER',
                                                              passwordVariable: 'GIT_TOKEN')]) {
                                update()
                            }
                        } else {
                            update()
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "SUCCESS: ${service} -> ${env.IMAGE}"
            }
            failure {
                echo "FAILED: ${service}. Read the first red stage above."
            }
            always {
                sh "docker image rm ${env.IMAGE ?: 'none'} || true"
                sh 'docker image prune -f || true'
                deleteDir()
            }
        }
    }
}

/** Runs the body with AWS credentials from Jenkins, or with the instance role when no credential id is configured. */
def withAwsCredentials(Closure body) {
    if (env.AWS_CREDENTIALS_ID) {
        withCredentials([usernamePassword(credentialsId: env.AWS_CREDENTIALS_ID,
                                          usernameVariable: 'AWS_ACCESS_KEY_ID',
                                          passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            body()
        }
    } else {
        body()
    }
}
