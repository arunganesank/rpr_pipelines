import groovy.json.JsonOutput
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.ConcurrentHashMap


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    withEnv([
            "BASELINES_UPDATE_INITIATOR=${baseline_updater_pipeline.getBaselinesUpdateInitiator()}",
            "BASELINES_ORIGINAL_BUILD=${baseline_updater_pipeline.getBaselinesOriginalBuild(env.JOB_NAME, env.BUILD_NUMBER)}",
            "BASELINES_UPDATING_BUILD=${baseline_updater_pipeline.getBaselinesUpdatingBuild()}"
    ]) {
        dir("scripts") {
            switch (osName) {
                case "Windows":
                    bat """
                        make_results_baseline.bat ${delete} HybMTLX
                    """
                    break

                default:
                    println "Unix isn't supported"
            }
        }
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    String testsNames
    String testsPackageName

    if (options.tests.contains(".json")) {
        testsPackageName = options.tests
        testsNames = ""
    } else {
        testsPackageName = "none"
        testsNames = options.tests
    }

    timeout(time: options.TEST_TIMEOUT, unit: "MINUTES") {
        switch (osName) {
            case "Windows":
                dir("scripts") {
                    bat """
                        run.bat \"${testsPackageName}\" \"${testsNames}\" 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                    """
                }
                break

            default:
                println "Unix isn't supported"
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        withNotifications(stage: options.customStageName, title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "10", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(stage: options.customStageName, title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
            timeout(time: "40", unit: "MINUTES") {
                downloadFiles("/volume1/CIS/MaterialX/renderTool/", "tool")

                String binaryName = hybrid.getArtifactName(osName)
                String binaryPath = "/volume1/web/${options.originalBuildLink.split('/job/', 2)[1].replace('/job/', '/')}Artifacts/${binaryName}"
                downloadFiles(binaryPath, ".")

                bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " ${binaryName} -aoa")
                utils.removeFile(this, osName, "tool/HybridPro.dll")
                utils.moveFiles(this, osName, "BaikalNext/bin/HybridPro.dll", "tool/HybridPro.dll")
            }
        }

        withNotifications(stage: options.customStageName, title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/hybrid_mtlx_autotests_assets" : "/mnt/c/TestResources/hybrid_mtlx_autotests_assets"
            downloadFiles("/volume1/web/Assets/materials/", assetsDir)
        }
    
        String REF_PATH_PROFILE="/volume1/Baselines/hybrid_mtlx_autotests/${asicName}-${osName}"
        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, "", options.currentTry)

        if (options["updateRefs"].contains("Update")) {
            withNotifications(stage: options.customStageName, title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
                executeGenTestRefCommand(osName, options, options["updateRefs"].contains("clean"))
                uploadFiles("./Work/GeneratedBaselines/", REF_PATH_PROFILE)
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
            withNotifications(stage: options.customStageName, title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/hybrid_mtlx_autotests_baselines" : "/mnt/c/TestResources/hybrid_mtlx_autotests_baselines"
                println "[INFO] Downloading reference images for ${options.tests}"
                options.tests.split(" ").each {
                    downloadFiles("${REF_PATH_PROFILE}/${it.contains(".json") ? "" : it}", baselineDir, "", true, "nasURL", "nasSSHPort", true)
                }
            }
            withNotifications(stage: options.customStageName, title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
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
            GithubNotificator.updateStatus(options.customStageName, options['stageName'], "failure", options, "${e.getMessage()}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}", e.getCause())
        } else {
            GithubNotificator.updateStatus(options.customStageName, options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}", "${BUILD_URL}")
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
                    if (fileExists("Results/HybMTLX/session_report.json")) {

                        def sessionReport = readJSON file: 'Results/HybMTLX/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus(options.customStageName, options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus(options.customStageName, options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus(options.customStageName, options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"
                        utils.stashTestData(this, options, options.storeOnNAS)

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport()
                        }

                        utils.analyzeResults(this, sessionReport, options)
                    }
                }
            }
        } catch(e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
                if (e instanceof ExpectedExceptionWrapper) {
                    GithubNotificator.updateStatus(options.customStageName, options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                    throw e
                } else {
                    GithubNotificator.updateStatus(options.customStageName, options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${BUILD_URL}")
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


def getReportBuildArgs(Map options) {
    return """HybridPro \"${options.commitSHA}\" ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"\" """
}


def executePreBuild(Map options) {
    if (!options.originalBuildLink) {
        // get links to the latest built HybridPro in manual/weekly job
        def rawInfo = httpRequest(
            url: "${env.JENKINS_URL}/job/HybridPro-Build-Auto/job/master/api/json?tree=lastCompletedBuild[number,url]",
            authentication: 'jenkinsCredentials',
            httpMode: 'GET'
        )

        def parsedInfo = parseResponse(rawInfo.content)

        options.originalBuildLink = parsedInfo.lastCompletedBuild.url
        rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${options.originalBuildLink}">[BUILD] Link to used HybridPro</a></h3>""")
    }

    def tests = []

    dir('jobs_test_hybrid_mtlx') {
        checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
        dir ('jobs_launcher') {
            options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        }
        options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "[INFO] Test branch hash: ${options['testsBranch']}"

        def packageInfo

        if (options.testsPackage != "none") {
            packageInfo = readJSON file: "jobs/${options.testsPackage}"
            options.isPackageSplitted = packageInfo["split"]
            // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
            if (env.BRANCH_NAME && options.isPackageSplitted && options.tests) {
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
            options.tests = utils.uniteSuites(this, "jobs/weights.json", tests)
            modifiedPackageName = modifiedPackageName.replace('~,', '~')

            if (options.isPackageSplitted) {
                options.testsPackage = "none"
            } else {
                options.testsPackage = modifiedPackageName
                // check that package is splitted to parts or not
                if (packageInfo["groups"] instanceof Map) {
                    tests << "${modifiedPackageName}"
                } else {
                    // add group stub for each part of package
                    for (int i = 0; i < packageInfo["groups"].size(); i++) {
                        tests << "${modifiedPackageName}".replace(".json", ".${i}.json")
                    }
                }
                
                options.tests = tests
            }
        } else {
            options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List)
        }
    }

    options.testsList = options.tests
    println("Tests: ${options.testsList}")

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        options.reportUpdater = new ReportUpdater(this, env, options)
        options.reportUpdater.init(this.&getReportBuildArgs)
    }

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
                    options.testsList.each() { testName ->
                        def parts = testName.split("\\.")
                        def formattedTestName = parts[0] + "." + parts[1]
                        // Statuses for tests
                        GithubNotificator.createStatus(options.customStageName, "${gpuName}-${osName}-${formattedTestName}", "queued", options, "Scheduled", "${env.JOB_URL}")
                    }
                }
            }
        }
    }

    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/><br/>"
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            List lostStashes = []
            dir("summaryTestResults") {
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

                String matLibUrl

                withCredentials([string(credentialsId: "matLibUrl", variable: "MATLIB_URL")]) {
                    matLibUrl = MATLIB_URL
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}", "MATLIB_URL=${matLibUrl}"]) {
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
                println """
                    [ERROR] during archiving launcher.engine.log
                    ${e.toString()}
                """
            }

            Map summaryTestResults = [:]
            try {
                def summaryReport

                dir("summaryTestResults") {
                    archiveArtifacts artifacts: "summary_status.json"
                    summaryReport = readJSON file: "summary_status.json"
                }

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

            withNotifications(stage: options.customStageName, title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report", "Summary Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])
            }
        }
    } catch (e) {
        println e.toString()
        throw e
    }
}


def call(String commitSHA = "",
         String projectBranchName = "",
         String commitMessage = "",
         String originalBuildLink = "",
         String testsBranch = "",
         String testsPackage = "",
         String tests = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,NVIDIA_RTX4080,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100",
         String updateRefs = "No") {

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

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    try {
        Map options = [:]

        options << [platforms:platforms,
                    commitSHA:commitSHA,
                    projectBranchName:projectBranchName,
                    commitMessage:commitMessage,
                    originalBuildLink:originalBuildLink,
                    testsBranch:testsBranch,
                    testRepo:hybrid.MTLX_REPO,
                    PRJ_NAME: 'HybridProMTLX',
                    PRJ_ROOT: 'rpr-core',
                    projectRepo:hybrid.PROJECT_REPO,
                    testsPackage:testsPackage,
                    tests:tests,
                    updateRefs:updateRefs,
                    executeBuild:false,
                    executeTests:true,
                    storeOnNAS: true,
                    flexibleUpdates: true,
                    finishedBuildStages: new ConcurrentHashMap(),
                    splitTestsExecution: true,
                    parallelExecutionType:TestsExecutionType.valueOf("TakeAllNodes"),
                    problemMessageManager:problemMessageManager,
                    nodeRetry: [],
                    customStageName: "Test-MTLX"]

        multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        if (currentBuild.description) {
            currentBuild.description += "<br/>"
        }
        String problemMessage = problemMessageManager.publishMessages()
    }
}
