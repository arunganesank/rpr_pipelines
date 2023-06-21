import groovy.transform.Field

import utils


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


def call() {
    timestamps {
        stage("Increment version") {
            node("Windows && PreBuild") {
                currentBuild.description = ""
                incrementVersion(
                    "RadeonProRenderBlenderAddon",
                    "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderBlenderAddon.git",
                    "master",
                    "src\\rprblender\\__init__.py",
                    1,
                    '"version": (',
                    ", "
                )
                incrementVersion(
                    "RadeonProRenderMayaPlugin",
                    "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaPlugin.git",
                    "master",
                    "version.h",
                    2,
                    "#define PLUGIN_VERSION",
                )
                incrementVersion(
                    "AMDRenderStudio",
                    "git@github.com:Radeon-Pro/RenderStudio.git",
                    "main",
                    "VERSION.txt",
                    3
                )
            }
        }
    }
}