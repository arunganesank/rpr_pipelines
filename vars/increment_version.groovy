import groovy.transform.Field

import utils


def incrementVersion() {
    withNotifications(title: "Jenkins build configuration", configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderBlenderAddon.git")
        def version = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', ', ')
        println "[INFO] Current RPR Blender version: ${version}"

        def newMajorVersion = version_inc(version, 1, ', ')
        println "[INFO] New major version: ${newMajorVersion}"
        def newMinorVersion = version_inc(version, 2, ', ')
        println "[INFO] New minor version: ${newMinorVersion}"
        def newPatchVersion = version_inc(version, 3, ', ')
        println "[INFO] New minor version: ${newPatchVersion}"

        version_write("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', newPatchVersion, ', ')

        version = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', ', ', "true").replace(', ', '.')
        println "[INFO] Updated build version: ${version}"
    }

    withNotifications(title: "Jenkins build configuration", configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaPlugin.git")
        def version = version_read("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION')
        println "[INFO] Current RPR Maya version: ${version}"

        def newMajorVersion = version_inc(version, 1)
        println "[INFO] New major version: ${newMajorVersion}"
        def newMinorVersion = version_inc(version, 2)
        println "[INFO] New minor version: ${newMinorVersion}"
        def newPatchVersion = version_inc(version, 3)
        println "[INFO] New minor version: ${newPatchVersion}"

        version_write("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION', newPatchVersion)

        version = version_read("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION')
        println "[INFO] Updated build version: ${version}"
    }

    withNotifications(title: "Jenkins build configuration", configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: "main", repositoryUrl: "git@github.com:Radeon-Pro/RenderStudio.git")
        def version = readFile("VERSION.txt").trim()
        println "[INFO] Current Render Studio version: ${version}"

        def newMajorVersion = version_inc(version, 1)
        println "[INFO] New major version: ${newMajorVersion}"
        def newMinorVersion = version_inc(version, 2)
        println "[INFO] New minor version: ${newMinorVersion}"
        def newPatchVersion = version_inc(version, 3)
        println "[INFO] New minor version: ${newPatchVersion}"

        writeFile(file: "VERSION.txt", text: newPatchVersion)

        version = readFile("VERSION.txt").trim()
        println "[INFO] Updated build version: ${version}"
    }
}


def call() {
    timestamps {
        stage("Increment version") {
            node("Windows && PreBuild") {
                ws("WS/VersionIncrement") {
                    incrementVersion()
                }
            }
        }
    }
}