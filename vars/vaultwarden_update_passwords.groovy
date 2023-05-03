def ORG_PART = "--organizationid de28e25c-ded6-4ff0-be77-e4ee2330f77c"

def updateAnydeskPassword(String newPassword) {
    withEnv(["NEW_ANYDESK_PASS=${newPassword}"]) {
        if(isUnix()) {
            def uname = sh script: "uname", returnStdout: true
            boolean isMacOS = uname.startsWith("Darwin")

            if (isMacOS) {
                sh "echo \$NEW_ANYDESK_PASS | sudo /Applications/AnyDesk.app/Contents/MacOS/AnyDesk --set-password"
            } else {
                sh "echo \$NEW_ANYDESK_PASS | sudo anydesk --set-password"
            }
        } else {
            bat "echo %NEW_ANYDESK_PASS% | anydesk.exe --set-password"
        }
    }
}

def updatePasswordsInCollection(String collection) {
    def tasks = [:]
    def collectionId = sh(script: "bw get collection ${ORG_PART} ${collection} | jq -r .id", returnStdout: true)
    def itemIds = sh(script: "bw list items ${ORG_PART} --collectionid ${collectionId} | jq -r '.[] | select(has(\"fields\")) | select(.fields[].name == \"JENKINS_NODE\") | .id'",
                    returnStdout: true).split("\n").trim()
    for(itemId in itemIds) {
        def jnNodeName = sh(script: "bw get item ${itemId} | jq -r '.fields[] | select(.name == \"JENKINS_NODE\") | .value'", returnStdout: true)
        def newPassword = sh(script: "bw generate", returnStdout: true)
        tasks[jnNodeName] = {
            try {
                stage(jnNodeName) {
                    node(jnNodeName) {
                        updateAnydeskPassword(newPassword)
                    }
                    withEnv(["NEW_PASS=${newPassword}"]) {
                        sh "bw get item ${itemId} | jq '.login.password=\"\$NEW_PASS\"' | bw encode | bw edit item ${itemId}"
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
                sessionKey = sh(script: "bw login ${bwCreds['BW_EMAIL']} --passwordenv BW_PASSWORD --raw", returnStdout: true)
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