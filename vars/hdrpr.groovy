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
    supportedOS: ["Windows", "Ubuntu20"],
    productExtensions: ["Windows": "zip", "Ubuntu20": "zip"],
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
    //skip HybridPro on Ubuntu
    if (engine == "HybridPro" && osName == "Ubuntu20") {
        return true
    }

    return (engine == "HybridPro" && !(asicName.contains("RTX") || asicName.contains("AMD_RX6")))
}


def unpackUSD(String osName, Map options) {
    if (isUnix()) {
        sh "rm -rf USD"
    }

    dir("USD/build") {
        getProduct(osName, options, ".")
    }
}


def doSanityCheck(String osName, Map options) {
    dir("scripts") {
        switch(osName) {
            case "Windows":
                withEnv(["PATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib;c:\\JN\\WS\\HdRPR_Build\\USD\\build\\bin;${PATH}", "PYTHONPATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib\\python"]) {
                    bat """
                        do_sanity_check.bat ${options.engine} >> \"..\\${options.stageName}_${options.currentTry}.sanity_check_wrapper.log\" 2>&1
                    """
                }

                break
            default:
                withEnv(["PATH=/home/admin/JN/WS/HdRPR_Build/USD/build/lib:/home/admin/JN/WS/HdRPR_Build/USD/build/bin:${PATH}", "PYTHONPATH=/home/admin/JN/WS/HdRPR_Build/USD/build/lib/python"]) {
                    sh """
                        ./do_sanity_check.sh ${options.engine} >> \"../${options.stageName}_${options.currentTry}.sanity_check_wrapper.log\" 2>&1
                    """
                }
        }
    }
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
   dir("scripts") {
        switch(osName) {
            case "Windows":
                bat """
                    make_results_baseline.bat ${delete}
                """
                break
            default:
                println("[WARNING] ${osName} is not supported")   
        }
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    dir("scripts") {
        def testTimeout = options.timeouts["${options.tests}"]

        println "[INFO] Set timeout to ${testTimeout}"

        timeout(time: testTimeout, unit: 'MINUTES') { 
            switch(osName) {
                case "Windows":
                    withEnv(["PATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib;c:\\JN\\WS\\HdRPR_Build\\USD\\build\\bin;${PATH}", "PYTHONPATH=c:\\JN\\WS\\HdRPR_Build\\USD\\build\\lib\\python"]) {
                        bat """
                            set TOOL_VERSION=${options.toolVersion}
                            run.bat ${options.testsPackage} \"${options.tests}\" ${options.engine} ${options.testCaseRetries} ${options.updateRefs} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                        """
                    }

                    break

                default:
                    withEnv(["PATH=/home/admin/JN/WS/HdRPR_Build/USD/build/lib:/home/admin/JN/WS/HdRPR_Build/USD/build/bin:${PATH}", "PYTHONPATH=/home/admin/JN/WS/HdRPR_Build/USD/build/lib/python"]) {
                        sh """
                            set TOOL_VERSION=${options.toolVersion}
                            ./run.sh ${options.testsPackage} \"${options.tests}\" ${options.engine} ${options.testCaseRetries} ${options.updateRefs} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                        """
                    }
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // Built USD is tied with paths. Always work with USD from the same directory
    String newWorkspace = osName == "Windows" ? "${WORKSPACE}/../HdRPR_Build" : ""
    dir(newWorkspace) {
        // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
        Boolean stashResults = true

        try {
            withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                timeout(time: "5", unit: "MINUTES") {
                    cleanWS(osName)
                    checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
                    println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
                String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/hdrpr_autotests_assets" : "/mnt/c/TestResources/hdrpr_autotests_assets"
                downloadFiles("/volume1/web/Assets/hdrpr_autotests/", assets_dir)
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
                timeout(time: "10", unit: "MINUTES") {
                    // Built USD is tied with paths. Always work with USD from the same directory
                    newWorkspace = osName == "Windows" ? "" : "/home/admin/JN/WS/HdRPR_Build"
                    dir(newWorkspace) {
                        unpackUSD(osName, options)
                    }
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.SANITY_CHECK) {
                timeout(time: "5", unit: "MINUTES") {
                    doSanityCheck(osName, options)
                    if (!fileExists("./scripts/sanity.jpg")) {
                        println "[ERROR] Sanity check failed on ${env.NODE_NAME}. No output image found."
                        throw new ExpectedExceptionWrapper(NotificationConfiguration.NO_OUTPUT_IMAGE_SANITY_CHECK, new Exception(NotificationConfiguration.NO_OUTPUT_IMAGE_SANITY_CHECK))
                    }
                }
            }

            String enginePostfix = ""
            String REF_PATH_PROFILE="/volume1/Baselines/hdrpr_autotests/${asicName}-${osName}"
            switch(options.engine) {
                case 'Northstar':
                    enginePostfix = "NorthStar"
                    break
                case 'HybridPro':
                    enginePostfix = "HybridPro"
                    break
            }
            REF_PATH_PROFILE = enginePostfix ? "${REF_PATH_PROFILE}-${enginePostfix}" : REF_PATH_PROFILE

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
                    String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/hdrpr_autotests_baselines" : "/mnt/c/TestResources/hdrpr_autotests_baselines"
                    baselineDir = enginePostfix ? "${baselineDir}-${enginePostfix}" : baselineDir
                    println "[INFO] Downloading reference images for ${options.testsPackage}"
                    options.tests.split(" ").each { downloadFiles("${REF_PATH_PROFILE}/${it}", baselineDir) }
                }
                withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                    executeTestCommand(osName, asicName, options)
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
                    utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
                    utils.renameFile(this, osName, "sanity.log", "${options.stageName}_${options.currentTry}.sanity_check.log")
                }

                archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

                if (stashResults) {
                    dir('Work') {
                        if (fileExists("Results/HdRPR/session_report.json")) {
                            def sessionReport = readJSON file: 'Results/HdRPR/session_report.json'
                            if (sessionReport.summary.error > 0) {
                                GithubNotificator.updateStatus("Test", options['stageName'], "action_required", options, NotificationConfiguration.SOME_TESTS_ERRORED, "${BUILD_URL}")
                            } else if (sessionReport.summary.failed > 0) {
                                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.SOME_TESTS_FAILED, "${BUILD_URL}")
                            } else {
                                GithubNotificator.updateStatus("Test", options['stageName'], "success", options, NotificationConfiguration.ALL_TESTS_PASSED, "${BUILD_URL}")
                            }

                            println "Stashing test results to : ${options.testResultsName}"
                            utils.stashTestData(this, options, options.storeOnNAS)

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

                            if (options.reportUpdater) {
                                options.reportUpdater.updateReport(options.engine)
                            }
                        }
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


def executeBuildWindows(String osName, Map options) {
    withEnv(["PATH=c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
        GithubNotificator.updateStatus("Build", "${osName}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")

        String builtUSDPath = "${WORKSPACE}\\..\\HdRPR_Build\\USD\\build"

        if (options.rebuildUSD) {
            dir ("USD") {
                bat """
                    call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvarsall.bat" amd64 >> ${STAGE_NAME}_USD.log 2>&1
                    waitfor 1 /t 10 2>NUL || type nul>nul
                    python --version >> ${STAGE_NAME}_USD.log 2>&1
                    python build_scripts\\build_usd.py ${builtUSDPath} --openimageio --materialx >> ${STAGE_NAME}_USD.log 2>&1
                """
            }

            if (options.saveUSD) {
                uploadFiles("USD/*", "/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/Windows/USD")
            }
        }

        dir ("RadeonProRenderUSD") {
            dir("build") {
                bat """
                    python --version >> ..\\..\\${STAGE_NAME}.log 2>&1
                    cmake -Dpxr_DIR=${builtUSDPath} -DCMAKE_INSTALL_PREFIX=${builtUSDPath} -DPYTHON_INCLUDE_DIR=C:\\Python37\\include -DPYTHON_EXECUTABLE=C:\\Python37\\python.exe -DPYTHON_LIBRARIES=C:\\Python37\\libs\\python37.lib .. >> ..\\..\\${STAGE_NAME}.log 2>&1
                    cmake --build . --config RelWithDebInfo --target install >> ..\\..\\${STAGE_NAME}.log 2>&1
                """
            }
        }

        dir ("USD/build") {
            String ARTIFACT_NAME = "hdRpr-${osName}.zip"

            utils.removeFile(this, "Windows", ARTIFACT_NAME)
            bat("%CIS_TOOLS%\\7-Zip\\7z.exe a ${ARTIFACT_NAME} . -xr!src -xr!share -xr!build")

            String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            makeStash(includes: ARTIFACT_NAME, name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
            GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
        }
    }
}


def executeBuildLinux(String osName, Map options) {
    GithubNotificator.updateStatus("Build", "${osName}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-${osName}.log")

    String builtUSDPath = "${WORKSPACE}/../HdRPR_Build/USD/build"

    if (options.rebuildUSD) {
        dir("USD") {
            sh """
                export OS=
                python --version >> ${STAGE_NAME}_USD.log 2>&1
                python build_scripts/build_usd.py ${builtUSDPath} --openimageio --materialx >> ${STAGE_NAME}_USD.log 2>&1
            """

            if (options.saveUSD) {
                uploadFiles(".", "/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/${osName}/USD/")
            }
        }
    }

    dir ("RadeonProRenderUSD") {
        dir("build") {
            sh """
                python --version >> ../../${STAGE_NAME}.log 2>&1
                cmake -Dpxr_DIR=${builtUSDPath} -DCMAKE_INSTALL_PREFIX=${builtUSDPath} -DOPENEXR_LOCATION=${builtUSDPath} .. >> ../../${STAGE_NAME}.log 2>&1
                cmake --build . --target install >> ../../${STAGE_NAME}.log 2>&1
            """
        }
    }

    dir ("USD/build") {
        String ARTIFACT_NAME = "hdRpr-${osName}.zip"

        utils.removeFile(this, osName, ARTIFACT_NAME)
        sh("zip --symlinks -r ${ARTIFACT_NAME} . -x 'src' -x 'share' -x 'build'")

        String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

        makeStash(includes: ARTIFACT_NAME, name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
        GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
    }
}


def executeBuild(String osName, Map options) {
    // Built USD is tied with paths. Always work with USD from the same directory
    dir("${WORKSPACE}/../HdRPR_Build") {
        try {
            dir ("RadeonProRenderUSD") {
                withNotifications(title: "${osName}", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
                }
            }

            withNotifications(title: "${osName}", options: options, configuration: NotificationConfiguration.DOWNLOAD_USD_REPO) {
                if (options.rebuildUSD) {
                    dir('USD') {
                        checkoutScm(branchName: options.usdBranch, repositoryUrl: "git@github.com:PixarAnimationStudios/USD.git")
                    }
                } else {
                    downloadFiles("/volume1/CIS/${options.PRJ_ROOT}/${options.PRJ_NAME}/${osName}/USD", ".")
                }
            }

            utils.removeFile(this, osName, "*.log")

            outputEnvironmentInfo(osName)
            withNotifications(title: "${osName}", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
                switch(osName) {
                    case "Windows":
                        executeBuildWindows(osName, options)
                        break
                    default:
                        executeBuildLinux(osName, options)
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
}


def getReportBuildArgs(String engineName, Map options) {
    return """${utils.escapeCharsByUnicode("HdRPR")} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(engineName)}\""""
}


def executePreBuild(Map options) {
    // manual job
    if (!env.BRANCH_NAME) {
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.executeBuild = true
            options.executeTests = true
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.executeBuild = true
            options.executeTests = true
        } else  {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.executeBuild = true
            options.executeTests = true
        }
    }

    dir('RadeonProRenderUSD') {
        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
        }

        options.commitAuthor = utils.getBatOutput(this, "git show -s --format=%%an HEAD ")
        options.commitMessage = utils.getBatOutput(this, "git log --format=%%B -n 1")
        options.commitSHA = utils.getBatOutput(this, "git log --format=%%H -1 ")

        println """
            The last commit was written by ${options.commitAuthor}.
            Commit message: ${options.commitMessage}
            Commit SHA: ${options.commitSHA}
        """

        if (env.BRANCH_NAME) {
            withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                GithubNotificator githubNotificator = new GithubNotificator(this, options)
                githubNotificator.init(options)
                options["githubNotificator"] = githubNotificator
                githubNotificator.initPreBuild("${BUILD_URL}")
                options.projectBranchName = githubNotificator.branchName
            }
        } else {
            options.projectBranchName = options.projectBranch
        }

        options.majorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MAJOR_VERSION "', '')
        options.minorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MINOR_VERSION "', '')
        options.patchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', '')
        options.toolVersion = "${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"

        currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
        currentBuild.description += "<b>Version:</b> ${options.toolVersion}<br/>"
        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir("jobs_test_repo") {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            dir("jobs_launcher") {
                options["jobsLauncherBranch"] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            }
            options["testsBranch"] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            if (options.testsPackage != "none") {

                def groupNames = readJSON(file: "jobs/${options.testsPackage}")["groups"].collect { it.key }
                // json means custom test suite. Split doesn't supported
                options.testsPackage = "none"

                groupNames = utils.uniteSuites(this, "jobs/weights.json", groupNames)
                groupNames.each() {
                    def xmlTimeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xmlTimeout > 0) ? xmlTimeout : options.TEST_TIMEOUT
                }

                if (options.containsKey("engines")) {
                    options.engines.each { engine ->
                        groupNames.each { testGroup ->
                            tests << "${testGroup}-${engine}"
                        }
                    }
                } else {
                    groupNames.each { testGroup ->
                        tests << "${testGroup}"
                    }
                }

            } else if (options.tests) {
                def groupNames = options.tests.split() as List

                groupNames = utils.uniteSuites(this, "jobs/weights.json", groupNames)
                groupNames.each() {
                    def xmlTimeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xmlTimeout > 0) ? xmlTimeout : options.TEST_TIMEOUT
                }

                if (options.containsKey("engines")) {
                    options.engines.each { engine ->
                        groupNames.each { testGroup ->
                            tests << "${testGroup}-${engine}"
                        }
                }
                } else {
                    groupNames.each { testGroup ->
                        tests << "${testGroup}"
                    }
                }

            } else {
                options.executeTests = false
            }
        }

        println("[INFO] Tests: ${tests}")
        println("[INFO] Timeouts: ${options.timeouts}")

        options.testsList = tests
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
}


def executeDeploy(Map options, List platformList, List testResultList, String engine) {
    cleanWS()
    try {
        String engineName = options.displayingTestProfiles[engine]

        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${engineName}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
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
                dir("jobs_launcher") {
                    bat """
                        count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${engine}\" \"{}\"
                    """
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

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
                            GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${BUILD_URL}")
                            if (utils.isReportFailCritical(e.getMessage())) {
                                throw e
                            } else {
                                currentBuild.result = "FAILURE"
                                options.problemMessageManager.saveGlobalFailReason(errorMessage)
                            }
                        }
                    }
                }
            } catch(e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "failure", options, errorMessage, "${BUILD_URL}")
                println("[ERROR] Failed to build test report.")
                println(e.toString())
                println(e.getMessage())
                if (!options.testDataSaved && !options.storeOnNAS) {
                    try {
                        // Save test data for access it manually anyway
                        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                            "Test Report ${engineName}", "Summary Report, Compare Report" , options.storeOnNAS, \
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
                options["testsStatus-${engine}"] = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println(e.toString())
                println(e.getMessage())
                options["testsStatus-${engine}"] = ""
            }

            withNotifications(title: "Building test report for ${engineName}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, compare_report.html", \
                    "Test Report ${engineName}", "Summary Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${engineName}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
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


def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String testsBranch = "master",
        String usdBranch = "release",
        String platforms = 'Windows:AMD_RX6800XT',
        Boolean rebuildUSD = false,
        Boolean saveUSD = false,
        String updateRefs = 'No',
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
                        updateRefs: updateRefs,
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
                        saveUSD: saveUSD,
                        engines: enginesNamesList,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        skipCallback: this.&filter,
                        testCaseRetries: testCaseRetries,
                        BUILDER_TAG: "HdRPRBuilder"
                        ]
        }
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
