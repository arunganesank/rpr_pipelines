import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType


@Field final String PROJECT_REPO = "https://github.com/amfdev/StreamingSDK.git"
@Field final String TESTS_REPO = "https://github.com/luxteam/jobs_test_streaming_sdk.git"
@Field final String DRIVER_REPO = "https://github.com/amfdev/AMDVirtualDrivers.git"
@Field final String AMF_TESTS_REPO = "https://github.com/amfdev/AMFTests.git"
@Field final Map driverTestsExecuted = new ConcurrentHashMap()
@Field final List WEEKLY_REGRESSION_CONFIGURATION = ["HeavenDX11", "HeavenOpenGL", "ValleyDX11", "ValleyOpenGL", "Dota2Vulkan"]
@Field final String AUTOTESTS_PATH = "drivers/amf/stable/tools/tests/StreamingSDKTests"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "Android", "Ubuntu20"],
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "PUBG": "PUBG",
            "LoL": "LoL",
            "HeavenDX9": "HeavenDX9",
            "HeavenDX11": "HeavenDX11",
            "HeavenOpenGL": "HeavenOpenGL",
            "ValleyDX9": "ValleyDX9",
            "ValleyDX11": "ValleyDX11",
            "ValleyOpenGL":"ValleyOpenGL", 
            "Dota2DX11": "Dota2DX11",
            "Dota2Vulkan": "Dota2Vulkan", 
            "LatencyTool": "LatencyTool",
            "Non3DTestCases": "Non3DTestCases"
        ]
    ]
)


Boolean shouldSkipBuild(Map options, String osName) {
    return options.skipBuild.contains(osName)
}


String getServerLabels(Map options) {
    return "Windows && ${options.TESTER_TAG} && gpu${options.asicName} && !Disabled"
}

String getClientLabels(Map options) {
    return "Windows && ${options.CLIENT_TAG} && !Disabled"
}

String getMulticonnectionClientLabels(Map options) {
    return "${options.osName} && ${options.MULTICONNECTION_CLIENT_TAG} && !Disabled"
}


def getReportBuildArgs(String engineName, Map options) {
    if (options.storeOnNAS) {
        String branchName = env.BRANCH_NAME ?: options.projectBranch
        return "StreamingSDK ${options.commitSHA} ${branchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\""
    } else {
        return "AMDLink - - -"
    }
}


int getNumberOfRequiredAndroidDevices(Map options) {
    int androidDevicesNumber = 0

    if (options["osName"] == "Windows") {
        if (options.tests == "regression.2.json~" || options.tests == "regression.3.json~") {
            // some part of Windows regression contains multiconnection test groups
            androidDevicesNumber = 1
        } else if (options.multiconnectionConfiguration.android_client.keySet().any { (options.tests.split("-")[0].split() as List).contains(it) }) {
            // find multiconnection test group with max number of required Android devices
            (options.tests.split("-")[0].split() as List).each() {
                if (options.multiconnectionConfiguration.android_client.containsKey(it)) {
                    int currentDevicesNumber = options.multiconnectionConfiguration.android_client[it]

                    if (currentDevicesNumber > androidDevicesNumber) {
                        androidDevicesNumber = currentDevicesNumber
                    }
                }
            }
        }
    } else if (options["osName"] == "Android") {
        if (options.multiconnectionConfiguration.android_client.keySet().any { (options.tests.split("-")[0].split() as List).contains(it) }) {
            // some Android tests can requuire 2+ devices, check it
            (options.tests.split("-")[0].split() as List).each() {
                if (options.multiconnectionConfiguration.android_client.containsKey(it)) {
                    int currentDevicesNumber = options.multiconnectionConfiguration.android_client[it]

                    if (currentDevicesNumber > androidDevicesNumber) {
                        androidDevicesNumber = currentDevicesNumber
                    }
                }
            }
        } else {
            // at least 1 Android device is required for Android autotests
            androidDevicesNumber = 1
        }
    }

    return androidDevicesNumber
}


Boolean isIdleClient(Map options) {
    Boolean result = false

    // conditions for Windows autotests for additional devices (Android device and Second Windows client machine)
    int androidDevicesNumber = getNumberOfRequiredAndroidDevices(options)
    Boolean requiresSecondWinClient = options.multiconnectionConfiguration.second_win_client.any { (options.tests.split("-")[0].split() as List).contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~"

    println("Required ${androidDevicesNumber} Android device(s)")

    def suitableNodes = nodesByLabel label: getServerLabels(options), offline: false

    for (node in suitableNodes) {
        if (utils.isNodeIdle(node)) {
            result = true
        }
    }

    println(result)

    if (options["osName"] == "Windows") {
        Boolean firstClientReady = false

        // wait client machine
        suitableNodes = nodesByLabel label: getClientLabels(options), offline: false

        for (node in suitableNodes) {
            if (utils.isNodeIdle(node)) {
                firstClientReady = true
            }
        }

        result &= firstClientReady

        println(result)

        Boolean devicesAvailable = false

        if (androidDevicesNumber > 0) {
            lock(label: options.ANDROID_TAG, quantity: androidDevicesNumber, resource : null, skipIfLocked: true, variable: "ANDROID_DEVICES") {
                devicesAvailable = true
            }

            // wait required number of Android devices
            result &= devicesAvailable

            println(result)

            println(options["finishedBuildStages"])
            if (!options["finishedBuildStages"]["Android"] && !options.skipBuild.contains("Android")) {
                result = false
            }

            println(result)
        }

        if (requiresSecondWinClient) {
            Boolean secondClientReady = false

            // wait multiconnection client machine
            suitableNodes = nodesByLabel label: getMulticonnectionClientLabels(options), offline: false

            for (node in suitableNodes) {
                if (utils.isNodeIdle(node)) {
                    secondClientReady = true
                }
            }

            result &= secondClientReady
        }

        println(result)

        return result
    } else if (options["osName"] == "Android") {
        Boolean devicesAvailable = false

        lock(label: options.ANDROID_TAG, quantity: androidDevicesNumber, resource : null, skipIfLocked: true, variable: "ANDROID_DEVICES") {
            devicesAvailable = true
        }

        // wait required number of Android devices
        result &= devicesAvailable
        // wait when Windows artifact will be built
        return result && (options["finishedBuildStages"]["Windows"] || options.skipBuild.contains("Windows"))
    } else if (options["osName"] == "Ubuntu20") {
        Boolean firstClientReady = false

        // wait client machine
        suitableNodes = nodesByLabel label: getClientLabels(options), offline: false

        for (node in suitableNodes) {
            if (utils.isNodeIdle(node)) {
                firstClientReady = true
            }
        }

        if (!options["finishedBuildStages"]["Windows"] && !options.skipBuild.contains("Windows")) {
            result = false
        }

        result &= firstClientReady

        return result
    }
}


def getClientScreenWidth(String osName, Map options) {
    try {
        switch(osName) {
            case "Windows":
                return powershell(script: "[System.Windows.Forms.SystemInformation]::PrimaryMonitorSize.Width", returnStdout: true).split()[-1].trim()
            case "Ubuntu20":
                return sh(script: "xdpyinfo | awk '/dimensions/{split(\$2,a,\"x\"); print a[1]}'", returnStdout: true).trim()
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    } catch (e) {
        println("[ERROR] Failed to get client screen width")
        println(e)

        return 1920
    }
}


def getClientScreenHeight(String osName, Map options) {
    try {
        switch(osName) {
            case "Windows":
                return powershell(script: "[System.Windows.Forms.SystemInformation]::PrimaryMonitorSize.Height", returnStdout: true).split()[-1].trim()
            case "Ubuntu20":
                return sh(script: "xdpyinfo | awk '/dimensions/{split(\$2,a,\"x\"); print a[2]}'", returnStdout: true).trim()
            case "OSX":
                println("Unsupported OS")
                break
            default:
                println("Unsupported OS")
        }
    } catch (e) {
        println("[ERROR] Failed to get client screen height")
        println(e)

        return 1080
    }
}


def prepareTool(String osName, Map options, String executionType = null) {
    switch(osName) {
        case "Windows":
            utils.clearCurrentDir(this, osName)
            if (options.tests.startsWith("FS_") || options.tests.contains(" FS_")) {
                downloadFiles("/volume1/CIS/StreamingSDK/Builds/latest/StreamingSDK_Windows.zip", ".")
                unzip(zipFile: "StreamingSDK_Windows.zip")
            } else {
                downloadFiles("/volume1/CIS/StreamingSDK/Builds/latest/StreamingSDK_Windows.zip", ".")
                unzip(zipFile: "StreamingSDK_Windows.zip")

                if (options["engine"] == "LatencyTool") {
                    downloadFiles("/volume1/CIS/StreamingSDK/Builds/latest/LatencyTool_Windows.zip", ".")
                    unzip(zipFile: "LatencyTool_Windows.zip")
                }
            }

            break
        case "Android":
            downloadFiles("/volume1/CIS/StreamingSDK/Builds/latest/StreamingSDK_Android.zip", ".")
            unzip(zipFile: "StreamingSDK_Android.zip")
            utils.removeFile(this, "Windows", "app-arm.apk")
            utils.renameFile(this, "Windows", "app-arm-${options.androidTestingBuildName}.apk", "app-arm.apk")
            break
        case "Ubuntu20":
            utils.clearCurrentDir(this, osName)
            downloadFiles("/volume1/CIS/StreamingSDK/Builds/latest/StreamingSDK_Ubuntu20.zip", ".")
            unzip(zipFile: "StreamingSDK_Ubuntu20.zip")
            sh("chmod u+x RemoteGameServer")
            break
        case "OSX":
            println("Unsupported OS")
            break
        default:
            println("Unsupported OS")
    }
}


def unpackDriver(String osName, Map options) {
    switch(osName) {
        case "Windows":
            makeUnstash(name: "DriverWindows", unzip: false, storeOnNAS: options.storeOnNAS)
            unzip(zipFile: "${options.winTestingDriverName}.zip")
            break
        default:
            println("Unsupported OS")
    } 
}


def uninstallDriver(Map options) {
    try {
        powershell """
            \$command = "cd ${WORKSPACE}\\AMDVirtualDrivers; .\\uninstall.bat | Out-File ..\\${options.stageName}_${options.currentTry}_uninstall_driver.log"
            Start-Process powershell "\$command" -Verb RunAs -Wait
        """
    } catch (e) {
        println("[ERROR] Failed to uninstall driver")
        throw e
    }
}


def runDriverTests(Map options) {
    String title = "Driver tests"
    String logName = "${options.stageName}_${options.currentTry}_test_driver.log"
    String url = "${env.BUILD_URL}/artifact/${logName}"

    try {
        // start script which agree to install unsigned driver
        powershell """
            \$command = "C:\\Python39\\python.exe \$env:CIS_TOOLS\\register_dev_driver.py"
            Start-Process powershell "\$command" -Verb RunAs
        """

        powershell """
            \$command = "cd ${WORKSPACE}\\AMDVirtualDrivers; .\\AMDHidTests.exe | Out-File ..\\${logName}"
            Start-Process powershell "\$command" -Verb RunAs -Wait
        """

        dir("../../../../../..") {
            archiveArtifacts artifacts: logName, allowEmptyArchive: true
        }

        String driverTestsLog = readFile("..\\${logName}")
        String status = driverTestsLog.contains("FAILED") ? "action_required" : "success"
        String description = driverTestsLog.contains("FAILED") ? "Testing finished with error" : "Testing successfully finished"

        GithubNotificator.updateStatus("Test", title, status, options, description, url)
    } catch (e) {
        println("[ERROR] Failed to run driver tests")
        throw e
    } finally {
        driverTestsExecuted["executed"] = true
    }
}


def getServerIpAddress(String osName) {
    switch(osName) {
        case "Windows":
            return bat(script: "echo %IP_ADDRESS%",returnStdout: true).split('\r\n')[2].trim()
            break
        case "Ubuntu20":
            return sh(script: "echo \$IP_ADDRESS",returnStdout: true).trim()
            break
        default:
            println("Unsupported OS")
    }
}


def getGPUName(String osName) {
    try {
        dir("jobs_launcher") {
            dir("core") {
                switch(osName) {
                    case "Windows":
                        return python3("-c \"from system_info import get_gpu; print(get_gpu())\"").split('\r\n')[2].trim()
                        break
                    case "Ubuntu20":
                        return python3("-c \"from system_info import get_gpu; print(get_gpu())\"").trim()
                        break
                    default:
                        println("Unsupported OS")
                }
            }
        }
    } catch (e) {
        println("[ERROR] Failed to get GPU name")
        throw e
    }
}


def getOSName(String osName) {
    try {
        dir("jobs_launcher") {
            dir("core") {
                switch(osName) {
                    case "Windows":
                        return python3("-c \"from system_info import get_os; print(get_os())\"").split('\r\n')[2].trim()
                        break
                    case "Ubuntu20":
                        return python3("-c \"from system_info import get_os; print(get_os())\"").trim()
                        break
                    default:
                        println("Unsupported OS")
                }
            }
        }
    } catch (e) {
        println("[ERROR] Failed to get OS name")
        throw e
    }
}


def getCommunicationPort(String osName) {
    switch(osName) {
        case "Windows":
            return bat(script: "echo %COMMUNICATION_PORT%",returnStdout: true).split('\r\n')[2].trim()
            break
        case "Ubuntu20":
            return sh(script: "echo \$COMMUNICATION_PORT",returnStdout: true).trim()
            break
        default:
            println("Unsupported OS")
    }
}


def closeAmdLink(String osName, Map options, String executionType) {
    try {
        switch(executionType) {
            case "server":
                bat """
                    taskkill /f /im \"RadeonSoftware.exe\"
                """
                break
            default:
                bat """
                    taskkill /f /im \"AMDLink.exe\"
                """
        }
    } catch (e) {
    }
}


def executeTestCommand(String osName, String asicName, Map options, String executionType = "") {
    String testsNames
    String testsPackageName
    if (options.testsPackage != "none" && !options.isPackageSplitted) {
        if (options.tests.contains(".json")) {
            // if tests package isn't splitted and it's execution of this package - replace test package by test group and test group by empty string
            testsPackageName = options.tests
            testsNames = ""
        } else {
            // if tests package isn't splitted and it isn't execution of this package - replace tests package by empty string
            testsPackageName = "none"
            testsNames = options.tests
        }
    } else {
        testsPackageName = "none"
        testsNames = options.tests
    }

    // regression.json suite in weekly
    if (testsNames.contains("regression")) {
        testsPackageName = options.tests
        testsNames = ""
    }

    String collectTraces = "False"

    if ((executionType == "server" && options.serverCollectTraces) || (executionType == "client" && options.clientCollectTraces)) {
        collectTraces = options.collectTracesType
    }

    dir("scripts") {
        switch (osName) {
            case "Windows":
                if (executionType == "mcClient") {
                    bat """
                        set COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                        run_mc.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                    """
                } else if (executionType == "client") {
                    if (options.serverInfo.osName.contains("Windows")) {
                        bat """
                            set COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                            run_windows_client_for_windows.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" \"${options.engine}\" ${collectTraces} ${options.collectStreamingDump} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                        """
                    } else if (options.serverInfo.osName.contains("Ubuntu")) {
                        bat """
                            set COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                            run_windows_client_for_ubuntu.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${options.serverInfo.gpuName}\" \"${options.serverInfo.osName}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                        """
                    } else {
                        throw new Exception("Unknown server OS name ${options.serverInfo.osName}")
                    }
                } else {
                    def screenResolution = "${options.clientInfo.screenWidth}x${options.clientInfo.screenHeight}"

                    bat """
                        set COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                        run_windows_server.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${screenResolution}\" \"${options.engine}\" ${collectTraces} \"${options.inGameResolution}\" ${options.collectStreamingDump} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                    """
                }

                break

            case "Android":
                bat """
                    set COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                    run_android.bat \"${testsPackageName}\" \"${testsNames}\" \"${options.engine}\" \"${options.inGameResolution}\" ${options.collectStreamingDump} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\" 2>&1
                """

                break

            case "Ubuntu20":
                def screenResolution = "${options.clientInfo.screenWidth}x${options.clientInfo.screenHeight}"

                sh """
                    export COLLECT_INTERNAL_DRIVER_VERSION=${options.collectInternalDriverVersion}
                    ./run_ubuntu_server.sh \"${testsPackageName}\" \"${testsNames}\" \"${options.serverInfo.ipAddress}\" \"${options.serverInfo.communicationPort}\" \"${screenResolution}\" \"${options.engine}\" ${collectTraces} 1>> \"../${options.stageName}_${options.currentTry}_${executionType}.log\"  2>&1
                """
                break

            default:
                println "Linux isn't supported"
        }
    }
}


def saveResults(String osName, Map options, String executionType, Boolean stashResults, Boolean executeTestsFinished) {
    try {
        dir(options.stageName) {
            utils.moveFiles(this, osName, "../*.log", ".")
            utils.moveFiles(this, osName, "../scripts/*.log", ".")
            utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}_${executionType}.log")
        }

        archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

        if (stashResults) {
            dir("Work") {
                if (fileExists("Results/StreamingSDK/session_report.json")) {
                    def sessionReport = readJSON file: "Results/StreamingSDK/session_report.json"

                    if (executionType == "client" || executionType == "android") {
                        String stashPostfix = executionType == "client" ? "_client" : ""

                        println "Stashing all test results to : ${options.testResultsName}${stashPostfix}"
                        makeStash(includes: '**/*', name: "${options.testResultsName}${stashPostfix}", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    } else if (executionType == "mcClient") {
                         println "Stashing results of multiconnection client"
                        makeStash(includes: '**/*_second_client.html,**/*.jpg,**/*.webp,**/*.mp4', name: "${options.testResultsName}_sec_cl", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.json', name: "${options.testResultsName}_sec_cl_j", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    } else {
                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${env.BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${env.BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${env.BUILD_URL}")
                        }

                        println "Stashing logs to : ${options.testResultsName}_server"
                        makeStash(includes: '**/*log,**/*html', name: "${options.testResultsName}_serv_l", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.json', name: "${options.testResultsName}_server", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*.jpg,**/*.webp,**/*.mp4', name: "${options.testResultsName}_and_cl", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                        makeStash(includes: '**/*_server.zip', name: "${options.testResultsName}_ser_t", allowEmpty: true, storeOnNAS: options.storeOnNAS)
                    }

                    utils.analyzeResults(this, sessionReport, options, 0.5)
                }
            }
        }
    } catch(e) {
        if (executeTestsFinished) {
            if (e instanceof ExpectedExceptionWrapper) {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${env.BUILD_URL}")
                throw e
            } else {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${env.BUILD_URL}")
                throw new ExpectedExceptionWrapper(NotificationConfiguration.FAILED_TO_SAVE_RESULTS, e)
            }
        }
    }
}


def prepareLatencyToolEnvironment() {
    if (!isUnix()) {
        try {
            bat """
                taskkill /f /im \"anydesk.exe\"
                taskkill /f /im \"anydesk.exe\"
                taskkill /f /im \"pservice.exe\"
                taskkill /f /im \"parsecd.exe\"
                taskkill /f /im \"steam.exe\"
            """
        } catch (e) {
            println("[WARNING] Failed to close apps for Latency Tool")
            println(e)
        }
    }
}


def executeTestsClient(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {
        if (options.tests.contains("AMD_Link") || options.engine == "LatencyTool") {
            utils.reboot(this, osName)
        }

        if (options.engine == "LatencyTool") {
            prepareLatencyToolEnvironment()
        }

        timeout(time: "10", unit: "MINUTES") {
            if (!options.skipBuild.contains("Windows")) {
                cleanWS(osName)
            } else {
                utils.removeDir(this, osName, options.stageName)

                if (!fileExists("StreamingSDK/RemoteGameClient.exe")) {
                    options.problemMessageManager.saveSpecificFailReason("Streaming SDK executable not found", options["stageName"], osName)
                    throw new ExpectedExceptionWrapper("Streaming SDK executable not found")
                }
            }

            dir("../../../../../..") {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO, cleanCheckout: options.skipBuild.size() == 0)
            }
        }

        timeout(time: "5", unit: "MINUTES") {
            dir("jobs_launcher/install"){
                bat """
                    install_pylibs.bat
                """
            }

            if (options.projectBranch && !options.skipBuild.contains("Windows")) {
                dir("StreamingSDK") {
                    prepareTool(osName, options)
                }
            }
        }

        options["clientInfo"]["screenWidth"] = getClientScreenWidth(osName, options)
        println("[INFO] Screen width on client machine: ${options.clientInfo.screenWidth}")

        options["clientInfo"]["screenHeight"] = getClientScreenHeight(osName, options)
        println("[INFO] Screen height on client machine: ${options.clientInfo.screenHeight}")

        if (options.isDevelopBranch) {
            if (!driverTestsExecuted.containsKey("executed") || !driverTestsExecuted["executed"]) {
                try {
                    println("[INFO] Execute driver tests")

                    dir("AMDVirtualDrivers") {
                        unpackDriver(osName, options)
                        uninstallDriver(options)
                        runDriverTests(options)
                    }
                } catch (e) {
                    println(e)
                    GithubNotificator.updateStatus("Test", "Drivet tests", "action_required", options, "Failed to test driver")
                }
            }
        }

        options["clientInfo"]["ready"] = true
        println("[INFO] Client is ready to run tests")

        while (!options["serverInfo"]["ready"]) {
            if (options["serverInfo"]["failed"]) {
                throw new Exception("Server was failed")
            }

            sleep(5)
        }

        println("Client is synchronized with state of server. Start tests")
        
        executeTestCommand(osName, asicName, options, "client")

        options["clientInfo"]["executeTestsFinished"] = true
    } catch (e) {
        options["clientInfo"]["ready"] = false
        options["clientInfo"]["failed"] = true
        options["clientInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on client"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["clientInfo"]["finished"] = true

        saveResults(osName, options, "client", stashResults, options["clientInfo"]["executeTestsFinished"])

        if (options.tests.contains("AMD_Link")) {
            closeAmdLink(osName, options, "client")
        }

        if (options.engine == "LatencyTool") {
            utils.reboot(this, osName)
        }
    }
}


def executeTestsServer(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {
        killAdbServer()

        if (options.tests.contains("AMD_Link") || options.engine == "LatencyTool") {
            utils.reboot(this, osName)
        }

        if (options.engine == "LatencyTool") {
            prepareLatencyToolEnvironment()
        }

        withNotifications(title: options["stageName"], options: options, logUrl: "${env.BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                if (!options.skipBuild.contains(osName)) {
                    cleanWS(osName)
                } else {
                    utils.removeDir(this, osName, options.stageName)

                    String serverExecutableName = osName == "Windows" ? "RemoteGameServer.exe" : "RemoteGameServer"
                    if (!fileExists("StreamingSDK/${serverExecutableName}")) {
                        options.problemMessageManager.saveSpecificFailReason("Streaming SDK executable not found", options["stageName"], osName)
                        throw new ExpectedExceptionWrapper("Streaming SDK executable not found")
                    }
                }

                dir("../../../../../..") {
                    checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO, cleanCheckout: options.skipBuild.size() == 0)
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("jobs_launcher/install"){
                    if (isUnix()) {
                        sh """
                            chmod u+x install_pylibs.sh
                            ./install_pylibs.sh
                        """
                    } else {
                        bat """
                            install_pylibs.bat
                        """
                    }
                }

                if (options.projectBranch) {
                    if (!options.skipBuild.contains(osName)) {
                        dir("StreamingSDK") {
                            prepareTool(osName, options, "server")
                        }

                        // Android autotests support only Windows server machines
                        if (osName == "Windows") {
                            if (options["androidDevicesNumber"] > 0) {
                                dir("StreamingSDKAndroid") {
                                    initAndroidDevice(options)
                                    copyAndroidScripts()
                                    prepareTool("Android", options)
                                    installAndroidClient()
                                }
                            }
                        }
                    }
                }
            }
        }

        options["serverInfo"]["ipAddress"] = getServerIpAddress(osName)
        println("[INFO] IPv4 address of server: ${options.serverInfo.ipAddress}")

        options["serverInfo"]["gpuName"] = getGPUName(osName)
        println("[INFO] Name of GPU on server machine: ${options.serverInfo.gpuName}")

        options["serverInfo"]["osName"] = getOSName(osName)
        println("[INFO] Name of OS on server machine: ${options.serverInfo.osName}")

        options["serverInfo"]["communicationPort"] = getCommunicationPort(osName)
        println("[INFO] Communication port: ${options.serverInfo.communicationPort}")

        while (!options["clientInfo"]["ready"]) {
            if (options["clientInfo"]["failed"]) {
                throw new Exception("Client was failed")
            }

            sleep(5)
        }

        if (options.multiconnectionConfiguration.second_win_client.any { (options.tests.split("-")[0].split() as List).contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~") {
            while (!options["mcClientInfo"]["ready"]) {
                if (options["mcClientInfo"]["failed"]) {
                    throw new Exception("Multiconnection client was failed")
                }

                sleep(5)
            }
        }

        options["serverInfo"]["ready"] = true
        println("[INFO] Server is ready to run tests")

        println("Server is synchronized with state of client. Start tests")

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand(osName, asicName, options, "server")
        }

        options["serverInfo"]["executeTestsFinished"] = true
    } catch (e) {
        options["serverInfo"]["ready"] = false
        options["serverInfo"]["failed"] = true
        options["serverInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on server"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["serverInfo"]["finished"] = true

        saveResults(osName, options, "server", stashResults, options["serverInfo"]["executeTestsFinished"])

        if (options.tests.contains("AMD_Link")) {
            closeAmdLink(osName, options, "server")
        }

        if (options.engine == "LatencyTool") {
            utils.reboot(this, osName)
        }
    }
}


def executeTestsMulticonnectionClient(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {

        timeout(time: "10", unit: "MINUTES") {
            if (!options.skipBuild.contains("Windows")) {
                cleanWS(osName)
            } else {
                utils.removeDir(this, osName, options.stageName)

                if (!fileExists("StreamingSDK/RemoteGameClient.exe")) {
                    options.problemMessageManager.saveSpecificFailReason("Streaming SDK executable not found", options["stageName"], osName)
                    throw new ExpectedExceptionWrapper("Streaming SDK executable not found")
                }
            }

            dir("../../../../../..") {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO, cleanCheckout: options.skipBuild.size() == 0)
            }
        }

        timeout(time: "5", unit: "MINUTES") {
            dir("jobs_launcher/install"){
                bat """
                    install_pylibs.bat
                """
            }

            if (!options.skipBuild.contains("Windows")) {
                dir("StreamingSDK") {
                    prepareTool(osName, options)
                }
            }
        }

        options["mcClientInfo"]["ready"] = true
        println("[INFO] Multiconnection client is ready to run tests")

        while (!options["serverInfo"]["ready"]) {
            if (options["serverInfo"]["failed"]) {
                throw new Exception("Server was failed")
            }

            sleep(5)
        }

        println("Multiconnection client is synchronized with state of server. Start tests")
        
        executeTestCommand(osName, asicName, options, "mcClient")

        options["mcClientInfo"]["executeTestsFinished"] = true

    } catch (e) {
        options["mcClientInfo"]["ready"] = false
        options["mcClientInfo"]["failed"] = true
        options["mcClientInfo"]["exception"] = e

        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during tests on multiconnection client"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        options["mcClientInfo"]["finished"] = true

        saveResults(osName, options, "mcClient", stashResults, options["mcClientInfo"]["executeTestsFinished"])
    }
}


def rebootAndroidDevice() {
    try {
        bat "adb reboot"
        println "[INFO] Android device rebooted"
    } catch (Exception e) {
        println "[ERROR] Failed to reboot Android device"
    }
}


def killAdbServer() {
    try {
        bat "adb kill-server"
        println "[INFO] ADB server is killed"
    } catch (Exception e) {
        println "[ERROR] Failed to kill adb server"
    }
}


def initAndroidDevice(Map options) {
    try {
        bat "adb kill-server"
        println "[INFO] ADB server is killed"
    } catch (Exception e) {
        println "[ERROR] Failed to kill adb server"
    }

    (env.ANDROID_DEVICES.split(",") as List).each() {
        String deviceName = it.split("_")[1]

        try {
            bat "adb connect ${deviceName}:5555"
            println "[INFO] Connected to Android device"
        } catch (Exception e) {
            println "[ERROR] Failed to connect to Android device"
        }

        if (options.ANDROID_TAG == "Chromecast" || options.ANDROID_TAG == "XiaomiTVStick"
            || options.ANDROID_TAG == "Chromecast" || options.ANDROID_TAG == "FireStick") {

            // screensave can't be turned off, reboot the device to avoid it
            println "[INFO] Reboot device"
            bat "adb shell reboot"
            bat "adb connect ${deviceName}:5555"
            sleep(60)
        }

        try {
            bat "adb -s ${deviceName} shell rm -rf sdcard/video*"
            println "[INFO] Android deviced is cleared"
        } catch (Exception e) {
            println "[ERROR] Failed to clear Android device"
        }

        try {
            bat "adb -s ${deviceName} shell am force-stop com.amd.remotegameclient"
            println "[INFO] Android client is closed"
        } catch (Exception e) {
            println "[ERROR] Failed to close Android client"
        }
    }
}


def copyAndroidScripts() {
    try {
        utils.copyFile(this, "Windows", "%STREAMING_SCRIPTS_LOCATION%\\*", ".")
    } catch(Exception e) {
        println("[ERROR] Failed to copy installation scripts")
        throw e
    }
}


def installAndroidClient() {
    (env.ANDROID_DEVICES.split(",") as List).each() {
        String deviceName = it.split("_")[1]

        try {
            bat "uninstall.bat ${deviceName}"
            println "[INFO] Android client was uninstalled"
        } catch (Exception e) {
            println "[ERROR] Failed to uninstall Android client"
            println(e)
        }

        try {
            bat "install.bat ${deviceName}"
            sleep(15)
            println "[INFO] Android client was installed"
        } catch (Exception e) {
            println "[ERROR] Failed to install Android client"
            throw e
        }
    }
}


def executeTestsAndroid(String osName, String asicName, Map options) {
    Boolean stashResults = true

    try {
        if (options.tests.contains("AMD_Link")) {
            utils.reboot(this, "Windows")
        }

        killAdbServer()

        withNotifications(title: options["stageName"], options: options, logUrl: "${env.BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                if (!options.skipBuild.contains("Windows") && !options.skipBuild.contains("Android")) {
                    cleanWS(osName)
                } else {
                    utils.removeDir(this, "Windows", options.stageName)

                    if (!fileExists("StreamingSDK/RemoteGameServer.exe")) {
                        options.problemMessageManager.saveSpecificFailReason("Streaming SDK executable not found", options["stageName"], osName)
                        throw new ExpectedExceptionWrapper("Streaming SDK executable not found")
                    }
                }

                dir("../../../../../..") {
                    checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO, cleanCheckout: options.skipBuild.size() == 0)
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "5", unit: "MINUTES") {
                dir("jobs_launcher/install"){
                    bat """
                        install_pylibs.bat
                    """
                }

                if (!options.skipBuild.contains("Windows")) {
                    dir("StreamingSDK") {
                        prepareTool("Windows", options)
                    }
                }

                if (!options.skipBuild.contains("Android")) {
                    dir("StreamingSDKAndroid") {
                        initAndroidDevice(options)
                        copyAndroidScripts()
                        prepareTool("Android", options)
                        installAndroidClient()
                    }
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand(osName, asicName, options)
        }

    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries - 1) {
            stashResults = false
        } else {
            currentBuild.result = "FAILURE"
        }

        println "[ERROR] Failed during Android tests"
        println "Exception: ${e.toString()}"
        println "Exception message: ${e.getMessage()}"
        println "Exception cause: ${e.getCause()}"
        println "Exception stack trace: ${e.getStackTrace()}"
    } finally {
        saveResults("Windows", options, "android", stashResults, true)
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        if (osName == "Windows" || osName == "Ubuntu20") {
            options["clientInfo"] = new ConcurrentHashMap()
            options["serverInfo"] = new ConcurrentHashMap()
            options["mcClientInfo"] = new ConcurrentHashMap()

            println("[INFO] Start Client and Server processes for ${asicName}-${osName}")
            // create client and server threads and run them parallel
            Map threads = [:]

            threads["${options.stageName}-client"] = { 
                node(getClientLabels(options)) {
                    timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
                        ws("WS/${options.PRJ_NAME}_Test") {
                            dir(AUTOTESTS_PATH) {
                                executeTestsClient("Windows", asicName, options)
                            }
                        }
                    }
                }
            }

            if (options.multiconnectionConfiguration.second_win_client.any { (options.tests.split("-")[0].split() as List).contains(it) } || options.tests == "regression.1.json~" || options.tests == "regression.3.json~") {
                threads["${options.stageName}-multiconnection-client"] = { 
                    node(getMulticonnectionClientLabels(options)) {
                        timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
                            ws("WS/${options.PRJ_NAME}_Test") {
                                dir(AUTOTESTS_PATH) {
                                    executeTestsMulticonnectionClient("Windows", asicName, options)
                                }
                            }
                        }
                    }
                }
            }

            threads["${options.stageName}-server"] = { 
                dir(AUTOTESTS_PATH) {
                    int androidDevicesNumber = getNumberOfRequiredAndroidDevices(options)
                    options["androidDevicesNumber"] = androidDevicesNumber

                    if (androidDevicesNumber > 0) {
                        lock(label: options.ANDROID_TAG, quantity: androidDevicesNumber, resource : null, variable: "ANDROID_DEVICES") {
                            executeTestsServer(osName, asicName, options)
                        }
                    } else {
                        executeTestsServer(osName, asicName, options)
                    }
                } 
            }

            parallel threads

            if (options["serverInfo"]["failed"]) {
                def exception = options["serverInfo"]["exception"]
                throw new ExpectedExceptionWrapper("Server side tests got an error: ${exception.getMessage()}", exception)
            } else if (options["clientInfo"]["failed"]) {
                def exception = options["clientInfo"]["exception"]
                throw new ExpectedExceptionWrapper("Client side tests got an error: ${exception.getMessage()}", exception)
            }
        } else if (osName == "Android") {
            dir(AUTOTESTS_PATH) { 
                int androidDevicesNumber = getNumberOfRequiredAndroidDevices(options)
                options["androidDevicesNumber"] = androidDevicesNumber

                lock(label: options.ANDROID_TAG, quantity: androidDevicesNumber, resource : null, variable: "ANDROID_DEVICES") {
                    executeTestsAndroid(osName, asicName, options)
                }
            }
        } else {
            println("Unsupported OS")
        }
    } catch (e) {
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()}", "${env.BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${env.BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", e)
        }
    }
}


def executeBuildWindows(Map options) {
    dir("StreamingSDK\\drivers\\amf") {
        bat "git submodule update --recursive --init"
    }

    options.winBuildConfiguration.each() { winBuildConf ->

        println "Current build configuration: ${winBuildConf}."

        String winBuildName = "${winBuildConf}_vs2019"
        String logName = "${STAGE_NAME}.${winBuildName}.log"
        String logNameDriver = "${STAGE_NAME}.${winBuildName}.driver.log"
        String logNameLatencyTool = "${STAGE_NAME}.${winBuildName}.latency_tool.log"

        String buildSln = "StreamingSDK_All_vs2019.sln"
        String msBuildPath = bat(script: "echo %VS2019_PATH%",returnStdout: true).split('\r\n')[2].trim()
        String winArtifactsDir = "vs2019x64${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"
        String winDriverDir = "x64/${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"
        String winLatencyToolDir = "amf/bin/vs2019x64${winBuildConf.substring(0, 1).toUpperCase() + winBuildConf.substring(1).toLowerCase()}"

        if (options.isDevelopBranch) {
            dir("AMDVirtualDrivers") {
                withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: "develop", repositoryUrl: DRIVER_REPO)
                }

                GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/${logNameDriver}")

                bat """
                    set AMD_VIRTUAL_DRIVER=${WORKSPACE}\\AMDVirtualDrivers
                    set STREAMING_SDK=${WORKSPACE}\\StreamingSDK
                    set msbuild="${msBuildPath}"
                    %msbuild% AMDVirtualDrivers.sln /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\${logNameDriver} 2>&1
                """

                dir(winDriverDir) {
                    String DRIVER_NAME = "Driver_Windows_${winBuildConf}.zip"

                    bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${DRIVER_NAME} .")

                    makeArchiveArtifacts(name: DRIVER_NAME, storeOnNAS: options.storeOnNAS, randomizeArtifactsLinks: options.storeOnNAS)

                    if (options.winTestingDriverName == winBuildConf) {
                        utils.moveFiles(this, "Windows", DRIVER_NAME, "${options.winTestingDriverName}.zip")
                        makeStash(includes: "${options.winTestingDriverName}.zip", name: "DriverWindows", preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        }

        if (options.winTestingBuildName == winBuildName && options.engines.contains("Empty")) {
            dir("AMFTests") {
                withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: "master", repositoryUrl: AMF_TESTS_REPO)
                }

                GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/${logNameLatencyTool}")

                dir("drivers\\amf\\stable\\protected\\samples") {
                    bat """
                        set msbuild="${msBuildPath}"
                        %msbuild% LatancyTest_vs2019.sln /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\..\\..\\..\\..\\..\\${logNameLatencyTool} 2>&1
                    """
                }

                dir(winLatencyToolDir) {
                    String LATENCY_TOOL_NAME = "LatencyTool_Windows.zip"

                    bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${LATENCY_TOOL_NAME} .")

                    makeArchiveArtifacts(name: LATENCY_TOOL_NAME, storeOnNAS: options.storeOnNAS, randomizeArtifactsLinks: options.storeOnNAS)

                    if (options.winTestingDriverName == winBuildConf) {
                        makeStash(includes: LATENCY_TOOL_NAME, name: "LatencyToolWindows", preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }
        }

        dir("StreamingSDK\\drivers\\amf\\stable\\build\\solution") {
            GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/${logName}")

            try {
                bat """
                    set msbuild="${msBuildPath}"
                    %msbuild% ${buildSln} /target:build /maxcpucount /nodeReuse:false /property:Configuration=${winBuildConf};Platform=x64 >> ..\\..\\..\\..\\..\\..\\${logName} 2>&1
                """
            } catch (e) {
                String buildLog = readFile("..\\..\\..\\..\\..\\..\\${logName}")
                // if true - there is some errors
                if (!buildLog.contains("0 Error(s)")) {
                    // if number of errors is bigger than 1 or the error isn't connected with amfrt64.lib - it's unexpected error
                    if (!buildLog.contains("1 Error(s)") || !buildLog.contains("'amfrt64.lib'")) {
                        throw e
                    }
                }
            }
        }

        String archiveUrl = ""

        dir("StreamingSDK\\drivers\\amf\\stable\\bin\\${winArtifactsDir}") {
            if (!fileExists("RemoteGameClient.exe")) {
                String errorMessage = "RemoteGameClient.exe not found after build"
                options.problemMessageManager.saveSpecificFailReason(errorMessage, options["stageName"], osName)
                throw new ExpectedExceptionWrapper(errorMessage)
            } else if (!fileExists("RemoteGameServer.exe")) {
                String errorMessage = "RemoteGameServer.exe not found after build"
                options.problemMessageManager.saveSpecificFailReason(errorMessage, options["stageName"], osName)
                throw new ExpectedExceptionWrapper(errorMessage)
            }

            String BUILD_NAME = "StreamingSDK_Windows_${winBuildName}.zip"

            zip archive: true, zipFile: BUILD_NAME

            if (options.winTestingBuildName == winBuildName) {
                utils.moveFiles(this, "Windows", BUILD_NAME, "${options.winTestingBuildName}.zip")
                makeStash(includes: "${options.winTestingBuildName}.zip", name: "ToolWindows", preZip: false, storeOnNAS: options.storeOnNAS)
            }

            archiveUrl = "${env.BUILD_URL}artifact/${BUILD_NAME}"
            rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${archiveUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""
        }

    }

    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuildAndroid(Map options) {
    dir("StreamingSDK\\drivers\\amf") {
        bat "git submodule update --recursive --init"
    }

    withEnv(["PATH=C:\\Program Files\\Java\\jdk1.8.0_271\\bin;C:\\Program Files\\Java\\jdk1.8.0_241\\bin;${PATH}"]) {
        options.androidBuildConfiguration.each() { androidBuildConf ->

            println "Current build configuration: ${androidBuildConf}."

            String androidBuildName = "${androidBuildConf}"
            String logName = "${STAGE_NAME}.${androidBuildName}.log"

            String androidBuildKeys = "assemble${androidBuildConf.substring(0, 1).toUpperCase() + androidBuildConf.substring(1).toLowerCase()}"

            dir("StreamingSDK/drivers/amf/stable/protected/samples/CPPSamples/RemoteGameClientAndroid") {
                GithubNotificator.updateStatus("Build", "Android", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/${logName}")

                bat """
                    gradlew.bat ${androidBuildKeys} >> ..\\..\\..\\..\\..\\..\\..\\..\\${logName} 2>&1
                """

                String archiveUrl = ""

                dir("app/build/outputs/apk/arm/${androidBuildConf}") {
                    String BUILD_NAME = "StreamingSDK_Android_${androidBuildName}.zip"

                    bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${BUILD_NAME} app-arm-${androidBuildConf}.apk")

                    makeArchiveArtifacts(name: BUILD_NAME, storeOnNAS: options.storeOnNAS, randomizeArtifactsLinks: options.storeOnNAS)

                    if (options.androidTestingBuildName == androidBuildConf) {
                        utils.moveFiles(this, "Windows", BUILD_NAME, "android_${options.androidTestingBuildName}.zip")
                        makeStash(includes: "android_${options.androidTestingBuildName}.zip", name: "ToolAndroid", preZip: false, storeOnNAS: options.storeOnNAS)
                    }
                }
            }

        }

        GithubNotificator.updateStatus("Build", "Android", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
    }
}


def executeBuildUbuntu(Map options) {
    dir("StreamingSDK/drivers/amf") {
        sh "git submodule update --recursive --init"
    }

    String logName = "${STAGE_NAME}.log"

    dir("StreamingSDK/drivers/amf/public/src/components/ComponentsFFMPEG") {
        GithubNotificator.updateStatus("Build", "Ubuntu20", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/${logName}")

        sh """
            make >> ../../../../../../../${logName} 2>&1
        """
    }

    dir("StreamingSDK/drivers/amf/stable/protected/samples/CPPSamples/RemoteGameServer") {
        // TODO: temporary ducktape. Waiting for fix from side of developers
        if (!fileExists("../../../../../../Thirdparty/VulkanSDK/1.2.189.2")) {
            sh """
                mkdir -p ../../../../../../../Thirdparty/VulkanSDK/1.2.189.2
                cp -r \$VK_SDK_PATH ../../../../../../../Thirdparty/VulkanSDK/1.2.189.2/x86_64
            """
        }

        sh """
            chmod u+x ../../../../../../../Thirdparty/file_to_header/Linux64/file_to_header
            make >> ../../../../../../../../${logName} 2>&1
        """

        String archiveUrl = ""
    }

    dir("StreamingSDK/amf/bin/dbg_64") {
        String BUILD_NAME = "StreamingSDK_Ubuntu20.zip"
        
        sh("cp ../../bin/wirelessvr/build/lnx64a/B_dbg/libawvrrt64.so.1.4.10 libawvrrt64.so.1")

        sh("zip --symlinks -r ${BUILD_NAME} .")

        makeArchiveArtifacts(name: BUILD_NAME, storeOnNAS: options.storeOnNAS, randomizeArtifactsLinks: options.storeOnNAS)

        makeStash(includes: BUILD_NAME, name: "ToolUbuntu20", preZip: false, storeOnNAS: options.storeOnNAS)
    }

    GithubNotificator.updateStatus("Build", "Ubuntu20", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuild(String osName, Map options) {
    try {
        //utils.reboot(this, osName != "Android" ? osName : "Windows")

        dir("StreamingSDK") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
            }
        }

        utils.removeFile(this, osName != "Android" ? osName : "Windows", "*.log")

        outputEnvironmentInfo(osName != "Android" ? osName : "Windows")

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "Android":
                    executeBuildAndroid(options)
                    break
                case "Ubuntu20":
                    executeBuildUbuntu(options)
                    break
                case "OSX":
                    println("Unsupported OS")
                    break
                default:
                    println("Unsupported OS")
            }
        }
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}


def executePreBuild(Map options) {
    // manual job
    if (!env.BRANCH_NAME) {
    // auto job
    } else {

        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
        } else if("${env.BRANCH_NAME}" == "master") {
            println "[INFO] master branch was detected"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
        }
    }

    options["finishedBuildStages"]["Windows"] = true
    options["finishedBuildStages"]["Android"] = true
    options["finishedBuildStages"]["Ubuntu20"] = true


    Boolean collectTraces = (options.clientCollectTraces || options.serverCollectTraces)

    if (options.projectBranch) {
        if ("StreamingSDK") {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        }

        if (options.projectBranch) {
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = options.commitSHA[0..6]

        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commit shortSHA: ${options.commitShortSHA}"

        if (env.BRANCH_NAME) {
            withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                GithubNotificator githubNotificator = new GithubNotificator(this, options)
                githubNotificator.init(options)
                options["githubNotificator"] = githubNotificator
                githubNotificator.initPreBuild("${env.BUILD_URL}")
                options.projectBranchName = githubNotificator.branchName
            }
        }

        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    }

    def tests = []

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir("jobs_test_streaming_sdk") {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)

            dir(AUTOTESTS_PATH) {
                options["testsBranch"] = utils.getBatOutput(this, "git log --format=%%H -1 ")
                dir('jobs_launcher') {
                    options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
                }
                println "[INFO] Test branch hash: ${options['testsBranch']}"

                def packageInfo

                if (options.testsPackage != "none") {
                    if (fileExists("jobs/${options.testsPackage}")) {
                        packageInfo = readJSON file: "jobs/${options.testsPackage}"
                    } else {
                        packageInfo = readJSON file: "jobs/${options.testsPackage.replace('.json', '-windows.json')}"
                    }

                    options.isPackageSplitted = packageInfo["split"]
                    // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                    if (!env.BRANCH_NAME && options.isPackageSplitted && options.tests) {
                        options.testsPackage = "none"
                    }
                }

                if (options.testsPackage != "none") {
                    def tempTests = []

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
                            tempTests << it
                        } else {
                            if (tempTests.contains(it)) {
                                // add duplicated group name in name of package group name for exclude it
                                modifiedPackageName = "${modifiedPackageName},${it}"
                            }
                        }
                    }
                    options.tests = utils.uniteSuites(this, "jobs/weights.json", tempTests, collectTraces ? 90 : 70)

                    options.engines.each { engine ->
                        if (env.JOB_NAME.contains("Weekly") && !env.JOB_NAME.contains("APU") && !env.JOB_NAME.contains("XiaomiTVStick") 
                            && !env.JOB_NAME.contains("Chromecast") && !env.JOB_NAME.contains("FireStick")
                            && WEEKLY_REGRESSION_CONFIGURATION.contains(engine)) {

                            packageInfo = readJSON file: "jobs/regression-windows.json"

                            for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                tests << "regression.${i}.json~-${engine}"
                            }
                        } else {
                            options.tests.each() {
                                tests << "${it}-${engine}"
                            }
                        }
                    }

                    modifiedPackageName = modifiedPackageName.replace('~,', '~')

                    if (options.isPackageSplitted) {
                        options.testsPackage = "none"
                    } else {
                        options.testsPackage = modifiedPackageName
                        // check that package is splitted to parts or not
                        if (packageInfo["groups"] instanceof Map) {
                            options.engines.each { engine ->
                                tests << "${modifiedPackageName}-${engine}"
                            } 
                        } else {
                            // add group stub for each part of package
                            options.engines.each { engine ->
                                for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                    tests << "${modifiedPackageName}-${engine}".replace(".json", ".${i}.json")
                                }
                            }
                        }
                    }
                } else if (options.tests) {
                    options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List, collectTraces ? 90 : 70)
                    options.engines.each { engine ->
                        options.tests.each() {
                            tests << "${it}-${engine}"
                        }
                    }
                } else {
                    options.executeTests = false
                }

                options.tests = tests
            }
        }

        // clear games list if there isn't any test group in build or games string is empty
        if (!options.tests || !options.games) {
            options.tests = []
            options.engines = []
        }

        options.testsList = options.tests

        println "Groups: ${options.testsList}"

        dir("jobs_test_streaming_sdk/${AUTOTESTS_PATH}") {
            options.multiconnectionConfiguration = readJSON file: "jobs/multiconnection.json"
            options.latencyConfiguration = readJSON file: "jobs/latency.json"

            // Multiconnection group required Android client
            for (testsList in options.testsList) {
                if (!options.platforms.contains("Android") && (options.multiconnectionConfiguration.android_client.keySet().any { (testsList.split("-")[0].split() as List).contains(it) } || options.testsPackage == "regression.json~")) {
                    options.platforms = options.platforms + ";Android"

                    options.androidBuildConfiguration = ["debug"]
                    options.androidTestingBuildName = "debug"

                    println """
                        Android build configuration was updated: ${options.androidBuildConfiguration}
                        Android testing build name was updated: ${options.androidTestingBuildName}
                    """

                    break
                }
            }
        }

        if (!options.tests && options.testsPackage == "none") {
            options.executeTests = false
        }

        // make lists of raw profiles and lists of beautified profiles (displaying profiles)
        multiplatform_pipeline.initProfiles(options)

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${env.BUILD_URL}")

            if (options.isDevelopBranch) {
                GithubNotificator.createStatus('Test', "Driver tests", 'queued', options, 'Scheduled', "${env.JOB_URL}")
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String game) {
    dir(AUTOTESTS_PATH) {
        try {

            if (options["executeTests"] && testResultList) {
                withNotifications(title: "Building test report for ${game}", options: options, startUrl: "${env.BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                    dir("../../../../../..") {
                        checkoutScm(branchName: options.testsBranch, repositoryUrl: TESTS_REPO)
                    }
                }

                List lostStashesWindows = []
                List lostStashesAndroid = []
                dir("summaryTestResults") {
                    testResultList.each {
                        Boolean groupLost = false

                        if (it.endsWith(game)) {
                            List testNameParts = it.split("-") as List

                            String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                            dir(testName.replace("testResult-", "")) {
                                if (it.contains("Android")) {
                                    try {
                                        makeUnstash(name: "${it}", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}
                                            ${e.toString()}
                                        """

                                        lostStashesAndroid << ("'${it}'".replace("testResult-", ""))
                                    }
                                } else {
                                    try {
                                        makeUnstash(name: "${it}_serv_l", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_serv_l
                                            ${e.toString()}
                                        """

                                        groupLost = true
                                    }

                                    if (options.multiconnectionConfiguration.second_win_client.any { testGroup -> (it.split("-")[3].split() as List).contains(testGroup) } || testName.contains("regression.1.json~") || testName.contains("regression.3.json~")) {
                                        try {
                                            makeUnstash(name: "${it}_sec_cl", storeOnNAS: options.storeOnNAS)
                                        } catch (e) {
                                            println """
                                                [ERROR] Failed to unstash ${it}_sec_cl
                                                ${e.toString()}
                                            """
                                        }
                                    }

                                    try {
                                        makeUnstash(name: "${it}_client", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_client
                                            ${e.toString()}
                                        """

                                        groupLost = true
                                    }

                                    try {
                                        makeUnstash(name: "${it}_and_cl", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_and_cl
                                            ${e.toString()}
                                        """
                                    }

                                    try {
                                        makeUnstash(name: "${it}_ser_t", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_ser_t
                                            ${e.toString()}
                                        """
                                    }

                                    if (groupLost) {
                                        lostStashesWindows << ("'${it}'".replace("testResult-", ""))
                                    }
                                }
                            }
                        }
                    }
                }

                dir("serverTestResults") {
                    testResultList.each {
                        if (it.endsWith(game)) {
                            List testNameParts = it.split("-") as List

                            String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                            dir(testName.replace("testResult-", "")) {
                                try {
                                    makeUnstash(name: "${it}_server", storeOnNAS: options.storeOnNAS)
                                } catch (e) {
                                    println """
                                        [ERROR] Failed to unstash ${it}_server
                                        ${e.toString()}
                                    """
                                }
                            }
                        }
                    }
                }

                dir("secondClientTestResults") {
                    testResultList.each {
                        if (it.endsWith(game)) {
                            List testNameParts = it.split("-") as List

                            String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")

                            if (options.multiconnectionConfiguration.second_win_client.any { testGroup -> (it.split("-")[3].split() as List).contains(testGroup) } || testName.contains("regression.1.json~") || testName.contains("regression.3.json~")) {
                                dir(testName.replace("testResult-", "")) {
                                    try {
                                        makeUnstash(name: "${it}_sec_cl_j", storeOnNAS: options.storeOnNAS)
                                    } catch (e) {
                                        println """
                                            [ERROR] Failed to unstash ${it}_sec_cl_j
                                            ${e.toString()}
                                        """
                                    }
                                }
                            }
                        }
                    }
                }

                try {
                    dir ("scripts") {
                        python3("unite_case_results.py --target_dir \"..\\summaryTestResults\" --source_dir \"..\\serverTestResults\" --second_client_dir \"..\\secondClientTestResults\"")
                    }
                } catch (e) {
                    println "[ERROR] Can't unite server and client test results"
                }

                try {
                    dir("scripts") {
                        python3("prepare_test_cases.py --os_name \"Windows\"")
                    }

                    dir("jobs_launcher") {
                        bat """
                            count_lost_tests.bat \"${lostStashesWindows}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${game}\" \"{}\"
                        """
                    }

                    dir("scripts") {
                        python3("prepare_test_cases.py --os_name \"Android\"")
                    }

                    dir("jobs_launcher") {
                        bat """
                            count_lost_tests.bat \"${lostStashesAndroid}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]]\" \"${game}\" \"{}\"
                        """
                    }

                    dir("scripts") {
                        python3("prepare_test_cases.py --os_name \"Ubuntu\"")
                    }

                    dir("jobs_launcher") {
                        bat """
                            count_lost_tests.bat \"${lostStashesAndroid}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]]\" \"${game}\" \"{}\"
                        """
                    }
                } catch (e) {
                    println "[ERROR] Can't generate number of lost tests"
                }

                String branchName = env.BRANCH_NAME ?: options.projectBranch
                try {
                    Boolean showGPUViewTraces = options.clientCollectTraces || options.serverCollectTraces

                    GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${env.BUILD_URL}")
                    withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}", "SHOW_GPUVIEW_TRACES=${showGPUViewTraces}", "SHOW_STREAMING_DUMP=${options.collectStreamingDump}"]) {
                        dir("jobs_launcher") {
                            List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                            retryInfoList.each{ gpu ->
                                gpu['Tries'].each{ group ->
                                    group.each{ groupKey, retries ->
                                        if (groupKey.endsWith(game)) {
                                            List testNameParts = groupKey.split("-") as List
                                            String parsedName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                                            group[parsedName] = retries
                                        }
                                        group.remove(groupKey)
                                    }
                                }
                                gpu['Tries'] = gpu['Tries'].findAll{ it.size() != 0 }
                            }

                            def retryInfo = JsonOutput.toJson(retryInfoList)
                            dir("..\\summaryTestResults") {
                                JSON jsonResponse = JSONSerializer.toJSON(retryInfo, new JsonConfig());
                                writeJSON file: 'retry_info.json', json: jsonResponse, pretty: 4
                            }

                            bat """
                                build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(game, options)} \"${utils.escapeCharsByUnicode(game)}\"
                            """
                        }
                    }
                } catch (e) {
                    String errorMessage = utils.getReportFailReason(e.getMessage())
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "failure", options, errorMessage, "${env.BUILD_URL}")
                    if (utils.isReportFailCritical(e.getMessage())) {
                        options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                        println """
                            [ERROR] Failed to build test report.
                            ${e.toString()}
                        """
                        if (!options.testDataSaved) {
                            try {
                                // Save test data for access it manually anyway
                                // FIXME: save reports on NAS
                                utils.publishReport(this, "${env.BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                                    "Test Report ${game}", "Summary Report, Compare Report", options.storeOnNAS, \
                                    ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
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

                withNotifications(title: "Building test report for ${game}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                    // FIXME: save reports on NAS
                    utils.publishReport(this, "${env.BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                        "Test Report ${game}", "Summary Report, Compare Report", options.storeOnNAS, \
                        ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

                    if (summaryTestResults) {
                        GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "success", options,
                                "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${env.BUILD_URL}/Test_20Report")
                    } else {
                        GithubNotificator.updateStatus("Deploy", "Building test report for ${game}", "success", options,
                                NotificationConfiguration.REPORT_PUBLISHED, "${env.BUILD_URL}/Test_20Report")
                    }
                }
            }
        } catch (e) {
            println(e.toString())
            throw e
        } finally {
            utils.generateOverviewReport(this, this.&getReportBuildArgs, options)
        }
    }
}


def call(String projectBranch = "",
    String testsBranch = "master",
    String platforms = "Windows:AMD_RX6700XT;Android:AMD_RX6700XT",
    String clientTags = "PC-TESTER-VILNIUS-WIN10",
    String winBuildConfiguration = "release,debug",
    String winTestingBuildName = "debug_vs2019",
    String testsPackage = "regression.json",
    String tests = "",
    String desktopTesterTag = "StreamingSDK",
    String androidTesterTag = "Smartphone",
    Integer testCaseRetries = 2,
    Boolean clientCollectTraces = false,
    Boolean serverCollectTraces = false,
    String collectTracesType = "AfterTests",
    String games = "Valorant",
    String androidBuildConfiguration = "release,debug",
    String androidTestingBuildName = "debug",
    Boolean storeOnNAS = false,
    Boolean collectInternalDriverVersion = false,
    String skipBuild = "",
    String inGameResolution = "1920x1080",
    Boolean collectStreamingDump = false
    )
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            // temporary take built Streaming SDK from NAS
            Boolean executeBuild = false
            String winTestingDriverName = ""
            String branchName = ""
            Boolean isDevelopBranch = false

            List winBuildConfigurationList
            List androidBuildConfigurationList
            List skipBuildList

            if (projectBranch) {
                // Anroid and Ubuntu tests required built Windows Streaming SDK to run server side
                if ((platforms.contains("Android:") || platforms.contains("Ubuntu20:")) && !platforms.contains("Windows")) {
                    platforms = platforms + ";Windows"

                    winBuildConfiguration = "debug"
                    winTestingBuildName = "debug_vs2019"
                }

                winTestingDriverName = winTestingBuildName ? winTestingBuildName.split("_")[0] : ""

                println """
                    Platforms: ${platforms}
                    Tests: ${tests}
                    Tests package: ${testsPackage}
                    Store on NAS: ${storeOnNAS}
                """

                winBuildConfigurationList = winBuildConfiguration.split(',') as List

                println """
                    Win build configuration: ${winBuildConfigurationList}"
                    Win testing build name: ${winTestingBuildName}
                    Win driver build name: ${winTestingDriverName}
                """

                androidBuildConfigurationList = androidBuildConfiguration.split(',') as List

                println """
                    Android build configuration: ${androidBuildConfigurationList}"
                """

                skipBuildList = skipBuild ? skipBuild.split(',') as List : []

                println """
                    Skip build configuration: ${skipBuildList}"
                """

                branchName = env.BRANCH_NAME ?: projectBranch

                //Driver development is on hold
                //isDevelopBranch = (branchName == "origin/develop" || branchName == "develop")

                isDevelopBranch = (branchName == "origin/develop" || branchName == "develop")

                if (tests.startsWith("FS_") || tests.contains(" FS_")) {
                    executeBuild = false
                }
            } else {
                executeBuild = false
            }

            String[] tagParts = clientTags.split(";")
            String firstClientMachine = ""
            String secondClientMachine = ""

            if (tagParts.size() > 0) {
                firstClientMachine = tagParts[0]
            }

            if (tagParts.size() == 2) {
                secondClientMachine = tagParts[1]
            }

            String firstClientTag = firstClientMachine ? "StreamingSDKClient && (${firstClientMachine})" : "StreamingSDKClient"
            String secondClientTag = secondClientMachine ? "StreamingSDKClient && (${secondClientMachine})" : "StreamingSDKClient"

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo: PROJECT_REPO,
                        projectBranch: projectBranch,
                        testsBranch: testsBranch,
                        enableNotifications: false,
                        testsPackage:testsPackage,
                        tests:tests,
                        PRJ_NAME: "StreamingSDK",
                        splitTestsExecution: true,
                        winBuildConfiguration: winBuildConfigurationList,
                        winTestingBuildName: winTestingBuildName,
                        winTestingDriverName: winTestingDriverName,
                        androidBuildConfiguration: androidBuildConfigurationList,
                        androidTestingBuildName: androidTestingBuildName,
                        nodeRetry: nodeRetry,
                        platforms: platforms,
                        BUILD_TIMEOUT: 15,
                        // update timeouts dynamicly based on number of cases + traces are generated or not
                        TEST_TIMEOUT: 180,
                        DEPLOY_TIMEOUT: 240,
                        ADDITIONAL_XML_TIMEOUT: 15,
                        BUILDER_TAG: "BuilderStreamingSDK",
                        TESTER_TAG: "(${desktopTesterTag})",
                        CLIENT_TAG: firstClientTag,
                        ANDROID_TAG: androidTesterTag,
                        MULTICONNECTION_CLIENT_TAG: secondClientTag,
                        testsPreCondition: this.&isIdleClient,
                        testCaseRetries: testCaseRetries,
                        engines: games.split(",") as List,
                        games: games,
                        clientCollectTraces:clientCollectTraces,
                        serverCollectTraces:serverCollectTraces,
                        collectTracesType:collectTracesType,
                        storeOnNAS: storeOnNAS,
                        finishedBuildStages: new ConcurrentHashMap(),
                        isDevelopBranch: isDevelopBranch,
                        collectInternalDriverVersion: collectInternalDriverVersion ? 1 : 0,
                        executeBuild: executeBuild,
                        skipBuild: skipBuild,
                        inGameResolution: inGameResolution,
                        executeTests: true,
                        skipBuildCallback: this.&shouldSkipBuild,
                        parallelExecutionType:TestsExecutionType.valueOf("TakeAllNodes"),
                        retriesForTestStage:1,
                        collectStreamingDump:collectStreamingDump
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.SOME_STAGES_FAILED)
        problemMessageManager.publishMessages()
    }

}
