def collectArtifacts(machine, osName) {
    utils.createDir(this, machine)
    dir(machine) {
        utils.moveFiles(this, osName, "../*.log", ".")
        utils.moveFiles(this, osName, "../*.LOG", ".")
    }
    archiveArtifacts artifacts: "${machine}/*.log, ${machine}/*.LOG", allowEmptyArchive: true
}


def call(nodesLabels, driverIdentificator) {
    def windowsUpdateTasks = [:]
    def driversDir = "driver_installation_dir"
    windowsNodes = nodesByLabel "${nodesLabels}"
    
    windowsNodes.each() { machine ->
        windowsUpdateTasks["${machine}"] = {
            
            stage("Install_${machine}") {
                node("${machine}") {
                    if (!fileExists(driversDir)) {
                        utils.createDir(this, driversDir)
                    }
                    ws(driversDir) {
                        cleanWs()
                        driversSelection.updateDriver(params.driverIdentificator, "Windows", machine, "", env.WORKSPACE)
                        collectArtifacts(machine, "Windows")
                    }
                }
            }
        }
    }
    
    parallel windowsUpdateTasks
    return 0
}
