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
        "delimeter": ", "
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
        println "[INFO] Current ${toolName} version: ${version}"
        currentBuild.description += "<b>Old ${toolName} version:</b> ${version}<br/>"

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
    timestamps {
        stage("Increment version") {
            node("Windows && PreBuild") {
                currentBuild.description = ""

                println(toolParams)
                println(projectRepo)
                println(toolParams.("${projectRepo}"))

                def prefix = toolParams.("${projectRepo}").prefix ?: ""
                def delimiter = toolParams.("${projectRepo}").delimiter ?: "."
                incrementVersion(
                    toolParams.projectRepo.toolName,
                    toolParams.projectRepo.repoUrl,
                    toolParams.projectRepo.branchName,
                    toolParams.projectRepo.versionPath,,
                    versionIndex.toIncrement,
                    prefix,
                    delimiter
                )
            }
        }
    }
}