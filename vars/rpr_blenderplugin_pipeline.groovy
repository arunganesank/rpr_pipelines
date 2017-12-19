
def call(Map pipelineParams) {
  
    pipeline {
        agent none
        options {
            timestamps()
            skipDefaultCheckout()
        }
        environment
        {
            JOB_NAME_FMT="${JOB_NAME}".replace('%2F', '_')
            UPLOAD_PATH="builds/rpr-plugins/${JOB_NAME_FMT}/Build-${BUILD_ID}"
        }
        stages {
            stage('Build') {
                parallel {
                    stage('Build On Windows') {
                        agent {
                            label "Windows && VS2015"
                        }

                        steps {
                            ws("WS/${JOB_NAME_FMT}") {
                                bat 'set'
                                dir('RadeonProRenderBlenderAddon')
                                {
                                    checkout scm
                                }
                                dir('RadeonProRenderThirdPartyComponents')
                                {
                                    checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [
                                        [$class: 'CleanCheckout'],
                                        [$class: 'CheckoutOption', timeout: 30],
                                        [$class: 'CloneOption', timeout: 30]
                                        ], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/Radeon-Pro/RadeonProRenderThirdPartyComponents.git']]])
                                }
                                dir('RadeonProRenderPkgPlugin')
                                {
                                    checkout([$class: 'GitSCM', timeout: 30, branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [
                                        [$class: 'CleanCheckout'],
                                        [$class: 'CheckoutOption', timeout: 30],
                                        [$class: 'CloneOption', timeout: 60]
                                        ], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/Radeon-Pro/RadeonProRenderPkgPlugin.git']]])
                                }

                                dir('RadeonProRenderBlenderAddon')
                                {
                                    bat '''
                                    mklink /D ".\\ThirdParty\\AxfPackage\\"              "%workspace%\\RadeonProRenderThirdPartyComponents\\AxfPackage\\"
                                    mklink /D ".\\ThirdParty\\Expat 2.1.0\\"             "%workspace%\\RadeonProRenderThirdPartyComponents\\Expat 2.1.0\\"
                                    mklink /D ".\\ThirdParty\\ffmpeg\\"                  "%workspace%\\RadeonProRenderThirdPartyComponents\\ffmpeg\\"
                                    mklink /D ".\\ThirdParty\\glew\\"                    "%workspace%\\RadeonProRenderThirdPartyComponents\\glew\\"
                                    mklink /D ".\\ThirdParty\\oiio\\"                    "%workspace%\\RadeonProRenderThirdPartyComponents\\oiio\\"
                                    mklink /D ".\\ThirdParty\\OpenCL\\"                  "%workspace%\\RadeonProRenderThirdPartyComponents\\OpenCL\\"
                                    mklink /D ".\\ThirdParty\\RadeonProRender SDK\\"     "%workspace%\\RadeonProRenderThirdPartyComponents\\RadeonProRender SDK\\"
                                    mklink /D ".\\ThirdParty\\RadeonProRender-GLTF\\"    "%workspace%\\RadeonProRenderThirdPartyComponents\\RadeonProRender-GLTF\\"
                                    '''                
                                }
                                dir('RadeonProRenderPkgPlugin\\BlenderPkg')
                                {
                                    bat '''
                                    build_win_installer.cmd
                                    '''

                                    bat '''
                                    IF EXIST "%CIS_TOOLS%\\sendFiles.bat" (
                                        %CIS_TOOLS%\\sendFiles.bat output/_ProductionBuild/RadeonProRender*.msi %UPLOAD_PATH%
                                        )
                                    '''

                                    bat '''
                                        c:\\JN\\create_refhtml.bat build.html "https://builds.rpr.cis.luxoft.com/%UPLOAD_PATH%"
                                    '''

                                    archiveArtifacts 'build.html'

                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                echo 'sending notification result...'
                sendBuildStatusNotification(currentBuild.result)
            }
        }
    }
}
