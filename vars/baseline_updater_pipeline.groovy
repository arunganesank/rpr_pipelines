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
    "USD": "rpr_usdplugin_autotests",
    "maya_usd": "usd_maya_autotests"
]

@Field final Map PROJECT_MAPPING = [
    "blender": "Blender",
    "maya": "Maya",
    "max": "Max",
    "core": "Core",
    "blender_usd_hydra": "Blender USD Hydra",
    "inventor": "Inventor",
    "USD": "Houdini",
    "maya_usd": "Maya USD"
]

@Field final Map ENGINE_REPORT_MAPPING = [
    "full": "Tahoe",
    "full2": "Northstar",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "tahoe",
    "northstar": "Northstar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "rpr": "RPR",
    "gl": "GL"
]

@Field final Map ENGINE_BASELINES_MAPPING = [
    "full": "",
    "full2": "NorthStar",
    "hybridpro": "HybridPro",
    "hybrid": "Hybrid",
    "tahoe": "",
    "northstar": "NorthStar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL",
    "gl": "GL",
    "rpr": "RPR"
]


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def saveBaselines(String refPathProfile, String resultsDirectory = "results") {
    python3("${WORKSPACE}\\jobs_launcher\\common\\scripts\\generate_baselines.py --results_root ${resultsDirectory} --baseline_root baselines")
    uploadFiles("baselines/", refPathProfile)

    bat """
        if exist ${resultsDirectory} rmdir /Q /S ${resultsDirectory}
        if exist baselines rmdir /Q /S baselines
    """
}


def call(String jobName,
    String buildID,
    String resultPath,
    String caseName,
    String engine,
    String toolName,
    String updateType) {

    stage("UpdateBaselines") {
        node("Windows && !NoBaselinesUpdate") {
            ws("WS/UpdateBaselines") {
                ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

                try {
                    cleanWS()

                    toolName = toolName.toLowerCase()
                    baselineDirName = BASELINE_DIR_MAPPING[toolName]

                    if (engine == "None" || engine == "\"") {
                        engine = ""
                    }

                    String machineConfiguration
                    String groupName
                    String reportName = engine ? "Test_Report_${ENGINE_REPORT_MAPPING[engine.toLowerCase()]}" : "Test_Report"

                    String baselinesPath = "/Baselines/${baselineDirName}"
                    String reportComparePath

                    if (updateType == "Case" || updateType == "Group") {
                        groupName = resultPath.split("/")[-1]
                        reportComparePath = "results/${groupName}/report_compare.json"
                    }

                    if (updateType != "Build") {
                        def resultPathParts = resultPath.split("/")[0].split("-")
                        String gpuName = resultPathParts[0]
                        String osName = resultPathParts[1]

                        if (engine) {
                            String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                            machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                        } else {
                            machineConfiguration = "${gpuName}-${osName}"
                        }

                        String platform = resultPathParts[0] + "-" + resultPathParts[1]
                        currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? platform + '-' + ENGINE_REPORT_MAPPING[engine.toLowerCase()] : platform})<br/>"
                    } else {
                        currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? ENGINE_REPORT_MAPPING[engine.toLowerCase()] : ''})<br/>"
                    }

                    switch(updateType) {
                        case "Case":
                            currentBuild.description += "<b>Group:</b> ${groupName} / <b>Case:</b> ${caseName}<br/>"
                            break

                        case "Group":
                            currentBuild.description += "<b>Group:</b> ${groupName}<br/>"
                            break

                        case "Platform":
                            currentBuild.description += "Update all groups of platform<br/>"
                            break

                        case "Build":
                            currentBuild.description += "Update all baselines<br/>"

                            break

                        default:
                            throw new Exception("Unknown updateType ${updateType}")
                    }

                    dir("jobs_launcher") {
                        checkoutScm(branchName: 'master', repositoryUrl: 'git@github.com:luxteam/jobs_launcher.git')
                    }

                    switch(updateType) {
                        case "Case":
                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${resultPath}"
                            String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 

                            downloadFiles(remoteResultPath + "/report_compare.json", "results/${groupName}")
                            downloadFiles(remoteResultPath + "/Color/*${caseName}*", "results/${groupName}/Color")
                            downloadFiles(remoteResultPath + "/*${caseName}*.json", "results/${groupName}")

                            def testCases = readJSON(file: reportComparePath)

                            for (testCase in testCases) {
                                if (testCase["test_case"] == caseName) {
                                    JSON serializedJson = JSONSerializer.toJSON([testCase], new JsonConfig());
                                    writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
                                    break
                                }
                            }

                            saveBaselines(refPathProfile)

                            break

                        case "Group":
                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${resultPath}"
                            String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 

                            downloadFiles(remoteResultPath, "results")
                            saveBaselines(refPathProfile)

                            break

                        case "Platform":
                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${resultPath}*"
                            String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 
                            downloadFiles(remoteResultPath, "results")

                            dir("results") {
                                // one directory can contain test results of multiple groups
                                def grouppedDirs = findFiles()

                                for (currentDir in grouppedDirs) {
                                    if (currentDir.directory && currentDir.name.startsWith(resultPath)) {
                                        dir("${currentDir.name}/Results") {
                                            // skip empty directories
                                            if (findFiles().length == 0) {
                                                return
                                            }

                                            // find next dir name (e.g. Blender, Maya)
                                            String nextDirName = findFiles()[0].name
                                            saveBaselines(refPathProfile, nextDirName)
                                        }
                                    }
                                }
                            }

                            break

                        case "Build":
                            String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/"
                            downloadFiles(remoteResultPath, "results")

                            dir("results") {
                                // one directory can contain test results of multiple groups
                                def grouppedDirs = findFiles()

                                for (currentDir in grouppedDirs) {
                                    if (currentDir.directory && 
                                        (currentDir.name.startsWith("NVIDIA_") || currentDir.name.startsWith("AppleM1") || currentDir.name.startsWith("AMD_"))) {

                                        def resultPathParts = currentDir.name.split("-")
                                        String gpuName = resultPathParts[0]
                                        String osName = resultPathParts[1]

                                        if (engine) {
                                            String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                                            machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                                        } else {
                                            machineConfiguration = "${gpuName}-${osName}"
                                        }

                                        String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 

                                        dir("${currentDir.name}/Results") {
                                            // skip empty directories
                                            if (findFiles().length == 0) {
                                                return
                                            }

                                            // find next dir name (e.g. Blender, Maya)
                                            String nextDirName = findFiles()[0].name
                                            saveBaselines(refPathProfile, nextDirName)
                                        }
                                    }
                                }
                            }

                            break

                        default:
                            throw new Exception("Unknown updateType ${updateType}")
                    }
                } catch (e) {
                    println("[ERROR] Failed to update baselines on NAS")
                    problemMessageManager.saveGlobalFailReason(NotificationConfiguration.FAILED_UPDATE_BASELINES_NAS)
                    currentBuild.result = "FAILURE"
                    throw e
                }

                problemMessageManager.publishMessages()
            }
        }
    }
}