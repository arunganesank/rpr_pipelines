def call() {

    String remotePath = "/volume1/Stash/${env.JOB_NAME}/${env.BUILD_NUMBER}/"

    try {
        withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST")]) {
            if (isUnix()) {
                sh """
                    ${CIS_TOOLS}/removeStashes.sh $REMOTE_HOST ${remotePath}
                """
            } else {
                bat """
                    %CIS_TOOLS%\\removeStashes.bat %REMOTE_HOST% ${remotePath}
                """
            }
        }
    } catch (e) {
        println("Failed to remove stashes")
        println(e.toString())
        println(e.getMessage())
        throw e
    }

}
