import groovy.transform.Field

@Field final String ORG_PART = "--organizationid de28e25c-ded6-4ff0-be77-e4ee2330f77c"

def updateAnydeskPassword(String newPassword) {
    withEnv(["NEW_ANYDESK_PASS=${newPassword}"]) {
        if(isUnix()) {
            def uname = sh script: "uname", returnStdout: true
            boolean isMacOS = uname.startsWith("Darwin")

            // TODO: remove debug code
            if (isMacOS) {
                sh "echo [DEBUG] ${env.NODE_NAME}: change password to \$NEW_ANYDESK_PASS"
                // sh "echo \$NEW_ANYDESK_PASS | sudo /Applications/AnyDesk.app/Contents/MacOS/AnyDesk --set-password"
            } else {
                sh "echo [DEBUG] ${env.NODE_NAME}: change password to \$NEW_ANYDESK_PASS"
                // sh "echo \$NEW_ANYDESK_PASS | sudo anydesk --set-password"
            }
        } else {
            bat "echo [DEBUG] ${env.NODE_NAME}: change password to %NEW_ANYDESK_PASS%"
            // bat "echo %NEW_ANYDESK_PASS% | anydesk.exe --set-password"
        }
    }
}

def updatePasswordsInCollection(String collection) {
    def tasks = [:]
    def collectionId = sh(script: "bw get collection ${ORG_PART} ${collection} | jq -r .id", returnStdout: true).trim()
    def itemIds = sh(script: """bw list items --collectionid ${collectionId} | jq -r '.[] | select(has("fields")) | select(.fields[].name == "JENKINS_NODE") | .id'""",
                    returnStdout: true).trim().split("\n")
    for(itemId in itemIds) {
        def curItemId = itemId
        def jnNodeName = sh(script: """bw get item ${itemId} | jq -r '.fields[] | select(.name == "JENKINS_NODE") | .value'""", returnStdout: true).trim()
        def newPassword = sh(script: "bw generate", returnStdout: true).trim()
        tasks[jnNodeName] = {
            try {
                stage(jnNodeName) {
                    node(jnNodeName) {
                        updateAnydeskPassword(newPassword)
                    }
                    withEnv(["NEW_PASS=${newPassword}"]) {
                        sh """bw get item ${curItemId} | jq ".login.password=\\"\$NEW_PASS\\"" | bw encode | bw edit item ${curItemId}"""
                    }
                }
            } catch(e) {
                currentBuild.result = "UNSTABLE"
                println("[ERROR] Failed to update password for ${jnNodeName}")
                println(e.toString())
            }
        }
    }

    parallel tasks
}

def call(String collections) {
    def sessionKey
    def bwCreds = input message: 'Please enter your Vaultwarden credentials',
        parameters: [string(name: 'BW_EMAIL', trim: true), password(name: 'BW_PASSWORD')]

    // TODO: use special label
    node("PC-TESTER-TBILISI-UBUNTU20") {
        try {
            withEnv(["BW_PASSWORD=${bwCreds['BW_PASSWORD']}"]) {
                sessionKey = sh(script: "bw login ${bwCreds['BW_EMAIL']} --passwordenv BW_PASSWORD --raw", returnStdout: true).trim()
            }

            withEnv(["BW_SESSION=${sessionKey}"]) {
                sh "bw sync"
                for(collection in collections.split(',')) {
                    updatePasswordsInCollection(collection)
                }
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