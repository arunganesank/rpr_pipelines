import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/RPRHybrid.git"
@Field final String SDK_REPO = "git@github.com:luxteam/jobs_test_core.git"
@Field final String MTLX_REPO = "git@github.com:luxteam/jobs_test_hybrid_mtlx.git"


def getArtifactName(String osName) {
    switch (osName) {
        case "Windows": 
            return "BaikalNext_Build-${osName}.zip"
        default: 
            return "BaikalNext_Build-${osName}.tar.xz"
    }
}


def downloadAgilitySDK() {
    String agilitySDKLink = "https://www.nuget.org/api/v2/package/Microsoft.Direct3D.D3D12/1.706.4-preview"
    String archiveName = "AgilitySDK.zip"

    bat """
        curl --retry 5 -L -J -o "${archiveName}" "${agilitySDKLink}"
    """

    unzip dir: "AgilitySDK", glob: "", zipFile: archiveName

    return "${pwd()}\\AgilitySDK\\build\\native"
}


def executeBuildWindows(Map options) {
    String agilitySDKLocation = downloadAgilitySDK()

    withEnv(["AGILITY_SDK=${agilitySDKLocation}"]) {
        String buildType = options["cmakeKeys"].contains("-DCMAKE_BUILD_TYPE=Debug") ? "Debug" : "Release"
        bat """
            echo %AGILITY_SDK%
            mkdir Build
            cd Build
            cmake ${options['cmakeKeys']} -G "Visual Studio 15 2017 Win64" .. >> ..\\${STAGE_NAME}.log 2>&1
            cmake --build . --target PACKAGE --config ${buildType} >> ..\\${STAGE_NAME}.log 2>&1
            rename BaikalNext.zip BaikalNext_${STAGE_NAME}.zip
        """

        dir("Build/bin/${buildType}") {
            downloadFiles("/volume1/CIS/bin-storage/Hybrid/dxcompiler.dll", ".")
        }

        dir("Build") {
            dir("BaikalNext/bin") {
                bat """
                    xcopy /s/y/i ..\\..\\bin\\Release\\dxcompiler.dll .
                """
            }

            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " BaikalNext_${STAGE_NAME}.zip BaikalNext\\bin\\dxcompiler.dll")
        }

        if (env.BRANCH_NAME == "material_x") {
            withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.UPDATE_BINARIES) {

                hybrid_vs_northstar.updateBinaries(
                    newBinaryFile: "Build\\_CPack_Packages\\win64\\ZIP\\BaikalNext\\bin\\HybridPro.dll", 
                    targetFileName: "HybridPro.dll", osName: "Windows", compareChecksum: true
                )
            }
        }
    }
}


def executeBuildLinux(Map options) {
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 8 >> ../${STAGE_NAME}.log 2>&1
        make package >> ../${STAGE_NAME}.log 2>&1
        mv BaikalNext.tar.xz BaikalNext_${STAGE_NAME}.tar.xz
    """
}


def executeBuild(String osName, Map options) {
    String error_message = ""
    String context = "[BUILD] ${osName}"
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

        outputEnvironmentInfo(osName)
        
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            GithubNotificator.updateStatus("Build", osName, "in_progress", options, "Checkout has been finished. Trying to build...")
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                default:
                    executeBuildLinux(options)
            }
        }

        dir("Build") {
            makeStash(includes: "BaikalNext_${STAGE_NAME}*", name: "app${osName}", storeOnNAS: options.storeOnNAS)
        }
    } catch (e) {
        println(e.getMessage())
        error_message = e.getMessage()
        throw e
    } finally {
        archiveArtifacts "*.log"

        String artifactName = getArtifactName(osName)

        dir("Build") {
            makeArchiveArtifacts(name: artifactName, storeOnNAS: options.storeOnNAS)
        }

        String status = error_message ? "action_required" : "success"
        GithubNotificator.updateStatus("Build", osName, status, options, "Build finished as '${status}'", "${env.BUILD_URL}/artifact/${STAGE_NAME}.log")
    }
}

def executePreBuild(Map options) {
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    if ((commitMessage.contains("[CIS:GENREFALL]") || commitMessage.contains("[CIS:GENREF]")) && env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.updateUnitRefs = true
        options.updatePerfRefs = true
        options.updateSdkRefs = "Update"
        options.updateMtlxRefs = "Update"
        println("[CIS:GENREF] or [CIS:GENREFALL] have been found in comment")
    }

    if (env.CHANGE_URL) {
        println("Build was detected as Pull Request")
    }

    options.commitMessage = []
    commitMessage = commitMessage.split('\r\n')
    commitMessage[2..commitMessage.size()-1].collect(options.commitMessage) { it.trim() }
    options.commitMessage = options.commitMessage.join('\n')

    println "Commit list message: ${options.commitMessage}"
    
    dir("RprPerfTest") {
        String archiveName = "scenarios.zip"
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " ${archiveName} -aoa")
        makeArchiveArtifacts(name: archiveName, storeOnNAS: options.storeOnNAS)
    }

    if (env.BRANCH_NAME && (env.BRANCH_NAME == "master" || env.BRANCH_NAME.contains("-rc") || env.BRANCH_NAME.contains("release"))) {
        options.mtlxTestsPackage = "Full.json"
    }

    // set pending status for all
    if (env.CHANGE_ID) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
        }
        options["platforms"].split(";").each() { platform ->
            List tokens = platform.tokenize(":")
            String osName = tokens.get(0)
            // Statuses for builds
            GithubNotificator.createStatus("Build", osName, "queued", options, "Scheduled", "${env.JOB_URL}")
        }
    }
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def saveTriggeredBuildLink(String jobUrl, String testsName) {
    String url = "${jobUrl}/api/json?tree=lastBuild[number,url]"

    def rawInfo = httpRequest(
        url: url,
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    def parsedInfo = parseResponse(rawInfo.content)

    rtp(nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${parsedInfo.lastBuild.url}">[${testsName}] This build triggered a new build with tests</a></h3>""")

    return parsedInfo.lastBuild.url
}


def checkBuildResult(String buildUrl) {
    def rawInfo = httpRequest(
        url: "${buildUrl}/api/json?tree=result,description",
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    def parsedInfo = parseResponse(rawInfo.content)

    return parsedInfo
}


def awaitBuildFinishing(String buildUrl, String testsName, String reportLink) {
    waitUntil({checkBuildResult(buildUrl).result != null}, quiet: true)

    String buildResult = checkBuildResult(buildUrl)
    currentBuild.result = buildResult.result

    if (buildResult == "FAILURE") {
        currentBuild.description += "<span style='color: #7d6608'>${testsName} finished as Failed. Check <a href='reportLink'>test report</a> for more details</span><br/>"
    } else if (buildResult == "UNSTABLE") {
        currentBuild.description += "<span style='color: #641e16'>${testsName} finished as Unstable. Check <a href='reportLink'>test report</a> for more details</span><br/>"
    }

    currentBuild.description += buildResult.description
    buildResult.description += "<br/>"
}


def executeDeploy(Map options, List platformList, List testResultList) {
    String testPlatforms = getTestPlatforms(options)

    if (testPlatforms) {
        if (env.BRANCH_NAME == "master" && testPlatforms.contains("Windows")) {
            build(job: "HybridUEAuto/VictorianTrainsAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ToyShopAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ShooterGameAuto/rpr_master", wait: false)
        }

        String unitLink
        String perfLink
        String rprSdkLink
        String mtlxLink

        build(
            if (options.apiValues) {
                job: env.JOB_NAME.replace("Build", "Unit"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "ApiValues", value: options.apiValues),
                    booleanParam(name: "UpdateRefs", value: options.updateUnitRefs)
                ],
                wait: false,
                quietPeriod : 0
            }

            unitLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "Unit"), "UNIT TESTS")
        )

        if (options.scenarios) {
            build(
                job: env.JOB_NAME.replace("Build", "Perf"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "Scenarios", value: options.scenarios),
                    booleanParam(name: "UpdateRefs", value: options.updatePerfRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            perfLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "Perf"), "PERF TESTS")
        }

        if (options.rprSdkTestsPackage != "none") {
            build(
                job: env.JOB_NAME.replace("Build", "SDK"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "ProjectBranchName", value: options.projectBranch),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "TestsBranch", value: options.rprSdkTestsBranch),
                    string(name: "TestsPackage", value: options.rprSdkTestsPackage),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "UpdateRefs", value: options.updateSdkRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            rprSdkLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "SDK"), "RPR SDK TESTS")
        }

        if (options.mtlxTestsPackage != "none") {
            build(
                job: env.JOB_NAME.replace("Build", "MTLX"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "ProjectBranchName", value: options.projectBranch),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "TestsBranch", value: options.mtlxTestsBranch),
                    string(name: "TestsPackage", value: options.mtlxTestsPackage),
                    string(name: "Tests", value: ""),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "UpdateRefs", value: options.updateMtlxRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            mtlxLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "MTLX"), "MATERIALX TESTS")
        }

        if (unitLink) {
            String reportLink = "${unitLink}/testReport"
            awaitBuildFinishing(unitLink, "Unit tests", reportLink)
        }
        if (perfLink) {
            String reportLink = "${unitLink}/Performance_20Tests_20Report"
            awaitBuildFinishing(perfLink, "Performance tests", reportLink)
        }
        if (rprSdkLink) {
            String reportLink = "${unitLink}/Test_20Report_20HybridPro"
            awaitBuildFinishing(rprSdkLink, "RPR SDK tests", reportLink)
        }
        if (mtlxLink) {
            String reportLink = "${unitLink}/Test_20Report"
            awaitBuildFinishing(mtlxLink, "MaterialX tests", reportLink)
        }
    }

    // set error statuses for PR, except if current build has been superseded by new execution
    if (env.CHANGE_ID && !currentBuild.nextBuild) {
        // if jobs was aborted or crushed remove pending status for unfinished stages
        GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")
    }
}


def getTestPlatforms(Map options) {
    List platformsByOS = options.originalPlatforms.split(";") as List

    List testPlatforms = []

    for (entry in options["finishedBuildStages"]) {
        if (entry.value["successfully"]) {
            for (platforms in platformsByOS) {
                if (platforms.startsWith(entry.key)) {
                    testPlatforms.add(platforms)
                    break
                }
            }
        } else {
            currentBuild.result = "FAILURE"
        }
    }

    return testPlatforms.join(";")
}


def call(String pipelineBranch = "master",
         String projectBranch = "",
         String rprSdkTestsBranch = "master",
         String mtlxTestsBranch = "master",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String apiValues = "vulkan,d3d12",
         String scenarios = "all",
         String rprSdkTestsPackage = "Full.json",
         String mtlxTestsPackage = "regression.json",
         Boolean updateUnitRefs = false,
         Boolean updatePerfRefs = false,
         String updateSdkRefs = "No",
         String updateMtlxRefs = "No",
         String cmakeKeys = "-DCMAKE_BUILD_TYPE=Release -DBAIKAL_ENABLE_RPR=ON -DBAIKAL_NEXT_EMBED_KERNELS=ON") {

    List apiList = apiValues.split(",") as List

    println "[INFO] Testing APIs: ${apiList}"

    currentBuild.description = ""

    def processedPlatforms = []

    platforms.split(';').each() { platform ->
        List tokens = platform.tokenize(':')
        String platformName = tokens.get(0)
        processedPlatforms.add(platformName)
    }

    processedPlatforms = processedPlatforms.join(";")

    options = [platforms:processedPlatforms,
               originalPlatforms:platforms,
               pipelineBranch:pipelineBranch,
               projectBranch:projectBranch,
               rprSdkTestsBranch:rprSdkTestsBranch,
               mtlxTestsBranch:mtlxTestsBranch,
               apiValues:apiValues,
               scenarios:scenarios,
               rprSdkTestsPackage:rprSdkTestsPackage,
               mtlxTestsPackage:mtlxTestsPackage,
               updateUnitRefs:updateUnitRefs,
               updatePerfRefs:updatePerfRefs,
               updateSdkRefs:updateSdkRefs,
               updateMtlxRefs:updateMtlxRefs,
               PRJ_NAME:"HybridPro",
               PRJ_ROOT:"rpr-core",
               projectRepo:PROJECT_REPO,
               BUILDER_TAG:"HybridBuilder",
               executeBuild:true,
               executeTests:false,
               forceDeploy:true,
               cmakeKeys:cmakeKeys,
               storeOnNAS: true,
               finishedBuildStages: new ConcurrentHashMap()]

    multiplatform_pipeline(processedPlatforms, this.&executePreBuild, this.&executeBuild, null, this.&executeDeploy, options)
}
