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
                String temp = "C:\\Users\\${env.USERNAME}\\AppData\\Local\\Temp"
                utils.removeDir(this, "Windows", temp)
                println(bat (script: "if exist \"${temp}\" dir \"${temp}\" else echo \"Temp dir doesn't exist\"", returnStdout: true))
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