def  call (Map configMap){
    pipeline {
        agent { 
            label 'ROBOSHOP' 
        }
        environment { 
            def appVersion= ""
            acc_id = "578257748163"
            component = "catalogue"
            project = "roboshop"
            SCANNER_HOME = "sonar-8"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES') 
        }
        // parameters {
        //     string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
        //     text(name: 'BIOGRAPHY', defaultValue: '', description: 'Enter some information about the person')
        //     booleanParam(name: 'TOGGLE', defaultValue: true, description: 'Toggle this value')
        //     choice(name: 'CHOICE', choices: ['One', 'Two', 'Three'], description: 'Pick something')
        //     password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'Enter a password')
        // }

        stages {
            stage('Read package.json') {
                steps {
                    script {
                        // Read and parse the file
                        def packageJson = readJSON file: 'package.json'
                        
                        // Access top-level keys directly using dot notation
                        appVersion = packageJson.version
                        echo "Version: ${appVersion}"
                    }
                }
            }
            stage('npm install') {
                steps {
                    echo 'npm install..'
                    script {
                    sh """
                        npm install
                    """
                }
            }
            }

            // this command gives us coverage report and test cases report, sonarqube access this to check quality gate
            stage('Unit tests') {
                steps {
                    script {
                        try {
                              sh """
                            npm test
                        """
                        utils.updateCommitStatus( 'success', 'Unit tests passed',"unit-tests")


                        }
                        catch (Exception e) {
                            utils.updateCommitStatus( 'failure', 'Unit tests failed',"unit-tests")
                            throw e
                        }
                    } 
                }
            }
            /* stage('SonarQube Analysis') {
                steps {
                    // 'My SonarQube Server' must match the name configured in Jenkins System Settings
                    withSonarQubeEnv('sonar-server') {
                        sh "${tool 'sonar-8'}/bin/sonar-scanner"
                    }
                }
            }

            stage('SonarQube Quality Gate') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            def qg = waitForQualityGate() // Pauses pipeline
                            if (qg.status != 'OK') {
                                utils.updateCommitStatus( 'failure', 'SonarQube quality gate failed',"sonar-scan")
                                error "Pipeline aborted: ${qg.status}"
                            }
                            else {
                                utils.updateCommitStatus( 'success', 'SonarQube quality gate passed',"sonar-scan")
                            }
                        }
                    }
                }
            } 
            */
            stage('Ensure ECR Repository') {
                steps {
                    withAWS(credentials: 'aws-id', region: 'us-east-1') {
                        sh """
                            aws ecr describe-repositories \
                            --repository-names ${project}/${component} \
                            --region us-east-1 \
                            || aws ecr create-repository \
                            --repository-name ${project}/${component} \
                            --region us-east-1
                        """
                    }
                }
            }
            stage('Dependabot Scan') {
                steps {
                    script {
                    withCredentials([
                        string(
                            credentialsId: 'github-pat',
                            variable: 'GITHUB_TOKEN'
                        )
                    ]) {
                        try {
                            sh '''
                            set +x

                            curl --fail --silent --show-error --location \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: Bearer ${GITHUB_TOKEN}" \
                            -H "X-GitHub-Api-Version: 2026-03-10" \
                            "https://api.github.com/repos/Manjunath-Yelipeta/catalogue/dependabot/alerts?state=open&per_page=100" \
                            -o dependabot-alerts.json

                            echo "Open Dependabot alerts:"
                            jq -r '
                            .[] |
                            [
                                .number,
                                .security_advisory.severity,
                                .dependency.package.name,
                                .security_advisory.ghsa_id,
                                .html_url
                            ] |
                            @tsv
                            ' dependabot-alerts.json

                            HIGH_CRITICAL_COUNT=$(jq '
                            [
                                .[] |
                                select(
                                .security_advisory.severity == "high" or
                                .security_advisory.severity == "critical"
                                )
                            ] |
                            length
                            ' dependabot-alerts.json)

                            echo "Open HIGH/CRITICAL alerts: ${HIGH_CRITICAL_COUNT}"

                            if [ "${HIGH_CRITICAL_COUNT}" -gt 0 ]; then
                                echo "Pipeline failed: HIGH or CRITICAL Dependabot alerts found."
                                exit 1
                            fi

                            echo "Dependabot scan passed."
                        '''
                        utils.updateCommitStatus( 'success', 'Dependabot scan passed',"library-scan")
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus( 'failure', 'Dependabot scan failed',"library-scan")
                            throw e
                        }
                        }
                    }
                }
            }

        

            stage('Docker Build ') {
                steps {
                    script {
                    // The plugin sets up the environment variables automatically
                    try {
                        withAWS(credentials: 'aws-id', region: 'us-east-1') {
                        script {
                            sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                            """
                            utils.updateCommitStatus( 'success', 'Docker build passed',"build-image")
                        }
                    }
                    }
                    catch (Exception e) {
                        utils.updateCommitStatus( 'failure', 'Docker build failed',"build-image")
                        throw e
                    }
                }
                }
                }
            
            stage('Trivy Scan') {
                steps {
                    script {
                        try {
                            def dockerfileScan = sh(
                            script: """
                                trivy config \
                                --exit-code 1 \
                                --severity HIGH,CRITICAL \
                                --format table \
                                ./Dockerfile
                            """,
                            returnStatus: true
                        )

                        def imageScan = sh(
                            script: """
                                trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --exit-code 1 \
                                --severity HIGH,CRITICAL \
                                --format table \
                                ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (dockerfileScan != 0 || imageScan != 0) {
                            utils.updateCommitStatus( 'failure', 'Trivy scan failed',"image-scan")
                            error(
                                'Trivy found HIGH/CRITICAL issues in the Dockerfile ' +
                                'and/or OS packages. Failing the pipeline.'
                            )
                        }
                        utils.updateCommitStatus( 'success', 'Trivy scan passed',"image-scan")
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus( 'failure', 'Trivy scan failed',"image-scan")
                            throw e
                        }
                        
                    }
                }
            }

            stage('Docker Ecr Push') {
                steps {
                    script {
                    // The plugin sets up the environment variables automatically
                    try {
                        withAWS(credentials: 'aws-id', region: 'us-east-1') {
                        script {
                            sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """
                            utils.updateCommitStatus( 'success', 'Docker Ecr Push passed',"push-image")
                        }
                    }
                    }
                    catch (Exception e) {
                        utils.updateCommitStatus( 'failure', 'Docker Ecr Push failed',"push-image")
                        throw e
                    }
                    }
                    
                }
            }



            stage('pre-build') {
                steps {
                    echo 'Pre-build..'
                    script {
                    sh '''
                        echo 'Pre-build.. din-sri'
                    '''
                }
            }
            }
            stage('Build') {
                steps {
                    echo 'Building..'
                    script {
                    sh """
                        echo 'Building.. catalogue'
                        
                    """
                }

            }
            }

            stage('Test') {
                steps {
                    echo 'Testing..'
                    sh '''
                        echo 'Testing.. din-str'
                    '''
                }
            }

            stage('Deploy') {
            /*
                input {
                    message "Should we continue?"
                    ok "Yes, we should."
                    submitter "alice,bob"
                    parameters {
                        string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
                    }
                }*/
                steps {
                    echo 'Deploying....'
                    sh '''
                        echo 'Deploying.... din-sri'
                    '''
                }
            }
            
        }
            post { 
            always { 
                echo 'I will always say Hello again!'
                archiveArtifacts(
                    artifacts: 'dependabot-alerts.json',
                    allowEmptyArchive: true
                )
            }
            success { 
                echo 'I will say Hello only if successful'
            }
            failure { 
                echo 'I will say Bye only if failure'
            }
        }
        
    }
}


    
