import groovy.transform.Field
import utils


def getLatestArchiveName() {
    withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
        def fileNames = bat(returnStdout: true, script: '%CIS_TOOLS%\\' 
            + "listFiles.bat \"/volume1/Shared/Art_NAS_files/USDScenes\" " + '%REMOTE_HOST% %SSH_PORT% -t').split("\n") as List
        println("Found archives: ${fileNames}")
        return fileNames[0]
    }
}


def call() {
    timestamps {
        stage("Update LiveMode scene") {
            node("Windows && PreBuild") {
                def fileName = getLatestArchiveName()
                println("Latest archive name: ${fileName}")
                downloadFiles("/volume1/Shared/Art_NAS_files/USDScenes/${fileName}", ".")

                unzip(zipFile: fileName)

                def folderName = fileName.take(fileName.lastIndexOf("."))
                println("Root folder name: ${folderName}")
                uploadFiles("./${folderName}/usd", "/volume1/web/Assets/render_studio_autotests/Scene_002_usd")
                uploadFiles("./${folderName}/Scene002.blend", "/volume1/web/Assets/render_studio_autotests/Scene_002_usd")
            }
        }
    }
}