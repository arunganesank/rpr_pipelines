import groovy.transform.Field

import utils


def incrementVersion() {
    dir('RadeonProRenderBlenderAddon') {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderBlenderAddon.git")
        def blenderVersion = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', ', ')
        println "[INFO] Current RPR Blender version: ${blenderVersion}"

        def newMajorVersion = version_inc(version, 1, ', ')
        println "[INFO] New major version: ${newMajorVersion}"
        def newMinorVersion = version_inc(version, 2, ', ')
        println "[INFO] New minor version: ${newMinorVersion}"
        def newPatchVersion = version_inc(version, 3, ', ')
        println "[INFO] New minor version: ${newPatchVersion}"

        version_write("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', newPatchVersion, ', ')

        blenderVersion = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\rprblender\\__init__.py", '"version": (', ', ', "true").replace(', ', '.')
        println "[INFO] Updated build version: ${blenderVersion}"
    }

    dir('RadeonProRenderMayaPlugin') {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderMayaPlugin.git")
        def mayaVersion = version_read("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION')
        println "[INFO] Current RPR Maya version: ${mayaVersion}"

        newMajorVersion = version_inc(version, 1)
        println "[INFO] New major version: ${newMajorVersion}"
        newMinorVersion = version_inc(version, 2)
        println "[INFO] New minor version: ${newMinorVersion}"
        newPatchVersion = version_inc(version, 3)
        println "[INFO] New minor version: ${newPatchVersion}"

        version_write("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION', newPatchVersion)

        mayaVersion = version_read("${env.WORKSPACE}\\RadeonProRenderMayaPlugin\\version.h", '#define PLUGIN_VERSION')
        println "[INFO] Updated build version: ${mayaVersion}"
    }

    dir('AMDRenderStudio') {
        checkoutScm(branchName: "main", repositoryUrl: "git@github.com:Radeon-Pro/RenderStudio.git")
        def studioVersion = readFile("VERSION.txt").trim()
        println "[INFO] Current Render Studio version: ${studioVersion}"

        newMajorVersion = version_inc(version, 1)
        println "[INFO] New major version: ${newMajorVersion}"
        newMinorVersion = version_inc(version, 2)
        println "[INFO] New minor version: ${newMinorVersion}"
        newPatchVersion = version_inc(version, 3)
        println "[INFO] New minor version: ${newPatchVersion}"

        writeFile(file: "VERSION.txt", text: newPatchVersion)

        studioVersion = readFile("VERSION.txt").trim()
        println "[INFO] Updated build version: ${studioVersion}"
    }
}


def call() {
    timestamps {
        stage("Increment version") {
            node("Windows && PreBuild") {
                incrementVersion()
            }
        }
    }
}