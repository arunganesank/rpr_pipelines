import groovy.transform.Field
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/WebUsdViewer.git"
@Field final String TEST_REPO = "git@github.com:luxteam/jobs_test_web_viewer.git"

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


def executeTestCommand(String osName, String asicName, Map options) {
    def testTimeout = options.timeouts["${options.tests}"]
    String testsNames = options.tests
    String testsPackageName = options.testsPackage
    if (options.testsPackage != "none" && !options.isPackageSplitted) {
        if (testsNames.contains(".json")) {
            // if tests package isn't splitted and it's execution of this package - replace test package by test group and test group by empty string
            testsPackageName = options.tests
            testsNames = ""
        } else {
            // if tests package isn't splitted and it isn't execution of this package - replace tests package by empty string
            testsPackageName = ""
        }
    }

    println "Set timeout to ${testTimeout}"

    String mode = osName == "Windows" ? "desktop": "web"

    timeout(time: testTimeout, unit: 'MINUTES') {
        switch (osName) {
            case "Windows":
                dir('scripts') {
                    bat """
                        set TOOL_VERSION=${options.pluginVersion}
                        run.bat \"${testsPackageName}\" \"${testsNames}\" ${mode} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
                break
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true
    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

                dir("driver") {
                    if (osName == "Windows") {
                        downloadFiles("/volume1/CIS/WebUSD/Drivers/chromedriver_desktop.exe", ".")
                        bat("rename chromedriver_desktop.exe chromedriver.exe")
                    } else {
                        downloadFiles("/volume1/CIS/WebUSD/Drivers/chromedriver_web.exe", ".")
                        bat("rename chromedriver_web.exe chromedriver.exe")
                    }
                }
            }
        }

        if (osName == "Windows") {
            withNotificatiexecuteTestCommandons(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                timeout(time: "10", unit: "MINUTES") {
                    getProduct(osName, options)

                    def installedProductCode = powershell(script: """(Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'AMD RenderStudio'\").IdentifyingNumber""", returnStdout: true)

                    // TODO: compare product code of built application and installed application
                    if (installedProductCode) {
                        println("[INFO] Found installed AMD RenderStudio. Uninstall it...")
                        uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
                    }

                    dir("${CIS_TOOLS}\\..\\PluginsBinaries") {
                        bat "msiexec.exe /i ${options[getProduct.getIdentificatorKey('Windows')]}.msi /qb"
                    }
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
                String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_assets" : "/mnt/c/TestResources/render_studio_autotests_assets"
                downloadFiles("/volume1/web/Assets/render_studio_autotests/", assetsDir)
            }
        }

        options.REF_PATH_PROFILE = "/volume1/Baselines/render_studio_autotests/${asicName}-${osName}"

        outputEnvironmentInfo(osName, "", options.currentTry)

        if (options["updateRefs"].contains("Update")) {
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
                executeGenTestRefCommand(osName, options, options["updateRefs"].contains("clean"))
                uploadFiles("./Work/GeneratedBaselines/", options.REF_PATH_PROFILE)
                // delete generated baselines when they're sent 
                switch(osName) {
                    case "Windows":
                        bat """
                            if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines
                        """
                        break

                    default:
                        sh """
                            rm -rf ./Work/GeneratedBaselines
                        """
                }
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_baselines" : "/mnt/c/TestResources/render_studio_autotests_baselines"
                println "[INFO] Downloading reference images for ${options.tests}"
                options.tests.split(" ").each { downloadFiles("${options.REF_PATH_PROFILE}/${it.contains(".json") ? "" : it}", baselineDir) }
            }
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)
    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }
        println e.toString()
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", e)
        }
    } finally {
        try {
            dir(options.stageName) {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/RenderStudio/session_report.json")) {

                        def sessionReport = readJSON file: 'Results/RenderStudio/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"

                        utils.stashTestData(this, options, options.storeOnNAS)
                        // reallocate node if there are still attempts
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            if (sessionReport.summary.total != sessionReport.summary.skipped) {
                                // remove broken render studio
                                uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
                                removeInstaller(osName: osName, options: options, extension: "msi")
                                String errorMessage = (options.currentTry < options.nodeReallocateTries) ? "All tests were marked as error. The test group will be restarted." : "All tests were marked as error."
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.engine)
                        }
                    }
                }
            }
        } catch(e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
                if (e instanceof ExpectedExceptionWrapper) {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                    throw e
                } else {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${BUILD_URL}")
                    throw new ExpectedExceptionWrapper(NotificationConfiguration.FAILED_TO_SAVE_RESULTS, e)
                }
            }
        }
        if (!options.executeTestsFinished) {
            bat """
                shutdown /r /f /t 0
            """
        }
    }
}


String patchSubmodule() {
    String commitSHA

    if (isUnix()) {
        commitSHA = sh (script: "git log --format=%h -1 ", returnStdout: true).trim()
    } else {
        commitSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()
    }

    String version = readFile("VERSION.txt").trim()
    writeFile(file: "VERSION.txt", text: "Version: ${version}. Hash: ${commitSHA}")
}


def patchVersions(Map options) {
    dir("WebUsdLiveServer") {
        patchSubmodule()
    }

    dir("WebUsdRouteServer") {
        patchSubmodule()
    }

    dir("WebUsdStorageServer") {
        patchSubmodule()
    }

    dir("WebUsdFrontendServer") {
        patchSubmodule()
    }

    dir("WebUsdStreamServer") {
        patchSubmodule()
    }

    String version = readFile("VERSION.txt").trim()

    if (env.CHANGE_URL) {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. PR: #${env.CHANGE_ID}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    } else {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. Branch: ${env.BRANCH_NAME ?: options.projectBranch}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
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

        dir("WebUsdFrontendServer") {
            frontendVersion = readFile("VERSION.txt").trim()
        }

        renderStudioVersion = readFile("VERSION.txt").trim()

        String envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
        envProductionContent = envProductionContent + "VUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"
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

    String frontendVersion
    String renderStudioVersion

    dir("WebUsdFrontendServer") {
        frontendVersion = readFile("VERSION.txt").trim()
    }

    renderStudioVersion = readFile("VERSION.txt").trim()

    envProductionContent = readFile("./WebUsdFrontendServer/.env.production")
    envProductionContent = envProductionContent + "\nVUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"
    writeFile(file: "./WebUsdFrontendServer/.env.production", text: envProductionContent)

    options["stage"] = "Build"

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
        println "[INFO] Start build" 
        println "[INFO] Download Web-rtc and AMF" 
        downloadFiles("/volume1/CIS/radeon-pro/webrtc-linux/", "${CIS_TOOLS}/../thirdparty/webrtc", "--quiet")
        downloadFiles("/volume1/CIS/WebUSD/AMF/", "${CIS_TOOLS}/../thirdparty/AMF", "--quiet")
        try {
            println "[INFO] Start build"

            sh """
                mkdir --parents Build/Install
            """

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

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.DEPLOY_APPLICATION) {
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

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.NOTIFY_BY_TG) {
        if (options.deploy) {
            notifyByTg(options)
        }
    }
}


def executeBuild(String osName, Map options) {  
    try {
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
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


def getReportBuildArgs(Map options) {
    if (options["isPreBuilt"]) {
        return """RenderStudio "PreBuilt" "PreBuilt" "PreBuilt" \"\" \"\""""
    } else {
        return """RenderStudio ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"\" \"\""""
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

        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
            String version = readFile("VERSION.txt").trim()

            if (env.BRANCH_NAME) {
                withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                    GithubNotificator githubNotificator = new GithubNotificator(this, options)
                    githubNotificator.init(options)
                    options["githubNotificator"] = githubNotificator
                    githubNotificator.initPreBuild("${BUILD_URL}")
                }

                options.platforms.split(';').each() { os ->
                    List tokens = os.tokenize(':')
                    String platform = tokens.get(0)
                    platform = platform == "Windows" ? "Windows" : "Web application"
                    GithubNotificator.createStatus("Build", platform, "queued", options, "Scheduled", "${env.JOB_URL}")

                    if (os == "Windows") {
                        GithubNotificator.createStatus("Sanity check", platform, "queued", options, "Scheduled", "${env.JOB_URL}")
                    }

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

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_usdviewer') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            options['testsBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"
            def packageInfo

            if (options.testsPackage != "none") {
                packageInfo = readJSON file: "jobs/${options.testsPackage}"
                options.isPackageSplitted = packageInfo["split"]
                // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                if (options.forceBuild && options.isPackageSplitted && options.tests) {
                    options.testsPackage = "none"
                }
            }

            if (options.testsPackage != "none") {
                if (options.isPackageSplitted) {
                    println "[INFO] Tests package '${options.testsPackage}' can be splitted"
                } else {
                    // save tests which user wants to run with non-splitted tests package
                    if (options.tests) {
                        tests = options.tests.split(" ") as List
                    }
                    println "[INFO] Tests package '${options.testsPackage}' can't be splitted"
                }
                // modify name of tests package if tests package is non-splitted (it will be use for run package few time with different engines)
                String modifiedPackageName = "${options.testsPackage}~"

                // receive list of group names from package
                List groupsFromPackage = []

                if (packageInfo["groups"] instanceof Map) {
                    groupsFromPackage = packageInfo["groups"].keySet() as List
                } else {
                    // iterate through all parts of package
                    packageInfo["groups"].each() {
                        groupsFromPackage.addAll(it.keySet() as List)
                    }
                }

                groupsFromPackage.each {
                    if (options.isPackageSplitted) {
                        tests << it
                    } else {
                        if (tests.contains(it)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it}"
                        }
                    }
                }

                tests.each {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                modifiedPackageName = modifiedPackageName.replace('~,', '~')

                if (options.isPackageSplitted) {
                    options.testsPackage = "none"
                } else {
                    options.testsPackage = modifiedPackageName
                    // check that package is splitted to parts or not
                    if (packageInfo["groups"] instanceof Map) {
                        tests << "${modifiedPackageName}"
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            tests << "${modifiedPackageName}".replace(".json", ".${i}.json")
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                    
                    options.tests = tests
                }
            } else {
                options.tests = options.tests.split(" ") as List
                options.tests.each {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
            }
        }
        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }
        options.testsList = options.tests
        println "timeouts: ${options.timeouts}"
    }

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        options.reportUpdater = new ReportUpdater(this, env, options)
        options.reportUpdater.init(this.&getReportBuildArgs)
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []
            dir("summaryTestResults") {
                unstashCrashInfo(options['nodeRetry'])
                testResultList.each {
                    dir("$it".replace("testResult-", "")) {
                        try {
                            makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                        } catch (e) {
                            println """
                                [ERROR] Failed to unstash ${it}
                                ${e.toString()}
                            """
                            lostStashes << ("'${it}'".replace("testResult-", ""))
                        }
                    }
                }
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"\" \"{}\"
                    """
                }
            } catch (e) {
                println "[ERROR] Can't generate number of lost tests"
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            writeJSON file: 'retry_info.json', json: JSONSerializer.toJSON(retryInfo, new JsonConfig()), pretty: 4
                        }
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(options)}"
                    }
                }
            } catch (e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println """
                        [ERROR] Failed to build test report.
                        ${e.toString()}
                    """
                    if (!options.testDataSaved && !options.storeOnNAS) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                                "Test Report", "Summary Report, Compare Report", options.storeOnNAS, \
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                            options.testDataSaved = true 
                        } catch (e1) {
                            println """
                                [WARNING] Failed to publish test data.
                                ${e.toString()}
                            """
                        }
                    }
                    throw e
                } else {
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(errorMessage)
                }
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        get_status.bat ..\\summaryTestResults
                    """
                }
            } catch (e) {
                println """
                    [ERROR] during slack status generation.
                    ${e.toString()}
                """
            }

            try {
                dir("jobs_launcher") {
                    archiveArtifacts "launcher.engine.log"
                }
            } catch(e) {
                println """
                    [ERROR] during archiving launcher.engine.log
                    ${e.toString()}
                """
            }

            Map summaryTestResults = [:]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults = [passed: summaryReport.passed, failed: summaryReport.failed, error: summaryReport.error]
                if (summaryReport.error > 0) {
                    println "[INFO] Some tests marked as error. Build result = FAILURE."
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                } else if (summaryReport.failed > 0) {
                    println "[INFO] Some tests marked as failed. Build result = UNSTABLE."
                    currentBuild.result = "UNSTABLE"
                    options.problemMessageManager.saveUnstableReason(NotificationConfiguration.SOME_TESTS_FAILED)
                }
            } catch(e) {
                println """
                    [ERROR] CAN'T GET TESTS STATUS
                    ${e.toString()}
                """
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            try {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            } catch (e) {
                println e.toString()
                options.testsStatus = ""
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report", "Summary Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options,
                            "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options,
                            NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println e.toString()
        throw e
    }
}


def call(
    String projectBranch = "",
    String platforms = 'Windows:AMD_RX6800XT;Web',
    Boolean enableNotifications = false,
    Boolean generateArtifact = true,
    Boolean deploy = true,
    String deployEnvironment = 'pr',
    String customDomain = '',
    Boolean disableSsl = false,
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
        Is prebuilt: ${isPreBuilt}
    """
    def options = [configuration: PIPELINE_CONFIGURATION,
                                platforms: platforms,
                                projectBranch:projectBranch,
                                projectRepo:PROJECT_REPO,
                                testRepo:TEST_REPO,
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
                                splitTestsExecution: true,
                                storeOnNAS: true,
                                flexibleUpdates: true
                                ]
    try {
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, null, options)
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