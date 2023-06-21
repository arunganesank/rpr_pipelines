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
    ]
]


def incrementVersion(String toolName, String repoUrl, String branchName, String versionPath, Integer index=3, String prefix="", String delimiter=".") {
    dir(toolName) {
        checkoutScm(branchName: branchName, repositoryUrl: repoUrl)
        def version = ""
        if (prefix != ""){
            version = version_read(versionPath, prefix, delimiter)
        } else {
            version = readFile(versionPath).trim()
        }
        println "[INFO] Current ${toolName} version: ${version.replace(delimiter, '.')}"
        currentBuild.description += "<b>Old ${toolName} version:</b> ${version.replace(delimiter, '.')}<br/>"

        def newVersion = version_inc(version, index, delimiter)
        println "[INFO] New version: ${newVersion}"

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
        println "[INFO] Updated version: ${version}"
        currentBuild.description += "<b>New ${toolName} version:</b> ${version}<br/><br/>"

//      bat """
//        git add version.h
//        git commit -m "buildmaster: version update to ${options.pluginVersion}"
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
                        } catch (e) {
                            println("[ERROR] Failed to increment version")
                            throw e
                        }
                    }
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