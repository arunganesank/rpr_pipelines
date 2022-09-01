import groovy.transform.Field
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/WebUsdViewer.git"

@Field final Integer MAX_TEST_INSTANCE_NUMBER = 10

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "msi"],
    artifactNameBase: "AMD_RenderStudio"
)


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


Integer getNextTestInstanceNumber(Map options) {
    downloadFiles("/volume1/CIS/WebUSD/State/TestingInstancesInfo.json", ".")

    def instancesInfo = readJSON(file: "TestingInstancesInfo.json")

    Integer testingNumber

    if (instancesInfo["prs"].containsKey(env.BRANCH_NAME)) {
        testingNumber = instancesInfo["prs"][env.BRANCH_NAME]
    } else {
        testingNumber = instancesInfo["globalCounter"]
        instancesInfo["prs"][env.BRANCH_NAME] = testingNumber
        instancesInfo["globalCounter"] = instancesInfo["globalCounter"] >= MAX_TEST_INSTANCE_NUMBER ? 1 : instancesInfo["globalCounter"] + 1
    }

    options.deployEnvironment = "test${testingNumber}"

    def jsonOutputInfo = JsonOutput.toJson(instancesInfo)
    JSON serializedInfo = JSONSerializer.toJSON(jsonOutputInfo, new JsonConfig());
    writeJSON(file: "TestingInstancesInfo.json", json: serializedInfo, pretty: 4)

    uploadFiles("TestingInstancesInfo.json", "/volume1/CIS/WebUSD/State")

    return testingNumber
}


def doSanityCheckWindows(String asicName, Map options) {
    options["stage"] = "Sanity check"
    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.INSTALL_APPPLICATION) {
        def installedProductCode = powershell(script: """(Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'AMD RenderStudio'\").IdentifyingNumber""", returnStdout: true)

        if (installedProductCode) {
            println("[INFO] Found installed AMD RenderStudio. Uninstall it...")
            uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
        }

        timeout(time: 10, unit: "MINUTES") {
            dir("${CIS_TOOLS}\\..\\PluginsBinaries") {
                bat "msiexec.exe /i ${options[getProduct.getIdentificatorKey('Windows')]}.msi /qb"
            }
        }
    }

    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
        downloadFiles("/volume1/web/Assets/web_viewer_autotests/Kitchen_set", ".")

        downloadFiles("/volume1/CIS/WebUSD/Scripts/*", ".")
    }

    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.SANITY_CHECK) {
        timeout(time: 2, unit: "MINUTES") {
            python3("webusd_check_win.py --scene_path ${env.WORKSPACE}\\Kitchen_set\\Kitchen_set.usd")
        }
    }

    utils.reboot(this, "Windows")

    dir("Windows-check") {
        utils.moveFiles(this, "Windows", "../webusd_check.log", "Windows.log")
        utils.moveFiles(this, "Windows", "../screen.jpg", "Windows.jpg")
    }

    archiveArtifacts(artifacts: "Windows-check/*")

    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.UNINSTALL_APPPLICATION) {
        uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
    }
}


def doSanityCheckLinux(String asicName, Map options) {
    options["stage"] = "Sanity check"
    withNotifications(title: "Web application", options: options, configuration: NotificationConfiguration.SANITY_CHECK) {
        withNotifications(title: "Web application", options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            downloadFiles("/volume1/CIS/WebUSD/Scripts/*", ".")
        }
        timeout(time: 2, unit: "MINUTES") {
            String testingUrl = "https://${options.deployEnvironment}.webusd.stvcis.com"

            python3("webusd_check_web.py --service_url ${testingUrl}")

            dir("Ubuntu20-check") {
                utils.moveFiles(this, "Ubuntu20", "../screen.jpg", "Ubuntu20.jpg")
            }

            archiveArtifacts(artifacts: "Ubuntu20-check/*")
        }
    }
}


def doSanityCheck(String osName, String asicName, Map options) {
    def platform = osName == 'Windows' ? "Windows" : "Web application"
    
    options["stage"] = "Sanity check"
    try {
        cleanWS(osName)

        withNotifications(title: platform, options: options, configuration: NotificationConfiguration.DOWNLOAD_APPPLICATION) {
            getProduct(osName, options)
        }

        switch(osName) {
            case 'Windows':
                doSanityCheckWindows(asicName, options)
                break
            default:
                println "[WARNING] ${osName} is not supported"
                break
        }

    } catch (e) {
        options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SANITY_CHECK_FAILED.replace("<gpuName>", asicName).replace("<osName>", platform))
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

String patchSubmodule(String serviceName) {
    String commitSHA

    if (isUnix()) {
        commitSHA = sh (script: "git log --format=%h -1 ", returnStdout: true).trim()
    } else {
        commitSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()
    }

    String version = readFile("VERSION.txt").trim()
    writeFile(file: "VERSION.txt", text: "${serviceName}: ${version}. Hash: ${commitSHA}")
}


def patchVersions(Map options) {
    dir("WebUsdLiveServer") {
        patchSubmodule("Live")
    }

    dir("WebUsdRouteServer") {
        patchSubmodule("Route")
    }

    dir("WebUsdStorageServer") {
        patchSubmodule("Storage")
    }

    dir("WebUsdFrontendServer") {
        patchSubmodule("Web")
    }

    dir("WebUsdStreamServer") {
        patchSubmodule("Stream")
    }

    String version = readFile("VERSION.txt").trim()

    if (env.CHANGE_URL) {
        writeFile(file: "VERSION.txt", text: "Renderstudio: ${version}. PR: #${env.CHANGE_ID}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    } else {
        writeFile(file: "VERSION.txt", text: "Renderstudio: ${version}. Branch: ${env.BRANCH_NAME ?: options.projectBranch}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    }
}


def executeBuildWindows(Map options) {
    options["stage"] = "Build"

    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
        utils.reboot(this, "Windows")

        Boolean failure = false
        String webrtcPath = "C:\\JN\\thirdparty\\webrtc"
        String amfPath = "C:\\JN\\thirdparty\\amf"

        downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF-WIN", amfPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.win", "${env.WORKSPACE.replace('C:', '/mnt/c').replace('\\', '/')}/WebUsdFrontendServer", "--quiet")
        bat "move WebUsdFrontendServer\\webusd.env.win WebUsdFrontendServer\\.env.production"

        String frontendVersion
        String renderStudioVersion
        String streamServerVersion

        dir("WebUsdRouteServer") {
            frontendVersion = readFile("VERSION.txt").trim()
        }

        dir("WebUsdStreamServer") {
            streamServerVersion = readFile("VERSION.txt").trim()
        }

        renderStudioVersion = readFile("VERSION.txt").trim()

        String envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent + "\nVUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}\nVUE_APP_URL_STREAMER_REST=${streamServerVersion}"
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)

        try {
            withEnv(["PATH=c:\\CMake322\\bin;c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1
                    cmake --version >> ${STAGE_NAME}.Build.log 2>&1
                    python--version >> ${STAGE_NAME}.Build.log 2>&1
                    python -m pip install conan >> ${STAGE_NAME}.Build.log 2>&1
                    mkdir Build
                    echo [WebRTC] >> Build\\LocalBuildConfig.txt
                    echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                    echo [AMF] >> Build/LocalBuildConfig.txt
                    echo path = ${amfPath.replace("\\", "/")}/AMF-WIN >> Build\\LocalBuildConfig.txt
                    python Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
                """
                println("[INFO] Start building installer")
                bat """
                    python Tools/Package.py -v >> ${STAGE_NAME}.Package.log 2>&1
                """

                println("[INFO] Saving exe files to NAS")

                dir("WebUsdFrontendServer\\dist_electron") {
                    def exeFile = findFiles(glob: '*.msi')
                    println("Found MSI files: ${exeFile}")
                    for (file in exeFile) {
                        renamedFilename = file.toString().replace(" ", "_")

                        if (options.branchPostfix) {
                            String filenameWithPostfix = file.toString().replace(" ", "_").replace(".msi", "(${options.branchPostfix}).msi")

                            bat """
                                rename "${file}" "${filenameWithPostfix}"
                            """

                            makeArchiveArtifacts(name: filenameWithPostfix, storeOnNAS: true)

                            bat """
                                rename "${filenameWithPostfix}" "${renamedFilename}"
                            """
                        } else {
                            bat """
                                rename "${file}" "${renamedFilename}"
                            """  

                            makeArchiveArtifacts(name: renamedFilename, storeOnNAS: true)
                        }

                        makeStash(includes: renamedFilename, name: getProduct.getStashName("Windows"), preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        } catch(e) {
            println("Error during build on Windows")
            println(e.toString())
            failure = true
        } finally {
            archiveArtifacts "*.log"
            if (failure) {
                currentBuild.result = "FAILED"
                error "error during build"
            }
        } 
    }
}


def executeBuildLinux(Map options) {
    Boolean failure = false

    String webUsdUrlBase

    withCredentials([string(credentialsId: "WebUsdHost", variable: "remoteHost")]) {
        webUsdUrlBase = remoteHost
    }

    Integer testingNumber

    if (options.deployEnvironment == "pr") {
        testingNumber = getNextTestInstanceNumber(options)
        options.deployEnvironment = "test${testingNumber}"
    }

    String envProductionContent

    if (!options.customDomain) {
        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.${options.deployEnvironment}", "./WebUsdFrontendServer", "--quiet")
        sh "mv ./WebUsdFrontendServer/webusd.env.${options.deployEnvironment} ./WebUsdFrontendServer/.env.production"
    } else {
        downloadFiles("/volume1/CIS/WebUSD/Additional/envs/template", "./WebUsdFrontendServer", "--quiet")
        sh "mv ./WebUsdFrontendServer/template ./WebUsdFrontendServer/.env.production"

        envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent.replaceAll("<custom_domain>", options.customDomain)
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)
    }

    if (options.disableSsl) {
        envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent.replaceAll("https", "http").replaceAll("wss", "ws")
        writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)
    }

    options["stage"] = "Build"

    withNotifications(title: "Web application", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
        println "[INFO] Start build" 
        println "[INFO] Download Web-rtc and AMF" 
        downloadFiles("/volume1/CIS/radeon-pro/webrtc-linux/", "${CIS_TOOLS}/../thirdparty/webrtc", "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF/", "${CIS_TOOLS}/../thirdparty/AMF", "--quiet")
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

            sh """
                cmake --version >> ${STAGE_NAME}.Build.log 2>&1
                python3 --version >> ${STAGE_NAME}.Build.log 2>&1
                python3 -m pip install conan >> ${STAGE_NAME}.Build.log 2>&1
                echo "[WebRTC]" >> Build/LocalBuildConfig.txt
                echo "path = ${CIS_TOOLS}/../thirdparty/webrtc/src" >> Build/LocalBuildConfig.txt
                echo "[AMF]" >> Build/LocalBuildConfig.txt
                echo "path = ${CIS_TOOLS}/../thirdparty/AMF/Install" >> Build/LocalBuildConfig.txt
                export OS=
                python3 Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
            """

            if (options.updateDeps){
                uploadFiles("./Build/Install/USD", "/volume1/CIS/WebUSD/${options.osName}", "--delete")
            }

            println("[INFO] Start building & sending docker containers to repo")
            String deployArgs = "-ba -da"
            containersBaseName = "docker.${webUsdUrlBase}/"

            env["WEBUSD_BUILD_REMOTE_HOST"] = webUsdUrlBase
            env["WEBUSD_BUILD_LIVE_CONTAINER_NAME"] = containersBaseName + "live"
            env["WEBUSD_BUILD_ROUTE_CONTAINER_NAME"] = containersBaseName + "route"
            env["WEBUSD_BUILD_STORAGE_CONTAINER_NAME"] = containersBaseName + "storage"
            env["WEBUSD_BUILD_STREAM_CONTAINER_NAME"] = containersBaseName + "stream"
            env["WEBUSD_BUILD_WEB_CONTAINER_NAME"] = containersBaseName + "web"

            sh """python3 Tools/Docker.py $deployArgs -v -c $options.deployEnvironment >> ${STAGE_NAME}.Docker.log 2>&1"""

            println("[INFO] Finish building & sending docker containers to repo")

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
            if (failure) {
                currentBuild.result = "FAILED"
                error "error during build"
            }
        }
        
    }

    options["stage"] = "Deploy"

    withNotifications(title: "Web application", options: options, configuration: NotificationConfiguration.DEPLOY_APPLICATION) {
        if (options.deploy) {
            println "[INFO] Start deploying on $options.deployEnvironment environment"
            failure = false
            Boolean status = true

            try {
                println "[INFO] Send deploy command"

                for (i=0; i < 5; i++) {
                    res = sh(
                        script: "curl --insecure https://admin.${webUsdUrlBase}/deploy?configuration=${options.deployEnvironment}",
                        returnStdout: true,
                        returnStatus: true
                    )

                    println ("RES - ${res}")

                    if (res == 0) {
                        println "[INFO] Successfully sended"
                        status = true
                        break
                    } else {
                        println "[ERROR] Host not available. Try again"
                        status = false
                    }
                }

                if (!status) {
                    throw new Exception("[ERROR] Host not available. Retries exceeded")
                }

                withCredentials([string(credentialsId: "WebUsdUrlTemplate", variable: "TEMPLATE")]) {
                    String url

                    if (options.deployEnvironment == "prod") {
                        url = TEMPLATE.replace("<instance>.", "")
                    } else {
                        url = TEMPLATE.replace("<instance>", options.deployEnvironment)
                    }
                    rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${url}">[${options.deployEnvironment}] Link to web application</a></h3>""")
                }
            } catch (e) {
                println "[ERROR] Error during deploy"
                println(e.toString())
                failure = true
                status = false
            } finally {
                if (failure) {
                    currentBuild.result = "FAILED"
                    error "error during deploy"
                }
            }
        }
    }

    withNotifications(title: "Web application", options: options, configuration: NotificationConfiguration.NOTIFY_BY_TG) {
        if (options.deploy) {
            notifyByTg(options)
        }
    }

    if (options.deploy) {
        doSanityCheckLinux("", options)
    }
}


def executeBuild(String osName, Map options) {  
    try {
        withNotifications(title: osName == "Windows" ? "Windows" : "Web application", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            cleanWS(osName)

            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

            patchVersions(options)

            if (options.customHybridLinux && isUnix()) {
                sh """
                    curl --insecure --retry 5 -L -o HybridPro.tar.xz ${options.customHybridLinux}
                """

                sh "tar -xJf HybridPro.tar.xz"

                sh """
                    yes | cp -rf BaikalNext/bin/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/binUbuntu18
                    yes | cp -rf BaikalNext/inc/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                    yes | cp -rf BaikalNext/inc/Rpr/* WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                """

                dir ("WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            } else if (options.customHybridWin && !isUnix()) {
                bat """
                    curl --insecure --retry 5 -L -o HybridPro.zip ${options.customHybridWin}
                """

                unzip dir: '.', glob: '', zipFile: 'HybridPro.zip'

                bat """
                    copy /Y BaikalNext\\bin\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\binWin64
                    copy /Y BaikalNext\\inc\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\inc\\Rpr\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\lib\\* WebUsdStreamServer\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\libWin64
                """

                dir ("WebUsdStreamServer/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            }
        }

        outputEnvironmentInfo(osName)

        switch(osName) {
            case 'Windows':
                options[getProduct.getIdentificatorKey(osName)] = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
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


def notifyByTg(Map options){
    println currentBuild.result

    String statusMessage = (currentBuild.result != null && currentBuild.result.contains("FAILED")) ? "Failed" : "Success"
    Boolean isPR = env.CHANGE_URL != null
    String branchName = env.CHANGE_URL ?: options.projectBranch

    if (branchName.contains("origin")){
        branchName = branchName.split("/", 2)[1]
    }

    String branchURL = isPR ? env.CHANGE_URL : "https://github.com/Radeon-Pro/WebUsdViewer/tree/${branchName}" 
    withCredentials([string(credentialsId: "WebUsdTGBotHost", variable: "tgBotHost")]){
        res = sh(
            script: "curl -X POST ${tgBotHost}/auto/notifications -H 'Content-Type: application/json' -d '{\"status\":\"${statusMessage}\",\"build_url\":\"${env.BUILD_URL}\", \"branch_url\": \"${branchURL}\", \"is_pr\": ${isPR}, \"user\": \"${options.commitAuthor}\"}'",
            returnStdout: true,
            returnStatus: true
        )
    }
}

def executePreBuild(Map options) {
    ws("WebUSD-prebuild") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()

        // get links to the latest built HybridPro
        def rawInfo = httpRequest(
            url: "${env.JENKINS_URL}/job/RadeonProRender-Hybrid/job/master/api/json?tree=lastCompletedBuild[number,url]",
            authentication: 'jenkinsCredentials',
            httpMode: 'GET'
        )

        def parsedInfo = parseResponse(rawInfo.content)

        withCredentials([string(credentialsId: "nasURLFrontend", variable: "REMOTE_HOST")]) {
            options.customHybridWin = "${REMOTE_HOST}/RadeonProRender-Hybrid/master/${parsedInfo.lastCompletedBuild.number}/Artifacts/BaikalNext_Build-Windows.zip"
            options.customHybridLinux = "${REMOTE_HOST}/RadeonProRender-Hybrid/master/${parsedInfo.lastCompletedBuild.number}/Artifacts/BaikalNext_Build-Ubuntu20.tar.xz"
        }

        rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${parsedInfo.lastCompletedBuild.url}">[HybridPro] Link to the used HybridPro build</a></h3>""")

        // branch postfix
        options["branchPostfix"] = ""
        if (env.BRANCH_NAME) {
            options["branchPostfix"] = "auto" + "_" + env.BRANCH_NAME.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
        } else {
            options["branchPostfix"] = "manual" + "_" + options.projectBranch.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
        }

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
        }

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
            String version = readFile("VERSION.txt").trim()

            if (env.BRANCH_NAME) {
                withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                    GithubNotificator githubNotificator = new GithubNotificator(this, options)
                    githubNotificator.init(options)
                    options["githubNotificator"] = githubNotificator
                }

                options.platforms.split(';').each() { os ->
                    List tokens = os.tokenize(':')
                    String platform = tokens.get(0)
                    platform = platform == "Windows" ? "Windows" : "Web application"
                    GithubNotificator.createStatus("Build", platform, "queued", options, "Scheduled", "${env.JOB_URL}")
                    GithubNotificator.createStatus("Sanity check", platform, "queued", options, "Scheduled", "${env.JOB_URL}")
                    if (options.deploy && platform == "Web application") {
                        GithubNotificator.createStatus("Deploy", platform, "queued", options, "Scheduled", "${env.JOB_URL}")
                    }
                }
            
                if ((env.BRANCH_NAME == "develop" || env.BRANCH_NAME == "main") && options.commitAuthor != "radeonprorender") {
                    println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                    println "[INFO] Current build version: ${version}"

                    if (env.BRANCH_NAME == "main") {
                        version = version_inc(version, 2)
                    } else {
                        version = version_inc(version, 3)
                    }

                    println "[INFO] New build version: ${version}"
                    writeFile(file: "VERSION.txt", text: version)

                    bat """
                        git commit VERSION.txt -m "buildmaster: version update to ${version}"
                        git push origin HEAD:${env.BRANCH_NAME}
                    """

                    //get commit's sha which have to be build
                    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                    options.commitShortSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()
                    options.projectBranch = options.commitSHA
                    println "[INFO] Project branch hash: ${options.projectBranch}"
                }
            }

            println "The last commit was written by ${options.commitAuthor}."
            println "Commit message: ${options.commitMessage}"
            println "Commit SHA: ${options.commitSHA}"
            println "Commit short SHA: ${options.commitShortSHA}"
            println "Version: ${options.version}"
        }
    }
}


def call(
    String projectBranch = "",
    String platforms = 'Windows:AMD_RX6800XT',
    Boolean enableNotifications = false,
    Boolean generateArtifact = true,
    Boolean deploy = true,
    String deployEnvironment = 'pr',
    String customDomain = '',
    Boolean disableSsl = false,
    Boolean rebuildDeps = true,
    Boolean updateDeps = false,
    String customHybridWin = "",
    String customHybridLinux = "",
    String customBuildLinkWindows = ""
) {
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    if (env.BRANCH_NAME) {
        switch (env.BRANCH_NAME) {
            case "develop":
                deployEnvironment = "dev"
                break
            case "main":
                deployEnvironment = "prod"
                break
        }
    }

    Boolean isPreBuilt = (customBuildLinkWindows)

    println """
        Deploy: ${deploy}
        Deploy environment: ${deployEnvironment}
        Custom domain: ${customDomain}
        Disable SSL: ${disableSsl}
        Rebuild deps: ${rebuildDeps}
        Update deps: ${updateDeps}
        Is prebuilt: ${isPreBuilt}
    """
    def options = [configuration: PIPELINE_CONFIGURATION,
                                platforms: platforms,
                                projectBranch:projectBranch,
                                projectRepo:PROJECT_REPO,
                                rebuildDeps:rebuildDeps,
                                updateDeps:updateDeps,
                                enableNotifications:enableNotifications,
                                deployEnvironment: deployEnvironment,
                                customDomain: customDomain,
                                disableSsl: disableSsl,
                                customHybridWin: customHybridWin,
                                customHybridLinux: customHybridLinux,
                                deploy:deploy, 
                                PRJ_NAME:'WebUsdViewer',
                                PRJ_ROOT:'radeon-pro',
                                BUILDER_TAG:'BuilderWebUsdViewer',
                                executeBuild:!isPreBuilt,
                                executeTests:true,
                                BUILD_TIMEOUT:'120',
                                problemMessageManager:problemMessageManager,
                                isPreBuilt:isPreBuilt,
                                customBuildLinkWindows:customBuildLinkWindows,
                                retriesForTestStage:1,
                                splitTestsExecution: false
                                ]
    try {
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&doSanityCheck, null, options)
        if (currentBuild.result == null) {
            currentBuild.result = "SUCCESS"
        }
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = problemMessageManager.publishMessages()
        if (currentBuild.result == "FAILURE"){
            GithubNotificator.closeUnfinishedSteps(options, "Build result: ${currentBuild.result}")
        }
        if (env.CHANGE_URL){
            GithubNotificator.sendPullRequestComment("Jenkins build finished as ${currentBuild.result}", options)
        } 
    }
}