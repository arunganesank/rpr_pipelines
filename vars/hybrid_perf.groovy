import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


def createPerfDirs() {
    if (isUnix()) {
        sh """
            mkdir -p Scenarios
            mkdir -p Telemetry
            mkdir -p Metrics
            mkdir -p Reports
            mkdir -p References
        """
    } else {
        bat """
            if not exist Scenarios mkdir Scenarios
            if not exist Telemetry mkdir Telemetry
            if not exist Metrics mkdir Metrics
            if not exist Reports mkdir Reports
            if not exist References mkdir References
        """
    }
}


def executeGenPerfTestRefCommand(String asicName, String osName, Map options) {
    dir('BaikalNext/RprPerfTest') {
        createPerfDirs()
        switch(osName) {
            case 'Windows':
                python3("ScenarioPlayer.py -s ${options.scenarios} -E ..\\bin >> ..\\..\\${STAGE_NAME}.perf.log 2>&1", "39")
                break
            case 'OSX':
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
                break
            default:
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
        }
    }
}


def executePerfTestCommand(String asicName, String osName, Map options) {
    dir('BaikalNext/RprPerfTest') {
        createPerfDirs()
        switch(osName) {
            case 'Windows':
                python3("ScenarioPlayer.py -s ${options.scenarios} -E ..\\bin -P >> ..\\..\\${STAGE_NAME}.perf.log 2>&1", "39")
                break
            case 'OSX':
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin -P >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
                break
            default:
                withEnv(["LD_LIBRARY_PATH=../bin:\$LD_LIBRARY_PATH"]) {
                    python3("ScenarioPlayer.py -s ${options.scenarios} -E ../bin -P >> ../../${STAGE_NAME}.perf.log 2>&1", "3.9")
                }
        }
    }
}


def executePerfTests(String osName, String asicName, Map options) {
    String errorMessage = ""
    String REF_PATH_PROFILE

    REF_PATH_PROFILE="/volume1/Baselines/rpr_hybrid_autotests/perf/${asicName}-${osName}"
    outputEnvironmentInfo(osName, "${STAGE_NAME}.perf")
    
    try {
        String assetsDir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_hybrid_autotests_assets" : "/mnt/c/TestResources/rpr_hybrid_autotests_assets"
        downloadFiles("/volume1/web/Assets/rpr_hybrid_autotests/", assetsDir)

        String binaryName = hybrid.getArtifactName(osName)
        String scenariosName = "scenarios.zip"
        String binaryPath = "/volume1/web/${options.originalBuildLink.split('/job/', 2)[1].replace('/job/', '/')}Artifacts/${binaryName}"
        String scenariosPath = "/volume1/web/${options.originalBuildLink.split('/job/', 2)[1].replace('/job/', '/')}Artifacts/${scenariosName}"
        downloadFiles(binaryPath, ".")

        switch(osName) {
            case "Windows":
                bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " ${binaryName} -aoa")
                break
            default:
                sh "tar -xJf ${binaryName}"
        }

        dir("BaikalNext") {
            dir("RprPerfTest") {
                downloadFiles(scenariosPath, ".")
                utils.unzip(this, scenariosName)
            }

            dir("RprPerfTest/Scenarios") {
                if (options.scenarios == "all") {
                    List scenariosList = []
                    def files = findFiles(glob: "*.json")
                    for (file in files) {
                        scenariosList << file.name
                    }
                    options.scenarios = scenariosList.join(" ")
                }
                for (scenarioName in options.scenarios.split()) {
                    def scenarioContent = readJSON file: scenarioName
                    // check that it's scene path not a case which is implemented programmatically
                    if (scenarioContent["scene_name"].contains("/")) {
                        String[] scenePathParts = scenarioContent["scene_name"].split("/")
                        scenarioContent["scene_name"] = assetsDir.replace("\\", "/") + "/" + scenePathParts[-2] + "/" + scenePathParts[-1]
                        JSON serializedJson = JSONSerializer.toJSON(scenarioContent, new JsonConfig());
                        writeJSON file: scenarioName, json: serializedJson, pretty: 4
                    }
                }
            }
        }

        if (options["updateRefs"]) {
            println "Updating references for performance tests"
            executeGenPerfTestRefCommand(asicName, osName, options)
            uploadFiles('./BaikalNext/RprPerfTest/Telemetry/', REF_PATH_PROFILE)
        } else {
            println "Execute Tests"
            downloadFiles("${REF_PATH_PROFILE}/", "./BaikalNext/RprPerfTest/References/")
            executePerfTestCommand(asicName, osName, options)
        }
    } catch (e) {
        println(e.getMessage())
        errorMessage = e.getMessage()
        currentBuild.result = "UNSTABLE"
    } finally {
        archiveArtifacts "*.log"

        dir("BaikalNext/RprPerfTest/Reports") {
            makeStash(includes: "*.json", name: "testPerfResult-${asicName}-${osName}", allowEmpty: true, storeOnNAS: options.storeOnNAS)

            // check results
            if (!options.updateRefs) {
                List reportsList = []
                def reports = findFiles(glob: "*.json")
                Boolean cliffDetected
                loop: for (report in reports) {
                    def reportContent = readJSON file: report.name
                    for (metric in reportContent) {
                        if (metric.value["Cliff_detected"]) {
                            cliffDetected = true
                            options.successfulTests["cliff_detected"] = true
                        } else if (metric.value["Unexpected_acceleration"]) {
                            unexpectedAcceleration = true
                            options.successfulTests["unexpected_acceleration"] = true
                        }
                    }
                }
                if (cliffDetected) {
                    currentBuild.result = "UNSTABLE"
                }
                if (cliffDetected) {
                    errorMessage += " Testing finished with 'cliff detected'."
                }

                errorMessage = errorMessage.trim()
            }
        }

        if (env.BRANCH_NAME) {
            String title = "${asicName}-${osName}"
            String description = errorMessage ? "Error: ${errorMessage}" : "Testing finished"
            String status = errorMessage ? "action_required" : "success"
            String url = "${env.BUILD_URL}/artifact/${STAGE_NAME}.perf.log"
            GithubNotificator.updateStatus(options.customStageName, title, status, options, description, url)
        }
    }
}


def changeWinDevMode(Boolean turnOn) {
    String value = turnOn ? "1" : "0"

    powershell """
        reg add "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\AppModelUnlock" /t REG_DWORD /f /v "AllowDevelopmentWithoutDevLicense" /d "${value}"
    """

    utils.reboot(this, "Windows")
}


def executeTests(String osName, String asicName, Map options) {
    GithubNotificator.updateStatus(options.customStageName, "${asicName}-${osName}", "in_progress", options, "In progress...")

    if (osName == "Windows") {
        changeWinDevMode(true)
    }

    Boolean someStageFail = false 

    try {
        executePerfTests(osName, asicName, options)
    } catch (e) {
        someStageFail = true
        println(e.toString())
        println(e.getMessage())
    }

    if (osName == "Windows") {
        changeWinDevMode(false)
    }

    if (someStageFail) {
        // send error signal for mark stage as failed
        error "Error during tests execution"
    }
}


def executePreBuild(Map options) {
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
                    if (options.scenarios) {
                        // Statuses for performance tests
                        GithubNotificator.createStatus(options.customStageName, "${gpuName}-${osName}", "queued", options, "Scheduled", "${env.JOB_URL}")
                    }
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
            if (options.scenarios && !options["updateRefs"]) {
                checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/HTMLReportsShared")

                dir("performanceReports") {
                    testResultList.each() {
                        try {
                            dir("${it}".replace("testResult-", "")) {
                                makeUnstash(name: "${it.replace('testResult-', 'testPerfResult-')}", storeOnNAS: options.storeOnNAS)
                            }
                        }
                        catch(e) {
                            echo "[ERROR] Can't unstash ${it.replace('testResult-', 'testPerfResult-')}"
                            println(e.toString());
                            println(e.getMessage());
                        }
                    }
                }

                python3("-m pip install --user -r requirements.txt")
                python3("hybrid_perf_report.py --json_files_path \"performanceReports\"")

                utils.publishReport(this, "${BUILD_URL}", "PerformanceReport", "performace_report.html", "Performance Tests Report", "Performance Tests Report",
                    options.storeOnNAS, ["jenkinsBuildUrl": BUILD_URL, "jenkinsBuildName": currentBuild.displayName])
            }
        } catch(e) {
            println(e.toString())
        }
    }
}


def call(String commitSHA = "",
         String commitMessage = "",
         String originalBuildLink = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,NVIDIA_RTX4080,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX7900XTX,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String scenarios = "all",
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

    Map successfulTests = ["cliff_detected": false, "unexpected_acceleration": false]

    multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, this.&executeDeploy,
                           [platforms:platforms,
                            commitSHA:commitSHA,
                            commitMessage:commitMessage,
                            originalBuildLink:originalBuildLink,
                            updateRefs:updateRefs,
                            PRJ_NAME:"HybridProPerf",
                            PRJ_ROOT:"rpr-core",
                            projectRepo:hybrid.PROJECT_REPO,
                            executeBuild:false,
                            executeTests:true,
                            storeOnNAS: true,
                            finishedBuildStages: new ConcurrentHashMap(),
                            scenarios: scenarios,
                            customStageName: "Test-Perf"])
}