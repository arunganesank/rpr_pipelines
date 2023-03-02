import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "Ubuntu20": "tar.xz"],
    artifactNameBase: "BaikalNext_Build",
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "HybridPro": "HybridPro"
        ]
    ]
)


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    dir('scripts') {
        switch(osName) {
            case 'Windows':
                bat """
                    make_results_baseline.bat ${delete}
                """
                break
            case 'OSX':
                sh """
                    ./make_results_baseline.sh ${delete}
                """
                break
            default:
                sh """
                    ./make_results_baseline.sh ${delete}
                """
        }
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    switch(osName) {
        case 'Windows':
            dir('scripts') {
                bat """
                    run.bat ${options.testsPackage} \"${options.tests}\" 0 0 0 ${options.updateRefs} HybridPro >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
            }
            break
        case 'OSX':
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ./run.sh ${options.testsPackage} \"${options.tests}\" 0 0 0 ${options.updateRefs} HybridPro >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
            }
            break
        default:
            dir('scripts') {
                withEnv(["LD_LIBRARY_PATH=../rprSdk:\$LD_LIBRARY_PATH"]) {
                    sh """
                        ./run.sh ${options.testsPackage} \"${options.tests}\" 0 0 0 ${options.updateRefs} HybridPro >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                }
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
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PACKAGE) {
            dir("rprSdk") {
                downloadFiles(isUnix() ? options.rprsdkUbuntu : options.rprsdkWindows, "rprsdk.zip")
                utils.unzip(this, "rprsdk.zip")
            }

            String binaryName = hybrid.getArtifactName(osName)
            String binaryPath = "/volume1/web/${options.originalBuildLink.split('/job/', 2)[1].replace('/job/', '/')}Artifacts/${binaryName}"
            downloadFiles(binaryPath, ".")

            switch(osName) {
                case "Windows":
                    bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " ${binaryName} -aoa")
                    utils.removeFile(this, osName, "HybridPro.dll")
                    utils.moveFiles(this, osName, "BaikalNext/bin/HybridPro.dll", "rprSdk/HybridPro.dll")
                    break
                default:
                    sh "tar -xJf ${binaryName}"
                    utils.removeFile(this, osName, "HybridPro.so")
                    utils.moveFiles(this, osName, "BaikalNext/bin/HybridPro.so", "rprSdk/HybridPro.so")
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_core_autotests_assets" : "/mnt/c/TestResources/rpr_core_autotests_assets"
            downloadFiles("/volume1/web/Assets/rpr_core_autotests/", assetsDir)
        }

        String enginePostfix = options.engine
        String REF_PATH_PROFILE="/volume1/Baselines/rpr_core_autotests/${asicName}-${osName}"

        REF_PATH_PROFILE = enginePostfix ? "${REF_PATH_PROFILE}-${enginePostfix}" : REF_PATH_PROFILE
        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, "", options.currentTry)

        if (options["updateRefs"].contains("Update")) {
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
                executeGenTestRefCommand(osName, options, options["updateRefs"].contains("clean"))
                uploadFiles("./Work/GeneratedBaselines/", REF_PATH_PROFILE)
                // delete generated baselines when they're sent 
                switch(osName) {
                    case "Windows":
                        bat "if exist Work\\GeneratedBaselines rmdir /Q /S Work\\GeneratedBaselines"
                        break
                    default:
                        sh "rm -rf ./Work/GeneratedBaselines"        
                }
            }
        } else {
            withNotifications(title: options["stageName"], printMessage: true, options: options, configuration: NotificationConfiguration.COPY_BASELINES) {
                String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_core_autotests_baselines" : "/mnt/c/TestResources/rpr_core_autotests_baselines"
                baseline_dir = enginePostfix ? "${baseline_dir}-${enginePostfix}" : baseline_dir
                println "[INFO] Downloading reference images for ${options.tests}-${options.engine}"

                options.tests.split(" ").each() {
                    downloadFiles("${REF_PATH_PROFILE}/${it}", baseline_dir)
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)
    } catch (e) {
        String additionalDescription = ""
        if (options.currentTry + 1 < options.nodeReallocateTries) {
            stashResults = false
        } else {
            options.successfulTests = false
        }

        println(e.toString())
        println(e.getMessage())
        
        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}\n${additionalDescription}", e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED} ${additionalDescription}", "${BUILD_URL}")
            throw new ExpectedExceptionWrapper("${NotificationConfiguration.REASON_IS_NOT_IDENTIFIED}\n${additionalDescription}", e)
        }
    } finally {
        try {
            dir("${options.stageName}") {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Core/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Core/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                        }

                        println "Stashing test results to : ${options.testResultsName}"
                        
                        utils.stashTestData(this, options, options.storeOnNAS, "**/cache/**")
                        // reallocate node if there are still attempts
                        // if test group is fully errored or number of test cases is equal to zero
                        if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                            // check that group isn't fully skipped
                            if (sessionReport.summary.total != sessionReport.summary.skipped || sessionReport.summary.total == 0){
                                removeInstaller(osName: osName, options: options, extension: "zip")
                                String errorMessage
                                if (options.currentTry < options.nodeReallocateTries) {
                                    errorMessage = "All tests were marked as error. The test group will be restarted."
                                } else {
                                    errorMessage = "All tests were marked as error."
                                }
                                throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                            }
                        }

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.engine)
                        }
                    }
                }
            }
        } catch (e) {
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
}


def getReportBuildArgs(String engineName, Map options) {
    String buildNumber = options.collectTrackedMetrics ? env.BUILD_NUMBER : ""

    if (options["isPreBuilt"]) {
        return """Core "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(engineName)}\" \"${buildNumber}\""""
    } else {
        return """Core ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\" \"${buildNumber}\""""
    }
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def executePreBuild(Map options) {
    // get links to the latest built HybridPro
    String url = "${env.JENKINS_URL}/job/RPR-SDK-Auto/job/master/api/json?tree=lastSuccessfulBuild[number,url],lastUnstableBuild[number,url]"

    def rawInfo = httpRequest(
        url: url,
        authentication: "jenkinsCredentials",
        httpMode: "GET"
    )

    def parsedInfo = parseResponse(rawInfo.content)


    Integer rprsdkBuildNumber
    String rprsdkBuildUrl

    if (parsedInfo.lastSuccessfulBuild.number > parsedInfo.lastUnstableBuild.number) {
        rprsdkBuildNumber = parsedInfo.lastSuccessfulBuild.number
        rprsdkBuildUrl = parsedInfo.lastSuccessfulBuild.url
    } else {
        rprsdkBuildNumber = parsedInfo.lastUnstableBuild.number
        rprsdkBuildUrl = parsedInfo.lastUnstableBuild.url
    }

    withCredentials([string(credentialsId: "nasURLFrontend", variable: "REMOTE_HOST")]) {
        options.rprsdkWindows = "/volume1/web/RPR-SDK-Auto/master/${rprsdkBuildNumber}/Artifacts/binCoreWin64.zip"
        options.rprsdkUbuntu = "/volume1/web/RPR-SDK-Auto/master/${rprsdkBuildNumber}/Artifacts/binCoreUbuntu20.zip"
    }

    dir('jobs_test_core') {
        checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
        dir ('jobs_launcher') {
            options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        }
        options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println("[INFO] Test branch hash: ${options['testsBranch']}")

        if (options.testsPackage != "none") {
            def tests = []
            // json means custom test suite. Split doesn't supported
            def tempTests = readJSON file: "jobs/${options.testsPackage}"
            tempTests["groups"].each() {
                // TODO: fix: duck tape - error with line ending
                tests << it.key
            }

            options.testsPackage = "none"
            options.tests = tests.join(" ")          
        }

        options.testsList = ["HybridPro"]
    }

    // make lists of raw profiles and lists of beautified profiles (displaying profiles)
    multiplatform_pipeline.initProfiles(options)

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        options.reportUpdater = new ReportUpdater(this, env, options)
        options.reportUpdater.init(this.&getReportBuildArgs)
    }

    if (env.BRANCH_NAME && options.githubNotificator) {
        options.githubNotificator.initChecks(options, "${BUILD_URL}")
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
                    // Statuses for tests
                    GithubNotificator.createStatus("Test", "${gpuName}-${osName}", "queued", options, "Scheduled", "${env.JOB_URL}")
                }
            }
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    cleanWS()
    try {
        if (options['executeTests'] && testResultList) {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(engine)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List
                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), engine)) {
                            return
                        }

                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                echo "[ERROR] Failed to unstash ${testName}"
                                lostStashes.add("'${testName}'".replace("testResult-", ""))
                                println(e.toString())
                                println(e.getMessage())
                            }
                        }
                    }
                }
            }

            try {
                dir("core_tests_configuration") {
                    downloadFiles("/volume1/web/Assets/rpr_core_autotests/", ".", "--include='*.json' --include='*/' --exclude='*'")
                }
            } catch (e) {
                println("[ERROR] Can't download json files with core tests configuration")
            }

            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"false\" \"${options.testsPackage}\" \"${options.tests.toString()}\" \"HybridPro\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            JSON jsonResponse = JSONSerializer.toJSON(retryInfo, new JsonConfig())
                            writeJSON file: 'retry_info.json', json: jsonResponse, pretty: 4
                        }
                    
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs("HybridPro", options)}"

                        bat "get_status.bat ..\\summaryTestResults"
                    }
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println("[ERROR] Failed to build test report.")
                    println(e.toString())
                    println(e.getMessage())

                    if (!options.testDataSaved && !options.storeOnNAS) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                                "Test Report FT", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                            options.testDataSaved = true 
                        } catch(e1) {
                            println("[WARNING] Failed to publish test data.")
                            println(e.toString())
                            println(e.getMessage())
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

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report FT", "Summary Report, Performance Report, Compare Report", options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                // set error statuses for PR, except if current build has been superseded by new execution
                if (env.CHANGE_ID && !currentBuild.nextBuild) {
                    // if jobs was aborted or crushed remove pending status for unfinished stages
                    GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")
                    String status = currentBuild.result ?: "success"
                    status = status.toLowerCase()
                    String commentMessage = ""
                    if (!options.successfulTests || summaryTestResults.error > 0) {
                        commentMessage = "\\n Functional tests failures - ${env.BUILD_URL}/HTML_20Failures_20FT/"
                    }
                    String commitUrl = "${options.githubNotificator.repositoryUrl}/commit/${options.githubNotificator.commitSHA}"
                    GithubNotificator.sendPullRequestComment("[FUNCTIONAL TESTS] Jenkins build for ${commitUrl} finished as ${status} ${commentMessage}", options)
                }
            }
        }
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    }
}


def call(String commitSHA = "",
         String originalBuildLink = "",
         String testsBranch = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String updateRefs = "No") {

    currentBuild.description = ""

    multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, this.&executeDeploy,
                           [configuration: PIPELINE_CONFIGURATION,
                            platforms:platforms,
                            commitSHA:commitSHA,
                            originalBuildLink:originalBuildLink,
                            testsBranch:testsBranch,
                            testRepo:hybrid.FT_REPO,
                            updateRefs:updateRefs,
                            PRJ_NAME:"HybridProFT",
                            PRJ_ROOT:"rpr-core",
                            projectRepo:hybrid.PROJECT_REPO,
                            testsPackage:"Full.json",
                            tests:"",
                            engines:["HybridPro"],
                            executeBuild:false,
                            executeTests:true,
                            storeOnNAS: true,
                            flexibleUpdates: true,
                            finishedBuildStages: new ConcurrentHashMap(),
                            successfulTests: true,
                            retriesForTestStage:2])
}