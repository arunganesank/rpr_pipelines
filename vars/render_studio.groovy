import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/RenderStudio.git"
@Field final String TEST_REPO = "git@github.com:luxteam/jobs_test_web_viewer.git"

@Field final Integer MAX_TEST_INSTANCE_NUMBER = 9

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "msi"],
    artifactNameBase: "AMD_RenderStudio",
    testProfile: "mode",
    displayingProfilesMapping: [
        "mode": [
            "Desktop": "Desktop",
            "Web": "Web"
        ]
    ]
)


int getNumberOfRequiredClients(Map options) {
    // at least one client is required for offline autotests
    int clientsNumber = 1

    List testGroups

    if (options.testsPackage != "none" && !options.isPackageSplitted) {
        // some parts of regression contain live mode tests
        int partNumber = options.tests.split("\\.")[1] as int

        testGroups = options.packageInfo["groups"][partNumber].keySet() as List
    } else {
        testGroups = options.tests.split("-")[0].split() as List
    }

    if (options["liveModeConfiguration"].clients_number.keySet().any { testGroups.contains(it) }) {
        testGroups.each() {
            if (options["liveModeConfiguration"].clients_number.containsKey(it)) {
                int currentClientsNumber = options["liveModeConfiguration"].clients_number[it]

                if (currentClientsNumber > clientsNumber) {
                    clientsNumber = currentClientsNumber
                }
            }
        }
    }

    return clientsNumber
}


String getLabels(Map options) {
    return "${options.osName} && Tester && gpu${options.asicName} && !Disabled"
}


Boolean hasIdleClients(Map options) {
    int requiredClientsNumber = getNumberOfRequiredClients(options)
    println("Required ${requiredClientsNumber} client(s)")

    def suitableNodes = nodesByLabel(label: getLabels(options), offline: false)

    int idleNodesNumber = 0

    for (node in suitableNodes) {
        if (utils.isNodeIdle(node)) {
            idleNodesNumber++
        }
    }

    println("Found ${idleNodesNumber} suitable idle client(s)")

    // do not wait idle clients in case of only 1 required clients
    return (requiredClientsNumber == 1) || (requiredClientsNumber <= idleNodesNumber)
}


Boolean filter(Map options, String asicName, String osName, String testName, String mode) {
    if ((osName == "Windows" && mode == "Desktop") || (osName == "Web" && mode == "Web")) {
        return false
    }

    return true
}


Boolean isWebDeployed(Map options) {
    if (options["osName"] == "Windows" && options["platforms"].contains("Web") && !options["skipBuild"]) {
        return options["finishedBuildStages"]["Web"]
    } else {
        return true
    }
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def removeClosedPRs(Map options) {
    // get list of existing PRs
    def rawInfo = httpRequest(
        url: "${env.JENKINS_URL}/job/RenderStudio-Auto/view/change-requests/api/json?tree=jobs[name,color]",
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    def parsedInfo = parseResponse(rawInfo.content)

    for (job in parsedInfo["jobs"]) {
        if (job["color"] == "disabled") {
            render_studio_deploy("pr${job['name'].split('-')[1]}", "remove")
        }
    }
 }


def uninstallAMDRenderStudio(String osName, Map options) {
    def installedProductCode = powershell(script: """(Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'AMD RenderStudio'\").IdentifyingNumber""", returnStdout: true)

    // TODO: compare product code of built application and installed application
    if (installedProductCode) {
        println("[INFO] Found installed AMD RenderStudio. Uninstall it...")
        uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)

        utils.removeDir(this, osName, "C:\\Users\\%USERNAME%\\AppData\\Roaming\\AMDRenderStudio\\Storage")
    }
}


def installAMDRenderStudio(String osName, Map options) {
    dir("${CIS_TOOLS}\\..\\PluginsBinaries") {
        bat "msiexec.exe /i ${options[getProduct.getIdentificatorKey('Windows', options)]}.msi /qb"
    }
}


def getDesktopLink(String osName, Map options) {
    switch(osName) {
        case "Windows":
            String desktopLinkName = "AMD RenderStudio.lnk"

            String publicDir = "C:\\Users\\Public"
            String userDir = bat(script: "echo %USERPROFILE%",returnStdout: true).split('\r\n')[2].trim()

            dir(userDir) {
                if (!fileExists("/")) {
                    throw new ExpectedExceptionWrapper("Failed to locate user profile directory")
                }
            }

            String publicDesktopLinkDir = "${publicDir}\\Desktop\\${desktopLinkName}"
            String userDesktopLinkDir = "${userDir}\\Desktop\\${desktopLinkName}"

            if (fileExists(publicDesktopLinkDir)) {
                return publicDesktopLinkDir
            }

            if (fileExists(userDesktopLinkDir)) {
                return userDesktopLinkDir
            }

            return null
        default:
            println "[WARNING] ${osName} is not supported"
    }
}


def runApplication(String appLink, String osName, Map options) {
    switch(osName) {
        case "Windows":
            try {
                bat "\"${appLink}\""
            } catch (e) {
                throw new ExpectedExceptionWrapper("Failed to launch Render Studio application")
            }
        default:
            println "[WARNING] ${osName} is not supported"
    }
}


def runApplicationTests(String osName, Map options) {
    // Step 0: remove existing application
    uninstallAMDRenderStudio(osName, options)

    // Step 1: install app
    installAMDRenderStudio(osName, options)

    // Step 2: check app link on the desktop
    String appLink = getDesktopLink(osName, options)

    if (appLink == null) {
        throw new ExpectedExceptionWrapper("Render Studio application link not found in Public/User directory")
    }

    // Step 3: check app link in start menu
    String startMenuLinkDir = "C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs\\AMD\\AMD RenderStudio.lnk"

    if (!fileExists(startMenuLinkDir)) {
        throw new ExpectedExceptionWrapper("Render Studio application link not found in start menu")
    }

    // Step 4: run app using link on the desktop
    runApplication(appLink, osName, options)

    // Step 5: try to uninstall app (should be unsuccessfull, issue on Render Studio side)
    uninstallAMDRenderStudio(osName, options)

    // Step 6: close app window
    try {
        utils.closeProcess(this, "AMD RenderStudio", osName, options)
    } catch (e) {
        throw new ExpectedExceptionWrapper("Failed to close Render Studio process")
    }

    // Step 7: check processes after closing app
    // Boolean process = utils.isProcessExists(this, "AMD RenderStudio", osName, options) || utils.isProcessExists(this, "StreamingApp", osName, options)
    // if (process) {
    //     throw new ExpectedExceptionWrapper("Render Studio processes are not closed after application closing")
    // }

    // Step 8: uninstall app
    uninstallAMDRenderStudio(osName, options)

    // Step 9: check processes after uninstalling
    Boolean processFound = utils.isProcessExists(this, "AMD RenderStudio", osName, options)

    if (processFound) {
        throw new ExpectedExceptionWrapper("Processes are not closed after application uninstallation")
    }
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir('scripts') {
        bat """
            make_results_baseline.bat ${delete}
        """
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    def testTimeout = options.timeouts["${options.tests}"]
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

    println "Set timeout to ${testTimeout}"

    timeout(time: testTimeout, unit: 'MINUTES') {
        switch(osName) {
            case 'Windows':
                dir('scripts') {
                    bat """
                        set TOOL_VERSION=${options.version}
                        run.bat \"${testsPackageName}\" \"${testsNames}\" ${options.mode.toLowerCase()} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
                break
            case 'Web':
                // TODO: rename system name
                dir("scripts") {
                    sh """
                        set TOOL_VERSION=${options.version}
                        run.bat \"${testsPackageName}\" \"${testsNames}\" ${options.mode.toLowerCase()} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
        }
    }
}


def checkoutAutotests(Map options) {
    withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
        timeout(time: "5", unit: "MINUTES") {
            cleanWS("Windows")
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
        }
    }
}


def prepareAMDRenderStudio(String osName, Map options, String clientType, int clientNumber = -1) {
    // TODO: use clientType variable
    // TODO: do specific steps for Live Mode activation
    if (osName == "Windows") {
        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.RUN_APPLICATION_TESTS) {
            timeout(time: "10", unit: "MINUTES") {
                try {
                    getProduct("Windows", options)
                    runApplicationTests(osName, options)
                } catch (e) {
                    if (e instanceof ExpectedExceptionWrapper) {
                        options.problemMessageManager.saveSpecificFailReason(e.getMessage(), options["stageName"], osName)
                    }
                    throw e
                }
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "10", unit: "MINUTES") {
                uninstallAMDRenderStudio(osName, options)
                installAMDRenderStudio(osName, options)

                // open application once to generate Storage files
                String appLink = getDesktopLink(osName, options)
                runApplication(appLink, osName, options)
                sleep(10)
                utils.closeProcess(this, "AMD RenderStudio", osName, options)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_assets" : "/mnt/c/TestResources/render_studio_autotests_assets"
            downloadFiles("/volume1/web/Assets/render_studio_autotests/", assetsDir)
        }
    } else {
        withCredentials([string(credentialsId: "WebUsdUrlTemplate", variable: "TEMPLATE")]) {
            String url

            if (options.deployEnvironment == "prod") {
                url = TEMPLATE.replace("<instance>.", "")
            } else {
                url = TEMPLATE.replace("<instance>", options.deployEnvironment)
            }

            String localConfigContent = readFile("local_config.py").replace("<domain_name>", url)
            writeFile(file: "local_config.py", text: localConfigContent)
        }
    }
}


def executeTestsOnClient(String osName, String asicName, Map options, String clientType, int clientNumber = -1) {
    // TODO: use clientType variable
    options.REF_PATH_PROFILE = "/volume1/Baselines/render_studio_autotests/${asicName}-${osName}-${options.mode}"

    outputEnvironmentInfo("Windows", "", options.currentTry)

    if (options["updateRefs"].contains("Update")) {
        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand("Windows", asicName, options)
            executeGenTestRefCommand("Windows", options, options["updateRefs"].contains("clean"))
            uploadFiles("./Work/GeneratedBaselines/", options.REF_PATH_PROFILE)
            // delete generated baselines when they're sent 
            bat """
                if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines
            """
        }
    } else {
        withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
            String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/render_studio_autotests_baselines" : "/mnt/c/TestResources/render_studio_autotests_baselines"
            println "[INFO] Downloading reference images for ${options.tests}-${options.mode}"
            options.tests.split(" ").each() {
                if (it.contains(".json")) {
                    downloadFiles("${options.REF_PATH_PROFILE}/", baselineDir)
                } else {
                    downloadFiles("${options.REF_PATH_PROFILE}/${it}", baselineDir)
                }
            }
        }
        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            executeTestCommand("Windows", asicName, options)
        }
    }
}


String getLiveModeKey(String clientType, int clientNumber = -1) {
    return clientType == "secondary" ? "secondary-${clientNumber}" : "primary"
}


def processClientException(def exception, Map options, String clientType, int clientNumber = -1) {
    if (clientType != "offline") {
        String key = getLiveModeKey(clientType, clientNumber)
        options["liveModeInfo"][key]["exception"] = exception
    }

    if (options.currentTry < options.nodeReallocateTries - 1) {
        options["stashResults"] = false
    } else {
        currentBuild.result = "FAILURE"
    }
    println exception.toString()
    if (exception instanceof ExpectedExceptionWrapper) {
        GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${exception.getMessage()}", "${BUILD_URL}")
        throw new ExpectedExceptionWrapper("${exception.getMessage()}", exception.getCause())
    } else {
        GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
        throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", exception)
    }
}


def saveTestResults(String osName, Map options, String clientType, int clientNumber = -1) {
    String stashPostfix = ""
    String modeKey = ""

    if (clientType != "offline") {
        // if the client type isn't offline - add the postfix with the client type
        modeKey = getLiveModeKey(clientType, clientNumber)
        stashPostfix = "_${modeKey}"
    }

    // TODO: implement merging on the side of the primary client
    if (clientType == "primary") {
        for (key in options["liveModeInfo"].keySet()) {
            if (key == "primary") {
                continue
            }

            while (!options["liveModeInfo"][key]["stashed"]) {
                if (options["liveModeInfo"][key]["exception"]) {
                    throw new Exception("${key} was failed")
                }

                sleep(5)
            }
        }

        return
    }

    try {
        dir(options.stageName) {
            utils.moveFiles(this, "Windows", "../*.log", ".")
            utils.moveFiles(this, "Windows", "../scripts/*.log", ".")
            utils.renameFile(this, "Windows", "launcher.engine.log", "${options.stageName}${stashPostfix}_${options.currentTry}.log")
        }
        archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
        if (options["stashResults"]) {
            dir('Work') {
                if (fileExists("Results/RenderStudio/session_report.json")) {
                    println "Stashing test results to : ${options.testResultsName}"

                    if (clientType == "secondary") {
                        // save results to merge them on the primary client
                        makeStash(includes: "**/*", name: "${options.testResultsName}${stashPostfix}", storeOnNAS: options.storeOnNAS)
                        options["liveModeInfo"][modeKey]["stashed"] = true
                    } else {
                        utils.stashTestData(this, options, options.storeOnNAS)

                        def sessionReport = readJSON file: 'Results/RenderStudio/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")

                            dir("C:\\Users\\${env.USERNAME}\\AppData\\Roaming") {
                                utils.removeDir(this, osName, "AMDRenderStudio")
                            }
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        // reallocate node if there are still attempts
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            if (sessionReport.summary.total != sessionReport.summary.skipped) {
                                uninstallMSI("AMD RenderStudio", options.stageName, options.currentTry)
                                removeInstaller(osName: "Windows", options: options, extension: "msi")
                                String errorMessage = (options.currentTry < options.nodeReallocateTries) ? "All tests were marked as error. The test group will be restarted." : "All tests were marked as error."
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.mode)
                        }
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
}


def executeOfflineTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    options["stashResults"] = true

    try {
        checkoutAutotests(options)
        prepareAMDRenderStudio(osName, options, "offline")
        executeTestsOnClient(osName, asicName, options, "offline")

        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", "Windows")
    } catch (e) {
        processClientException(e, options, "offline")
    } finally {
        saveTestResults(osName, options, "offline")
    }
}


def syncLMClients(String osName, Map options, String clientType, int clientNumber = -1) {
    String modeKey = getLiveModeKey(clientType, clientNumber)
    options["liveModeInfo"][modeKey]["ready"] = true

    for (key in options["liveModeInfo"].keySet()) {
        while (!options["liveModeInfo"][key]["ready"]) {
            if (options["liveModeInfo"][key]["exception"]) {
                throw new Exception("${key} was failed")
            }

            sleep(5)
        }
    }

    println("[INFO] ${env.NODE_NAME} (${modeKey} client) is ready")
}


def getCommunicationPort(Map options) {
    // always use port 10000 to synchronize autotests
    return "10000"
}


def executeLMTestsPrimary(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    options["stashResults"] = true

    try {
        checkoutAutotests(options)
        prepareAMDRenderStudio(osName, options, "primary")
        syncLMClients(osName, options, "primary")
        executeTestsOnClient(osName, asicName, options, "primary")

        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", "Windows")
    } catch (e) {
        processClientException(e, options, "primary")
    } finally {
        saveTestResults(osName, options, "primary")
    }
}


def executeLMTestsSecondary(String osName, String asicName, Map options, int clientNumber) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    try {
        checkoutAutotests(options)
        prepareAMDRenderStudio(osName, options, "secondary", clientNumber)
        syncLMClients(osName, options, "secondary", clientNumber)
        executeTestsOnClient(osName, asicName, options, "secondary", clientNumber)

        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", "Windows")
    } catch (e) {
        processClientException(e, options, "secondary", clientNumber)
    } finally {
        saveTestResults(osName, options, "secondary", clientNumber)
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    options["stashResults"] = true

    try {
        int requiredClientsNumber = getNumberOfRequiredClients(options)

        if (requiredClientsNumber == 0) {
            // run offline autotests
            throw new Exception("Required 0 clients. Unexpected situation")
        } else if (requiredClientsNumber == 1) {
            // run Live Mode autotests
            // take one client as primary-client, and other clients as secondary-clients
            executeOfflineTests(osName, asicName, options)
        } else if (requiredClientsNumber > 1) {
            // run Live Mode autotests
            // take one client as primary client, and other clients as secondary clients

            options["liveModeInfo"] = [:]

            // create threads for the primary client and the secondary clients and run them parallel
            Map threads = [:]

            threads["${options.stageName}-primary"] = { 
                options["liveModeInfo"]["primary"] = new ConcurrentHashMap()
                executeLMTestsPrimary(osName, asicName, options)
            }

            println("[INFO] Take ${env.NODE_NAME} ${asicName}-${osName} node as the primary client")

            // one of required clients is the primary client
            options["secondaryClientsNumber"] = requiredClientsNumber - 1

            for (int i = 0; i < options["secondaryClientsNumber"]; i++) {
                int clientNumber = i
                options["liveModeInfo"]["secondary-${clientNumber}"] = new ConcurrentHashMap()
                threads["${options.stageName}-secondary-${i}"] = {
                    node(getLabels(options)) {
                        timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
                            ws("WS/${options.PRJ_NAME}_Test") {
                                println("[INFO] Take ${env.NODE_NAME} ${asicName}-${osName} node as the secondary client #${clientNumber}")
                                executeLMTestsSecondary(osName, asicName, options, clientNumber)
                            }
                        }
                    }
                }
            }

            parallel threads

            for (entry in options["liveModeInfo"]) {
                if (entry.value["exception"]) {
                    def exception = entry.value["exception"]
                    throw new ExpectedExceptionWrapper("Client side tests got an error: ${exception.getMessage()}", exception)
                }
            }
        }
    } catch (e) {
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", e)
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
    dir("Live") {
        patchSubmodule()
    }

    dir("Route") {
        patchSubmodule()
    }

    dir("Storage") {
        patchSubmodule()
    }

    dir("Frontend") {
        patchSubmodule()
    }

    dir("Engine") {
        patchSubmodule()
    }

    String version = readFile("VERSION.txt").trim()

    if (env.CHANGE_URL) {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. PR: #${env.CHANGE_ID}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    } else {
        writeFile(file: "VERSION.txt", text: "Version: ${version}. Branch: ${env.BRANCH_NAME ?: options.projectBranch}. Build: #${env.BUILD_NUMBER}. Hash: ${options.commitShortSHA}")
    }
}


def executeBuildScript(String osName, Map options, String usdPath = "default") {
    downloadFiles("/volume1/CIS/WebUSD/Modules/USD/${osName}/${usdPath}/info.json", ".", , "--quiet")
    def usdInfo = readJSON(file: "info.json")

    println("[INFO] Found USD module hash: ${usdInfo['hash']}")

    if (options.usdHash != usdInfo["hash"] && env.BRANCH_NAME && env.BRANCH_NAME == "main") {
        println("[INFO] New USD version detected in main branch. It'll be saved")
        options.saveUSD = true
    }

    dir("Build/Downloads/lights") {
        downloadFiles("/volume1/CIS/WebUSD/Lights/", ".", , "--quiet")
    }

    if (options.rebuildUSD) {
        if (isUnix()) {
            sh """
                export OS=
                python Tools/Build.py -ss -sr -sl -sh -sa -v >> ${STAGE_NAME}.Build.log 2>&1
            """
        } else {
            bat """
                call "%VS2019_VSVARSALL_PATH%" >> ${STAGE_NAME}.EnvVariables.log 2>&1
                python Tools/Build.py -ss -sr -sl -sh -sa -v >> ${STAGE_NAME}.Build.log 2>&1
            """
        }

        if (options.saveUSD) {
            dir("Build/Install/USD") {
                def newUSDInfo = JsonOutput.toJson(["hash": options.usdHash])
                writeJSON(file: 'info.json', json: JSONSerializer.toJSON(newUSDInfo, new JsonConfig()), pretty: 4)
                uploadFiles(".", "/volume1/CIS/WebUSD/Modules/USD/${osName}/${usdPath}/", "--quiet")
            }
        }
    } else {
        dir("Build/Install/USD") {
            downloadFiles("/volume1/CIS/WebUSD/Modules/USD/${osName}/${usdPath}/", ".", , "--quiet")
        }
    }

    if (isUnix()) {
        sh """
            export OS=
            python Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
        """
    } else {
        bat """
            call "%VS2019_VSVARSALL_PATH%" >> ${STAGE_NAME}.EnvVariables.log 2>&1
            python Tools/Build.py -v >> ${STAGE_NAME}.Build.log 2>&1
        """
    }
}


def executeBuildWindows(Map options) {
    options["stage"] = "Build"

    withEnv(["PATH=C:\\Cmake326\\bin;${PATH}"]) {
        withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE_WEBUSD) {
            utils.reboot(this, "Windows")

            String webrtcPath = "C:\\JN\\thirdparty\\webrtc"
            String amfPath = "C:\\JN\\thirdparty\\amf"

            downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")
            downloadFiles("/volume1/CIS/WebUSD/AMF-WIN", amfPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

            if (options.projectBranchName.contains("demo/november")) {
                downloadFiles("/volume1/CIS/WebUSD/Additional/templates/env.demo.desktop.template", "${env.WORKSPACE.replace('C:', '/mnt/c').replace('\\', '/')}/Frontend", "--quiet")
                bat "move Frontend\\env.demo.desktop.template Frontend\\.env.production"
            } else {
                downloadFiles("/volume1/CIS/WebUSD/Additional/templates/env.desktop.template", "${env.WORKSPACE.replace('C:', '/mnt/c').replace('\\', '/')}/Frontend", "--quiet")
                bat "move Frontend\\env.desktop.template Frontend\\.env.production"
            }

            String frontendVersion
            String renderStudioVersion

            dir("Frontend") {
                frontendVersion = readFile("VERSION.txt").trim()
            }

            renderStudioVersion = readFile("VERSION.txt").trim()

            String envProductionContent = readFile("./Frontend/.env.production")
            envProductionContent = envProductionContent + "VUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"

            withCredentials([string(credentialsId: "WebUsdUrlTemplate", variable: "TEMPLATE")]) {
                String url = TEMPLATE.replace("<instance>", options.deployEnvironment)

                envProductionContent = envProductionContent.replace("VUE_APP_URL_STORAGE=", "VUE_APP_URL_STORAGE=\"${url}/storage/\"")
            }

            writeFile(file: "./Frontend/.env.production", text: envProductionContent)

            try {
                withEnv(["PATH=c:\\CMake322\\bin;c:\\python37\\;c:\\python37\\scripts\\;${PATH}", "PYTHON39_PATH=c:\\Python39\\python.exe", "PYTHON39_SCRIPTS_PATH=c:\\Python39\\Scripts"]) {
                    bat """
                        cmake --version >> ${STAGE_NAME}.Build.log 2>&1
                        python--version >> ${STAGE_NAME}.Build.log 2>&1
                        python -m pip install conan >> ${STAGE_NAME}.Build.log 2>&1
                        mkdir Build
                        echo [WebRTC] >> Build\\LocalBuildConfig.txt
                        echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                        echo [AMF] >> Build/LocalBuildConfig.txt
                        echo path = ${amfPath.replace("\\", "/")}/AMF-WIN >> Build\\LocalBuildConfig.txt
                    """

                    executeBuildScript("Windows", options)

                    println("[INFO] Start building installer")

                    bat """
                        python Tools/Package.py -v >> ${STAGE_NAME}.Package.log 2>&1
                    """

                    println("[INFO] Saving exe files to NAS")

                    dir("Frontend\\dist_electron") {
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

                            makeStash(includes: renamedFilename, name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)
                        }
                    }
                }
            } catch(e) {
                println("Error during build on Windows")
                println(e.toString())

                def exception = e

                try {
                    String buildLogContent = readFile("${STAGE_NAME}.Build.log")
                    if (buildLogContent.contains("CMake error : Cannot restore timestamp")) {
                        exception = new ExpectedExceptionWrapper(NotificationConfiguration.USD_GLTF_BUILD_ERROR, e)
                        exception.retry = true

                        utils.reboot(this, osName)
                    }
                } catch (e1) {
                    println("[WARNING] Could not analyze build log")
                }

                throw exception
            } finally {
                archiveArtifacts "*.log"
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

    String envProductionContent

    if (!options.customDomain) {
        downloadFiles("/volume1/CIS/WebUSD/Additional/templates/env.web.template", "./Frontend", "--quiet")
        sh "mv ./Frontend/env.web.template ./Frontend/.env.production"

        envProductionContent = readFile("./Frontend/.env.production")

        if (options.deployEnvironment == "prod") {
            envProductionContent = envProductionContent.replace("<domain_name>.", "")
        } else {
            envProductionContent = envProductionContent.replace("<domain_name>", options.deployEnvironment)
        }

        writeFile(file: "./Frontend/.env.production", text: envProductionContent)
    } else {
        downloadFiles("/volume1/CIS/WebUSD/Additional/templates/env.web.customdomain.template", "./Frontend", "--quiet")
        sh "mv ./Frontend/env.web.customdomain.template ./Frontend/.env.production"

        envProductionContent = readFile("./Frontend/.env.production")
        envProductionContent = envProductionContent.replaceAll("<custom_domain>", options.customDomain)
        writeFile(file: "./Frontend/.env.production", text: envProductionContent)
    }

    if (options.disableSsl) {
        envProductionContent = readFile("./Frontend/.env.production")
        envProductionContent = envProductionContent.replaceAll("https", "http").replaceAll("wss", "ws")
        writeFile(file: "./Frontend/.env.production", text: envProductionContent)
    }

    String frontendVersion
    String renderStudioVersion

    dir("Frontend") {
        frontendVersion = readFile("VERSION.txt").trim()
    }

    renderStudioVersion = readFile("VERSION.txt").trim()

    envProductionContent = readFile("./Frontend/.env.production")
    envProductionContent = envProductionContent + "\nVUE_APP_FRONTEND_VERSION=${frontendVersion}\nVUE_APP_RENDER_STUDIO_VERSION=${renderStudioVersion}"
    writeFile(file: "./Frontend/.env.production", text: envProductionContent)

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
            """

            executeBuildScript("Linux", options)

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

    withNotifications(title: "Web", options: options, configuration: NotificationConfiguration.DEPLOY_APPLICATION) {
        if (options.deploy) {
            println "[INFO] Start deploying on $options.deployEnvironment environment"
            failure = false

            try {
                println "[INFO] Start deploy"

                render_studio_deploy(options.deployEnvironment)

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
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

            patchVersions(options)

            if (options.customHybridLinux && isUnix()) {
                downloadFiles(options.customHybridLinux, ".")

                sh "tar -xJf BaikalNext_Build-Ubuntu20.tar.xz"

                sh """
                    yes | cp -rf BaikalNext/bin/* Engine/RadeonProRenderUSD/deps/RPR/RadeonProRender/binUbuntu18
                    yes | cp -rf BaikalNext/inc/* Engine/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                    yes | cp -rf BaikalNext/inc/Rpr/* Engine/RadeonProRenderUSD/deps/RPR/RadeonProRender/inc
                """

                dir ("Engine/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            } else if (options.customHybridWin && !isUnix()) {
                downloadFiles(options.customHybridWin, ".")

                unzip dir: '.', glob: '', zipFile: 'BaikalNext_Build-Windows.zip'

                bat """
                    copy /Y BaikalNext\\bin\\* Engine\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\binWin64
                    copy /Y BaikalNext\\inc\\* Engine\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\inc\\Rpr\\* Engine\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\inc
                    copy /Y BaikalNext\\lib\\* Engine\\RadeonProRenderUSD\\deps\\RPR\\RadeonProRender\\libWin64
                """

                dir ("Engine/RadeonProRenderUSD/deps/RPR/RadeonProRender/rprTools") {
                    downloadFiles("/volume1/CIS/WebUSD/Additional/RadeonProRenderCpp.cpp", ".")
                }
            }
        }

        utils.removeFile(this, osName, "*.log")

        outputEnvironmentInfo(osName == "Windows" ? "Windows" : "Ubuntu20")

        switch(osName) {
            case 'Windows':
                options[getProduct.getIdentificatorKey(osName, options)] = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                executeBuildWindows(options)
                break
            case 'Web':
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
    // TODO: required actualization

    /*println currentBuild.result

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
    }*/
}


def getReportBuildArgs(String mode, Map options) {
    String buildNumber = ""

    if (options.useTrackedMetrics) {
        if (env.BRANCH_NAME && env.BRANCH_NAME != "main") {
            // use any large build number in case of PRs and other branches in auto job
            // it's required to display build as last one
            buildNumber = "10000"
        } else {
            buildNumber = env.BUILD_NUMBER
        }
    }

    if (options["isPreBuilt"]) {
        return """RenderStudio "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(mode)}\" ${buildNumber}"""
    } else {
        return """RenderStudio ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(mode)}\" ${buildNumber}"""
    }
}


def fillDescription(Map options) {
    currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    currentBuild.description += "<b>Commit HybridPro SHA:</b> ${options.hybridProSHA}<br/>"

    currentBuild.description += "<br/>"

    currentBuild.description += "<b>Render Studio version:</b> ${options.version}<br/>"

    dir("Frontend") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Frontend version:</b> ${version}<br/>"
    }

    dir("Engine") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Engine version:</b> ${version}<br/>"
    }

    dir("Live") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Live server version:</b> ${version}<br/>"
    }

    dir("Route") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Router version:</b> ${version}<br/>"
    }

    dir("Storage") {
        String version = readFile("VERSION.txt").trim()
        currentBuild.description += "<b>Storage version:</b> ${version}<br/>"
    }
}


def executePreBuild(Map options) {
    options.executeTests = true

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
    }

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    options.commitShortSHA = bat (script: "git log --format=%%h -1 ", returnStdout: true).split('\r\n')[2].trim()

    if (options["executeBuild"]) {
        // get links to the latest built HybridPro
        String url = "${env.JENKINS_URL}/job/Archive/job/RadeonProRender-HybridManual/992/api/json?tree=number,url,description"

        def rawInfo = httpRequest(
            url: url,
            authentication: 'jenkinsCredentials',
            httpMode: 'GET'
        )

        def parsedInfo = parseResponse(rawInfo.content)


        Integer hybridBuildNumber
        String hybridBuildUrl

        hybridBuildNumber = 992
        hybridBuildUrl = parsedInfo.url

        options.hybridProSHA = parsedInfo.description.split("Commit SHA:</b>")[1].split("<br/>")[0]

        withCredentials([string(credentialsId: "nasURLFrontend", variable: "REMOTE_HOST")]) {
            options.customHybridWin = "/volume1/web/Archive/RadeonProRender-HybridManual/${hybridBuildNumber}/Artifacts/BaikalNext_Build-Windows.zip"
            options.customHybridLinux = "/volume1/web/Archive/RadeonProRender-HybridManual/${hybridBuildNumber}/Artifacts/BaikalNext_Build-Ubuntu20.tar.xz"
        }

        rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${hybridBuildUrl}">[HybridPro] Link to the used HybridPro build</a></h3>""")

        // branch postfix
        options["branchPostfix"] = ""
        if (env.BRANCH_NAME) {
            options["branchPostfix"] = "auto" + "_" + env.BRANCH_NAME.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
        } else {
            options["branchPostfix"] = "manual" + "_" + options.projectBranch.replace('/', '-').replace('origin-', '') + "_" + env.BUILD_NUMBER
        }
    }

    if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith("PR-")) {
        options.deployEnvironment = "pr${env.BRANCH_NAME.split('-')[1]}"
    } else if (env.BRANCH_NAME && env.BRANCH_NAME == "main") {
        //removeClosedPRs(options)
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
        String version = readFile("VERSION.txt").trim()

        options.usdHash = bat (script: "git submodule--helper list", returnStdout: true).split('\r\n')[2].split()[1].trim()

        if (env.BRANCH_NAME) {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                GithubNotificator githubNotificator = new GithubNotificator(this, options)
                githubNotificator.init(options)
                options["githubNotificator"] = githubNotificator
                githubNotificator.initPreBuild("${BUILD_URL}")
                options.projectBranchName = githubNotificator.branchName
            }

            if (env.CHANGE_URL) {
                GithubApiProvider githubApiProvider = new GithubApiProvider(this)
                String originalUSDHash = githubApiProvider.getContentInfo(options["projectRepo"].replace("git@github.com:", "https://github.com/").replaceAll(".git\$", ""), env.CHANGE_TARGET, "USD")["sha"]

                println """
                    Original USD Hash: ${originalUSDHash}
                    Current USD Hash: ${options.usdHash}
                """

                if (originalUSDHash != options.usdHash) {
                    println("[INFO] USD module was updated. It'll be rebuilt")
                    options.rebuildUSD = true
                }
            }

            if ((env.BRANCH_NAME == "main" || env.BRANCH_NAME == "release") && options.commitAuthor != "radeonprorender") {
                println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                println "[INFO] Current build version: ${version}"

                if (env.BRANCH_NAME == "release") {
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
        } else {
            options.projectBranchName = options.projectBranch
        }

        options.version = version

        fillDescription(options)
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_web_viewer') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            options['testsBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            if (options.testsPackage != "none") {
                if (fileExists("jobs/${options.testsPackage}")) {
                    options.packageInfo = readJSON file: "jobs/${options.testsPackage}"
                } else {
                    options.packageInfo = readJSON file: "jobs/${options.testsPackage.replace('.json', '-desktop.json')}"
                }

                options.isPackageSplitted = options.packageInfo["split"]
                // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                if (options.isPackageSplitted && options.tests) {
                    options.testsPackage = "none"
                }
            }

            if (options.testsPackage != "none") {
                def tempTests = []

                if (options.isPackageSplitted) {
                    println("[INFO] Tests package '${options.testsPackage}' can be splitted")
                } else {
                    // save tests which user wants to run with non-splitted tests package
                    if (options.tests) {
                        tempTests = options.tests.split(" ") as List
                    }
                    println("[INFO] Tests package '${options.testsPackage}' can't be splitted")
                }

                // modify name of tests package if tests package is non-splitted (it will be use for run package few time with different modes)
                String modifiedPackageName = "${options.testsPackage}~"

                // receive list of group names from package
                List groupsFromPackage = []

                if (options.packageInfo["groups"] instanceof Map) {
                    groupsFromPackage = options.packageInfo["groups"].keySet() as List
                } else {
                    // iterate through all parts of package
                    options.packageInfo["groups"].each() {
                        groupsFromPackage.addAll(it.keySet() as List)
                    }
                }

                groupsFromPackage.each() {
                    if (options.isPackageSplitted) {
                        tempTests << it
                    } else {
                        if (tempTests.contains(it)) {
                            // add duplicated group name in name of package group name for exclude it
                            modifiedPackageName = "${modifiedPackageName},${it}"
                        }
                    }
                }

                options.tests = tempTests

                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.modes.each { mode ->
                    options.tests.each() {
                        tests << "${it}-${mode}"
                    }
                }

                modifiedPackageName = modifiedPackageName.replace('~,', '~')

                if (options.isPackageSplitted) {
                    options.testsPackage = "none"
                } else {
                    options.testsPackage = modifiedPackageName
                    // check that package is splitted to parts or not
                    if (options.packageInfo["groups"] instanceof Map) {
                        options.modes.each { mode ->
                            tests << "${modifiedPackageName}-${mode}"
                        } 
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        options.modes.each { mode ->
                            for (int i = 0; i < options.packageInfo["groups"].size(); i++) {
                                tests << "${modifiedPackageName}-${mode}".replace(".json", ".${i}.json")
                            }
                        }

                        for (int i = 0; i < options.packageInfo["groups"].size(); i++) {
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                }
            } else if (options.tests) {
                options.tests =  options.tests.split(" ") as List
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.modes.each { mode ->
                    options.tests.each() {
                        tests << "${it}-${mode}"
                    }
                }
            } else {
                options.executeTests = false
            }
            options.tests = tests

            options["liveModeConfiguration"] = readJSON(file: "jobs/live_mode_configuration.json")
        }
        
        options.testsList = options.tests

        println "timeouts: ${options.timeouts}"

        // make lists of raw profiles and lists of beautified profiles (displaying profiles)
        multiplatform_pipeline.initProfiles(options)

        if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
            options.reportUpdater = new ReportUpdater(this, env, options)
            options.reportUpdater.init(this.&getReportBuildArgs)
        }

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String mode) {
    cleanWS()
    try {
        String modeName = options.displayingTestProfiles[mode]

        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${modeName}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(mode)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), mode)) {
                            return
                        }

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                echo "[ERROR] Failed to unstash ${it}"
                                lostStashes.add("'${testName}'")
                                println(e.toString())
                                println(e.getMessage())
                            }

                        }
                    }
                }
            }

            try {
                dir("scripts") {
                    python3("prepare_test_cases.py --mode desktop")
                }

                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${mode}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                String metricsRemoteDir

                if (env.BRANCH_NAME) {
                    metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/RenderStudio/auto/main/${mode}"
                } else {
                    metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/RenderStudio/weekly/${mode}"
                }

                GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                if (options.useTrackedMetrics) {
                    utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
                }

                String matLibUrl

                withCredentials([string(credentialsId: "matLibUrl", variable: "MATLIB_URL")]) {
                    matLibUrl = MATLIB_URL
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}", "MATLIB_URL=${matLibUrl}"]) {
                    dir("jobs_launcher") {
                        List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                        retryInfoList.each{ gpu ->
                            gpu['Tries'].each{ group ->
                                group.each{ groupKey, retries ->
                                    if (groupKey.endsWith(mode)) {
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
                        try {
                            bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(modeName, options)}"
                        } catch (e) {
                            String errorMessage = utils.getReportFailReason(e.getMessage())
                            GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "failure", options, errorMessage, "${BUILD_URL}")
                            if (utils.isReportFailCritical(e.getMessage())) {
                                throw e
                            } else {
                                currentBuild.result = "FAILURE"
                                options.problemMessageManager.saveGlobalFailReason(errorMessage)
                            }
                        }
                    }
                }

                if (options.saveTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "failure", options, errorMessage, "${BUILD_URL}")
                println("[ERROR] Failed to build test report.")
                println(e.toString())
                println(e.getMessage())
                if (!options.testDataSaved && !options.storeOnNAS) {
                    try {
                        // Save test data for access it manually anyway
                        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                            "Test Report ${modeName}", "Summary Report, Compare Report" , options.storeOnNAS, \
                            ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                        options.testDataSaved = true 
                    } catch(e1) {
                        println("[WARNING] Failed to publish test data.")
                        println(e.toString())
                        println(e.getMessage())
                    }
                }
                throw e
            }

            try {
                dir("jobs_launcher") {
                    bat "get_status.bat ..\\summaryTestResults"
                }
            } catch(e) {
                println("[ERROR] Failed to generate slack status.")
                println(e.toString())
                println(e.getMessage())
            }

            try {
                dir("jobs_launcher") {
                    archiveArtifacts "launcher.engine.log"
                }
            } catch(e) {
                println("[ERROR] during archiving launcher.engine.log")
                println(e.toString())
                println(e.getMessage())
            }

            Map summaryTestResults = [:]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults['passed'] = summaryReport.passed
                summaryTestResults['failed'] = summaryReport.failed
                summaryTestResults['error'] = summaryReport.error
                if (summaryReport.error > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"

                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                } else if (summaryReport.failed > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
                    currentBuild.result = "UNSTABLE"

                    options.problemMessageManager.saveUnstableReason(NotificationConfiguration.SOME_TESTS_FAILED)
                }
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                println("[ERROR] CAN'T GET TESTS STATUS")
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            try {
                options["testsStatus-${mode}"] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options["testsStatus-${mode}"] = ""
            }

            withNotifications(title: "Building test report for ${modeName}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report ${modeName}", "Summary Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${modeName}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }

            println "BUILD RESULT: ${currentBuild.result}"
            println "BUILD CURRENT RESULT: ${currentBuild.currentResult}"
        }
    } catch(e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        if (!options.storeOnNAS) {
            utils.generateOverviewReport(this, this.&getReportBuildArgs, options)
        }
    }
}


def call(
    String projectBranch = "",
    String testsBranch = "master",
    String platforms = 'Windows',
    Boolean enableNotifications = false,
    Boolean generateArtifact = true,
    Boolean deploy = true,
    String deployEnvironment = 'pr',
    String customDomain = '',
    Boolean disableSsl = false,
    String testsPackage = "regression.json",
    String tests = '',
    String updateRefs = 'No',
    Integer testCaseRetries = 5,
    Boolean skipBuild = false,
    String customBuildLinkWindows = "",
    Boolean rebuildUSD = false,
    Boolean saveUSD = false
) {
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    if (env.BRANCH_NAME) {
        switch (env.BRANCH_NAME) {
            case "main":
                deployEnvironment = "dev"
                break
            case "release":
                deployEnvironment = "prod"
                break
        }
    }

    if (skipBuild && !customBuildLinkWindows && platforms.contains("Windows:")) {
        skipBuild = false
    } else if (customBuildLinkWindows && !skipBuild) {
        skipBuild = true
    }

    Boolean isPreBuilt = skipBuild

    List modes = []

    if (platforms.contains("Windows:")) {
        modes.add("Desktop")
    }
    if (platforms.contains("Web:")) {
        modes.add("Web")
    }

    println """
        Deploy: ${deploy}
        Deploy environment: ${deployEnvironment}
        Custom domain: ${customDomain}
        Disable SSL: ${disableSsl}
        Is prebuilt: ${isPreBuilt}
        Modes: ${modes}
    """

    boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") 
        || (env.JOB_NAME.contains("Manual") && testsPackage == "Full.json")
        || env.BRANCH_NAME)

    boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly") || (env.BRANCH_NAME && env.BRANCH_NAME == "main")

    def options = [configuration: PIPELINE_CONFIGURATION,
                                platforms: platforms,
                                projectBranch:projectBranch,
                                projectRepo:PROJECT_REPO,
                                testRepo:TEST_REPO,
                                testsBranch:testsBranch,
                                enableNotifications:enableNotifications,
                                deployEnvironment: deployEnvironment,
                                customDomain: customDomain,
                                disableSsl: disableSsl,
                                deploy:deploy, 
                                PRJ_NAME:'RS',
                                PRJ_ROOT:'radeon-pro',
                                BUILDER_TAG:'BuilderWebUsdViewer',
                                executeBuild:!isPreBuilt,
                                executeTests:true,
                                BUILD_TIMEOUT:120,
                                TEST_TIMEOUT:240,
                                NON_SPLITTED_PACKAGE_TIMEOUT: 240,
                                problemMessageManager:problemMessageManager,
                                isPreBuilt:isPreBuilt,
                                splitTestsExecution: true,
                                storeOnNAS: true,
                                flexibleUpdates: true,
                                skipCallback: this.&filter,
                                modes: modes,
                                testsPackage:testsPackage,
                                tests:tests,
                                updateRefs:updateRefs,
                                testsPreCondition: this.&hasIdleClients,
                                testCaseRetries:testCaseRetries,
                                executeBuild: !skipBuild,
                                customBuildLinkWindows:customBuildLinkWindows,
                                ADDITIONAL_XML_TIMEOUT:15,
                                nodeRetry: [],
                                rebuildUSD: rebuildUSD,
                                saveUSD: saveUSD,
                                finishedBuildStages: new ConcurrentHashMap(),
                                parallelExecutionType:TestsExecutionType.valueOf("TakeAllNodes"),
                                useTrackedMetrics:useTrackedMetrics,
                                saveTrackedMetrics:saveTrackedMetrics
                                ]
    try {
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
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
        if (env.CHANGE_URL){
            GithubNotificator.sendPullRequestComment("Jenkins build finished as ${currentBuild.result}", options)
        } 
    }
}