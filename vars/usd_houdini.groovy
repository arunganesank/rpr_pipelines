import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.ConcurrentHashMap


@Field final String PRODUCT_NAME = "AMD%20Radeon™%20ProRender%20for%20USDPlugin"
@Field final String PROJECT_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderUSD.git"

@Field final PipelineConfiguration PIPELINE_CONFIGURATION = new PipelineConfiguration(
    supportedOS: ["Windows", "OSX", "Ubuntu20"],
    productExtensions: ["Windows": "tar.gz", "OSX": "tar.gz", "MacOS_ARM": "tar.gz", "Ubuntu18": "tar.gz", "Ubuntu20": "tar.gz"],
    artifactNameBase: "hdRpr_",
    buildProfile: "toolVersion",
    testProfile: "toolVersion_engine"
)


def installHoudiniPlugin(String osName, Map options) {
    getProduct(osName, options, ".", false)

    switch(osName) {
        case 'Windows':
            bat """
                cd hdRpr*
                echo y | activateHoudiniPlugin.exe >> \"..\\${options.stageName}_${options.currentTry}.install.log\"  2>&1
            """
            break

        case "OSX":
            sh """
                cd hdRpr*
                chmod +x activateHoudiniPlugin
                echo y | ./activateHoudiniPlugin >> \"../${options.stageName}_${options.currentTry}.install.log\" 2>&1
            """
            break

        default:
            sh """
                export HOUDINI_USER_PREF_DIR=/home/\$(eval whoami)/houdini${options.toolVersion.tokenize('.')[0]}.${options.toolVersion.tokenize('.')[1]}
                cd hdRpr*
                chmod +x activateHoudiniPlugin
                echo y | ./activateHoudiniPlugin \"../${options.stageName}_${options.currentTry}.install.log\" 2>&1
            """
    }
}


def buildRenderCache(String osName, Map options) {
    dir("scripts") {
        switch(osName) {
            case 'Windows':
                bat """
                    build_rpr_cache.bat \"${options.win_tool_path}\\bin\\husk.exe\" >> \"..\\${options.stageName}_${options.currentTry}.cb.log\"  2>&1
                """
                break
            case 'OSX':
                sh """
                    chmod +x build_rpr_cache.sh
                    ./build_rpr_cache.sh \"${options.osx_tool_path}/bin/husk\" >> \"../${options.stageName}_${options.currentTry}.cb.log\" 2>&1
                """
                break
            default:
                sh """
                    export HOUDINI_USER_PREF_DIR=/home/\$(eval whoami)/houdini${options.toolVersion.tokenize('.')[0]}.${options.toolVersion.tokenize('.')[1]}
                    export LD_LIBRARY_PATH="/home/\$(eval whoami)/Houdini/hfs${options.toolVersion}/dsolib"
                    chmod +x build_rpr_cache.sh
                    ./build_rpr_cache.sh \"/home/\$(eval whoami)/${options.unix_tool_path}/bin/husk\" >> \"../${options.stageName}_${options.currentTry}.cb.log\" 2>&1
                """     
        }
    }
}


def executeGenTestRefCommand(String osName, Map options, Boolean delete) {
    withEnv([
            "BASELINES_UPDATE_INITIATOR=${baseline_update_pipeline.getBaselinesUpdateInitiator()}",
            "BASELINES_ORIGINAL_BUILD=${baseline_update_pipeline.getBaselinesOriginalBuild()}",
            "BASELINES_UPDATING_BUILD=${baseline_update_pipeline.getBaselinesUpdatingBuild()}"
    ]) {
        dir('scripts') {
            switch(osName) {
                case 'Windows':
                    bat """
                        make_results_baseline.bat ${delete}
                    """
                    break
                default:
                    sh """
                        chmod +x make_results_baseline.sh
                        ./make_results_baseline.sh ${delete}
                    """
            }
        }
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    dir('scripts') {
        String rprTracesRoot = "No"
        String rifTracesRoot = "No"

        if (options.enableRPRTracing) {
            rprTracesRoot = "${env.WORKSPACE}/${env.STAGE_NAME}_RPR_Trace"
        }

        if (options.enableRIFTracing) {
            rifTracesRoot = "${env.WORKSPACE}/${env.STAGE_NAME}_RIF_Trace"
        }

        def testTimeout = options.timeouts["${options.tests}"]

        println "[INFO] Set timeout to ${testTimeout}"

        timeout(time: testTimeout, unit: 'MINUTES') { 
            switch(osName) {
                case 'Windows':
                    bat """
                        run.bat ${options.testsPackage} \"${options.tests}\" \"${options.win_tool_path}\\bin\\husk.exe\" ${options.updateRefs} ${options.engine} ${options.width} ${options.height} ${options.minSamples} ${options.maxSamples} ${options.threshold} \"${rprTracesRoot}\" \"${rifTracesRoot}\" >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                    break

                case 'OSX':
                    sh """
                        chmod +x run.sh
                        ./run.sh ${options.testsPackage} \"${options.tests}\" \"${options.osx_tool_path}/bin/husk\" ${options.updateRefs} ${options.engine} ${options.width} ${options.height} ${options.minSamples} ${options.maxSamples} ${options.threshold} \"${rprTracesRoot}\" \"${rifTracesRoot}\"  >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
                    break

                default:
                    sh """
                        export HOUDINI_USER_PREF_DIR=/home/\$(eval whoami)/houdini${options.toolVersion.tokenize('.')[0]}.${options.toolVersion.tokenize('.')[1]}
                        export LD_LIBRARY_PATH="/home/\$(eval whoami)/Houdini/hfs${options.toolVersion}/dsolib"
                        chmod +x run.sh
                        ./run.sh ${options.testsPackage} \"${options.tests}\" \"/home/\$(eval whoami)/${options.unix_tool_path}/bin/husk\" ${options.updateRefs} ${options.engine} ${options.width} ${options.height} ${options.minSamples} ${options.maxSamples} ${options.threshold} \"${rprTracesRoot}\" \"${rifTracesRoot}\" >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                    """
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    utils.reboot(this, osName)

    try {
        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
                println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/${options.assetsName}_assets" : "/mnt/c/TestResources/${options.assetsName}_assets"
            downloadFiles("/volume1/web/Assets/${options.assetsName}/", assetsDir, "", true)
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_PLUGIN) {
            timeout(time: "10", unit: "MINUTES") {
                installHoudiniPlugin(osName, options)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.BUILD_CACHE) {
            timeout(time: "5", unit: "MINUTES") {
                buildRenderCache(osName, options)
                if (!fileExists("./Work/Results/Houdini/cache_building.jpg")) {
                    println "[ERROR] Failed to build cache on ${env.NODE_NAME}. No output image found."
                    throw new ExpectedExceptionWrapper("No output image after cache building.", new Exception("No output image after cache building."))
                }
            }
        }

        String REF_PATH_PROFILE="/volume1/Baselines/${options.assetsName}/${asicName}-${osName}-${options.testProfile}"
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
                String baselineDir = isUnix() ? "${CIS_TOOLS}/../TestResources/${options.assetsName}_baselines" : "/mnt/c/TestResources/${options.assetsName}_baselines"
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
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true
            archiveArtifacts artifacts: "${env.STAGE_NAME}_RIF_Trace/**/*.*", allowEmptyArchive: true
            archiveArtifacts artifacts: "${env.STAGE_NAME}_RPR_Trace/**/*.*", allowEmptyArchive: true
            if (stashResults) {
                dir('Work') {
                    if (fileExists("Results/Houdini/session_report.json")) {
                        def sessionReport = readJSON file: 'Results/Houdini/session_report.json'
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
                            options.reportUpdater.updateReport(options.testProfile)
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


def executeBuildWindows(String osName, Map options) {
    try {
        clearBinariesWin()

        dir ("RadeonProRenderUSD") {
            GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")
            
            String additionalKeys = ""

            if (options.toolVersion.startsWith("19.0.")) {
                additionalKeys = "-G 'Visual Studio 16 2019'"
            }

            additionalKeys = additionalKeys ? "--cmake_options \"${additionalKeys}\"" : ""

            options.win_tool_path = "C:\\Program Files\\Side Effects Software\\Houdini ${options.toolVersion}"
            bat """
                mkdir build
                set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH%;
                set HFS=${options.win_tool_path}
                python --version >> ..\\${STAGE_NAME}.log 2>&1
                python pxr\\imaging\\plugin\\hdRpr\\package\\generatePackage.py -i "." -o "build" ${additionalKeys} >> ..\\${STAGE_NAME}.log 2>&1
            """

            dir("build") {                
                String ARTIFACT_NAME = "hdRpr-${options.pluginVersion}-Houdini-${options.toolVersion}-${osName}.tar.gz"
                bat "rename hdRpr* ${ARTIFACT_NAME}"
                String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

                bat "rename hdRpr* hdRpr_${osName}.tar.gz"
                makeStash(includes: "hdRpr_${osName}.tar.gz", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
                GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
            }
        }
    } catch (e) {
        println("Error during build on Windows")
        println(e.toString())

        def exception = e

        try {
            String buildLogContent = readFile("${STAGE_NAME}.log")
            if (buildLogContent.contains("PDB API call failed")) {
                exception = new ExpectedExceptionWrapper(NotificationConfiguration.USD_GLTF_BUILD_ERROR, e)
                exception.retry = true

                utils.reboot(this, osName)
            }
        } catch (e1) {
            println("[WARNING] Could not analyze build log")
        }

        throw exception
    }
}


def executeBuildOSX(String osName, Map options) {
    clearBinariesUnix()

    dir ("RadeonProRenderUSD") {
        GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-OSX.log")

        options.osx_tool_path = "/Applications/Houdini/Houdini${options.toolVersion}/Frameworks/Houdini.framework/Versions/Current/Resources"
        String[] versionParts = options.toolVersion.split("\\.")
        sh """
            mkdir build
            export HFS=${options.osx_tool_path}
            /Applications/Houdini/Houdini${options.toolVersion}/Frameworks/Houdini.framework/Versions/${versionParts[0]}.${versionParts[1]}/Resources/bin/hserver
            python3 --version >> ../${STAGE_NAME}.log 2>&1
            python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" >> ../${STAGE_NAME}.log 2>&1
        """
        
        dir("build") {        
            String ARTIFACT_NAME = "hdRpr-${options.pluginVersion}-Houdini-${options.toolVersion}-macOS.tar.gz"
            sh "mv hdRpr*.tar.gz ${ARTIFACT_NAME}"
            String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            sh "mv hdRpr*.tar.gz hdRpr_${osName}.tar.gz"
            makeStash(includes: "hdRpr_${osName}.tar.gz", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
            GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
        }
    }
}


def executeBuildUnix(String osName, Map options) {
    clearBinariesUnix()

    dir("RadeonProRenderUSD") {
        GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-${osName}.log")
        
        String installation_path
        if (env.HOUDINI_INSTALLATION_PATH) {
            installation_path = "${env.HOUDINI_INSTALLATION_PATH}"
        } else {
            installation_path = "/home/\$(eval whoami)"
        }

        options.unix_tool_path = "Houdini/hfs${options.toolVersion}"
        sh """
            mkdir build
            export HFS=${installation_path}/${options.unix_tool_path}
            \$HFS/bin/hserver
            python3 --version >> ../${STAGE_NAME}.log 2>&1
            python3 pxr/imaging/plugin/hdRpr/package/generatePackage.py -i "." -o "build" >> ../${STAGE_NAME}.log 2>&1
        """

        dir("build") {
            String ARTIFACT_NAME = "hdRpr-${options.pluginVersion}-Houdini-${options.toolVersion}-${osName}.tar.gz"
            sh "mv hdRpr*.tar.gz ${ARTIFACT_NAME}"
            String artifactURL = makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)

            sh "mv hdRpr*.tar.gz hdRpr_${osName}.tar.gz"
            makeStash(includes: "hdRpr_${osName}.tar.gz", name: getProduct.getStashName(osName, options), preZip: false, storeOnNAS: options.storeOnNAS)
            GithubNotificator.updateStatus("Build", "${osName}-${options.buildProfile}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, artifactURL)
        }
    }
}


def executeBuild(String osName, Map options) {
    try {
        if (osName != "OSX") {
            withNotifications(title: "${osName}-${options.buildProfile}", options: options, configuration: NotificationConfiguration.INSTALL_HOUDINI) {
                timeout(time: "5", unit: "MINUTES") {
                    withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: "sidefxCredentials", usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                        println(python3("${CIS_TOOLS}/houdini_api.py --client_id \"$USERNAME\" --client_secret_key \"$PASSWORD\" --version \"${options.buildProfile}\" --skip_installation \"True\""))
                    }
                }
            }
        }

        dir ("RadeonProRenderUSD") {
            withNotifications(title: "${osName}-${options.buildProfile}", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName)
            }

            if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith(hybrid_to_blender_workflow.BRANCH_NAME_PREFIX) && osName != "OSX") {
                dir("deps/RPR") {
                    hybrid_to_blender_workflow.replaceHybrid(osName, options)
                }
            }
        }

        utils.removeFile(this, osName, "*.log")

        outputEnvironmentInfo(osName)
        withNotifications(title: "${osName}-${options.buildProfile}", options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
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

        options[getProduct.getIdentificatorKey(osName, options)] = options.commitSHA + "_" + options.toolVersion
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
    }
}

def getReportBuildArgs(String toolName, Map options, String title = "USD") {
    boolean collectTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual")))

    if (options["isPreBuilt"]) {
        return """${title} "PreBuilt" "PreBuilt" "PreBuilt" \"${utils.escapeCharsByUnicode(toolName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    } else {
        return """${title} ${options.commitSHA} ${options.projectBranchName} \"${utils.escapeCharsByUnicode(options.commitMessage)}\" \"${utils.escapeCharsByUnicode(toolName)}\" ${collectTrackedMetrics ? env.BUILD_NUMBER : ""}"""
    }
}

def executePreBuild(Map options) {
    // manual job with prebuilt plugin
    if (options["isPreBuilt"]) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options.executeBuild = false
        options.executeTests = true

        options.win_tool_path = "C:\\Program Files\\Side Effects Software\\Houdini ${options.houdiniVersions[0]}"
        options.osx_tool_path = "/Applications/Houdini/Houdini${options.houdiniVersions[0]}/Frameworks/Houdini.framework/Versions/Current/Resources"
        options.unix_tool_path = "Houdini/hfs${options.houdiniVersions[0]}"
    // manual job
    } else if (options.forceBuild) {
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "Smoke.json"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "Smoke.json"
        } else  {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "Smoke.json"
        }
    }

    if (!options['isPreBuilt']) {
        dir('RadeonProRenderUSD') {
            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, prBranchName: options.prBranchName, prRepoName: options.prRepoName, disableSubmodules: true)
            }

            options.commitAuthor = utils.getBatOutput(this, "git show -s --format=%%an HEAD ")
            options.commitMessage = utils.getBatOutput(this, "git log --format=%%B -n 1")
            options.commitSHA = utils.getBatOutput(this, "git log --format=%%H -1 ")
            println """
                The last commit was written by ${options.commitAuthor}.
                Commit message: ${options.commitMessage}
                Commit SHA: ${options.commitSHA}
            """

            withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                options.majorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MAJOR_VERSION "', '')
                options.minorVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_MINOR_VERSION "', '')
                options.patchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', '')
                options.pluginVersion = "${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"

                if (options['incrementVersion']) {
                    withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
                        GithubNotificator githubNotificator = new GithubNotificator(this, options)
                        githubNotificator.init(options)
                        options["githubNotificator"] = githubNotificator
                        githubNotificator.initPreBuild("${BUILD_URL}")
                        options.projectBranchName = githubNotificator.branchName
                    }

                    if (env.BRANCH_NAME == "master" && options.commitAuthor != "radeonprorender") {
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        println "[INFO] Current build version: ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"

                        newVersion = version_inc(options.patchVersion, 1, ' ')
                        println "[INFO] New build version: ${newVersion}"

                        version_write("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', newVersion, '')
                        options.patchVersion = version_read("${env.WORKSPACE}\\RadeonProRenderUSD\\cmake\\defaults\\Version.cmake", 'set(HD_RPR_PATCH_VERSION "', '')
                        options.pluginVersion = "${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"
                        println "[INFO] Updated build version: ${options.patchVersion}"

                        bat """
                            git add cmake/defaults/Version.cmake
                            git commit -m "buildmaster: version update to ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}"
                            git push origin HEAD:master
                        """

                        //get commit's sha which have to be build
                        options['projectBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
                        println "[INFO] Project branch hash: ${options.projectBranch}"
                    }
                } else {
                    options.projectBranchName = options.projectBranch
                }

                currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
                currentBuild.description += "<b>Version:</b> ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}<br/>"
                currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
                currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
                currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
            }
        }
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_houdini') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            }
            options['testsBranch'] = utils.getBatOutput(this, "git log --format=%%H -1 ")
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

                options.houdiniVersions.each { houdiniVersion ->
                    options.engines.each { engine ->
                        groupNames.each { testGroup ->
                            tests << "${testGroup}-${houdiniVersion}_${engine}"
                        }
                    }
                }

            } else if (options.tests) {
                def groupNames = options.tests.split() as List

                groupNames = utils.uniteSuites(this, "jobs/weights.json", groupNames)
                groupNames.each() {
                    def xmlTimeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
                    options.timeouts["${it}"] = (xmlTimeout > 0) ? xmlTimeout : options.TEST_TIMEOUT
                }

                options.houdiniVersions.each { houdiniVersion ->
                    options.engines.each { engine ->
                        groupNames.each { testGroup ->
                            tests << "${testGroup}-${houdiniVersion}_${engine}"
                        }
                    }
                }

            } else {
                options.executeTests = false
            }
        }

        print("[INFO] Tests: ${tests}")
        println "[INFO] Timeouts: ${options.timeouts}"

        options.buildsList = options.houdiniVersions
        options.testsList = tests

        // make lists of raw profiles and lists of beautified profiles (displaying profiles)
        multiplatform_pipeline.initProfiles(options)

        if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
            options.reportUpdater = new ReportUpdater(this, env, options)
            options.reportUpdater.init(this.&getReportBuildArgs)
        }

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }

        if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
            // if something was merged into master branch it could trigger build in master branch of autojob
            hybrid_to_blender_workflow.clearOldBranches("RadeonProRenderUSD", PROJECT_REPO, options)
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList, String testProfile) {
    String[] profileParts = testProfile.split("_")
    String toolVersion = profileParts[0]
    String engine = profileParts[1]

    try {
        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report for ${testProfile}", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
            List lostStashes = []

            dir("summaryTestResults") {
                testResultList.each() {
                    if (it.endsWith(testProfile)) {
                        List testNameParts = it.replace("testResult-", "").split("-") as List
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
                    bat "count_lost_tests.bat \"${lostStashes}\" .. ..\\summaryTestResults \"${options.splitTestsExecution}\" \"${options.testsPackage}\" \"[]\" \"${testProfile}\" \"{}\""
                }
            } catch (e) {
                println("[ERROR] Can't generate number of lost tests")
            }

            try {
                boolean useTrackedMetrics = (env.JOB_NAME.contains("Weekly") || (env.JOB_NAME.contains("Manual")))
                boolean saveTrackedMetrics = env.JOB_NAME.contains("Weekly")
                String[] toolVersionParts = toolVersion.split("\\.")
                String metricsProfileDir = "${toolVersionParts[0]}.${toolVersionParts[1]}_${engine}"
                String metricsRemoteDir = "/volume1/Baselines/TrackedMetrics/USD-Houdini/${metricsProfileDir}"
                GithubNotificator.updateStatus("Deploy", "Building test report for ${testProfile}", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")

                if (useTrackedMetrics) {
                    utils.downloadMetrics(this, "summaryTestResults/tracked_metrics", "${metricsRemoteDir}/")
                }

                withEnv(["JOB_STARTED_TIME=${options.JOB_STARTED_TIME}", "BUILD_NAME=${options.baseBuildName}"]) {
                    dir("jobs_launcher") {
                        options.branchName = options.projectBranch ?: env.BRANCH_NAME

                        if (!options["isPreBuilt"]) {
                            options.commitMessage = options.commitMessage.replace("'", "")
                            options.commitMessage = options.commitMessage.replace('"', '')
                        }

                        def retryInfo = JsonOutput.toJson(options.nodeRetry)
                        dir("..\\summaryTestResults") {
                            writeJSON file: 'retry_info.json', json: JSONSerializer.toJSON(retryInfo, new JsonConfig()), pretty: 4
                        }
                        def tool = "Houdini ${testProfile}"
                        bat "build_reports.bat ..\\summaryTestResults ${getReportBuildArgs(testProfile, options, utils.escapeCharsByUnicode(tool))}"
                        bat "get_status.bat ..\\summaryTestResults"
                    }
                }

                if (saveTrackedMetrics) {
                    utils.uploadMetrics(this, "summaryTestResults/tracked_metrics", metricsRemoteDir)
                }
            } catch (e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report for ${testProfile}", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println """
                        [ERROR] Failed to build test report.
                        ${e.toString()}
                    """
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                                "Test Report ${testProfile}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
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
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                summaryTestResults = [passed: summaryReport.passed, failed: summaryReport.failed, error: summaryReport.error]
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
                println """
                    [ERROR] CAN'T GET TESTS STATUS
                    ${e.toString()}
                """
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            try {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            } catch(e) {
                println e.toString()
                options.testsStatus = ""
            }

            withNotifications(title: "Building test report for ${testProfile}", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    "Test Report ${testProfile}", "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName, "updatable": options.containsKey("reportUpdater")])

                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${testProfile}", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report for ${testProfile}", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }

        if (env.BRANCH_NAME && env.BRANCH_NAME.startsWith(hybrid_to_blender_workflow.BRANCH_NAME_PREFIX)) {
            hybrid_to_blender_workflow.createBlenderBranch(options)
        }
    } catch (e) {
        println e.toString()
        throw e
    }
}


def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms) {
        filteredPlatforms +=  ";" + platform
    } else {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}


def call(String projectRepo = PROJECT_REPO,
        String projectBranch = "",
        String testsBranch = "master",
        String platforms = 'Windows:AMD_RX6800XT,AMD_680M,AMD_WX9100,AMD_RX7900XT;OSX:AMD_RX5700XT;Ubuntu20:AMD_RX6700XT',
        String houdiniVersions = "19.0.622,19.5.534",
        String updateRefs = 'No',
        String testsPackage = "Smoke.json",
        String tests = "",
        String enginesNames = "Northstar",
        String width = "0",
        String height = "0",
        String minSamples = "30",
        String maxSamples = "50",
        String threshold = "0.05",
        Boolean enableRIFTracing = false,
        Boolean enableRPRTracing = false,
        String tester_tag = "Houdini",
        Boolean splitTestsExecution = true,
        Boolean incrementVersion = true,
        String parallelExecutionTypeString = "TakeOneNodePerGPU",
        Boolean forceBuild = false,
        String customBuildLinkWindows = "",
        String customBuildLinkUbuntu20 = "",
        String customBuildLinkMacOS = "",
        String mergeablePR = "") {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            Integer gpusCount = platforms.split(";").sum {
                platforms.tokenize(":").with {
                    (it.size() > 1) ? it[1].split(",").size() : 0
                }
            }

            def enginesNamesList = enginesNames.split(",") as List
            
            Boolean isPreBuilt = customBuildLinkWindows || customBuildLinkMacOS || customBuildLinkUbuntu20

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
                            if (customBuildLinkMacOS) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                            break
                        // Ubuntu20
                        default:
                            if (customBuildLinkUbuntu20) {
                                filteredPlatforms = appendPlatform(filteredPlatforms, platform)
                            }
                        }
                }

                platforms = filteredPlatforms
            }

            withNotifications(options: options, configuration: NotificationConfiguration.HOUDINI_VERSIONS_PARAM) {
                if (isPreBuilt && (houdiniVersions.split(",") as List).size() > 1) {
                    throw new Exception()
                }
            }

            def parallelExecutionType = TestsExecutionType.valueOf(parallelExecutionTypeString)

            String prRepoName = ""
            String prBranchName = ""
            if (mergeablePR) {
                String[] prInfo = mergeablePR.split(";")
                prRepoName = prInfo[0]
                prBranchName = prInfo[1]
            }

            options << [configuration: PIPELINE_CONFIGURATION,
                        projectRepo: projectRepo,
                        projectBranch: projectBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_houdini.git",
                        testsBranch: testsBranch,
                        assetsName: "usd_houdini_autotests",
                        updateRefs: updateRefs,
                        PRJ_NAME: "RadeonProRenderUSDPlugin",
                        PRJ_ROOT: "rpr-plugins",
                        BUILDER_TAG: 'BuilderHoudini',
                        TESTER_TAG: tester_tag,
                        incrementVersion: incrementVersion,
                        testsPackage: testsPackage,
                        tests: tests.replace(',', ' '),
                        forceBuild: forceBuild,
                        reportName: 'Test_20Report',
                        splitTestsExecution: splitTestsExecution,
                        BUILD_TIMEOUT: 45,
                        TEST_TIMEOUT: 180,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:180,
                        houdiniVersions: houdiniVersions.split(",") as List,
                        gpusCount: gpusCount,
                        width: width,
                        height: height,
                        minSamples: minSamples,
                        maxSamples: maxSamples,
                        threshold: threshold,
                        engines: enginesNamesList,
                        enableRIFTracing:enableRIFTracing,
                        enableRPRTracing:enableRPRTracing,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        parallelExecutionType: parallelExecutionType,
                        parallelExecutionTypeString: parallelExecutionTypeString,
                        storeOnNAS: true,
                        flexibleUpdates: true,
                        finishedBuildStages: new ConcurrentHashMap(),
                        customBuildLinkWindows: customBuildLinkWindows,
                        customBuildLinkUbuntu20: customBuildLinkUbuntu20,
                        customBuildLinkOSX: customBuildLinkMacOS,
                        isPreBuilt:isPreBuilt,
                        prRepoName:prRepoName,
                        prBranchName:prBranchName,
                        notificationsTitlePrefix: "HOUDINI"
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
