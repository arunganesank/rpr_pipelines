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
    productExtensions: ["Windows": "tar.gz"],
    artifactNameBase: "hdRpr_",
    testProfile: "engine"
)


def executeBuildWindows(String osName, Map options) {
    withEnv(["PATH=c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
        clearBinariesWin()

        String builtUSDPath = "${WORKSPACE}\\USD\\build"

        if (options.rebuildUSD) {
            dir ("USD") {
                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvarsall.bat" amd64 >> ${STAGE_NAME}_USD.log 2>&1
                    waitfor 1 /t 10 2>NUL || type nul>nul
                    python --version >> ${STAGE_NAME}_USD.log 2>&1
                    python build_scripts\\build_usd.py ${builtUSDPath} --openimageio --materialx >> ${STAGE_NAME}_USD.log 2>&1
                """
            }
        }

        dir ("RadeonProRenderUSD") {
            GithubNotificator.updateStatus("Build", "${osName}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")

            dir("build") {
                
                bat """
                    python --version >> ..\\${STAGE_NAME}.log 2>&1
                    cmake -Dpxr_DIR=${builtUSDPath} -DCMAKE_INSTALL_PREFIX=${builtUSDPath} -DPYTHON_INCLUDE_DIR=C:\\Python37\\include -DPYTHON_EXECUTABLE=C:\\Python37\\python.exe -DPYTHON_LIBRARIES=C:\\Python37\\libs\\python37.lib .. >> ${STAGE_NAME}.log 2>&1
                    cmake --build . --config RelWithDebInfo --target install >> ${STAGE_NAME}.log 2>&1
                """
                
                bat "rename hdRpr* hdRpr-${osName}.tar.gz"

                String ARTIFACT_NAME = "hdRpr-${osName}.tar.gz"
                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

                bat "rename hdRpr* hdRpr_${osName}.tar.gz"
                makeStash(includes: "hdRpr_${osName}.tar.gz", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
                GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
            }
        }
    }
}


def executeBuild(String osName, Map options) {
    try {
        dir ("RadeonProRenderUSD") {
            withNotifications(title: "${osName}", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
            }
        }

        if (options.rebuildUSD) {
            withNotifications(title: "${osName}-${options.buildProfile}", options: options, configuration: NotificationConfiguration.DOWNLOAD_USD_REPO) {
                dir('USD') {
                    checkoutScm(branchName: options.usdBranch, repositoryUrl: "git@github.com:PixarAnimationStudios/USD.git")
                }
            }
        }

        utils.removeFile(this, osName, "*.log")

        outputEnvironmentInfo(osName)
        withNotifications(title: "${osName}", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(osName, options)
                    break
                case "OSX":
                    executeBuildOSX(osName, options)
                    break
                default:
                    executeBuildUnix(osName, options)
            }
        }

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA
    } catch (e) {
        def exception = e

        try {
            String buildLogContent = readFile("Build-${osName}.log")
            if (buildLogContent.contains("Segmentation fault")) {
                exception = new ExpectedExceptionWrapper(NotificationConfiguration.SEGMENTATION_FAULT, e)
                exception.retry = true

                utils.reboot(this, osName)
            }
        } catch (e1) {
            println("[WARNING] Could not analyze build log")
        }

        throw exception
    } finally {
        archiveArtifacts "*.log"
        if (options.rebuildUSD) {
            archiveArtifacts "USD/*.log"
        }
    }
}

def executePreBuild(Map options) {
    options['executeBuild'] = true
    options['executeTests'] = true
    // TODO: implement
}

def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String usdBranch = "release",
        String platforms = 'Windows:AMD_RX6800XT',
        Boolean rebuildUSD = false,
        String updateRefs = 'No',
        String testsPackage = "Smoke.json",
        String tests = "",
        String enginesNames = "Northstar",
        Boolean splitTestsExecution = true,
        String parallelExecutionTypeString = "TakeOneNodePerGPU") {

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
                        updateRefs: updateRefs,
                        PRJ_NAME: "HdRPR",
                        testsPackage: testsPackage,
                        tests: tests.replace(',', ' '),
                        reportName: 'Test_20Report',
                        splitTestsExecution: splitTestsExecution,
                        BUILD_TIMEOUT: 45,
                        TEST_TIMEOUT: 180,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:180,
                        rebuildUSD: rebuildUSD,
                        engines: enginesNamesList,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        ]
        }
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
