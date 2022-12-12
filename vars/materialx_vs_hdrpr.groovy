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
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "HybridPro": "HybridPro",
            "Northstar": "Northstar"
        ]
    ]
)


Boolean filter(Map options, String asicName, String osName, String testName, String engine) {
    return (engine == "HybridPro" && !(asicName.contains("RTX") || asicName.contains("AMD_RX6")))
}


def executeTests(String osName, String asicName, Map options) {
    // TODO
}


def getReportBuildArgs(String engineName, Map options) {
    return """${utils.escapeCharsByUnicode("MaterialX vs HdRPR")} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\""""
}


def executeDeploy(Map options, List platformList, List testResultList, String engine) {
    // TODO
}


def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String testsBranch = "master",
        String usdBranch = "release",
        String platforms = 'Windows:AMD_RX6800XT',
        Boolean rebuildUSD = false,
        String testsPackage = "Smoke.json",
        String tests = "",
        String enginesNames = "Northstar",
        Boolean splitTestsExecution = true,
        String parallelExecutionTypeString = "TakeAllNodes",
        Integer testCaseRetries = 3
    ) {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            def enginesNamesList = enginesNames.split(",") as List

            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo: projectRepo,
                        projectBranch: projectBranch,
                        usdBranch: usdBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_hdrpr.git",
                        testsBranch: testsBranch,
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
                        engines: enginesNamesList,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        skipCallback: this.&filter,
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
