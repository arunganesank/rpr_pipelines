import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
     * Download files using sh/bat from CIS_TOOLS (based on ssh & rsync)
     * @param server_path - full path to the folder on the server
     * @param local_path - full path to the folder on the target PC 
     * @param customKeys - custom keys of rsync command (e.g. include/exclude)
     * @param remoteHost - remote host url (default - NAS)
*/

def call(
    String server_path,
    String local_path,
    String customKeys = "",
    Boolean clearEnv = true,
    String remoteHost = "nasURL",
    String sshPort = "nasSSHPort",
    Boolean canBeAbsent = false
) {
    int times = 3
    int retries = 0
    String scriptFile = clearEnv ? "downloadFilesSync" : "downloadFiles"

    while (retries++ < times) {
        print("Try to download files with rsync №${retries}")
        try {
            int status = 0

            withCredentials([string(credentialsId: remoteHost, variable: 'REMOTE_HOST'), string(credentialsId: sshPort, variable: "SSH_PORT")]) {
                // Avoid warnings connected with using Groovy String interpolation with credentials
                // See docs for more details: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
                if (isUnix()) {
                    status = sh(returnStatus: true, 
                        script: '$CIS_TOOLS/' + "${scriptFile}.sh \"${server_path}\" \"${local_path}\" " + '$REMOTE_HOST $SSH_PORT' + " \"${customKeys}\" ")
                } else {
                    status = bat(returnStatus: true, 
                        script: '%CIS_TOOLS%\\' + "${scriptFile}.bat \"${server_path}\" \"${local_path}\" " + '%REMOTE_HOST% %SSH_PORT%' + " \"${customKeys}\" ")
                }
            }

            if (status == 0) {
                return
            } else {
                print("[ERROR] Rsync returned non-zero exit code: ${status}")
            }
        } catch (FlowInterruptedException error) {
            println("[INFO] Job was aborted during downloading files from ${remoteHost}.")
            throw error
        } catch(e) {
            println(e.toString())
            println(e.getMessage())
            println(e.getStackTrace())
            sleep(60)
        }

        if (canBeAbsent) {
            println("[WARNING] Failed to download files. All attempts has been exceeded")
        } else {
            throw new Exception("Failed to download files. All attempts has been exceeded")
        }
    }
}
