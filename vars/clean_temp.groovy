import groovy.transform.Field
import jenkins.model.*


@NonCPS
def getNodes(String labels) {
    jenkins.model.Jenkins.instance.nodes.collect { thisAgent ->
        if (thisAgent.labelString.contains("${labels}")) {
            return thisAgent.name
        }
    }
}


def cleanTemp(String agentName) {
    node("${agentName}") {
        timeout(time: 10, unit: "MINUTES") {
            try {
                bat """set folder=\"C:\\Users\\${env.USERNAME}\\AppData\\Local\\Temp\"
                    cd /d %folder%
                    for /F \"delims=\" %%i in ('dir /b') do (rmdir \"%%i\" /s/q || del \"%%i\" /s/q)"""
            } catch (Exception e) {
                println("An error occured: ${e}")
            }
            println("${agentName} is DONE")
        }
    }
}


def clean() {
    def nodeList = getNodes("Windows Tester")

    Map nodesTasks = [:]
    
    for(i = 0; i < nodeList.size(); i++) {
        def agentName = nodeList[i]

        if (agentName != env.NODE_NAME && agentName != null) {
            nodesTasks[agentName] = {
                cleanTemp(agentName)
            }
        }
    }

    parallel nodesTasks
}


def call(){
    timestamps {
        stage("Clean Temp directory") {
            node("Windows && PreBuild") {
                clean()
            }
        }
    }
}