import groovy.transform.Field

import utils


@Field final Map versionIndex = [
    "Patch": 3,
    "Minor": 2,
    "Major": 1
]


@Field final Map toolParams = [
    "RPR Blender": [
        "toolName": "RadeonProRenderBlenderAddon",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderBlenderAddon.git",
        "branchName": "master",
        "versionPath": "src\\rprblender\\__init__.py",
        "prefix": '"version": (',
        "delimiter": ", "
    ],
    "RPR Maya": [
        "toolName": "RadeonProRenderMayaPlugin",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaPlugin.git",
        "branchName": "master",
        "versionPath": "version.h",
        "prefix": "#define PLUGIN_VERSION"
    ],
    "Render Studio": [
        "toolName": "AMDRenderStudio",
        "repoUrl": "git@github.com:Radeon-Pro/RenderStudio.git",
        "branchName": "main",
        "versionPath": "VERSION.txt"
    ],
    "USD Blender": [
        "toolName": "BlenderUSDHydraAddon",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/BlenderUSDHydraAddon.git",
        "branchName": "master",
        "versionPath": "src\\hdusd\\__init__.py",
        "prefix": '"version": (',
        "delimiter": ", "
    ],
    "USD Maya": [
        "toolName": "RPRMayaUSD",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaUSD.git",
        "branchName": "main",
        "versionPath": "installation\\installation_hdrpr_only.iss",
        "prefix": "#define AppVersionString "
    ],
    "USD Houdini": [
        "toolName": "RadeonProRenderUSD",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderUSD.git",
        "branchName": "master",
        "versionPath": "cmake\\defaults\\Version.cmake"
    ],
    "USD Inventor": [
        "toolName": "RadeonProRenderInventorPlugin",
        "repoUrl": "git@github.com:Radeon-Pro/RadeonProRenderInventorPlugin.git",
        "branchName": "master",
        "versionPath": "rprplugin_installer.iss",
        "prefix": "AppVersion="
    ],
    "RPR Anari": [
        "toolName": "RadeonProRenderAnari",
        "repoUrl": "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderANARI.git",
        "branchName": "develop",
        "versionPath": "version.h"
    ]
]


def incrementVersion(String toolName, String repoUrl, String branchName, String versionPath, Integer index=3, String prefix="", String delimiter=".") {
    if (toolName == "RadeonProRenderInventorPlugin" && index == 3) {
        currentBuild.result = "FAILURE"
        println "[INFO] Version index is out of range"
        return
    }
    dir(toolName) {
        checkoutScm(branchName: branchName, repositoryUrl: repoUrl)
        def version = ""

        if (toolName == "RadeonProRenderUSD") {
            def majorVersion = version_read(versionPath, 'set(HD_RPR_MAJOR_VERSION "', '')
            def minorVersion = version_read(versionPath, 'set(HD_RPR_MINOR_VERSION "', '')
            def patchVersion = version_read(versionPath, 'set(HD_RPR_PATCH_VERSION "', '')
            version = "${majorVersion}.${minorVersion}.${patchVersion}"
        } else if (toolName == "RadeonProRenderAnari") {
            def majorVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_MAJOR ", '')
            def minorVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_MINOR ", '')
            def patchVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_PATCH ", '')
            version = "${majorVersion}.${minorVersion}.${patchVersion}"
        } else {
            if (prefix != ""){
                version = version_read(versionPath, prefix, delimiter)
            } else {
                version = readFile(versionPath).trim()
            }
        }

        println "[INFO] Current ${toolName} version: ${version.replace(delimiter, '.')}"
        currentBuild.description += "<b>Old ${toolName} version:</b> ${version.replace(delimiter, '.')}<br/>"

        def newVersion = version_inc(version, index, delimiter)
        println "[INFO] New version: ${newVersion}"

        if (toolName == "RadeonProRenderUSD") {
            newVersions = newVersion.split(delimiter)
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", 'set(HD_RPR_MAJOR_VERSION "', newVersions[0], '')
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", 'set(HD_RPR_MINOR_VERSION "', newVersions[1], '')
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", 'set(HD_RPR_PATCH_VERSION "', newVersions[2], '')

            majorVersion = version_read(versionPath, 'set(HD_RPR_MAJOR_VERSION "', '')
            minorVersion = version_read(versionPath, 'set(HD_RPR_MINOR_VERSION "', '')
            patchVersion = version_read(versionPath, 'set(HD_RPR_PATCH_VERSION "', '')
            version = "${majorVersion}.${minorVersion}.${patchVersion}"
        } else if (toolName == "RadeonProRenderAnari") {
            newVersions = newVersion.split(delimiter)
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", "#define RPR_ANARI_VERSION_MAJOR ", newVersions[0], '')
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", "#define RPR_ANARI_VERSION_MINOR ", newVersions[1], '')
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", "#define RPR_ANARI_VERSION_PATCH ", newVersions[2], '')

            majorVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_MAJOR ", '')
            minorVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_MINOR ", '')
            patchVersion = version_read(versionPath, "#define RPR_ANARI_VERSION_PATCH ", '')
            version = "${majorVersion}.${minorVersion}.${patchVersion}"
        } else {
            version_write("${env.WORKSPACE}//${toolName}//${versionPath}", prefix, newVersion, delimiter)

            if (prefix != ""){
                if (delimiter == ", ") {
                    version = version_read(versionPath, prefix, delimiter, "true").replace(', ', '.')
                } else {
                    version = version_read(versionPath, prefix)
                }
            } else {
                version = readFile(versionPath).trim()
            }
        }
        println "[INFO] Updated version: ${version}"
        currentBuild.description += "<b>New ${toolName} version:</b> ${version}<br/><br/>"

//      bat """
//        git add version.h
//        git commit -m "buildmaster: version update to ${version}"
//        git push origin HEAD:master
//        """
    }
}


def call(String projectRepo = "RPR Blender", String toIncrement = "Patch") {
    int maxTries = 3
    String nodeLabels = "Windows && PreBuild"

    timestamps {
        stage("Increment version") {
            for (int currentTry = 0; currentTry < maxTries; currentTry++) {
                String nodeName = null

                try {
                    node(nodeLabels) {
                        nodeName = env.NODE_NAME

                        try {
                            currentBuild.description = ""
                            if (!toolParams.keySet().contains(projectRepo)) {
                                def repoUrl = ""

                                if (projectRepo == "RenderStudioLiveServer"){
                                    repoUrl = "git@github.com:s1lentssh/WebUsdLiveServer.git"
                                } else if (projectRepo == "RenderStudioRouteServer"){
                                    repoUrl = "git@github.com:s1lentssh/WebUsdRouteServer.git"
                                } else {
                                    repoUrl = "git@github.com:Radeon-Pro/${projectRepo}.git"
                                }

                                incrementVersion(
                                    projectRepo,
                                    repoUrl,
                                    "main",
                                    "VERSION.txt",
                                    versionIndex[toIncrement],
                                )
                            } else {
                                def prefix = toolParams[projectRepo]["prefix"] ?: ""
                                def delimiter = toolParams[projectRepo]["delimiter"] ?: "."

                                incrementVersion(
                                    toolParams[projectRepo]["toolName"],
                                    toolParams[projectRepo]["repoUrl"],
                                    toolParams[projectRepo]["branchName"],
                                    toolParams[projectRepo]["versionPath"],
                                    versionIndex[toIncrement],
                                    prefix,
                                    delimiter
                                )
                            }
                            return
                        } catch (e) {
                            println("[ERROR] Failed to increment version")
                            throw e
                        }
                    }
                    break
                } catch (e) {
                    if (currentTry + 1 == maxTries) {
                        currentBuild.result = "FAILURE"
                        throw new Exception("Failed to increment version. All attempts exceeded")
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
}