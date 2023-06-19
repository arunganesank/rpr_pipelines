import groovy.transform.Field

@Field final String ORG_PART = "--organizationid de28e25c-ded6-4ff0-be77-e4ee2330f77c"

def getAnydeskId() {
    if(isUnix()) {
        return sh(script:"anydesk --get-id", returnStdout: true).trim()
    } else {
        return bat(script: """@echo off
            for /f "delims=" %%i in ('"C:\\Program Files (x86)\\AnyDesk\\AnyDesk.exe" --get-id') do set ID=%%i 
            echo %ID%""", returnStdout: true).trim()
    }
}

def updateNodeLogin(String jnNodeName, String itemId, Map tasks) {
    tasks[jnNodeName] = {
        stage(jnNodeName) {
            def newAnydeskId
            try {
                if (env.NODE_NAME != jnNodeName) {
                    node(jnNodeName) {
                        newAnydeskId = getAnydeskId()
                    }
                } else {
                    newAnydeskId = getAnydeskId()
                }
                sh """
                    set +x
                    bw get item ${itemId} | jq ".login.username=\\"${newAnydeskId}\\"" | bw encode | bw edit item ${itemId} > /dev/null
                """
            } catch(e) {
                currentBuild.result = "UNSTABLE"
                println("[ERROR] Failed to update anydesk id for ${jnNodeName}")
                println(e.toString())
            }
        }
    }
}

def updateIdInCollection(String collection, Map tasks) {
    def collectionId = sh(script: "bw get collection ${ORG_PART} ${collection} | jq -r .id", returnStdout: true).trim()
    def itemIds = sh(script: """bw list items --collectionid ${collectionId} | jq -r '.[] | select(has("fields")) | select(.fields[].name == "JENKINS_NODE") | .id'""",
                    returnStdout: true).trim()
    if(itemIds) {
        for(itemId in itemIds.split("\n")) {
            def jnNodeName = sh(script: """bw get item ${itemId} | jq -r '.fields[] | select(.name == "JENKINS_NODE") | .value'""", returnStdout: true).trim()
            updateNodeLogin(jnNodeName, itemId, tasks)
        }
    }
}

def call(String collections, String nodes) {
    def tasks = [:]
    def sessionKey
    def bwCreds = input message: 'Please enter your Vaultwarden credentials',
        parameters: [string(name: 'BW_EMAIL', trim: true), password(name: 'BW_PASSWORD')]

    // TODO: use special label
    node("BitWarden") {
        try {
            withEnv(["BW_PASSWORD=${bwCreds['BW_PASSWORD']}"]) {
                sessionKey = sh(script: "bw login ${bwCreds['BW_EMAIL']} --passwordenv BW_PASSWORD --raw", returnStdout: true).trim()
            }

            withEnv(["BW_SESSION=${sessionKey}"]) {
                sh "bw sync"
                if(collections) {
                    for(collection in collections.split(',')) {
                        updateIdInCollection(collection, tasks)
                    }
                }
                if(nodes) {
                    for(nodeName in nodes.split(',')) {
                        def itemId = sh(script: """bw list items ${ORG_PART} | jq -r '.[] | select(has("fields")) | select(.fields[].name == "JENKINS_NODE") | select(.fields[].value == "${nodeName}") | .id'""",
                                        returnStdout: true).trim()
                        updateNodeLogin(nodeName, itemId, tasks)
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