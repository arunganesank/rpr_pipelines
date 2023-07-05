import groovy.transform.Field

import utils


def getLatestName() {
    withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
        def fileNames = bat(returnStdout: true, script: '%CIS_TOOLS%\\' 
            + "listFiles.bat \"/volume1/Shared/Art NAS files/USDScenes\" " + '%REMOTE_HOST% %SSH_PORT% -t').split("\n") as List
            println(fileNames)
        return fileNames[0]
    }
}


def call() {
    timestamps {
        stage("Update LiveMode scene") {
            node("Windows && PreBuild") {
                def fileName = getLatestName()
                println(fileName)
                downloadFiles("/volume1/Shared/Art NAS files/USDScenes/${fileName}", ".")

                unzip(zipFile: fileName)

                def folderName = fileName.take(fileName.lastIndexOf("."))
                println(folderName)
                uploadFiles("./${folderName}", "/volume1/web/Assets/render_studio_autotests/Scene_002_usd")
            }
        }
    }
}