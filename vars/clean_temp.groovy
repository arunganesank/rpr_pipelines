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
        stage("Cleaning Temp directory on ${agentName}") {
            File temp = new File("%APPDATA%\\Local\\Temp")
            FileUtils.cleanDirectory(temp)
        }
    }
}


def clean() {
    def nodeList = getNodes("Windows")
    
    for(i = 0; i < nodeList.size(); i++) {
        def agentName = nodeList[i]

        if (agentName != null) {
            println "Cleaning %TEMP% on " + agentName
            cleanTemp(agentName)
        }
    }
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