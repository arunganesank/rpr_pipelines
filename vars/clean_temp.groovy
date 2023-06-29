import groovy.transform.Field
import org.apache.commons.io.FileUtils
import jenkins.model.*


@NonCPS
def getNodes(String label) {
    jenkins.model.Jenkins.instance.nodes.collect { thisAgent ->
        if (thisAgent.labelString.contains("${label}")) {
            return thisAgent.name
        }
    }
}


def cleanTemp(String agentName) {
    node("${agentName}") {
        File temp = new File("C:\\Users\\${env.USERNAME}\\AppData\\Local\\Temp")
        if (temp.exists()) {
            println("Cleaning %TEMP% on ${agentName}")
            println("Files in directory (before): ${temp.list().length}")
            try {
                FileUtils.cleanDirectory(temp)
            } catch (Exception e) {
                println("An error occured: ${e}")
            }
            println("Files in directory (after): ${temp.list().length}")
        }
    }
}


def clean() {
    def nodeList = getNodes("Windows && Tester")

    Map nodesTasks = [:]
    
    for(i = 0; i < nodeList.size(); i++) {
        def agentName = nodeList[i]

        if ((agentName != null) and (agentName != env.NODE_NAME)) {
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