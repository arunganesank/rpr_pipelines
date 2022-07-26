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


Integer getNextTestInstanceNumber(Map options) {
    downloadFiles("/volume1/CIS/WebUSD/State/TestingInstancesInfo.txt", ".")

    def instancesInfo = readJSON(file: "TestingInstancesInfo.txt")

    Integer testingNumber

    if (instancesInfo["prs"].containsKey(env.BRANCH_NAME)) {
        Integer testingNumber = instancesInfo["prs"][env.BRANCH_NAME]
    } else {
        Integer testingNumber = instancesInfo["globalCounter"]
        instancesInfo["globalCounter"] = instancesInfo["globalCounter"] >= MAX_TEST_INSTANCE_NUMBER ? 1 : instancesInfo["globalCounter"] + 1
    }
 
    options.deployEnvironment = "test${testingNumber}"

    def jsonOutputInfo = JsonOutput.toJson(instancesInfo)
    JSON serializedInfo = JSONSerializer.toJSON(jsonOutputInfo, new JsonConfig());
    writeJson(file: "TestingInstancesInfo.txt", json: serializedInfo), pretty: 4

    uploadFiles("TestingInstancesInfo.txt", "/volume1/CIS/WebUSD/State")
}


def doSanityCheckWindows(String asicName, Map options) {
    utils.reboot(this, "Windows")

    withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_APPPLICATION) {
        def installedProductCode = powershell(script: """(Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'AMD RenderStudio'\").IdentifyingNumber""", returnStdout: true)

        if (installedProductCode) {
            println("[INFO] Found installed AMD RenderStudio. Uninstall it...")
            uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
        }

        timeout(time: 10, unit: "MINUTES") {
            bat """
                start "" /wait "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[getProduct.getIdentificatorKey('Windows')]}.msi" 1>${env.WORKSPACE}\\${options.stageName}_${options.currentTry}.msi.install.log 2>&1
            """
        }
    }

    withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
        downloadFiles("/volume1/Assets/web_viewer_autotests/Kitchen_set", ".")

        downloadFiles("/volume1/CIS/WebUSD/Scripts/*", ".")
    }

    withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.SANITY_CHECK) {
        timeout(time: 2, unit: "MINUTES") {
            python3("webusd_check.py --scene_path ${env.WORKSPACE}\\Kitchen_set\\Kitchen_set.usd")
        }
    }

    dir("${options.stageName}") {
        utils.moveFiles(this, "Windows", "../webusd_check.log", "${options.stageName}.log")
        utils.moveFiles(this, "Windows", "../screen.jpg", "${options.stageName}.jpg")
    }

    archiveArtifacts(artifacts: "${options.stageName}/*")

    withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.UNINSTALL_APPPLICATION) {
        uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
    }
}


def doSanityCheck(String osName, String asicName, Map options) {
    try {
        cleanWS(osName)

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_APPPLICATION) {
            getProduct(osName, options)
        }

        switch(osName) {
            case 'Windows':
                doSanityCheckWindows(asicName, options)
                break
            default:
                println "[WARNING] ${osName} is not supported"
        }
    } catch (e) {
        options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SANITY_CHECK_FAILED.replace("<gpuName>", asicName).replace("<osName>", "Windows"))
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}


def executeBuildWindows(Map options) {
    withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
        utils.reboot(this, "Windows")

        Boolean failure = false
        String webrtcPath = "C:\\JN\\thirdparty\\webrtc"
        String amfPath = "C:\\JN\\thirdparty\\amf"

        downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF-WIN", amfPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

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
                dir("WebUsdWebServer\\dist_electron") {
                    def exe_file = findFiles(glob: '*.msi')
                    println("Found MSI files: ${exe_file}")
                    for (file in exe_file) {
                        renamed_filename = file.toString().replace(" ", "_")
                        bat """
                            rename "${file}" "${renamed_filename}"
                        """
                        makeArchiveArtifacts(name: renamed_filename, storeOnNAS: true)
                        makeStash(includes: renamed_filename, name: getProduct.getStashName("Windows"), preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        } catch(e) {
            println("Error during build on Windows")
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
}


def executeBuildLinux(Map options) {
    Boolean failure = false

    String webUsdUrlBase

    withCredentials([string(credentialsId: "WebUsdHost", variable: "remoteHost")]) {
        webUsdUrlBase = remoteHost
    }

    if (options.deployEnvironment == "pr") {
        Integer testingNumber = getNextTestInstanceNumber(options)
        options.deployEnvironment = "test${testingNumber}"
    }

    downloadFiles("/volume1/CIS/WebUSD/Additional/envs/webusd.env.${options.deployEnvironment}", "./WebUsdWebServer", "--quiet")
    sh "mv ./WebUsdWebServer/webusd.env.${options.deployEnvironment} ./WebUsdWebServer/.env.production"

    withNotifications(title: "Ubuntu20", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
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

    withNotifications(title: "Ubuntu20", options: options, configuration: NotificationConfiguration.DEPLOY_APPLICATION) {
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
                    String url = TEMPLATE.replace("<instance>", options.deployEnvironment)
                    rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${url}">[${options.deployEnvironment}] Link to web application</a></h3>""")
                }
            } catch (e) {
                println "[ERROR] Error during deploy"
                println(e.toString())
                failure = true
                status = false
            }

            if (failure) {
                currentBuild.result = "FAILED"
                error "error during deploy"
            }
        }
    }

    withNotifications(title: "Ubuntu20", options: options, configuration: NotificationConfiguration.NOTIFY_BY_TG) {
        if (options.deploy) {
            notifyByTg(options)
        }
    }
}


def executeBuild(String osName, Map options) {  
    try {
        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CLEAN_ENVIRONMENT) {
            cleanWS(osName)
        }

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
            }
        }

        if (env.CHANGE_URL) {
            // TODO add github notification
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


def call(
    String projectBranch = "",
    String platforms = 'Windows:AMD_RX6800XT;Ubuntu20',
    Boolean enableNotifications = false,
    Boolean generateArtifact = true,
    Boolean deploy = true,
    String deployEnvironment = 'pr',
    Boolean rebuildDeps = false,
    Boolean updateDeps = false,
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
        Rebuild deps: ${rebuildDeps}
        Update deps: ${updateDeps}
        Is prebuilt: ${isPreBuilt}
    """

    try {
        multiplatform_pipeline(platforms, null, this.&executeBuild, this.&doSanityCheck, null,
                                [configuration: PIPELINE_CONFIGURATION,
                                projectBranch:projectBranch,
                                projectRepo:PROJECT_REPO,
                                rebuildDeps:rebuildDeps,
                                updateDeps:updateDeps,
                                enableNotifications:enableNotifications,
                                deployEnvironment: deployEnvironment,
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
                                ])
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = problemMessageManager.publishMessages()
    }
}