import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.ConcurrentHashMap


@Field final String PROJECT_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderUSD.git"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows"],
    productExtensions: ["Windows": "zip"],
    artifactNameBase: "hdRpr-",
    displayingProfilesMapping: [
        "engine": [
            "HybridPro": "HybridPro",
            "Northstar": "Northstar"
        ]
    ]
)


def unpackUSD(String osName, Map options) {
    dir("USD/build") {
        getProduct(osName, options, ".")
    }
}


def unpackMaterialX(String osName, Map options) {
    dir("materialx") {
        switch(osName) {
            case "Windows":
                bat """
                    curl --insecure --retry 5 -L -o MaterialX.zip ${options.materialXWindows}
                """
                break

            default:
                println("[WARNING] ${osName} is not supported")   
        }

        unzip dir: ".", glob: "", zipFile: "MaterialX.zip"
    }
}


def executeTestCommandHdRPR(String osName, String asicName, Map options, String engine) {
    withEnv(["PATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib;c:\\JN\\WS\\HdRPR_Build\\USD\\build\\bin;${PATH}", "PYTHONPATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib\\python"]) {
        dir("scripts") {
            timeout(time: '50', unit: 'MINUTES') { 
                switch(osName) {
                    case "Windows":
                        bat """
                            set TOOL_VERSION=${options.toolVersion}
                            run_comparison.bat ${options.testsPackage} \"${options.tests}\" ${engine} ${options.testCaseRetries} "Update" "..\\..\\USD\\build\\bin\\usdview" >> \"../${STAGE_NAME}_${engine}_${options.currentTry}.log\" 2>&1
                        """
                        break

                    default:
                        println("[WARNING] ${osName} is not supported")   
                }
            }
        }
    }
}


def executeTestCommandMaterialX(String osName, String asicName, Map options) {
    dir("scripts") {
        timeout(time: '50', unit: 'MINUTES') { 
            switch(osName) {
                case "Windows":
                    bat """
                        run_comparison.bat ${options.testsPackage} \"${options.tests}\" ${options.testCaseRetries} >> \"../${STAGE_NAME}_MaterialX_${options.currentTry}.log\" 2>&1
                    """
                    break

                default:
                    println("[WARNING] ${osName} is not supported")   
            }
        }
    }
}


def doSanityCheck(String osName, Map options) {
    withEnv(["PATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib;c:\\JN\\WS\\HdRPR_Build\\USD\\build\\bin;${PATH}", "PYTHONPATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib\\python"]) {
        dir("scripts") {
            switch(osName) {
                case "Windows":
                    bat """
                        do_sanity_check.bat ${options.engine} "..\\..\\USD\\build\\bin\\usdview" >> \"..\\${options.stageName}_${options.currentTry}.sanity_check_wrapper.log\"  2>&1
                    """
                    break
                default:
                    println("[WARNING] ${osName} is not supported")    
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // Built USD is tied with paths. Always work with USD from the same directory
    dir("${WORKSPACE}/../HdRPR_Build") {
        // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
        Boolean stashResults = true

        cleanWS(osName)

        try {
            dir("hdrpr") {
                withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                    timeout(time: "5", unit: "MINUTES") {
                        checkoutScm(branchName: options.hdrprTestsBranch, repositoryUrl: options.hdrprTestRepo)
                        println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
                    }
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
                    String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/hdrpr_autotests_assets" : "/mnt/c/TestResources/hdrpr_autotests_assets"
                    downloadFiles("/volume1/web/Assets/hdrpr_autotests/", assets_dir)
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                    timeout(time: "10", unit: "MINUTES") {
                        dir ("..") {
                            unpackUSD(osName, options)
                        }
                    }
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.SANITY_CHECK) {
                    timeout(time: "5", unit: "MINUTES") {
                        options.engine = "Northstar"
                        doSanityCheck(osName, options)
                        if (!fileExists("./scripts/sanity.jpg")) {
                            println "[ERROR] Sanity check failed on ${env.NODE_NAME}. No output image found."
                            throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE_SANITY_CHECK, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE_SANITY_CHECK))
                        }
                    }
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                    executeTestCommandHdRPR(osName, asicName, options, "Northstar")
                    utils.moveFiles(this, osName, "Work", "Work-Northstar")
                    dir("scripts") {
                        utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_Northstar_engine_${options.currentTry}.log")
                    }

                    executeTestCommandHdRPR(osName, asicName, options, "HybridPro")
                    utils.moveFiles(this, osName, "Work", "Work-HybridPro")
                    dir("scripts") {
                        utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_HybridPro_engine_${options.currentTry}.log")
                    }
                }
            }

            dir("materialx") {
                withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                    timeout(time: "5", unit: "MINUTES") {
                        checkoutScm(branchName: options.materialxTestsBranch, repositoryUrl: options.materialxTestRepo)
                        println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
                    }
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
                    String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/materialx_autotests_assets" : "/mnt/c/TestResources/materialx_autotests_assets"
                    downloadFiles("/volume1/web/Assets/materialx_autotests/", assets_dir)
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                    timeout(time: "10", unit: "MINUTES") {
                        unpackMaterialX(osName, options)
                    }
                }

                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                    executeTestCommandMaterialX(osName, asicName, options)
                    utils.moveFiles(this, osName, "Work", "Work-MaterialX")
                    dir("scripts") {
                        utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_MaterialX_engine_${options.currentTry}.log")
                    }
                }
            }

            options.executeTestsFinished = true

            utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)
        } catch (e) {
            if (options.currentTry < options.nodeReallocateTries) {
                stashResults = false
            }
            println e.toString()
            if (e instanceof ExpectedExceptionWrapper) {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                throw new ExpectedExceptionWrapper(e.getMessage(), e.getCause())
            } else {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, "${BUILD_URL}")
                throw new ExpectedExceptionWrapper(NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, e)
            }
        } finally {
            try {
                dir("hdrpr") {
                    dir(options.stageName) {
                        utils.moveFiles(this, osName, "../*.log", ".")
                        utils.moveFiles(this, osName, "../scripts/*.log", ".")
                    }

                    archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

                    if (stashResults) {
                        dir("Work") {
                            if (fileExists("Results/HdRPR/session_report.json")) {
                                def sessionReport = readJSON file: 'Results/HdRPR/session_report.json'
                                if (sessionReport.summary.error > 0) {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                                } else if (sessionReport.summary.failed > 0) {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                                } else {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                                }

                                println "Total: ${sessionReport.summary.total}"
                                println "Error: ${sessionReport.summary.error}"
                                println "Skipped: ${sessionReport.summary.skipped}"
                                if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                                    if (sessionReport.summary.total != sessionReport.summary.skipped){
                                        String errorMessage = (options.currentTry < options.nodeReallocateTries) ?
                                                "All tests were marked as error. The test group will be restarted." :
                                                "All tests were marked as error."
                                        throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                                    }
                                }
                            }
                        }

                        dir("Work-Northstar/Results/HdRPR") {
                            utils.stashTestData(this, options, options.storeOnNAS, "", "Northstar")
                        }

                        dir("Work-HybridPro/Results/HdRPR") {
                            utils.stashTestData(this, options, options.storeOnNAS, "", "HybridPro")
                        }
                    } else {
                        println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
                    }
                }

                dir("materialx") {
                    dir(options.stageName) {
                        utils.moveFiles(this, osName, "../*.log", ".")
                        utils.moveFiles(this, osName, "../scripts/*.log", ".")
                    }

                    archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

                    if (stashResults) {
                        dir("Work") {
                            if (fileExists("Results/MaterialX/session_report.json")) {
                                def sessionReport = readJSON file: 'Results/MaterialX/session_report.json'
                                if (sessionReport.summary.error > 0) {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                                } else if (sessionReport.summary.failed > 0) {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                                } else {
                                    GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                                }

                                println "Total: ${sessionReport.summary.total}"
                                println "Error: ${sessionReport.summary.error}"
                                println "Skipped: ${sessionReport.summary.skipped}"
                                if (sessionReport.summary.total == sessionReport.summary.error + sessionReport.summary.skipped || sessionReport.summary.total == 0) {
                                    if (sessionReport.summary.total != sessionReport.summary.skipped){
                                        String errorMessage = (options.currentTry < options.nodeReallocateTries) ?
                                                "All tests were marked as error. The test group will be restarted." :
                                                "All tests were marked as error."
                                        throw new ExpectedExceptionWrapper(errorMessage, new Exception(errorMessage))
                                    }
                                }
                            }
                        }

                        dir("Work-MaterialX/Results/MaterialX") {
                            utils.stashTestData(this, options, options.storeOnNAS, "", "MaterialX")
                        }
                    } else {
                        println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
                    }
                }

                if (options.reportUpdater) {
                    options.reportUpdater.updateReport()
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
}


def getReportBuildArgs(Map options) {
    return """${utils.escapeCharsByUnicode("HdRPR")} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\""""
}


def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.materialxTestsBranch, repositoryUrl: options.materialxTestRepo)
            }

            dir("summaryTestResults") {
                testResultList.each {
                    String dirName = it.replace("testResult-", "")

                    dir("Northstar/${dirName}") {
                        try {
                            makeUnstash(name: "${it}-Northstar", storeOnNAS: options.storeOnNAS)
                        } catch (e) {
                            println("Can't unstash ${it}-Northstar")
                            println(e.toString())
                        }
                    }

                    dir("HybridPro/${dirName}") {
                        try {
                            makeUnstash(name: "${it}-HybridPro", storeOnNAS: options.storeOnNAS)
                        } catch (e) {
                            println("Can't unstash ${it}-HybridPro")
                            println(e.toString())
                        }
                    }

                    dir("MaterialX/${dirName}") {
                        try {
                            makeUnstash(name: "${it}-MaterialX", storeOnNAS: options.storeOnNAS)
                        } catch (e) {
                            println("Can't unstash ${it}-MaterialX")
                            println(e.toString())
                        }
                    }
                }
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                dir("jobs_launcher") {
                    withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}"]) {
                        bat """
                            build_comparison_reports.bat ..\\\\summaryTestResults ${options.buildArgsFunc(options)}
                        """
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
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html", "Test Report", "Summary Report", options.storeOnNAS,
                                ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])
                            options.testDataSaved = true
                        } catch(e1) {
                            println """
                                [WARNING] Failed to publish test data.
                                ${e1.toString()}
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

            Map summaryTestResults = ["passed": 0, "failed": 0, "error": 0]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/compared_configurations_info.json'

                summaryReport.each { configuration ->
                    summaryTestResults["passed"] += configuration.value["summary"]["passed"]
                    summaryTestResults["failed"] += configuration.value["summary"]["failed"]
                    summaryTestResults["error"] += configuration.value["summary"]["error"]
                }

                if (summaryTestResults["error"] > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                }
                else if (summaryTestResults["failed"] > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
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

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html", "Test Report", "Summary Report", options.storeOnNAS,
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])
                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        throw e
    }
}


def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String hdrprTestsBranch = "master",
        String materialxBranch = "master",
        String usdBranch = "release",
        String platforms = 'Windows:AMD_RX6800XT',
        String materialXWindows = "",
        Boolean rebuildUSD = false,
        String testsPackage = "Smoke.json",
        String tests = "",
        String parallelExecutionTypeString = "TakeAllNodes",
        Integer testCaseRetries = 3
    ) {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo: projectRepo,
                        projectBranch: projectBranch,
                        usdBranch: usdBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_materialx.git",
                        testsBranch: hdrprTestsBranch,
                        hdrprTestRepo:"git@github.com:luxteam/jobs_test_hdrpr.git",
                        hdrprTestsBranch: hdrprTestsBranch,
                        materialxTestRepo:"git@github.com:luxteam/jobs_test_materialx.git",
                        materialxTestsBranch: materialxBranch,
                        PRJ_NAME: "HdRPR",
                        PRJ_ROOT:"rpr-plugins",
                        testsPackage: testsPackage,
                        tests: tests.replace(',', ' '),
                        reportName: 'Test_20Report',
                        splitTestsExecution: true,
                        BUILD_TIMEOUT: 45,
                        TEST_TIMEOUT: 180,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:180,
                        DEPLOY_TIMEOUT:120,
                        rebuildUSD: rebuildUSD,
                        saveUSD: false,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        testCaseRetries: testCaseRetries,
                        materialXWindows: materialXWindows,
                        reportType: ReportType.COMPARISON,
                        buildArgsFunc: this.&getReportBuildArgs
                        ]
        }
        multiplatform_pipeline(platforms, hdrpr.&executePreBuild, hdrpr.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
