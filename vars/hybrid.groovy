import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/RPRHybrid.git"
@Field final String FT_REPO = "git@github.com:luxteam/jobs_test_core.git"


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
        options.updateUTRefs = true
        options.updatePTRefs = true
        options.updateFTRefs = "Update"
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


def executeDeploy(Map options, List platformList, List testResultList) {
    String testPlatforms = getTestPlatforms(options)

    String links = ""

    if (testPlatforms) {
        if (env.BRANCH_NAME == "master" && testPlatforms.contains("Windows")) {
            build(job: "HybridProMTLX-Auto/master", wait: false)
            build(job: "HybridUEAuto/VictorianTrainsAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ToyShopAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ShooterGameAuto/rpr_master", wait: false)
        }

        build(
            job: env.JOB_NAME.replace("Build", "UT"),
            parameters: [
                string(name: "PipelineBranch", value: options.pipelineBranch),
                string(name: "OriginalBuildLink", value: env.BUILD_URL),
                string(name: "Platforms", value: testPlatforms),
                string(name: "ApiValues", value: options.apiValues),
                booleanParam(name: "UpdateRefs", value: options.updateUTRefs)
            ],
            wait: false,
            quietPeriod : 0
        )

        String utLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "UT"), "UNIT TESTS")

        build(
            job: env.JOB_NAME.replace("Build", "PT"),
            parameters: [
                string(name: "PipelineBranch", value: options.pipelineBranch),
                string(name: "CommitSHA", value: options.commitSHA),
                string(name: "OriginalBuildLink", value: env.BUILD_URL),
                string(name: "Platforms", value: testPlatforms),
                string(name: "Scenarios", value: options.scenarios),
                booleanParam(name: "UpdateRefs", value: options.updatePTRefs)
            ],
            wait: false,
            quietPeriod : 0
        )

        String ptLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "PT"), "PERF TESTS")

        build(
            job: env.JOB_NAME.replace("Build", "FT"),
            parameters: [
                string(name: "PipelineBranch", value: options.pipelineBranch),
                string(name: "CommitSHA", value: options.commitSHA),
                string(name: "ProjectBranchName", value: options.projectBranch),
                string(name: "OriginalBuildLink", value: env.BUILD_URL),
                string(name: "TestsBranch", value: options.testsBranch),
                string(name: "Platforms", value: testPlatforms),
                string(name: "UpdateRefs", value: options.updateFTRefs)
            ],
            wait: false,
            quietPeriod : 0
        )

        String ftLink = saveTriggeredBuildLink(env.JOB_URL.replace("Build", "FT"), "FUNCTIONAL TESTS")

        links += "\\n Unit tests build: ${utLink}"
        links += "\\n Performance tests build: ${ptLink}"
        links += "\\n Functional tests build: ${ftLink}"
    }

    // set error statuses for PR, except if current build has been superseded by new execution
    if (env.CHANGE_ID && !currentBuild.nextBuild) {
        // if jobs was aborted or crushed remove pending status for unfinished stages
        GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")

        String status = currentBuild.result ?: "success"
        status = status.toLowerCase()
        String commentMessage = status == "success" ? "\\n Autotests will be launched soon" : "\\nAutotests won't be launched"

        if (links) {
            commentMessage += links
        }

        String commitUrl = "${options.githubNotificator.repositoryUrl}/commit/${options.githubNotificator.commitSHA}"
        GithubNotificator.sendPullRequestComment("[PROJECT BUILDING] Building for ${commitUrl} finished as ${status} ${commentMessage}", options)
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
            currentBuild.result = "FAILED"
        }
    }

    return testPlatforms.join(";")
}


def call(String pipelineBranch = "master",
         String projectBranch = "",
         String testsBranch = "master",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String apiValues = "vulkan,d3d12",
         String scenarios = "all",
         Boolean updateUTRefs = false,
         Boolean updatePTRefs = false,
         String updateFTRefs = "No",
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
               testsBranch:testsBranch,
               apiValues:apiValues,
               scenarios:scenarios,
               updateUTRefs:updateUTRefs,
               updatePTRefs:updatePTRefs,
               updateFTRefs:updateFTRefs,
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
