import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


def executeGenTestRefCommand(String asicName, String osName, Map options, String apiValue = "vulkan") {
    dir('BaikalNext/RprTest') {
        options.enableRTX = ""
        if (!asicName.contains("RTX")) {
            println "[INFO] Enable rrn for ${asicName}"
            options.enableRTX = "-enable-rrn"
        }

        switch(osName) {
            case 'Windows':
                bat """
                    ..\\bin\\RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            case 'OSX':
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            default:
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
        }
    }
}

def executeTestCommand(String asicName, String osName, Map options, String apiValue = "vulkan") {
    dir('BaikalNext/RprTest') {
        options.enableRTX = ""
        if (!asicName.contains("RTX")) {
            println "[INFO] Enable rrn for ${asicName}"
            options.enableRTX = "-enable-rrn"
        }

        switch(osName) {
            case 'Windows':
                bat """
                    ..\\bin\\RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            case 'OSX':
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            default:
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
        }
    }
}


def executeTestsWithApi(String osName, String asicName, Map options, String apiValue = "vulkan") {
    cleanWS(osName)
    String error_message = ""
    String REF_PATH_PROFILE
    Boolean isRTXCard = asicName.contains("RTX") || asicName.contains("AMD_RX6") || asicName.contains("AMD_RX7")

    if (isRTXCard) {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${apiValue}/${asicName}-${osName}"
        outputEnvironmentInfo(osName, "${STAGE_NAME}")
    } else {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${apiValue}/AMD_RX6800XT-Windows"
        outputEnvironmentInfo(osName, "${STAGE_NAME}")
    }

    try {
        String binaryName = hybrid.getArtifactName(osName)
        String binaryPath = "/volume1/web/${options.originalBuildLink.split('/job/', 2)[1].replace('/job/', '/')}Artifacts/${binaryName}"
        downloadFiles(binaryPath, ".")

        switch(osName) {
            case "Windows":
                bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " ${binaryName} -aoa")
                break
            default:
                sh "tar -xJf ${binaryName}"
        }

        if (options["updateRefs"]) {
            println "Updating Reference Images"
            try {
                executeGenTestRefCommand(asicName, osName, options, apiValue)
            } catch (e) {
                // ignore exceptions in case of baselines updating on d3d12
                if (!options["updateRefs"] || apiValue != "d3d12") {
                    throw e
                }
            }

            if (isRTXCard) {
                // skip refs updating on non rtx cards
                uploadFiles("./BaikalNext/RprTest/ReferenceImages/", REF_PATH_PROFILE)
            }            
        } else {
            println "Execute Tests"
            downloadFiles("${REF_PATH_PROFILE}/", "./BaikalNext/RprTest/ReferenceImages/")
            executeTestCommand(asicName, osName, options, apiValue)
        }
    } catch (e) {
        println("Exception during tests execution")
        println(e.getMessage())
        error_message = e.getMessage()
        options.successfulTests = false

        try {
            if (options['updateRefs']) {
                currentBuild.description += "<span style='color: #b03a2e'>References weren't updated for ${asicName}-${osName}-${apiValue} due to non-zero exit code returned by RprTest tool</span><br/>"
            }

            dir('HTML_Report') {
                checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")
                python3("-m pip install -r requirements.txt")
                python3("hybrid_report.py --xml_path ../${STAGE_NAME}_${apiValue}.gtest.xml --images_basedir ../BaikalNext/RprTest --report_path ../${asicName}-${osName}-${apiValue}-Failures")
            }

            if (!options.storeOnNAS) {
                makeStash(includes: "${asicName}-${osName}-${apiValue}-Failures/**/*", name: "testResult-${asicName}-${osName}-${apiValue}", allowEmpty: true)
            }

            utils.publishReport(this, "${BUILD_URL}", "${asicName}-${osName}-${apiValue}-Failures", "report.html", "${STAGE_NAME}_${apiValue}_Failures", "${STAGE_NAME}_${apiValue}_Failures", options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

            options["failedConfigurations"].add("testResult-" + asicName + "-" + osName + "-" + apiValue)
        } catch (err) {
            println("[ERROR] Failed to publish HTML report.")
            println(err.getMessage())
        }
    } finally {
        String title = "${asicName}-${osName}-${apiValue}"
        String description = error_message ? "Testing finished with error message: ${error_message}" : "Testing finished"
        String status = error_message ? "action_required" : "success"
        String url = error_message ? "${env.BUILD_URL}/${STAGE_NAME}_${apiValue}_Failures" : "${env.BUILD_URL}/artifact/${STAGE_NAME}_${apiValue}.log"
        GithubNotificator.updateStatus('Test', title, status, options, description, url)

        archiveArtifacts "*.log"
        archiveArtifacts "*.gtest.xml"
        junit "*.gtest.xml"
    }
}


def changeWinDevMode(Boolean turnOn) {
    String value = turnOn ? "1" : "0"

    powershell """
        reg add "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\AppModelUnlock" /t REG_DWORD /f /v "AllowDevelopmentWithoutDevLicense" /d "${value}"
    """

    utils.reboot(this, "Windows")
}


def executeTests(String osName, String asicName, Map options) {
    GithubNotificator.updateStatus("Test", "${asicName}-${osName}", "in_progress", options, "In progress...")

    if (osName == "Windows") {
        changeWinDevMode(true)
    }

    Boolean someStageFail = false 

    options["apiValues"].each() { apiValue ->
        try {
            if (apiValue == "d3d12" && osName.contains("Ubuntu")) {
                // DX12 tests are supported only on Windows
                return
            }

            // run in parallel to display api value in UI of JUnit plugin
            Map stages = ["${apiValue}" : { executeTestsWithApi(osName, asicName, options, apiValue) }]
            parallel stages
        } catch (e) {
            someStageFail = true
            println(e.toString())
            println(e.getMessage())
        }
    }

    if (osName == "Windows") {
        changeWinDevMode(false)
    }

    if (someStageFail) {
        // send error signal for mark stage as failed
        error "Error during tests execution"
    }
}


def executePreBuild(Map options) {
    // set pending status for all
    if (env.CHANGE_ID) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
        }
        options['platforms'].split(';').each() { platform ->
            List tokens = platform.tokenize(':')
            String osName = tokens.get(0)
            // Statuses for builds
            if (tokens.size() > 1) {
                gpuNames = tokens.get(1)
                gpuNames.split(',').each() { gpuName ->
                    options["apiValues"].each() { apiValue ->
                        if (apiValue == "d3d12" && osName.contains("Ubuntu")) {
                            // DX12 tests are supported only on Windows
                            return
                        }

                        // Statuses for tests
                        GithubNotificator.createStatus("Test", "${gpuName}-${osName}-${apiValue}", "queued", options, "Scheduled", "${env.JOB_URL}")
                    }
                }
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    cleanWS()
    if (options["executeTests"] && testResultList) {
        try {
            String reportFiles = ""
            dir("SummaryReport") {
                options["apiValues"].each() { apiValue ->
                    testResultList.each() {
                        if (apiValue == "d3d12" && testResultList.contains("Ubuntu")) {
                            // DX12 tests are supported only on Windows
                            return
                        }

                        try {
                            if (!options.storeOnNAS) {
                                makeUnstash(name: "${it}_${apiValue}", storeOnNAS: options.storeOnNAS)
                                reportFiles += ", ${it}-${apiValue}-Failures/report.html".replace("testResult-", "")
                            } else if (options["failedConfigurations"].contains(it + "-" + apiValue)) {
                                reportFiles += ",../${it}_${apiValue}_Failures/report.html".replace("testResult-", "Test-")
                                println(reportFiles)
                            }
                        } catch(e) {
                            println("[ERROR] Can't unstash ${it}")
                            println(e.toString())
                            println(e.getMessage())
                        }
                    }
                }
            }

            if (options.failedConfigurations.size() != 0) {
                utils.publishReport(this, "${BUILD_URL}", "SummaryReport", "${reportFiles.replaceAll('^,', '')}",
                    "HTML Failures", reportFiles.replaceAll('^,', '').replaceAll("\\.\\./", ""), options.storeOnNAS,
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
            }
        } catch(e) {
            println(e.toString())
        }
    }

    // set error statuses for PR, except if current build has been superseded by new execution
    if (env.CHANGE_ID && !currentBuild.nextBuild) {
        // if jobs was aborted or crushed remove pending status for unfinished stages
        GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")
        String status = currentBuild.result ?: "success"
        status = status.toLowerCase()
        String commentMessage = ""
        if (!options.successfulTests) {
            commentMessage = "\\n Unit tests failures - ${env.BUILD_URL}/HTML_20Failures/"
        }
        String commitUrl = "${options.githubNotificator.repositoryUrl}/commit/${options.githubNotificator.commitSHA}"
        GithubNotificator.sendPullRequestComment("[UNIT TESTS] Jenkins build for ${commitUrl} finished as ${status} ${commentMessage}", options)
    }
}


def call(String commitSHA = "",
         String originalBuildLink = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String apiValues = "vulkan",
         Boolean updateRefs = false) {

    List apiList = apiValues.split(",") as List

    println "[INFO] Testing APIs: ${apiList}"

    currentBuild.description = ""

    multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, null,
                           [platforms:platforms,
                            commitSHA:commitSHA,
                            originalBuildLink:originalBuildLink,
                            updateRefs:updateRefs,
                            PRJ_NAME:"HybridPro",
                            PRJ_ROOT:"rpr-core",
                            projectRepo:hybrid.PROJECT_REPO,
                            BUILDER_TAG:"HybridBuilder",
                            executeBuild:false,
                            executeTests:true,
                            storeOnNAS: true,
                            finishedBuildStages: new ConcurrentHashMap(),
                            apiValues: apiList,
                            successfulTests: true])
}