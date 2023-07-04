import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic


@Field final Map BASELINE_DIR_MAPPING = [
    "blender": "rpr_blender_autotests",
    "maya": "rpr_maya_autotests",
    "max": "rpr_max_autotests",
    "core": "rpr_core_autotests",
    "blender_usd_hydra": "usd_blender_autotests",
    "inventor": "usd_inventor_autotests",
    "usd_viewer": "usd_rprviewer_autotests",
    "usd": "usd_houdini_autotests",
    "maya_usd": "usd_maya_autotests",
    "anari": "rpr_anari_autotests",
    "render_studio": "render_studio_autotests",
    "hybrid_mtlx": "hybrid_mtlx_autotests",
    "hdrpr": "hdrpr_autotests"
]

@Field final Map PROJECT_MAPPING = [
    "blender": "Blender",
    "maya": "Maya",
    "max": "Max",
    "core": "Core",
    "blender_usd_hydra": "Blender USD Hydra",
    "inventor": "Inventor",
    "usd": "Houdini",
    "maya_usd": "Maya USD",
    "anari": "Anari",
    "render_studio": "Render Studio",
    "hybrid_mtlx": "Hybrid MTLX",
    "hdrpr": "HdRPR"
]

@Field final Map PROFILE_REPORT_MAPPING = [
    "full": "Tahoe",
    "full2": "Northstar",
    "northstar64": "Northstar64",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "tahoe",
    "northstar": "Northstar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "rpr": "RPR",
    "gl": "GL",
    "hip": "HIP",
    "web": "Web",
    "desktop": "Desktop"
]

@Field final Map PROFILE_BASELINES_MAPPING = [
    "full": "",
    "full2": "NorthStar",
    "northstar64": "Northstar64",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "",
    "northstar": "NorthStar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "gl": "GL",
    "rpr": "RPR",
    "hip": "HIP",
    "web": "Web",
    "desktop": "Desktop"
]

@Field final Map AUTOTESTS_PROJECT_DIR_MAPPING = [
    "blender": "Blender",
    "maya": "Maya",
    "max": "Max",
    "core": "Core",
    "blender_usd_hydra": "BlenderUSDHydra",
    "inventor": "Inventor",
    "usd_viewer": "RPRViewer",
    "usd": "Houdini",
    "maya_usd": "Maya",
    "anari": "Anari",
    "render_studio": "RenderStudio",
    "hybrid_mtlx": "HybMTLX",
    "hdrpr": "HdRPR"
]


String getBaselinesUpdateInitiator() {
    for (def cause in currentBuild.rawBuild.getCauses()) {
        if (cause.getClass().toString().contains("UserIdCause")) {
            return cause.getUserId()
        }
    }

    return "Unknown"
}

def getBaselinesOriginalBuild(String jobName = null, String buildID = null) {
    if (jobName && buildID) {
        return "${env.JENKINS_URL}job/${jobName.replace('/', '/job/')}/${buildID}"
    } else {
        return "Unknown"
    }
}

String getBaselinesUpdatingBuild() {
    return env.BUILD_URL
}


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


class UpdateInfo {

    String jobName
    String buildID
    String platform
    String groupsNames
    String casesNames
    String profile
    String toolName
    String updateType
    Boolean onlyFails
    Boolean allPlatforms

    UpdateInfo(String jobName,
        String buildID,
        String platform,
        String groupsNames,
        String casesNames,
        String profile,
        String toolName,
        String updateType,
        Boolean onlyFails,
        Boolean allPlatforms) {

        this.jobName = jobName
        this.buildID = buildID
        this.platform = platform
        this.groupsNames = groupsNames
        this.casesNames = casesNames
        this.profile = profile
        this.toolName = toolName
        this.updateType = updateType
        this.onlyFails = onlyFails
        this.allPlatforms = allPlatforms
    }
}


def generateDescription(UpdateInfo updateInfo, String profile) {
    String platform = updateInfo.platform
    String groupsNames = updateInfo.groupsNames
    String casesNames = updateInfo.casesNames
    String toolName = updateInfo.toolName
    String updateType = updateInfo.updateType
    Boolean allPlatforms = updateInfo.allPlatforms

    if (allPlatforms){
        if (profile) {
            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName.toLowerCase()]} "
            currentBuild.description += "(${PROFILE_REPORT_MAPPING.containsKey(profile.toLowerCase()) ? PROFILE_REPORT_MAPPING[profile.toLowerCase()] : profile})<br/>"
        } else {
            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName.toLowerCase()]}<br/>"
        }
    } else {
        if (profile) {
            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName.toLowerCase()]} "
            currentBuild.description += "(${PROFILE_REPORT_MAPPING.containsKey(profile.toLowerCase()) ? platform + '-' + PROFILE_REPORT_MAPPING[profile.toLowerCase()] : platform + '-' + profile})<br/>"
        } else {
            currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName.toLowerCase()]} (${platform})<br/>"
        }
    }

    if (updateType == "Cases") {
        if (casesNames.contains(",")) {
            currentBuild.description += "<b>Group:</b> ${groupsNames} / <b>Multiple cases</b><br/>"
        } else {
            currentBuild.description += "<b>Group:</b> ${groupsNames} / <b>Case:</b> ${casesNames}<br/>"
        }
    } else {
        if (groupsNames.contains(",")) {
            currentBuild.description += "<b>Multiple groups</b><br/>"
        } else {
            currentBuild.description += "<b>Group:</b> ${groupsNames}<br/>"
        }
    }
}


boolean isSuitableDir(UpdateInfo updateInfo, String directory, String targetGroup, String remoteResultPath) {
    String platform = updateInfo.platform
    Boolean allPlatforms = updateInfo.allPlatforms

    if (!directory.endsWith("/")) {
        // not a directory
        return false
    }

    if (directory.split("-").length != 2 && directory.split("-").length != 3) {
        println("[INFO] Directory ${directory} hasn't required structure. Skip it")
        return false
    }

    String gpuName = directory.split("-")[0]
    String osName = directory.split("-")[1].replace("/", "")

    List groups = null

    if (directory.split("-").length == 3) {
        groups = directory.split("-")[2].replace("/", "").split() as List
    }

    if (!allPlatforms) {
        String targetGpuName = platform.split("-")[0]
        String targetOsName = platform.split("-")[1].replace("/", "")

        if (gpuName != targetGpuName || osName != targetOsName) {
            println("[INFO] Directory ${directory} doesn't apply to the required platform. Skip it")
            return false
        }
    }

    if (directory.contains(".json~") || ! groups) {
        // non-splittable package detected
        List nonSplittablePackageDirs

        withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
            nonSplittablePackageDirs = bat(returnStdout: true, script: '%CIS_TOOLS%\\' 
                + "listFiles.bat \"${remoteResultPath}\" " + '%REMOTE_HOST% %SSH_PORT%').split("\n") as List
        }

        Boolean dirFound = false

        for (nonSplittablePackageDir in nonSplittablePackageDirs) {
            if (nonSplittablePackageDir.replace("/", "") == targetGroup) {
                dirFound = true
                break
            }
        }

        if (!dirFound) {
            println("[INFO] Directory ${directory} doesn't contain ${targetGroup} test group. Skip it")
            return false
        }
    } else if (!groups.contains(targetGroup)) {
        println("[INFO] Directory ${directory} doesn't contain ${targetGroup} test group. Skip it")
        return false
    }

    return true
}


def doGroupUpdate(UpdateInfo updateInfo, String directory, String targetGroup, String profile, String remoteResultPath) {
    String casesNames = updateInfo.casesNames
    String toolName = updateInfo.toolName
    String updateType = updateInfo.updateType
    Boolean onlyFails = updateInfo.onlyFails

    String machineConfiguration

    String gpuName = directory.split("-")[0]
    String osName = directory.split("-")[1].replace("/", "")

    if (profile) {
        String profileBaselineName = PROFILE_BASELINES_MAPPING.containsKey(profile.toLowerCase()) ? PROFILE_BASELINES_MAPPING[profile.toLowerCase()] : profile
        machineConfiguration = profileBaselineName ? "${gpuName}-${osName}-${profileBaselineName}" : "${gpuName}-${osName}"
    } else {
        machineConfiguration = "${gpuName}-${osName}"
    }

    String baselinesPathProfile = "/volume1/Baselines/${BASELINE_DIR_MAPPING[toolName.toLowerCase()]}/${machineConfiguration}"

    if (updateType == "Cases") {
        downloadFiles("${remoteResultPath}/${targetGroup}/report_compare.json", "results/${targetGroup}", "", true, "nasURL", "nasSSHPort", true)

        String reportComparePath = "results/${targetGroup}/report_compare.json"
        def testCases = readJSON(file: reportComparePath)
        def targetCases = []

        if (!fileExists(reportComparePath)) {
            println("[WARNING] report_compare.json file doesn't exist in ${remoteResultPath}/${targetGroup} directory")
            return
        }

        for (targetCase in casesNames.split(",")) {
            downloadFiles("${remoteResultPath}/${targetGroup}/Color/*${targetCase}*", "results/${targetGroup}/Color", "", true, "nasURL", "nasSSHPort", true)
            downloadFiles("${remoteResultPath}/${targetGroup}/*${targetCase}*.json", "results/${targetGroup}", "", true, "nasURL", "nasSSHPort", true)

            for (testCase in testCases) {
                if (testCase["test_case"] == targetCase) {
                    if (!onlyFails || testCase["test_status"] == "failed") {
                        targetCases.add(testCase)
                        break
                    }
                }
            }
        }

        JSON serializedJson = JSONSerializer.toJSON(targetCases, new JsonConfig());
        writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
        saveBaselines(updateInfo.jobName, updateInfo.buildID, baselinesPathProfile)
    } else {
        downloadFiles("${remoteResultPath}/${targetGroup}", "results", "", true, "nasURL", "nasSSHPort", true)

        String reportComparePath = "results/${targetGroup}/report_compare.json"

        if (!fileExists(reportComparePath)) {
            println("[WARNING] report_compare.json file doesn't exist in ${remoteResultPath}/${targetGroup} directory")
            return
        }

        if (onlyFails) {
            def testCases = readJSON(file: reportComparePath)
            def targetCases = []

            for (testCase in testCases) {
                if (testCase["test_status"] == "failed") {
                    targetCases.add(testCase)
                }
            }

            JSON serializedJson = JSONSerializer.toJSON(targetCases, new JsonConfig());
            writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
        }

        saveBaselines(updateInfo.jobName, updateInfo.buildID, baselinesPathProfile)
    }
}


def saveBaselines(String jobName, String buildID, String baselinesPathProfile, String resultsDirectory = "results") {
    withEnv([
            "BASELINES_UPDATE_INITIATOR=${getBaselinesUpdateInitiator()}",
            "BASELINES_ORIGINAL_BUILD=${getBaselinesOriginalBuild(jobName, buildID)}",
            "BASELINES_UPDATING_BUILD=${getBaselinesUpdatingBuild()}"
    ]) {

        python3("${WORKSPACE}\\jobs_launcher\\common\\scripts\\generate_baselines.py --results_root ${resultsDirectory} --baseline_root baselines")

        List filesNames = []

        dir("baselines") {
            def files = findFiles()

            for (file in files) {
                filesNames << file.name
            }
        }

        if (filesNames.size() > 0) {
            if (!filesNames.contains("primary")) {
                println("Detected baselines only for one client")
                uploadFiles("baselines/", baselinesPathProfile)
            } else {
                println("Detected baselines for multiple clients. Upload them separately")

                dir("baselines") {
                    for (file in filesNames) {
                        uploadFiles("${file}/", "${baselinesPathProfile}-${file}")
                    }
                }
            }
        } else {
            println("No baselines were generated")
        }

        bat """
            if exist ${resultsDirectory} rmdir /Q /S ${resultsDirectory}
            if exist baselines rmdir /Q /S baselines
        """
    }
}


def call(String jobName,
    String buildID,
    String platform,
    String groupsNames,
    String casesNames,
    String profile,
    String toolName,
    String updateType,
    Boolean onlyFails,
    Boolean allPlatforms) {

    def updateInfo = new UpdateInfo(jobName,
        buildID,
        platform,
        groupsNames,
        casesNames,
        profile,
        toolName,
        updateType,
        onlyFails,
        allPlatforms)

    int maxTries = 3
    String nodeLabels = "Windows && !NoBaselinesUpdate"

    stage("UpdateBaselines") {
        for (int currentTry = 0; currentTry < maxTries; currentTry++) {
            String nodeName = null

            try {
                node(nodeLabels) {
                    ws("WS/UpdateBaselines") {
                        nodeName = env.NODE_NAME

                        ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

                        try {
                            cleanWS()

                            // duck tape for one autotests repository for two projects
                            if (toolName == "inventor" && jobName.contains("USD-Viewer")) {
                                toolName = "usd_viewer"
                            }

                            toolName = toolName.toLowerCase()

                            if (profile == "None" || profile == "\"") {
                                profile = ""
                            }

                            String reportName

                            if (profile) {
                                reportName = PROFILE_REPORT_MAPPING.containsKey(profile.toLowerCase()) ? "Test_Report_${PROFILE_REPORT_MAPPING[profile.toLowerCase()]}" : "Test_Report_${profile}"
                            } else {
                                reportName = "Test_Report"
                            }

                            generateDescription(updateInfo, profile)

                            dir("jobs_launcher") {
                                checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/jobs_launcher.git")
                            }

                            List directories

                            // search all directories in the target report
                            withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                                directories = bat(returnStdout: true, script: '%CIS_TOOLS%\\' + "listFiles.bat \"/volume1/web/${jobName}/${buildID}/${reportName}\" " + '%REMOTE_HOST% %SSH_PORT%').split("\n") as List
                            }

                            for (targetGroup in groupsNames.split(",")) {
                                directories.each() { directory ->
                                    String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${directory}/Results/${AUTOTESTS_PROJECT_DIR_MAPPING[toolName.toLowerCase()]}"
                                    if (!isSuitableDir(updateInfo, directory, targetGroup, remoteResultPath)) {
                                        return
                                    }

                                    doGroupUpdate(updateInfo, directory, targetGroup, profile, remoteResultPath)
                                }
                            }
                        } catch (e) {
                            println("[ERROR] Failed to update baselines on NAS")
                            problemMessageManager.saveGlobalFailReason(NotificationConfiguration.FAILED_UPDATE_BASELINES_NAS)
                            throw e
                        }

                        problemMessageManager.publishMessages()
                    }
                }

                break
            } catch (e) {
                if (currentTry + 1 == maxTries) {
                    currentBuild.result = "FAILURE"
                    throw new Exception("Failed to update baselines. All attempts exceeded")
                }

                if (nodeName) {
                    nodeLabels += " && !${nodeName}"
                    println("New list of labels: ${nodeLabels}")
                } else {
                    println("No node name. Can't update list of labels")
                }
            }
        }
    }
}