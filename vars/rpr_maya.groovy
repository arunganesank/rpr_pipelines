import groovy.transform.Field
import groovy.json.JsonOutput;
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20for%20Maya"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "OSX"],
    productExtensions: ["Windows": "msi", "OSX": "dmg"],
    artifactNameBase: "RadeonProRender",
    testProfile: "engine",
    displayingProfilesMapping: [
        "engine": [
            "Northstar": "Northstar",
            "HybridPro": "HybridPro",
            "HIP": "HIP",
            "HIPvsNS": "HIPvsNS",
            "HybridLow": "HybridLow",
            "HybridMedium": "HybridMedium",
            "HybridHigh": "HybridHigh"
        ]
    ]
)


Boolean filter(Map options, String asicName, String osName, String testName, String engine) {
    if (engine.contains("HIP") && !(asicName.contains("AMD") && osName == "Windows")) {
        return true
    }

    if (engine == "Northstar" && asicName == "AMD_680M") {
        return true
    }

    if (engine == "HybridPro" && osName == "OSX") {
        return true
    }

    if (engine == "HybridPro" && asicName == "AMD_RX5700XT") {
        return true
    }

    return false
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    withEnv([
            "BASELINES_UPDATE_INITIATOR=${baseline_updater_pipeline.getBaselinesUpdateInitiator()}",
            "BASELINES_ORIGINAL_BUILD=${baseline_updater_pipeline.getBaselinesOriginalBuild(env.JOB_NAME, env.BUILD_NUMBER)}",
            "BASELINES_UPDATING_BUILD=${baseline_updater_pipeline.getBaselinesUpdatingBuild()}"
    ]) {
        dir('scripts') {
            switch(osName) {
                case 'Windows':
                    bat """
                        make_results_baseline.bat ${delete}
                    """
                    break
                // OSX 
                default:
                    sh """
                        ./make_results_baseline.sh ${delete}
                    """
                    break
            }
        }
    }
}

def buildRenderCache(String osName, String toolVersion, String log_name, Integer currentTry, String engine)
{
    def maxCBTries = 3
    def currentCBTry = 0

    while (currentCBTry < maxCBTries) {
        try {
            timeout(time: "7", unit: "MINUTES") {
                dir("scripts") {
                    switch(osName) {
                        case 'Windows':
                            bat """
                                ${engine.contains("HIP") ? "set TH_FORCE_HIP=1" : ""}
                                build_rpr_cache.bat ${toolVersion} ${engine} >> \"..\\${log_name}_${currentTry}.cb.log\"  2>&1
                            """
                            break
                        case 'OSX':
                            sh """
                                ${engine.contains("HIP") ? "export TH_FORCE_HIP=1" : ""}
                                ./build_rpr_cache.sh ${toolVersion} ${engine} >> \"../${log_name}_${currentTry}.cb.log\" 2>&1
                            """
                            break
                        default:
                            println "[WARNING] ${osName} is not supported"
                    }
                }
            }

            break
        } catch (FlowInterruptedException e) {
            e.getCauses().each() {
                String causeClassName = it.getClass().toString()
                
                if (causeClassName.contains("UserInterruption")) {
                    throw e
                }
            }
        } catch (e) {
            currentCBTry++

            if (currentCBTry >= maxCBTries) {
                String cacheBuildingLog = readFile("${log_name}_${currentTry}.cb.log")
                if (cacheBuildingLog.contains("Cannot open renderer description file \"FireRenderRenderer.xml\"")) {
                    throw new ExpectedExceptionWrapper(NotificationConfiguration.PLUGIN_NOT_FOUND, e)
                }
                throw e
            }
        }
    }
}

def executeTestCommand(String osName, String asicName, Map options)
{
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
        def tracesVariable = []

        if (options.collectTraces) {
            tracesVariable = "RPRTRACEPATH=${env.WORKSPACE}/traces"
            tracesVariable = isUnix() ? [tracesVariable] : [tracesVariable.replace("/", "\\")]
            utils.createDir(this, "traces")
        }

        withEnv(tracesVariable) {
            switch(osName) {
                case 'Windows':
                    dir('scripts') {
                        bat """
                            ${options.engine.contains("HIP") ? "set TH_FORCE_HIP=1" : ""}
                            run.bat gpu \"${testsPackageName}\" \"${testsNames}\" 0 0 25 50 0.05 ${options.toolVersion} ${options.engine} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\"  2>&1
                        """
                    }
                    break
                case 'OSX':
                    dir('scripts') {
                        sh """
                            ${options.engine.contains("HIP") ? "export TH_FORCE_HIP=1" : ""}
                            ./run.sh gpu \"${testsPackageName}\" \"${testsNames}\" 0 0 25 50 0.05 ${options.toolVersion} ${options.engine} ${options.testCaseRetries} ${options.updateRefs} 1>> \"../${options.stageName}_${options.currentTry}.log\" 2>&1
                        """
                    }
                    break
                default:
                    println("[WARNING] ${osName} is not supported")
            }
        }
    }
}


def cloneTestsRepository(String osName, Map options) {
    checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

    if (options.tests.contains("RPR_Export") || options.tests.contains("regression.0")) {
        dir("RadeonProRenderSDK") {
            if (options["isPreBuilt"]) {
                checkoutScm(branchName: "master", repositoryUrl: rpr_sdk.RPR_SDK_REPO, useLFS: true)
            } else {
                checkoutScm(branchName: options.rprsdkCommitSHA, repositoryUrl: rpr_sdk.RPR_SDK_REPO, useLFS: true)
            }

            // the Jenkins plugin sometimes can't perform git lfs pull
            if (osName == "OSX") {
                dir('hipbin') {
                    sh """
                        git lfs install
                        git lfs pull
                    """
                }
            }
        }
    }
}


def downloadMayaPrefs(String osName, String toolVersion) {
    timeout(time: "5", unit: "MINUTES") {
        String prefsDir = isUnix() ? "/Users/${env.USER}/Library/Preferences/Autodesk/Maya/${toolVersion}/prefs" : "/mnt/c/Users/${env.USERNAME}/Documents/Maya/${toolVersion}/prefs"
        String customKeys = isUnix() ? "--protect-args" : ""
        downloadFiles("/volume1/CIS/tools-preferences/Maya/${osName}/${toolVersion}/prefs/*", prefsDir, customKeys, false, "nasURL", "nasSSHPort", true)
    }
}


def executeTests(String osName, String asicName, Map options)
{
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    try {
        utils.removeEnvVars(this)

        // FIXME: too many random errors on Maya on Mac machines
        if (osName == "OSX") {
            utils.reboot(this, osName)
        }

        withNotifications(title: options["stageName"], options: options, logUrl: "${env.BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "30", unit: "MINUTES") {
                try {
                    if (osName == "OSX" && asicName == "AppleM1") {
                        sh """
                            pkill -f /Applications/Autodesk/maya2023/Maya.app/Contents/MacOS/Maya; sleep 1; pkill -f /Applications/Autodesk/maya2023/Maya.app/Contents/MacOS/Maya
                            pkill -f /Applications/Autodesk/maya2022/Maya.app/Contents/MacOS/Maya; sleep 1; pkill -f /Applications/Autodesk/maya2022/Maya.app/Contents/MacOS/Maya
                            pkill -f /Applications/Autodesk/maya2020/Maya.app/Contents/MacOS/Maya; sleep 1; pkill -f /Applications/Autodesk/maya2020/Maya.app/Contents/MacOS/Maya
                        """
                    }
                } catch (e) {
                    // just try to close Maya if it's opened
                }
                
                cleanWS(osName)
                cloneTestsRepository(osName, options)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_maya_autotests_assets" : "/mnt/c/TestResources/rpr_maya_autotests_assets"

            downloadFiles("/volume1/web/Assets/rpr_maya_autotests/", assets_dir)
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_PREFERENCES) {
            downloadMayaPrefs(osName, options.toolVersion)
        }

        try {
            Boolean newPluginInstalled = false
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                timeout(time: "15", unit: "MINUTES") {
                    usd_maya.uninstallRPRMayaUSDPlugin(osName, options)
                }

                timeout(time: "15", unit: "MINUTES") {
                    getProduct(osName, options)
                    newPluginInstalled = installMSIPlugin(osName, "Maya", options)
                    println "[INFO] Install function on ${env.NODE_NAME} return ${newPluginInstalled}"
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE) {
                if (newPluginInstalled) {
                    timeout(time: "20", unit: "MINUTES") {
                        String cacheImgPath = "./Work/Results/Maya/cache_building.jpg"
                        utils.removeFile(this, osName, cacheImgPath)
                        buildRenderCache(osName, options.toolVersion, options.stageName, options.currentTry, options.engine)
                        if(!fileExists(cacheImgPath)){
                            throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE))
                        } else {
                            //verifyMatlib("Maya", cacheImgPath, 50, osName, options)
                        }
                    }
                }
            }
            
        } catch(e) {
            println(e.toString())
            println("[ERROR] Failed to install plugin on ${env.NODE_NAME}.")
            // deinstalling broken addon
            installMSIPlugin(osName, "Maya", options, false, true)
            // remove installer of broken addon
            removeInstaller(osName: osName, options: options)
            throw e
        }

        String enginePostfix = ""
        String REF_PATH_PROFILE="/volume1/Baselines/rpr_maya_autotests/${asicName}-${osName}"
        switch(options.engine) {
            case 'Northstar':
            case 'HIPvsNS':
                enginePostfix = "NorthStar"
                break
            case 'HybridPro':
                enginePostfix = "HybridPro"
                break
            case 'HybridLow':
                enginePostfix = "HybridLow"
                break
            case 'HybridMedium':
                enginePostfix = "HybridMedium"
                break
            case 'HybridHigh':
                enginePostfix = "HybridHigh"
                break
            case 'HIP':
                enginePostfix = "HIP"
                break
        }
        REF_PATH_PROFILE = enginePostfix ? "${REF_PATH_PROFILE}-${enginePostfix}" : REF_PATH_PROFILE

        options.REF_PATH_PROFILE = REF_PATH_PROFILE

        outputEnvironmentInfo(osName, options.stageName, options.currentTry)

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
                String baseline_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_maya_autotests_baselines" : "/mnt/c/TestResources/rpr_maya_autotests_baselines"
                baseline_dir = enginePostfix ? "${baseline_dir}-${enginePostfix}" : baseline_dir
                println "[INFO] Downloading reference images for ${options.tests}-${options.engine}"

                options.tests.split(" ").each() {
                    if (it.contains(".json")) {
                        downloadFiles("${REF_PATH_PROFILE}/", baseline_dir, "", true, "nasURL", "nasSSHPort", true)
                    } else {
                        downloadFiles("${REF_PATH_PROFILE}/${it}", baseline_dir, "", true, "nasURL", "nasSSHPort", true)
                    }
                }
            }
            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }
        }
        options.executeTestsFinished = true

        utils.compareDriverVersion(this, "${options.stageName}_${options.currentTry}.log", osName)

        if (options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] != -1) {
            // mark that one group was finished and counting of errored groups in succession must be stopped
            options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] = new AtomicInteger(-1)
        }

    } catch (e) {
        String additionalDescription = ""
        if (options.currentTry + 1 < options.nodeReallocateTries) {
            stashResults = false
        } else {
            if (!options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"]) {
                options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"] = new AtomicInteger(0)
            }
            Integer errorsInSuccession = options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"]
            // if counting of errored groups in succession must isn't stopped
            if (errorsInSuccession >= 0) {
                errorsInSuccession = options["errorsInSuccession"]["${osName}-${asicName}-${options.engine}"].addAndGet(1)
            
                if (errorsInSuccession >= 3) {
                    additionalDescription = "Number of errored groups in succession exceeded (max - 3). Next groups for this platform will be aborted"
                }
            }
        }
        println(e.toString())
        println(e.getMessage())

        utils.reboot(this, osName)

        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${e.getMessage()} ${additionalDescription}", "${env.BUILD_URL}")
            throw new ExpectedExceptionWrapper("${e.getMessage()}\n${additionalDescription}", e.getCause())
        } else {
            String errorMessage = "The reason is not automatically identified. Please contact support."
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, "${errorMessage} ${additionalDescription}", "${env.BUILD_URL}")
            throw new ExpectedExceptionWrapper("${errorMessage}\n${additionalDescription}", e)
        }
    } finally {
        try {
            dir("${options.stageName}") {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")

                // if some json files are broken - rerun (e.g. Maya crash can cause that)
                try {
                    String engineLogContent = readFile("${options.stageName}_${options.currentTry}.log")
                    if (engineLogContent.contains("json.decoder.JSONDecodeError")) {
                        throw new ExpectedExceptionWrapper(NotificationConfiguration.FILES_CRASHED, e)
                    }
                } catch (ExpectedExceptionWrapper e1) {
                    throw e1
                } catch (e1) {
                    println("[WARNING] Could not analyze autotests log")
                }

                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Maya/session_report.json")) {

                        def sessionReport = null
                        sessionReport = readJSON file: 'Results/Maya/session_report.json'

                        if (sessionReport.summary.error > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${env.BUILD_URL}")
                        } else if (sessionReport.summary.failed > 0) {
                            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${env.BUILD_URL}")
                        } else {
                            GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${env.BUILD_URL}")
                        }

                        println("Stashing test results to : ${options.testResultsName}")

                        utils.stashTestData(this, options, options.storeOnNAS, "**/rpr_export_scenes/**,**/cache/**")

                        if (options.reportUpdater) {
                            options.reportUpdater.updateReport(options.engine)
                        }

                        try {
                            utils.analyzeResults(this, sessionReport, options)
                        } catch (e) {
                            installMSIPlugin(osName, "Maya", options, false, true)
                            // remove installer of broken addon
                            removeInstaller(osName: osName, options: options)
                            throw e
                        }
                    }
                }
            } else {
                println "[INFO] Task ${options.tests}-${options.engine} on ${options.nodeLabels} labels will be retried."
            }
        } catch (e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
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
}

def executeBuildWindows(Map options)
{
    dir('RadeonProRenderMayaPlugin\\MayaPkg') {
        GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/Build-Windows.log")

        bat """
            build_windows_installer.cmd >> ../../${STAGE_NAME}.log  2>&1
        """

        if (options.branch_postfix) {
            bat """
                rename RadeonProRender*msi *.(${options.branch_postfix}).msi
            """
        }

        String ARTIFACT_NAME = options.branch_postfix ? "RadeonProRenderMaya_${options.pluginVersion}.(${options.branch_postfix}).msi" : "RadeonProRenderMaya_${options.pluginVersion}.msi"
        String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

        bat """
            rename RadeonProRender*.msi RadeonProRenderMaya.msi
        """

        bat """
            echo import msilib >> getMsiProductCode.py
            echo db = msilib.OpenDatabase(r'RadeonProRenderMaya.msi', msilib.MSIDBOPEN_READONLY) >> getMsiProductCode.py
            echo view = db.OpenView("SELECT Value FROM Property WHERE Property='ProductCode'") >> getMsiProductCode.py
            echo view.Execute(None) >> getMsiProductCode.py
            echo print(view.Fetch().GetString(1)) >> getMsiProductCode.py
        """

        // FIXME: hot fix for STVCIS-1215
        options[getProduct.getIdentificatorKey("Windows", options)] = python3("getMsiProductCode.py").split('\r\n')[2].trim()[1..-2]

        println "[INFO] Built MSI product code: ${options.productCode}"

        makeStash(includes: 'RadeonProRenderMaya.msi', name: getProduct.getStashName("Windows", options), preZip: false, storeOnNAS: options.storeOnNAS)

        GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
    }
}

def executeBuildOSX(Map options)
{
    dir('RadeonProRenderMayaPlugin/MayaPkg') {
        GithubNotificator.updateStatus("Build", "OSX", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${env.BUILD_URL}/artifact/Build-OSX.log")

        dir('../RadeonProRenderSDK/hipbin') {
            // FIXME: Old MacOS builder doesn't have git lfs
            if (env.NODE_LABELS.split().contains("OldNAS")) {
                downloadFiles("/volume1/CIS/bin-storage/hipbin_3.01.00.zip", ".", "", true, "nasURLOld", "nasSSHPort")
            } else {
                downloadFiles("/volume1/CIS/bin-storage/hipbin_3.01.00.zip", ".")
            }

            utils.unzip(this, "hipbin_3.01.00.zip")
        }

        sh """
            ./build_osx_installer.sh >> ../../${STAGE_NAME}.log 2>&1
        """

        dir('.installer_build') {
            if (options.branch_postfix) {
                sh"""
                    for i in RadeonProRender*; do name="\${i%.*}"; mv "\$i" "\${name}.(${options.branch_postfix})\${i#\$name}"; done
                """
            }

            String ARTIFACT_NAME = options.branch_postfix ? "RadeonProRenderMaya_${options.pluginVersion}.(${options.branch_postfix}).dmg" : "RadeonProRenderMaya_${options.pluginVersion}.dmg"
            String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            sh "cp RadeonProRender*.dmg RadeonProRenderMaya.dmg"
            makeStash(includes: 'RadeonProRenderMaya.dmg', name: getProduct.getStashName("OSX", options), preZip: false, storeOnNAS: options.storeOnNAS)

            // TODO: detect ID of installed plugin
            options[getProduct.getIdentificatorKey("OSX", options)] = options.commitSHA

            GithubNotificator.updateStatus("Build", "OSX", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
        }
    }
}


def executeBuild(String osName, Map options)
{
    try {
        dir("RadeonProRenderMayaPlugin") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)

                dir("RadeonProRenderSDK") {
                    hybrid.replaceHybridPro(osName, options)
                }
            }
        }

        outputEnvironmentInfo(osName)

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                    executeBuildOSX(options)
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }
        }
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def getReportBuildArgs(String engineName, Map options) {
    String buildNumber = ""

    if (options.useTrackedMetrics) {
        if (env.BRANCH_NAME && env.BRANCH_NAME != "master") {
            // use any large build number in case of PRs and other branches in auto job
            // it's required to display build as last one
            buildNumber = "10000"
        } else {
            buildNumber = env.BUILD_NUMBER
        }
    }

    if (options["isPreBuilt"]) {
        return """"Maya" "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(engineName)}\" ${options.useTrackedMetrics ? buildNumber : ""}"""
    } else {
        return """"Maya" ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\" ${options.useTrackedMetrics ? buildNumber : ""}"""
    }
}

def executePreBuild(Map options)
{
    if (env.BRANCH_NAME && env.BRANCH_NAME.contains("PR-333")) {
        options.toolVersion = "2023"
    }

    // manual job with prebuilt plugin
    if (options.isPreBuilt) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options.executeBuild = false
        options.executeTests = true
    // manual job
    } else if (!env.BRANCH_NAME) {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options['executeBuild'] = true
            options['executeTests'] = true
            options['testsPackage'] = "regression.json"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
           options['executeBuild'] = true
           options['executeTests'] = true
           options['testsPackage'] = "regression.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['testsPackage'] = "regression.json"
        }
    }

    // branch postfix
    options["branch_postfix"] = ""
    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options["branch_postfix"] = "release"
    } else if(env.BRANCH_NAME && env.BRANCH_NAME != "master" && env.BRANCH_NAME != "develop") {
        options["branch_postfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if(options.projectBranch && options.projectBranch != "master" && options.projectBranch != "develop") {
        options["branch_postfix"] = options.projectBranch.replace('/', '-')
    }

    if (!options['isPreBuilt']) {
        dir('RadeonProRenderMayaPlugin') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }

            options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
            options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
            options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            options.commitShortSHA = options.commitSHA[0..6]
            options.branchName = env.BRANCH_NAME ?: options.projectBranch

            dir("RadeonProRenderSDK") {
                options.rprsdkCommitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }

            println "The last commit was written by ${options.commitAuthor}."
            println "Commit message: ${options.commitMessage}"
            println "Commit SHA: ${options.commitSHA}"
            println "Commit shortSHA: ${options.commitShortSHA}"
            println "Branch name: ${options.branchName}"

            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                options.pluginVersion = version_read("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION')

                if (env.BRANCH_NAME) {
                    withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                        GithubNotificator githubNotificator = new GithubNotificator(this, options)
                        githubNotificator.init(options)
                        options["githubNotificator"] = githubNotificator
                        githubNotificator.initPreBuild("${env.BUILD_URL}")
                        options.projectBranchName = githubNotificator.branchName
                    }
                    
                    if (env.BRANCH_NAME == "master" && options.commitAuthor != "radeonprorender") {
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        options.pluginVersion = increment_version("RPR Maya", "Patch", true)

                        //get commit's sha which have to be build
                        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                        options.projectBranch = options.commitSHA
                        println "[INFO] Project branch hash: ${options.projectBranch}"
                    } else {
                        if (options.commitMessage.contains("CIS:BUILD")) {
                            options['executeBuild'] = true
                        }

                        if (options.commitMessage.contains("CIS:TESTS")) {
                            options['executeBuild'] = true
                            options['executeTests'] = true
                        }
                        // get a list of tests from commit message for auto builds
                        options.tests = utils.getTestsFromCommitMessage(options.commitMessage)
                        println "[INFO] Test groups mentioned in commit message: ${options.tests}"
                    }
                } else {
                    options.projectBranchName = options.projectBranch
                }

                def majorVersion = options.pluginVersion.tokenize('.')[0]
                def minorVersion = options.pluginVersion.tokenize('.')[1]
                def patchVersion = options.pluginVersion.tokenize('.')[2]

                currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
                currentBuild.description += "<b>Version: </b>"
                currentBuild.description += increment_version.addVersionButton("RPR Maya", "Major", majorVersion)
                currentBuild.description += increment_version.addVersionButton("RPR Maya", "Minor", minorVersion)
                currentBuild.description += increment_version.addVersionButton("RPR Maya", "Patch", patchVersion)
                currentBuild.description += "<br/>"
                currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
                currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
                currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
            }
        }
    }
    
    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_maya')  {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            dir ('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            def packageInfo

            if (options.testsPackage != "none") {
                packageInfo = readJSON file: "jobs/${options.testsPackage}"
                options.isPackageSplitted = packageInfo["split"]
                // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
                if (!env.BRANCH_NAME && options.isPackageSplitted && options.tests) {
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
                options.tests = utils.uniteSuites(this, "jobs/weights.json", tempTests)
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
                options.engines.each { engine ->
                    options.tests.each() {
                        tests << "${it}-${engine}"
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
                        options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                    } else {
                        // add group stub for each part of package
                        options.engines.each { engine ->
                            for (int i = 0; i < packageInfo["groups"].size(); i++) {
                                tests << "${modifiedPackageName}-${engine}".replace(".json", ".${i}.json")
                            }
                        }

                        for (int i = 0; i < packageInfo["groups"].size(); i++) {
                            options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                        }
                    }
                }
            } else if (options.tests) {
                options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List)
                options.tests.each() {
                    def xml_timeout = utils.getTimeoutFromXML(this, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
                }
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

        options.testsList = options.tests

        println "timeouts: ${options.timeouts}"

        // make lists of raw profiles and lists of beautified profiles (displaying profiles)
        multiplatform_pipeline.initProfiles(options)

        if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
            options.reportUpdater = new ReportUpdater(this, env, options)
            options.reportUpdater.init(this.&getReportBuildArgs)
        }

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${env.BUILD_URL}")
        }
    }
}

def executeDeploy(Map options, List platformList, List testResultList, String engine)
{
    cleanWS()
    try {
        String engineName = options.displayingTestProfiles[engine]

        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${engineName}", options: options, startUrl: "${env.BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }

            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(engine)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List

                        if (filter(options, testNameParts.get(0), testNameParts.get(1), testNameParts.get(2), engine)) {
                            return
                        }

                        String testName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                        dir(testName) {
                            try {
                                makeUnstash(name: "$it", storeOnNAS: options.storeOnNAS)
                            } catch(e) {
                                println "[ERROR] Failed to unstash ${it}"
                                lostStashes.add("'${testName}'")
                                println(e.toString())
                                println(e.getMessage())
                            }

                        }
                    }
                }
            }
            
            try {
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${engine}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                String metricsRemoteDir

                if (env.BRANCH_NAME || (env.JOB_NAME.contains("Manual") && options.testsPackageOriginal == "regression.json")) {
                    metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/RPR-MayaPlugin/auto/main/${engine}"
                } else {
                    metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/RPR-MayaPlugin/weekly/${engine}"
                }

                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${env.BUILD_URL}")
                
                if (options.useTrackedMetrics) {
                    utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        List retryInfoList = utils.deepcopyCollection(this, options.nodeRetry)
                        retryInfoList.each{ gpu ->
                            gpu['Tries'].each{ group ->
                                group.each{ groupKey, retries ->
                                    if (groupKey.endsWith(engine)) {
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
                            bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(engineName, options)}"
                        } catch (e) {
                            String errorMessage = utils.getReportFailReason(e.getMessage())
                            GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${env.BUILD_URL}")
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
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${env.BUILD_URL}")
                println("[ERROR] Failed to build test report.")
                println(e.toString())
                println(e.getMessage())
                if (!options.testDataSaved && !options.storeOnNAS) {
                    try {
                        // Save test data for access it manually anyway
                        utils.publishReport(this, "${env.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                            "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                            ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

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
                println("[ERROR] during slack status generation")
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
                def summaryReport
                dir("summaryTestResults") {
                    archiveArtifacts artifacts: "summary_status.json"
                    summaryReport = readJSON file: "summary_status.json"
                }

                summaryTestResults['passed'] = summaryReport.passed
                summaryTestResults['failed'] = summaryReport.failed
                summaryTestResults['error'] = summaryReport.error
                if (summaryReport.error > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"

                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                }
                else if (summaryReport.failed > 0) {
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
                options["testsStatus-${engine}"] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options["testsStatus-${engine}"] = ""
            }

            withNotifications(title: "Building test report for ${engineName}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${env.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report ${engineName}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": env.BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${env.BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${env.BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        if (!options.storeOnNAS) {
            utils.generateOverviewReport(this, this.&getReportBuildArgs, options)
        }
    }
}

def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms) {
        filteredPlatforms +=  ";" + platform
    } else  {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}

def call(String projectRepo = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaPlugin.git",
        String projectBranch = "",
        String testsBranch = "master",
        String platforms = 'Windows:NVIDIA_RTX3080TI,NVIDIA_RTX4080,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX7900XTX,AMD_RX5700XT,AMD_WX9100',
        String updateRefs = 'No',
        String testsPackage = "",
        String tests = "",
        String toolVersion = "2023",
        String customBuildLinkWindows = "",
        String customBuildLinkOSX = "",
        String enginesNames = "Northstar,HybridPro",
        String tester_tag = 'Maya',
        String mergeablePR = "",
        String parallelExecutionTypeString = "TakeAllNodes",
        Integer testCaseRetries = 5,
        Boolean collectTraces = false,
        String customHybridProWindowsLink = "",
        String customHybridProUbuntuLink = "")
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []
    Map errorsInSuccession = [:]

    if (env.BRANCH_NAME && env.BRANCH_NAME == "PR-367") {
        testsBranch = "inemankov/updated_logs_parsing"
        toolVersion = "2024"
    }

    boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") 
        || (env.JOB_NAME.contains("Manual") && (testsPackage == "Full.json" || testsPackage == "regression.json"))
        || env.BRANCH_NAME)
    boolean saveTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.BRANCH_NAME && env.BRANCH_NAME == "master"))

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            if (env.BRANCH_NAME && env.BRANCH_NAME == "PR-278") {
                testsBranch = "inemankov/remove_tahoe"
            }
            
            def enginesNamesList = enginesNames.split(',') as List
            def formattedEngines = []
            enginesNamesList.each {
                formattedEngines.add(it.replace(' ', '_'))
            }

            Boolean isPreBuilt = customBuildLinkWindows || customBuildLinkOSX

            if (isPreBuilt) {
                //remove platforms for which pre built plugin is not specified
                String filteredPlatforms = ""

                platforms.split(';').each() { platform ->
                    List tokens = platform.tokenize(':')
                    String platformName = tokens.get(0)

                    switch(platformName) {
                        case 'Windows':
                            if (customBuildLinkWindows) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                        case 'OSX':
                            if (customBuildLinkOSX) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                    }
                }

                platforms = filteredPlatforms
            }

            gpusCount = 0
            platforms.split(';').each() { platform ->
                List tokens = platform.tokenize(':')
                if (tokens.size() > 1) {
                    gpuNames = tokens.get(1)
                    gpuNames.split(',').each() {
                        gpusCount += 1
                    }
                }
            }

            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            println "Platforms: ${platforms}"
            println "Tests: ${tests}"
            println "Tests package: ${testsPackage}"
            println "Tests execution type: ${parallelExecutionType}"

            String prRepoName = ""
            String prBranchName = ""
            if (mergeablePR) {
                String[] prInfo = mergeablePR.split(";")
                prRepoName = prInfo[0]
                prBranchName = prInfo[1]
            }

            String builderTag = "BuilderRPRMaya"

            if (env.BRANCH_NAME && env.BRANCH_NAME == "PR-384") {
                builderTag = "(PC-BUILDER-HAMBURG-WIN10 || PC-BUILDER-BERLIN-WIN10 || PC-BUILDER-PORTLAND-WIN10)"
            }

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_maya.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        PRJ_NAME:"RadeonProRenderMayaPlugin",
                        PRJ_ROOT:"rpr-plugins",
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        toolVersion:toolVersion,
                        executeBuild:false,
                        executeTests:isPreBuilt,
                        isPreBuilt:isPreBuilt,
                        reportName:'Test_20Report',
                        splitTestsExecution:true,
                        gpusCount:gpusCount,
                        BUILDER_TAG:builderTag,
                        TEST_TIMEOUT:120,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:75,
                        BUILD_TIMEOUT:120,
                        DEPLOY_TIMEOUT:180,
                        TESTER_TAG:tester_tag,
                        customBuildLinkWindows: customBuildLinkWindows,
                        customBuildLinkOSX: customBuildLinkOSX,
                        engines: formattedEngines,
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        parallelExecutionType:parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        testCaseRetries:testCaseRetries,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        skipCallback: this.&filter,
                        collectTraces: collectTraces,
                        useTrackedMetrics:useTrackedMetrics,
                        saveTrackedMetrics:saveTrackedMetrics,
                        customHybridProWindowsLink: customHybridProWindowsLink,
                        customHybridProUbuntuLink: customHybridProUbuntuLink
                        ]

            withNotifications(options: options, configuration: NotificationConfiguration.VALIDATION_FAILED) {
                validateParameters(options)
            }
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }

}
