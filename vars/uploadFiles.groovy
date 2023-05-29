import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
     * Upload files using sh/bat from CIS_TOOLS (based on ssh & rsync)
     * @param server_path - full path to the folder on the server
     * @param local_path - full path to the folder on the target PC 
     * @param customKeys - custom keys of rsync command (e.g. include/exclude)
     * @param remoteHost - remote host url (default - NAS)
*/

def call(String local_path, String server_path, String customKeys = "", String remoteHost = "nasURL") {
    int times = 1
    int retries = 0

    while (retries++ < times) {
        print("Try to upload files with rsync №${retries}")
        try {
            int status = 0

            withCredentials([string(credentialsId: remoteHost, variable: 'REMOTE_HOST'), string(credentialsId: 'nasSSHPort', variable: 'SSH_PORT')]) {
                // Avoid warnings connected with using Groovy String interpolation with credentials
                // See docs for more details: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
                if (isUnix()) {
                    status = sh(returnStatus: true,
                        script: '$CIS_TOOLS/uploadFiles.sh' + " \"${local_path}\" \"${server_path}\" " + '$REMOTE_HOST $SSH_PORT' + " \"${customKeys}\"")
                } else {
                    status = bat(returnStatus: true,
                        script: '%CIS_TOOLS%\\uploadFiles.bat' + " \"${local_path}\" \"${server_path}\" " + '%REMOTE_HOST% %SSH_PORT%' + " \"${customKeys}\"")
                }
            }

            if (status == 0) {
                return
            } else {
                print("[ERROR] Rsync returned non-zero exit code: ${status}")
            }
        } catch (FlowInterruptedException error) {
            println("[INFO] Job was aborted during uploading files to ${remoteHost}.")
            throw error
        } catch(e) {
            println(e.toString())
            println(e.getMessage())
            println(e.getStackTrace())
            sleep(60)
        }

        throw new Exception("Failed to upload files. All attempts has been exceeded")
    }
}