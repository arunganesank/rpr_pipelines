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
        timeout(time: 20, unit: "MINUTES") {
            try {
                utils.removeDir(this, "Windows", "C:\\Users\\${env.USERNAME}\\AppData\\Local\\Temp")
                println("Cleaned ${agentName}")
            } catch (Exception e) {
                println("An error occured: ${e}")
            }
        }
    }
}


def clean() {
    def nodeList = getNodes("Windows Tester")

    Map nodesTasks = [:]
    
    for(i = 0; i < nodeList.size(); i++) {
        def agentName = nodeList[i]

        if (agentName != env.NODE_NAME && agentName != null) {
            println "Removing %TEMP% on " + agentName
            nodesTasks[agentName] = {
                cleanTemp(agentName)
            }
        }
    }

    parallel nodesTasks
}


def call(){
    timestamps {
        stage("Remove Temp directory") {
            node("Windows && PreBuild") {
                clean()
            }
        }
    }
}