import groovy.transform.Field
import org.apache.commons.io.FileUtils
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
            File temp = new File("C:\\Users\\${env.USERNAME}\\AppData\\Local\\Temp")
            if (temp.exists()) {
                println("Cleaning %TEMP% on ${agentName}")
                try {
                    FileUtils.cleanDirectory(temp)
                    println("Cleaned ${agentName}")
                } catch (Exception e) {
                    println("An error occured: ${e}")
                }
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
            println "Cleaning %TEMP% on " + agentName
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