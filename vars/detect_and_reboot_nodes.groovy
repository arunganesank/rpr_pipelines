def call(String EXECUTING_NODE) {
    node(EXECUTING_NODE) {
        downloadFiles("/volume1/CIS/PDUs/*.csv", ".", "", true)
        withCredentials([string(credentialsId: 'jenkinsURL', variable: 'JN_URL'), usernamePassword(credentialsId: 'jenkinsCredentials', passwordVariable: 'JN_PASSWORD', usernameVariable: 'JN_USERNAME')]) {
            if (isUnix()) {
                python3("-m pip install -r ${CIS_TOOLS}/autoreboot/requirements.txt")
                print(python3("${CIS_TOOLS}/autoreboot/detect_and_reboot_nodes.py --pdus_file \"${WORKSPACE}/pdus.csv\" --nodes_file \"${WORKSPACE}/nodes_mapping.csv\""))
            } else {
                python3("-m pip install -r ${CIS_TOOLS}\\autoreboot\\requirements.txt")
                print(python3("${CIS_TOOLS}\\autoreboot\\detect_and_reboot_nodes.py --pdus_file \"${WORKSPACE}\\pdus.csv\" --nodes_file \"${WORKSPACE}\\nodes_mapping.csv\""))
            }
        }
    }
}