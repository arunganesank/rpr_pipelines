import utils

def call(String project = "") {
    if (!project) {
        project = getProjectName()
    }

    if (isManualJob()) {
        println("BuildDiscarderProperty will use settings for manual job.")

        properties([[$class: 'BuildDiscarderProperty', strategy:
            [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '40']]])
    } else if (isWeeklyJob()) {
        println("BuildDiscarderProperty will use settings for weekly job.")

        properties([[$class: 'BuildDiscarderProperty', strategy:
            [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '90', numToKeepStr: '20']]])
    } else if (isAutoJob()) {
        println("BuildDiscarderProperty will use settings for auto job.")

        if (isMasterBranch() && isCustomBranch()) {
            properties([[$class: 'BuildDiscarderProperty', strategy:
                [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '90', numToKeepStr: '20']]])
        } else if (isReleaseBranch() || isTag()) {
            properties([[$class: 'BuildDiscarderProperty', strategy:
                [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '360', numToKeepStr: '20']]])
        } else if (isPR()) {
            properties([[$class: 'BuildDiscarderProperty', strategy:
                [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '5']]])
        }
    } else {
        println("BuildDiscarderProperty will use default settings.")

        properties([[$class: 'BuildDiscarderProperty', strategy:
            [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '20']]])
    }
}


def getProjectName(){
    if (env.JOB_NAME.contains("Maya")) {
        return "Maya"
    } else if (env.JOB_NAME.contains("Blender")) {
        return "Blender"
    } else if (env.JOB_NAME.contains("Max")) {
        return "Max"
    } else if (env.JOB_NAME.contains("USDViewer")) {
        return "USDViewer"
    } else if (env.JOB_NAME.contains("Core")) {
        return "Core"
    } else if (env.JOB_NAME.contains("MaterialLibrary")) {
        return "MaterialLibrary"
    } else if (env.JOB_NAME.contains("ShooterGameAuto") || env.JOB_NAME.contains("ToyShopAuto") || env.JOB_NAME.contains("VictorianTrainsAuto")) {
        return "HybrudUE"
    } else {
        return "Default"
    }
}


def isWeeklyJob(){
    return env.JOB_NAME.contains("-Weekly") ? true : false
}


def isManualJob(){
    return env.JOB_NAME.contains("-Manual") ? true : false
}


def isAutoJob(){
    return env.JOB_NAME.contains("-Auto") ? true : false
}


def isMasterBranch(){
    return env.BRANCH_NAME && (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "main")
}


def isReleaseBranch(){
    return env.BRANCH_NAME && (env.BRANCH_NAME == "release")
}


def isCustomBranch(){
    return env.BRANCH_NAME && env.BRANCH_NAME != "master" && env.BRANCH_NAME != "main" && env.BRANCH_NAME != "release"
}


def isPR(){
    return env.CHANGE_URL
}


def isTag(){
    return env.TAG_NAME
}


def isCisDevelopJob(){
    return env.JOB_NAME.startsWith("Dev")
}