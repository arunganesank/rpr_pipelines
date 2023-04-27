import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException


String getSuitableStorageURL() {
    if (env.NODE_LABELS.split().contains("MacOS")) {
        return "nasURLOld"
    } else {
        return "nasURL"
    }
}

String getSuitableStoragePort() {
    return "nasSSHPort"
}


/**
 * Implementation of stashes through custom machine
 *
 * @param params Map with parameters
 * Possible elements:
 *     name - Name of stash which will be unstashed
 *     unzip - Unzip content of stash (default - true)
 *     storeOnNAS - Specify unstash data from NAS or make default Jenkins unstash
 *     debug - Print more info about making of stash (default - false)
 */
def call(Map params) {    
    String stdout
    String stashName

    try {
        stashName = params["name"]

        Boolean unzip = params.containsKey("unzip") ? params["unzip"] : true
        Boolean storeOnNAS = params.containsKey("storeOnNAS") ? params["storeOnNAS"] : false
        Boolean debug = params["debug"]

        if (storeOnNAS) {
            String remotePath = "/volume1/Stashes/${env.JOB_NAME}/${env.BUILD_NUMBER}/${stashName}/"

            int times = 3
            int retries = 0
            int status = 0

            String zipName = "stash_${stashName}.zip"

            while (retries++ < times) {
                try {
                    print("Try to make unstash №${retries}")

                    String storageURLCredential = getSuitableStorageURL()
                    String storageSSHPortCredential = getSuitableStoragePort()

                    withCredentials([string(credentialsId: storageURLCredential, variable: "REMOTE_HOST"), string(credentialsId: storageSSHPortCredential, variable: "SSH_PORT")]) {
                        if (isUnix()) {
                            status = sh(returnStatus: true, script: '$CIS_TOOLS/downloadFiles.sh' + " \"${remotePath}\" . " + '$REMOTE_HOST $SSH_PORT')
                        } else {
                            status = bat(returnStatus: true, script: '%CIS_TOOLS%\\downloadFiles.bat' + " \"${remotePath}\" . " + '%REMOTE_HOST% %SSH_PORT%')
                        }
                    }

                    if (status == 23) {
                        println("[ERROR] Failed to download stash")
                    } else if (status == 24) {
                        print("[ERROR] Partial transfer due to vanished source files")
                    } else if (status != 0) {
                        println("[ERROR] Download script returned non-zero code: ${status}")
                    } else {
                        break
                    }
                } catch (FlowInterruptedException e1) {
                    println("[INFO] Making of unstash of stash with name '${stashName}' was aborting.")
                    throw e1
                } catch(e1) {
                    println(e1.toString())
                    println(e1.getMessage())
                    println(e1.getStackTrace())
                }
            }

            if (unzip) {
                if (isUnix()) {
                    stdout = sh(returnStdout: true, script: "unzip -o -u \"${zipName}\"")
                } else {
                    stdout = bat(returnStdout: true, script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " \"${zipName}\" -aoa")
                }
            }

            if (debug) {
                println(stdout)
            }
            if (unzip) {
                if (isUnix()) {
                    sh "rm -rf \"${zipName}\""
                } else {
                    bat "del \"${zipName}\""
                }
            }
        } else {
            unstash(stashName)
        }
    } catch (e) {
        println("Failed to unstash stash with name '${stashName}'")
        println(e.toString())
        println(e.getMessage())
        throw e
    }

}
