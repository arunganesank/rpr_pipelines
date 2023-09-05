import groovy.transform.Field

@Field final String ORG_PART = "--organizationid de28e25c-ded6-4ff0-be77-e4ee2330f77c"

def updateAnydeskPassword(String newPassword) {
    withEnv(["NEW_ANYDESK_PASS=${newPassword}"]) {
        if(isUnix()) {
            def uname = sh script: "uname", returnStdout: true
            boolean isMacOS = uname.startsWith("Darwin")

            if (isMacOS) {
                // TODO: gui password change
                // sh "echo \$NEW_ANYDESK_PASS | sudo /Applications/AnyDesk.app/Contents/MacOS/AnyDesk --set-password"
                println("Skipping MacOS")
            } else {
                sh "echo \$NEW_ANYDESK_PASS | sudo anydesk --set-password"
            }
        } else {
            bat "echo %NEW_ANYDESK_PASS% | \"C:\\Program Files (x86)\\AnyDesk\\AnyDesk.exe\" --set-password"
        }
    }
}

def updateNodePassword(String jnNodeName, String itemId, Map tasks) {
    def newPassword = sh(script: "bw generate", returnStdout: true).trim()
    tasks[jnNodeName] = {
        stage(jnNodeName) {
            try {
                if (env.NODE_NAME != jnNodeName) {
                    node(jnNodeName) {
                        updateAnydeskPassword(newPassword)
                    }
                } else {
                    updateAnydeskPassword(newPassword)
                }
                withEnv(["NEW_PASS=${newPassword}"]) {
                    sh """
                        set +x
                        bw get item ${itemId} | jq ".login.password=\\"\$NEW_PASS\\"" | bw encode | bw edit item ${itemId} > /dev/null
                    """
                }
            } catch(e) {
                currentBuild.result = "UNSTABLE"
                println("[ERROR] Failed to update password for ${jnNodeName}")
                println(e.toString())
            }
        }
    }
}

def updatePasswordsInCollection(String collection, Map tasks) {
    def collectionId = sh(script: "bw get collection ${ORG_PART} ${collection} | jq -r .id", returnStdout: true).trim()
    def itemIds = sh(script: """bw list items --collectionid ${collectionId} | jq -r '.[] | select(has("fields")) | select(.fields[].name == "JENKINS_NODE") | .id'""",
                    returnStdout: true).trim()
    if(itemIds) {
        for(itemId in itemIds.split("\n")) {
            def jnNodeName = sh(script: """bw get item ${itemId} | jq -r '.fields[] | select(.name == "JENKINS_NODE") | .value'""", returnStdout: true).trim()
            updateNodePassword(jnNodeName, itemId, tasks)
        }
    }
}

def call(String collections, String nodes) {
    def tasks = [:]
    def sessionKey
    def bwCreds = input message: 'Please enter your Vaultwarden credentials',
        parameters: [string(name: 'BW_EMAIL', trim: true), password(name: 'BW_PASSWORD')]

    node("BitWarden") {
        try {
            withEnv(["BW_PASSWORD=${bwCreds['BW_PASSWORD']}"]) {
                sessionKey = sh(script: "bw login ${bwCreds['BW_EMAIL']} --passwordenv BW_PASSWORD --raw", returnStdout: true).trim()
            }

            withEnv(["BW_SESSION=${sessionKey}"]) {
                sh "bw sync"
                if(collections) {
                    for(collection in collections.split(',')) {
                        updatePasswordsInCollection(collection, tasks)
                    }
                }
                if(nodes) {
                    for(nodeName in nodes.split(',')) {
                        def itemId = sh(script: """bw list items ${ORG_PART} | jq -r '.[] | select(has("fields")) | select(.fields[].name == "JENKINS_NODE") | select(.fields[].value == "${nodeName}") | .id'""",
                                        returnStdout: true).trim()
                        updateNodePassword(nodeName, itemId, tasks)
                    }
                }
                parallel tasks
            }
        } catch(e) {
            currentBuild.result = "FAILURE"
            println(e.toString())
            throw e
        } finally {
            sh "bw logout"
        }
    }
}