import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


@Field final String PROJECT_REPO = "https://github.com/Radeon-Pro/RPRHybrid.git"
@Field final String HTTP_PROJET_REPO = "https://github.com/Radeon-Pro/RPRHybrid"
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


def makeRelease(Map options) {
    def releases = options["githubApiProvider"].getReleases(HTTP_PROJET_REPO)
    boolean releaseExists = false

    // find and delete existing release if it exists
    for (release in releases) {
        if (release['tag_name'] == env.TAG_NAME) {
            println("[INFO] Previous release found. Delete existing assets from it")

            releaseExists = true
            options["release_id"] = "${release.id}"

            // remove existing assets
            def assets = options["githubApiProvider"].getAssets(HTTP_PROJET_REPO, "${release.id}")

            for (asset in assets) {
                options["githubApiProvider"].removeAsset(HTTP_PROJET_REPO, "${asset.id}")
            }

            break
        }
    }

    if (!releaseExists) {
        def releaseInfo = options["githubApiProvider"].createRelease(HTTP_PROJET_REPO, env.TAG_NAME, "Version ${env.TAG_NAME}")
        options["release_id"] = "${releaseInfo.id}"
    }

    bat """
        mkdir release
        mkdir release\\Windows
        mkdir release\\Ubuntu
    """

    if (options["finishedBuildStages"].containsKey("Windows") && options["finishedBuildStages"]["Windows"]["successfully"]) {
        makeUnstash(name: "appWindows", storeOnNAS: options.storeOnNAS)
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " BaikalNext_Build-Windows.zip -aoa")
        utils.moveFiles(this, "Windows", "BaikalNext/bin/HybridPro.dll", "release/Windows/HybridPro.dll")
        utils.removeDir(this, "Windows", "BaikalNext")
    }

    if (options["finishedBuildStages"].containsKey("Ubuntu20") && options["finishedBuildStages"]["Ubuntu20"]["successfully"]) {
        makeUnstash(name: "appUbuntu20", storeOnNAS: options.storeOnNAS)
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " BaikalNext_Build-Ubuntu20.tar.xz -aoa")
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " BaikalNext_Build-Ubuntu20.tar -aoa")
        utils.moveFiles(this, "Windows", "BaikalNext/bin/HybridPro.so", "release/Ubuntu/HybridPro.so")
        utils.removeDir(this, "Windows", "BaikalNext")
    }

    dir("release") {
        String archiveName = "HybridPro.zip"
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${archiveName}\" .")
        options["githubApiProvider"].addAsset(HTTP_PROJET_REPO, options["release_id"], archiveName)
    }
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
            if (env.TAG_NAME) {
                // use stashed artifacts on deploy stage to upload them on GitHub release
                makeStash(includes: "BaikalNext_${STAGE_NAME}*", name: "app${osName}", storeOnNAS: options.storeOnNAS)
            }
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
    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
    }

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    if ((commitMessage.contains("[CIS:GENREFALL]") || commitMessage.contains("[CIS:GENREF]")) && env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.updateUnitRefs = true
        options.updatePerfRefs = true
        // do not update HybridPro MTLX and RPR SDK refs automatically
        options.updateSdkRefs = "No"
        options.updateMtlxRefs = "No"
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

    if (env.TAG_NAME) {
        options["githubApiProvider"] = new GithubApiProvider(this)
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

    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/><br/>"
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def getTriggeredBuildLink(String jobUrl) {
    String url = "${jobUrl}/api/json?tree=lastBuild[number,url]"

    withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
        url = url.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
    }

    def parsedInfo = doRequest(url)

    return parsedInfo.lastBuild.url
}


def getProblemsCount(String buildUrl, String testsName) {
    try {
        withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
            buildUrl = buildUrl.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
        }

        switch (testsName) {
            case "Unit":
                def parsedInfo = doRequest("${buildUrl}/testReport/api/json")
                return ["failed": parsedInfo["failCount"], "error": 0]
            case "Performance":
                // TODO: add implementation for Perf tests when they'll be fixed
                return ["failed": 0, "error": 0]
            case "RPR SDK":
                def parsedInfo = doRequest("${buildUrl}/artifact/summary_status.json")
                return ["failed": parsedInfo["failed"], "error": parsedInfo["error"]]
            case "MaterialX":
                def parsedInfo = doRequest("${buildUrl}/artifact/summary_status.json")
                return ["failed": parsedInfo["failed"], "error": parsedInfo["error"]]
            default: 
                println("[WARNING] Unexpected testsName '${testsName}'")
                return null
        }
    } catch(e) {
        return null
    }
}


def buildDescriptionContent(String testsName, String buildUrl, String reportLink, String logsLink) {
    def buildInfo = checkBuildResult(buildUrl)

    String statusDescription = ""

    if (buildInfo.inProgress) {
        statusDescription = "(autotests are in progress)"
    } else {
        currentBuild.result = buildInfo.result

        Map problems = getProblemsCount(buildUrl, testsName)

        if (problems) {
            if (problems["failed"] > 0 && problems["error"] > 0) {
                statusDescription = "(${problems.failed} failed / ${problems.error} error)"
            } else if (problems["failed"] > 0) {
                statusDescription = "(${problems.failed} failed)"
            } else if (problems["error"] > 0) {
                statusDescription = "(${problems.error} error)"
            }
        } else {
            statusDescription = "(could not receive autotests results)"
        }
    }

    if (buildInfo.result == "FAILURE") {
        return "${testsName} tests are Failed. <a href='${reportLink}'>Test report</a> / <a href='${logsLink}'>Logs link</a> ${statusDescription}"
    } else if (buildInfo.result == "UNSTABLE") {
        return "${testsName} tests are Unstable. <a href='${reportLink}'>Test report</a> / <a href='${logsLink}'>Logs link</a> ${statusDescription}"
    } else if (buildInfo.result == "SUCCESS") {
        return "${testsName} tests are Success. <a href='${reportLink}'>Test report</a> / <a href='${logsLink}'>Logs link</a> ${statusDescription}"
    } else {
        return "${testsName} tests with unexpected status. <a href='${reportLink}'>Test report</a> / <a href='${logsLink}'>Logs link</a> ${statusDescription}"
    }
}


def buildDescriptionLine(Map options, String buildUrl, String testsName) {
    String messageContent = ""

    switch (testsName) {
        case "Original":
            String reportLink = "${buildUrl}"
            String logsLink = "${options.unitLink}/artifact"
            messageContent = "Original build. <a href='${reportLink}'>Build link</a> / <a href='${logsLink}'>Logs link</a>"
            break
        case "Unit":
            String reportLink = "${buildUrl}/testReport"
            String logsLink = "${buildUrl}/artifact"
            messageContent = buildDescriptionContent(testsName, buildUrl, reportLink, logsLink)
            break
        case "Performance":
            String reportLink = "${buildUrl}/Performance_20Tests_20Report"
            String logsLink = "${buildUrl}/artifact"
            messageContent = buildDescriptionContent(testsName, buildUrl, reportLink, logsLink)
            break
        case "RPR SDK":
            String reportLink = "${buildUrl}/Test_20Report_20HybridPro"
            String logsLink = "${buildUrl}/artifact"
            messageContent = buildDescriptionContent(testsName, buildUrl, reportLink, logsLink)
            break
        case "MaterialX":
            String reportLink = "${buildUrl}/Test_20Report"
            String logsLink = "${buildUrl}/artifact"
            messageContent = buildDescriptionContent(testsName, buildUrl, reportLink, logsLink)
            break
        default: 
            throw new Exception("Unexpected testsName '${testsName}'")
    }

    return "<span style='color: #b03a2e; font-size: 150%'>${messageContent}</span><br/><br/>"
}


def doRequest(String url) {
    def rawInfo = httpRequest(
        url: url,
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    return parseResponse(rawInfo.content)
}


def checkBuildResult(String buildUrl) {
    withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
        buildUrl = buildUrl.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
    }

    def parsedInfo = doRequest("${buildUrl}/api/json?tree=result,description,inProgress")

    return parsedInfo
}


def addOrUpdateDescription(Map options, String newLine, String testsName) {
    for (buildUrl in options["buildsUrls"]) {
        // do not display the link in the description of the same build
        boolean skip = false
        switch (testsName) {
            case "Original":
                skip = buildUrl.contains("HybridPro-Build")
                break
            case "Unit":
                skip = buildUrl.contains("HybridPro-Unit")
                break
            case "Performance":
                skip = buildUrl.contains("HybridPro-Perf")
                break
            case "RPR SDK":
                skip = buildUrl.contains("HybridPro-SDK")
                break
            case "MaterialX":
                skip = buildUrl.contains("HybridPro-MTLX")
                break
            default: 
                throw new Exception("Unexpected testsName '${testsName}'")
        }

        if (skip) {
            continue
        }

        Integer buildNumber = buildUrl.split("/")[-1] as Integer
        String[] jobParts = buildUrl.replace(env.JENKINS_URL + "job/", "").replace("/${buildNumber}/", "").split("/job/")

        def item = Jenkins.instance

        for (part in jobParts) {
            item = item.getItem(part)
        }

        def build = item.getBuildByNumber(buildNumber)

        if (build.description != null) {
            List lines = build.description.split("<br/>") as List

            boolean lineReplaced = false

            println("Found lines")
            println(build.description)
            println(lines.size())

            for (int i = 0; i < lines.size(); i++) {
                String line = lines[i]
                println(line)
                println(testsName)
                println(line.contains(testsName))
                if (line.contains(testsName)) {
                    lines[i] = newLine.replace("<br/>", "")
                    build.description = lines.join("<br/>")
                    lineReplaced = true
                }
            }

            println("Replaced: " + lineReplaced)

            if (!lineReplaced) {
                build.description += newLine
            }
        }
    }
}


def awaitBuildFinishing(Map options, String buildUrl, String testsName) {
    if (checkBuildResult(buildUrl).inProgress) {
        return false
    } else if (!options["finishedTestBuilds"].containsKey(testsName) || !options["finishedTestBuilds"][testsName])  {
        options["finishedTestBuilds"][testsName] = true

        String description = buildDescriptionLine(options, buildUrl, testsName)
        addOrUpdateDescription(options, description, testsName)

        options.resultsDescription += description
    }

    return true
}


def executeDeploy(Map options, List platformList, List testResultList) {
    cleanWS("Windows")

    // set error statuses for PR, except if current build has been superseded by new execution
    if (env.CHANGE_ID && !currentBuild.nextBuild) {
        // if jobs was aborted or crushed remove pending status for unfinished stages
        GithubNotificator.closeUnfinishedSteps(options, "Build has been terminated unexpectedly")
    }

    if (env.TAG_NAME) {
        makeRelease(options)
    }
}


def launchAndWaitTests(Map options) {
    String testPlatforms = getTestPlatforms(options)
    String testPlatformsMtlx = getTestPlatformsMtlx(testPlatforms)

    if (!options["ueLaunched"]) {
        if (env.BRANCH_NAME == "master" && testPlatforms.contains("Windows")) {
            build(job: "HybridUEAuto/VictorianTrainsAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ToyShopAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ShooterGameAuto/rpr_master", wait: false)
        }

        options["ueLaunched"] == true
    }

    if (!options["unitLink"]) {
        // check that the platforms variable contains any GPU
        if (testPlatforms.contains(":")) {
            if (options.apiValues) {
                build(
                    job: env.JOB_NAME.replace("Build", "Unit"),
                    parameters: [
                        string(name: "PipelineBranch", value: options.pipelineBranch),
                        string(name: "CommitSHA", value: options.commitSHA),
                        string(name: "CommitMessage", value: options.commitMessage),
                        string(name: "OriginalBuildLink", value: env.BUILD_URL),
                        string(name: "Platforms", value: testPlatforms),
                        string(name: "ApiValues", value: options.apiValues),
                        booleanParam(name: "UpdateRefs", value: options.updateUnitRefs)
                    ],
                    wait: false,
                    quietPeriod : 0
                )

                options["unitLink"] = getTriggeredBuildLink(env.JOB_URL.replace("Build", "Unit"))
                options["buildsUrls"].add(options["unitLink"])
            }
        }
    }

    if (!options["perfLink"]) {
        if (options.scenarios) {
            build(
                job: env.JOB_NAME.replace("Build", "Perf"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "CommitMessage", value: options.commitMessage),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "Scenarios", value: options.scenarios),
                    booleanParam(name: "UpdateRefs", value: options.updatePerfRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            options["perfLink"] = getTriggeredBuildLink(env.JOB_URL.replace("Build", "Perf"))
            options["buildsUrls"].add(options["perfLink"])
        }
    }

    if (!options["rprSdkLink"]) {
        if (options.rprSdkTestsPackage != "none") {
            build(
                job: env.JOB_NAME.replace("Build", "SDK"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "ProjectBranchName", value: options.projectBranch),
                    string(name: "CommitMessage", value: options.commitMessage),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "TestsBranch", value: options.rprSdkTestsBranch),
                    string(name: "TestsPackage", value: options.rprSdkTestsPackage),
                    string(name: "Platforms", value: testPlatforms),
                    string(name: "UpdateRefs", value: options.updateSdkRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            options["rprSdkLink"] = getTriggeredBuildLink(env.JOB_URL.replace("Build", "SDK"))
            options["buildsUrls"].add(options["rprSdkLink"])
        }
    }

    if (!options["mtlxLink"]) {
        if (options.mtlxTestsPackage != "none") {
            build(
                job: env.JOB_NAME.replace("Build", "MTLX"),
                parameters: [
                    string(name: "PipelineBranch", value: options.pipelineBranch),
                    string(name: "CommitSHA", value: options.commitSHA),
                    string(name: "ProjectBranchName", value: options.projectBranch),
                    string(name: "CommitMessage", value: options.commitMessage),
                    string(name: "OriginalBuildLink", value: env.BUILD_URL),
                    string(name: "TestsBranch", value: options.mtlxTestsBranch),
                    string(name: "TestsPackage", value: options.mtlxTestsPackage),
                    string(name: "Tests", value: ""),
                    string(name: "Platforms", value: testPlatformsMtlx),
                    string(name: "UpdateRefs", value: options.updateMtlxRefs)
                ],
                wait: false,
                quietPeriod : 0
            )

            options["mtlxLink"] = getTriggeredBuildLink(env.JOB_URL.replace("Build", "MTLX"))
            options["buildsUrls"].add(options["mtlxLink"])
        }
    }

    if (!options["descriptionsInitialized"]) {
        // Wait a bit to let the builds start
        sleep(60)

        options["descriptionsInitialized"] = true

        String description = buildDescriptionLine(options, env.BUILD_URL, "Original")
        addOrUpdateDescription(options, description, "Original")

        if (options["unitLink"]) {
            description = buildDescriptionLine(options, options["unitLink"], "Unit")
            addOrUpdateDescription(options, description, "Unit")
        }
        if (options["perfLink"]) {
            description = buildDescriptionLine(options, options["perfLink"], "Performance")
            addOrUpdateDescription(options, description, "Performance")
        }
        if (options["rprSdkLink"]) {
            description = buildDescriptionLine(options, options["rprSdkLink"], "RPR SDK")
            addOrUpdateDescription(options, description, "RPR SDK")
        }
        if (options["mtlxLink"]) {
            description = buildDescriptionLine(options, options["mtlxLink"], "MaterialX")
            addOrUpdateDescription(options, description, "MaterialX")
        }
    }

    boolean subbuildsFinished = true

    if (options["unitLink"]) {
        subbuildsFinished &= awaitBuildFinishing(options, options["unitLink"], "Unit")
    }
    if (options["perfLink"]) {
        subbuildsFinished &= awaitBuildFinishing(options, options["perfLink"], "Performance")
    }
    if (options["rprSdkLink"]) {
        subbuildsFinished &= awaitBuildFinishing(options, options["rprSdkLink"], "RPR SDK")
    }
    if (options["mtlxLink"]) {
        subbuildsFinished &= awaitBuildFinishing(options, options["mtlxLink"], "MaterialX")
    }

    if (env.TAG_NAME && !options.emailSent && subbuildsFinished) {
        withCredentials([string(credentialsId: "HybridProNotifiedEmails", variable: "HYBRIDPRO_NOTIFIED_EMAILS")]) {
            String emailBody = "<span style='font-size: 150%'>Autotests results :</span><br/><br/>${options.resultsDescription}"
            emailBody += "<span style='font-size: 150%'><a href='${env.BUILD_URL}'>Original build link</a></span>"
            options.emailSent = true
            mail(to: HYBRIDPRO_NOTIFIED_EMAILS, subject: "[HYBRIDPRO] ${env.TAG_NAME} autotests results", mimeType: 'text/html', body: emailBody)
        }
    }

    return subbuildsFinished
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


def getTestPlatformsMtlx(String testPlatforms) {
    List platformsByOS = testPlatforms.split(";") as List

    for (platforms in platformsByOS) {
        if (platforms.startsWith("Windows")) {
            List suitablePlafroms = []

            if (platforms.split(":").length == 2) {
                List platformsList = platforms.split(":")[1].split(",") as List

                platformsList.each() { platform ->
                    if (platform.contains("RTX") || platform.contains("AMD_RX6") || platform.contains("AMD_RX7")) {
                        suitablePlafroms.add(platform)
                    }
                }

                return "Windows:" + suitablePlafroms.join(",")
            }
        }
    }

    return ""
}


def call(String pipelineBranch = "master",
         String projectBranch = "",
         String rprSdkTestsBranch = "master",
         String mtlxTestsBranch = "master",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100,AMD_680M;Ubuntu20:AMD_RX6700XT,NVIDIA_RTX3070TI",
         String apiValues = "vulkan,d3d12",
         String scenarios = "",
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

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    currentBuild.description = ""

    try {
        Map options = [:]

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
                   DEPLOY_TIMEOUT: 30,
                   finishedBuildStages: new ConcurrentHashMap(),
                   problemMessageManager:problemMessageManager,
                   resultsDescription: "",
                   deployPreCondition: this.&launchAndWaitTests,
                   buildsUrls: [env.BUILD_URL],
                   finishedTestBuilds: [:]]

        multiplatform_pipeline(processedPlatforms, this.&executePreBuild, this.&executeBuild, null, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        if (currentBuild.result == "FAILURE" && !currentBuild.description) {
            String problemMessage = problemMessageManager.publishMessages()
        }
    }
}
