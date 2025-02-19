import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "Ubuntu20": "tar.xz"],
    artifactNameBase: "BaikalNext_Build",
    testProfile: "apiValue",
    displayingProfilesMapping: [
        "apiValue": [
            "vulkan": "vulkan",
            "d3d12": "d3d12"
        ]
    ]
)


Boolean filter(Map options, String asicName, String osName, String apiValue) {
    if (apiValue == "d3d12" && osName.contains("Ubuntu")) {
        // DX12 tests are supported only on Windows
        return true
    }

    return false
}


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
                    ..\\bin\\RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            case 'OSX':
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            default:
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} -genref 1 --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
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
                    ..\\bin\\RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ..\\..\\${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            case 'OSX':
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
                break
            default:
                sh """
                    export LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH
                    ../bin/RprTest ${options.enableRTX} -videoapi ${apiValue} --gtest_filter=${options.gtestFilter} --gtest_output=xml:../../${STAGE_NAME}_${apiValue}.gtest.xml >> ../../${STAGE_NAME}_${apiValue}.log 2>&1
                """
        }
    }
}


def executeTestsWithApi(String osName, String asicName, Map options) {
    String apiValue = options.apiValue

    cleanWS(osName)
    String errorMessage = ""
    String REF_PATH_PROFILE
    Boolean isRTXCard = asicName.contains("RTX") || asicName.contains("AMD_RX6") || asicName.contains("AMD_RX7")

    if (isRTXCard) {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${apiValue}/${asicName}-${osName}"
        outputEnvironmentInfo(osName, "${STAGE_NAME}")
    } else {
        REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/${apiValue}/AMD_RX6800XT-Windows"
        outputEnvironmentInfo(osName, "${STAGE_NAME}")
    }

    String configurationName = "${asicName}-${osName}-${apiValue}"

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
            downloadFiles("${REF_PATH_PROFILE}/", "./BaikalNext/RprTest/ReferenceImages/", "", true, "nasURL", "nasSSHPort", true)
            executeTestCommand(asicName, osName, options, apiValue)
        }

        if (options["failedUpdatedConfigurations"].contains(configurationName)) {
            options["failedUpdatedConfigurations"].remove(configurationName)
        }
        if (!options["passedConfigurations"].contains(configurationName)) {
            options["passedConfigurations"].add(configurationName)
        }

        if (options["segmentationFaultConfigurations"].contains(configurationName)) {
            options["segmentationFaultConfigurations"].remove(configurationName)
        }
    } catch (e) {
        println("Exception during tests execution")
        println(e.getMessage())
        errorMessage = e.getMessage()

        String testsLogContent = readFile("${STAGE_NAME}_${apiValue}.log")

        if (options["passedConfigurations"].contains(configurationName)) {
            options["passedConfigurations"].remove(configurationName)
        }
        if (!options["failedUpdatedConfigurations"].contains(configurationName)) {
            options["failedUpdatedConfigurations"].add(configurationName)
        }

        if (testsLogContent.contains("Segmentation fault")) {
            println("[ERROR] Segmentation fault detected")

            if (!options["segmentationFaultConfigurations"].contains(configurationName)) {
                options["segmentationFaultConfigurations"].add(configurationName)
            }
        } else {
            try {
                dir('HTML_Report') {
                    checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")
                    python3("-m pip install -r requirements.txt")
                    python3("hybrid_report.py --xml_path ../${STAGE_NAME}_${apiValue}.gtest.xml --images_basedir ../BaikalNext/RprTest --report_path ../${asicName}-${osName}-${apiValue}-Failures")
                }

                if (!options.storeOnNAS) {
                    makeStash(includes: "${asicName}-${osName}-${apiValue}-Failures/**/*", name: "testResult-${asicName}-${osName}-${apiValue}", allowEmpty: true)
                }

                utils.publishReport(this, "${env.BUILD_URL}", "${asicName}-${osName}-${apiValue}-Failures", "report.html", "${STAGE_NAME}_${apiValue}_Failures", "${STAGE_NAME}_${apiValue}_Failures", options.storeOnNAS, ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName])

                options["failedConfigurations"].add("testResult-" + asicName + "-" + osName + "-" + apiValue)
            } catch (err) {
                println("[ERROR] Failed to publish HTML report.")
                println(err.getMessage())
            }
        }
    } finally {
        String title = "${asicName}-${osName}-${apiValue}"
        String description = errorMessage ? "Error: ${errorMessage}" : "Testing finished"
        String status = errorMessage ? "action_required" : "success"
        String url = errorMessage ? "${env.BUILD_URL}/${STAGE_NAME}_${apiValue}_Failures" : "${env.BUILD_URL}/artifact/${STAGE_NAME}_${apiValue}.log"
        GithubNotificator.updateStatus(options.customStageName, title, status, options, description, url)

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


def setTdrDelay(String asicName, Boolean turnOn) {
    if (turnOn) {
        if (asicName == "AMD_680M") {
            powershell """
                reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /t REG_DWORD /f /v "TdrDelay" /d "600"
                reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /t REG_DWORD /f /v "TdrDdiDelay" /d "600"
            """
        } else {
            powershell """
                reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /t REG_DWORD /f /v "TdrDelay" /d "120"
                reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /t REG_DWORD /f /v "TdrDdiDelay" /d "120"
            """
        }
    } else {
        powershell """
            reg delete "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /v "TdrDelay" /f
            reg delete "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers" /v "TdrDdiDelay" /f
        """
    }
}


def executeTests(String osName, String asicName, Map options) {
    GithubNotificator.updateStatus(options.customStageName, "${asicName}-${osName}-${options.apiValue}", "in_progress", options, "In progress...")

    if (osName == "Windows") {
        if (env.BRANCH_NAME == "PR-1214") {
            setTdrDelay(asicName, true)
        }

        changeWinDevMode(true)
    }

    Boolean someStageFail = false 

    try {
        executeTestsWithApi(osName, asicName, options)
    } catch (e) {
        someStageFail = true
        println(e.toString())
        println(e.getMessage())
    }

    if (osName == "Windows") {
        if (env.BRANCH_NAME == "PR-1214") {
            setTdrDelay(asicName, false)
        }

        changeWinDevMode(false)
    }

    if (someStageFail) {
        // send error signal for mark stage as failed
        error "Error during tests execution"
    }
}


def executePreBuild(Map options) {
    options.testsList = options.apiValues

    // set pending status for all
    if (env.CHANGE_ID) {
        GithubNotificator githubNotificator = new GithubNotificator(this, options)
        githubNotificator.init(options)
        options["githubNotificator"] = githubNotificator

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
                        GithubNotificator.createStatus(options.customStageName, "${gpuName}-${osName}-${apiValue}", "queued", options, "Scheduled", "${env.JOB_URL}")
                    }
                }
            }
        }
    }

    if (options["updateRefs"]) {
        println("Make a backup for baselines")

        utils.removeDir(this, "Windows", "backup")

        String backupsFolder = "/volume1/Baselines/rpr_hybrid_backups/"

        dir("backup") {
            String archiveName = env.BRANCH_NAME ? "auto_${env.BRANCH_NAME}_${env.BUILD_NUMBER}.zip" : "manual_${env.BUILD_NUMBER}.zip"

            downloadFiles("/volume1/Baselines/rpr_hybrid_autotests/", ".", "-q")
            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${archiveName}\" .")
            uploadFiles(archiveName, backupsFolder)
        }

        println("Remove old backups")

        withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
            List fileNames = bat(returnStdout: true, script: '%CIS_TOOLS%\\' 
                + "listFiles.bat ${backupsFolder} " + '%REMOTE_HOST% %SSH_PORT% -t').split("\n") as List

            fileNames = fileNames.findAll {it.endsWith(".zip")}

            println("Found backups (${fileNames.size()}): ${fileNames}")
            
            if (fileNames.size() > 20) {
                for (int i = 20; i < fileNames.size(); i++) {
                    println("Remove old backup: ${fileNames[i]}")
                    bat(script: '%CIS_TOOLS%\\removeStashes.bat ' + '%REMOTE_HOST%' + " ${backupsFolder}${fileNames[i]} " + '%SSH_PORT%')
                }
            }
        }
    }

    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/><br/>"
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
                utils.publishReport(this, "${env.BUILD_URL}", "SummaryReport", "${reportFiles.replaceAll('^,', '')}",
                    "HTML Failures Unit", reportFiles.replaceAll('^,', '').replaceAll("\\.\\./", ""), options.storeOnNAS,
                    ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
            }
        } catch(e) {
            println(e.toString())
        }
    }
}


String publishUpdateStatus(Map options) {
    currentBuild.description += "<br/><br/>"

    options["passedConfigurations"] = options["passedConfigurations"].sort()
    options["failedUpdatedConfigurations"] = options["failedUpdatedConfigurations"].sort()

    List commentContent = ["Baselines updating finished in the following build: ${env.BUILD_URL}\n"]

    if (options["passedConfigurations"].size() > 0) {
        String message = options["updateRefs"] ? "References were updated for:" : "Finished tests:"

        currentBuild.description += "<span style='color: #5fbc34'>${message}<br/><ul>"
        commentContent.add(message)

        options["passedConfigurations"].each() {
            currentBuild.description += "<li>${it}</li>"
            commentContent.add("- ${it}")
        }

        currentBuild.description += "</ul></span>"
    }

    if (options["passedConfigurations"].size() > 0 && options["failedUpdatedConfigurations"].size() > 0) {
        currentBuild.description += "<br/>"
        commentContent.add("")
    }

    if (options["failedUpdatedConfigurations"].size() > 0) {
        String message = options["updateRefs"] ?  "References weren't updated due to non-zero exit code returned by RprTest tool for:" : "Failed tests due to non-zero exit code returned by RprTest tool:"
        currentBuild.description += "<span style='color: #b03a2e'>${message}<br/><ul>"
        commentContent.add(message)

        options["failedUpdatedConfigurations"].each() {
            if (options["segmentationFaultConfigurations"].contains(it)) {
                currentBuild.description += "<li>${it} (segmentation fault detected)</li>"
                commentContent.add("- ${it} (segmentation fault detected)")
            } else {
                currentBuild.description += "<li>${it}</li>"
                commentContent.add("- ${it}")
            }
        }

        currentBuild.description += "</ul></span>"
    }

    if (env.CHANGE_URL && options["updateRefs"]) {
        GithubNotificator.sendPullRequestComment(commentContent.join("\n"), options)
    }
}


def call(String commitSHA = "",
         String commitMessage = "",
         String originalBuildLink = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,NVIDIA_RTX4080,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String apiValues = "vulkan,d3d12",
         String gtestFilter = "*",
         Boolean updateRefs = false) {

    currentBuild.description = ""

    if (env.CHANGE_URL && env.CHANGE_TARGET == "master") {
        while (jenkins.model.Jenkins.instance.getItem(env.JOB_NAME.split("/")[0]).getItem("master").lastBuild.result == null) {
            println("[INFO] Make a delay because there is a running build in master branch")
            sleep(300)
        }
    } else if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        def buildNumber = env.BUILD_NUMBER as int
        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }
        milestone(buildNumber) 
    }

    List apiList = apiValues.split(",") as List

    println "[INFO] Testing APIs: ${apiList}"

    Map options = [:]

    options << [configuration: PIPELINE_CONFIGURATION,
                platforms:platforms,
                commitSHA:commitSHA,
                commitMessage:commitMessage,
                originalBuildLink:originalBuildLink,
                updateRefs:updateRefs,
                PRJ_NAME:"HybridProUnit",
                PRJ_ROOT:"rpr-core",
                projectRepo:hybrid.PROJECT_REPO,
                tests:"",
                apiValues: apiList,
                gtestFilter: gtestFilter,
                executeBuild:false,
                executeTests:true,
                storeOnNAS: true,
                finishedBuildStages: new ConcurrentHashMap(),
                splitTestsExecution: false,
                retriesForTestStage:2,
                TEST_TIMEOUT:45,
                skipCallback: this.&filter,
                customStageName: "Test-Unit",
                passedConfigurations: [],
                failedUpdatedConfigurations: [],
                segmentationFaultConfigurations: []]

    multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, this.&executeDeploy, options)

    publishUpdateStatus(options)

    if (options["segmentationFaultConfigurations"]) {
        currentBuild.result = "FAILURE"
    }
}