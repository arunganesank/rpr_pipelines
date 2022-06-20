import groovy.transform.Field

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/WebUsdViewer.git"

@Field final String AMF_REPO = "git@github.com:s1lentssh/amf.git"

def executeBuildWindows(Map options)
{
    Boolean failure = false
    String webrtcPath = "C:\\JN\\thirdparty\\webrtc"

    downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

    try {
        withEnv(["PATH=c:\\CMake322\\bin;c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
            bat """
                call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1
                cmake --version >> ${STAGE_NAME}.log 2>&1
                python3--version >> ${STAGE_NAME}.log 2>&1
                python3 -m pip install conan >> ${STAGE_NAME}.log 2>&1
                mkdir Build
                echo [WebRTC] >> Build\\LocalBuildConfig.txt
                echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                python3 Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
            """
            println("[INFO] Start building & sending docker containers to repo")
            bat """
                python3 Tools/Docker.py -ba -da -v
            """
            println("[INFO] Finish building & sending docker containers to repo")
            if (options.generateArtifact){
                zip archive: true, dir: "Build/Install", glob: '', zipFile: "WebUsdViewer_Windows.zip"
            }
        }
    } catch(e) {
        println("Error during build on Windows")
        println(e.toString())
        failure = true
    }
    finally {
        archiveArtifacts "*.log"
    }
    

    if (failure) {
        currentBuild.result = "FAILED"
        error "error during build"
    }
}


def executeBuildLinux(Map options)
{
    Boolean failure = false
    println "[INFO] Start build" 
    downloadFiles("/volume1/CIS/radeon-pro/webrtc-linux/", "${CIS_TOOLS}/../thirdparty/webrtc", "--quiet")
    try {
        println "[INFO] Start build" 
        sh """
            mkdir --parents Build/Install
        """
        if (!options.rebuildDeps){
            downloadFiles("/volume1/CIS/WebUSD/${options.osName}/USD", "./Build/Install", "--quiet")
            // Because modes resetting after downloading from NAS
            sh """ chmod -R 775 ./Build/Install/USD"""
        }
        println "[INFO] Install AMF"
        sh """
            git clone --recurse-submodules ${AMF_REPO}
        """
        dir("amf"){
        sh """
            cmake -B Build ./amf -DCMAKE_INSTALL_PREFIX=Install
            cmake --build Build --config Release --target install
        """
        }
        sh """
            cmake --version >> ${STAGE_NAME}.log 2>&1
            python3 --version >> ${STAGE_NAME}.log 2>&1
            python3 -m pip install conan >> ${STAGE_NAME}.log 2>&1
            echo "[WebRTC]" >> Build/LocalBuildConfig.txt
            echo "path = ${CIS_TOOLS}/../thirdparty/webrtc/src" >> Build/LocalBuildConfig.txt
            echo "[AMF]" >> Build/LocalBuildConfig.txt
            echo "path = /opt/AMF/Install" >> Build/LocalBuildConfig.txt
            export OS=
            python3 Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
        """
        if (options.updateDeps){
            uploadFiles("./Build/Install/USD", "/volume1/CIS/WebUSD/${options.osName}", "--delete")
        }
        println("[INFO] Start building & sending docker containers to repo")
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'WebUsdDockerRegisterHost', usernameVariable: 'remoteHost', passwordVariable: 'remotePort']]){
            String deployArgs = "-ba -da"
            containersBaseName = "docker.${remoteHost}/" 
            if (env.CHANGE_URL){
                deployArgs = "-ba"
                containersBaseName = ""
                remoteHost = ""
                options["deployEnvironment"] = "pr"
            }
            env["WEBUSD_BUILD_REMOTE_HOST"] = remoteHost
            env["WEBUSD_BUILD_LIVE_CONTAINER_NAME"] = containersBaseName + "live"
            env["WEBUSD_BUILD_ROUTE_CONTAINER_NAME"] = containersBaseName + "route"
            env["WEBUSD_BUILD_STORAGE_CONTAINER_NAME"] = containersBaseName + "storage"
            env["WEBUSD_BUILD_STREAM_CONTAINER_NAME"] = containersBaseName + "stream"
            env["WEBUSD_BUILD_WEB_CONTAINER_NAME"] = containersBaseName + "web"

            sh """python3 Tools/Docker.py $deployArgs -v -c $options.deployEnvironment"""
        }
        println("[INFO] Finish building & sending docker containers to repo")
        sh "rm WebUsdWebServer/.env.production"
        if (options.generateArtifact){
            sh """
                tar -C Build/Install -czvf "WebUsdViewer_Ubuntu20.tar.gz" .
            """
            archiveArtifacts "WebUsdViewer_Ubuntu20.tar.gz"
        }
    } catch(e) {
        println("Error during build on Linux")
        println(e.toString())
        failure = true
    } finally {
        archiveArtifacts "*.log"
    }

    if (failure) {
        currentBuild.result = "FAILED"
        error "error during build"
    }
}

def executeBuild(String osName, Map options)
{   
    try {
        throw new Exception();
        cleanWS(osName)
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)
        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.${options.deployEnvironment}", "./WebUsdWebServer", "--quiet")
        sh "mv ./WebUsdWebServer/webusd.env.${options.deployEnvironment} ./WebUsdWebServer/.env.production"
        switch(osName) {
            case 'Windows':
                executeBuildWindows(options)
                break
            case 'Ubuntu20':
                executeBuildLinux(options)
                break
            default:
                println "[WARNING] ${osName} is not supported"
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executePreBuild(Map options)
{
    
    dir('WebUsdViewer') {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${commitMessage}"
        println "Commit SHA: ${options.commitSHA}"

        if (env.CHANGE_URL){
            // TODO add github notification
        }
    }
}



def executeTest(Map options){
    println "[WARN] Test for this pipeline doesn't exists. Skip Tests stage."
}

def executeDeploy(Map options, List platformList, List testResultList)
{
    println "[INFO] Start deploying on $options.deployEnvironment environment"
    failure = false
    Boolean status = true
    try{
        if (env.CHANGE_URL){
            println "[INFO] Local deploying for sanytize checks"
            String composePath = "/usr/local/bin/docker-compose"

            downloadFiles("/volume1/CIS/WebUSD/Additional/pr.yml", ".", "--quiet")
            sh """$composePath -f pr.yml up -d"""
            List containersNamesAssociations = [
                "${env.WEBUSD_BUILD_LIVE_CONTAINER_NAME}",
                "${env.WEBUSD_BUILD_ROUTE_CONTAINER_NAME}",
                "${env.WEBUSD_BUILD_STORAGE_CONTAINER_NAME}",
                "${env.WEBUSD_BUILD_STREAM_CONTAINER_NAME}",
                "front"
            ]
            for (i=0; i < 5; i++){
                try{
                    sleep(5)
                    for (name in containersNamesAssociations){
                        result = sh (
                            script: "$composePath -f pr.yml ps --services --filter \"status=running\" | grep $name",
                            returnStdout: true,
                            returnStatus: true
                        )
                        println result
                        if (result == 1){
                            throw new Exception("""[ERROR] Sanytize checks failed. Service ${name} doesn't work""")
                        }
                    }
                    sh """$composePath -f pr.yml stop"""
                    println "[INFO] Sanytize checks successfully passed"
                    break
                }catch (e){
                    println "[ERROR] Sanytize checks failed. Trying again"
                }
            }
            if (!status){
                sh """$composePath -f pr.yml stop"""
                throw new Exception("[ERROR] Sanytize checks failed. All attempts have been exhausted.")
            }
        }else {
            println "[INFO] Send deploy command"
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'WebUsdDockerRegisterHost', usernameVariable: 'remoteHost', passwordVariable: 'remotePort']]){
                for (i=0; i < 5; i++){
                    res = sh(
                        script: "curl --insecure https://admin.${remoteHost}/deploy?configuration=${options.deployEnvironment}",
                        returnStdout: true,
                        returnStatus: true
                    )
                    println ("RES - ${res}")
                    if (res == 0){
                        println "[INFO] Successfully sended"
                        break
                    }else{
                        println "[ERROR] Host not available. Try again"
                    }
                }
                if (!status){
                    throw new Exception("[ERROR] Host not available. Retries exceeded")
                }
        }
        }
    }catch (e){
        println "[ERROR] Error during deploy"
        println(e.toString())
        failure = true
        status = false
    }
    if (failure){
        currentBuild.result = "FAILED"
        error "error during deploy"
    }
    notifyByTg(options)
    
}


def notifyByTg(Map options){
    println currentBuild.result
    String status_message = currentBuild.result.contains("FAILED") ? "Success" : "Failed"
    Boolean is_pr = env.CHANGE_URL != null
    String branchName = env.CHANGE_URL ?: options.projectBranch
    if (branchName.contains("origin")){
        branchName = branchName.split("/", 2)[1]
    }
    String branchURL = is_pr ? env.CHANGE_URL : "https://github.com/Radeon-Pro/WebUsdViewer/tree/${branchName}" 
    withCredentials([string(credentialsId: "WebUsdTGBotHost", variable: "tgBotHost")]){
        res = sh(
            script: "curl -X POST ${tgBotHost}/auto/notifications -H 'Content-Type: application/json' -d '{\"status\":\"${status_message}\",\"build_url\":\"${env.BUILD_URL}\", \"branch_url\": \"${branchURL}\", \"is_pr\": ${is_pr}}'",
            returnStdout: true,
            returnStatus: true
        )
    }
}


def call(
    String projectBranch = "",
    String platforms = 'Windows;Ubuntu20',
    Boolean enableNotifications = true,
    Boolean generateArtifact = false,
    Boolean deploy = true,
    String deployEnvironment = 'test1;test2;test3;dev;prod;',
    Boolean rebuildDeps = false,
    Boolean updateDeps = false
) {
    if (env.BRANCH_NAME){
        switch (env.BRANCH_NAME){
            case "develop":
                deployEnvironment = "dev"
                break
            case "main":
                deployEnvironment = "prod"
                break
            case "auto_deploy":
                deployEnvironment = "test3"
                break
        }
    }
    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                            [projectBranch:projectBranch,
                            projectRepo:PROJECT_REPO,
                            rebuildDeps:rebuildDeps,
                            updateDeps:updateDeps,
                            enableNotifications:enableNotifications,
                            generateArtifact:generateArtifact,
                            deployEnvironment: deployEnvironment,
                            deploy:deploy, 
                            PRJ_NAME:'WebUsdViewer',
                            PRJ_ROOT:'radeon-pro',
                            BUILDER_TAG:'BuilderWebUsdViewer',
                            executeBuild:true,
                            executeTests:true,
                            executeDeploy:deploy,
                            BUILD_TIMEOUT:'120',
                            DEPLOY_TAG:'WebViewerDeployment',
                            ])
}