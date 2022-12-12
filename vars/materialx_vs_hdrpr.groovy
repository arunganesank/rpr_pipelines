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
        // TODO
    }
}


def executeTestCommandHdRPR(String osName, String asicName, Map options, String engine) {
    withEnv(["PATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib;c:\\JN\\WS\\HdRPR_Build\\USD\\build\\bin;${PATH}", "PYTHONPATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib\\python"]) {
        dir("scripts") {
            def testTimeout = options.timeouts["${options.tests}"]

            println "[INFO] Set timeout to ${testTimeout}"

            timeout(time: testTimeout, unit: 'MINUTES') { 
                switch(osName) {
                    case "Windows":
                        bat """
                            run.bat ${options.testsPackage} \"${options.tests}\" ${engine} ${options.testCaseRetries} 'No' >> \"../${STAGE_NAME}_${engine}_${options.currentTry}.log\" 2>&1
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
        def testTimeout = options.timeouts["${options.tests}"]

        println "[INFO] Set timeout to ${testTimeout}"

        timeout(time: testTimeout, unit: 'MINUTES') { 
            switch(osName) {
                case "Windows":
                    bat """
                        run.bat ${options.testsPackage} \"${options.tests}\" ${options.testCaseRetries} >> \"../${STAGE_NAME}_MaterialX_${options.currentTry}.log\" 2>&1
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

        try {
            withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                timeout(time: "5", unit: "MINUTES") {
                    cleanWS(osName)
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
                    unpackUSD(osName, options)
                }
            }

            outputEnvironmentInfo(osName, options.stageName, options.currentTry)

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

                executeTestCommandMaterialX(osName, asicName, options)
                utils.moveFiles(this, osName, "Work", "Work-MaterialX")
                dir("scripts") {
                    utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_MaterialX_engine_${options.currentTry}.log")
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
                dir(options.stageName) {
                    utils.moveFiles(this, osName, "../*.log", ".")
                    utils.moveFiles(this, osName, "../scripts/*.log", ".")
                }

                archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

                if (stashResults) {
                    dir("Work-Northstar/Results/Blender") {
                        makeStash(includes: "**/*", name: "${options.testResultsName}-Northstar", storeOnNAS: options.storeOnNAS)
                    }

                    dir("Work-HybridPro/Results/Blender") {
                        makeStash(includes: "**/*", name: "${options.testResultsName}-HybridPro", storeOnNAS: options.storeOnNAS)
                    }

                    dir("Work-MaterialX/Results/Blender") {
                        makeStash(includes: "**/*", name: "${options.testResultsName}-MaterialX", storeOnNAS: options.storeOnNAS)
                    }
                } else {
                    println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
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


def getReportBuildArgs(String engineName, Map options) {
    return """${utils.escapeCharsByUnicode("MaterialX vs HdRPR")} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\""""
}


def executeDeploy(Map options, List platformList, List testResultList, String engine) {
    // TODO
}


def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String hdrprTestsBranch = "master",
        String usdBranch = "release",
        String platforms = 'Windows:AMD_RX6800XT',
        Boolean rebuildUSD = false,
        String testsPackage = "Smoke.json",
        String tests = "",
        Boolean splitTestsExecution = true,
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
                        testRepo:"git@github.com:luxteam/jobs_test_hdrpr.git",
                        testsBranch: hdrprTestsBranch,
                        hdrprTestRepo:"git@github.com:luxteam/jobs_test_hdrpr.git",
                        hdrprTestsBranch: hdrprTestsBranch,
                        PRJ_NAME: "HdRPR",
                        PRJ_ROOT:"rpr-plugins",
                        testsPackage: testsPackage,
                        tests: tests.replace(',', ' '),
                        reportName: 'Test_20Report',
                        splitTestsExecution: splitTestsExecution,
                        BUILD_TIMEOUT: 45,
                        TEST_TIMEOUT: 180,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:180,
                        rebuildUSD: rebuildUSD,
                        saveUSD: false,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        testCaseRetries: testCaseRetries
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
