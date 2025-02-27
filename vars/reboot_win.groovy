def call()
{
    timestamps {
        def windowsUpdateTasks = [:]        
        def windowsNodes = nodesByLabel "Windows && !StreamingSDK && !NoReboot"
        
        println("---SELECTED NODES:")
        println(windowsNodes)
        
        windowsNodes.each() {
            windowsUpdateTasks["${it}"] = {
                stage("Update ${it}") {
                    node("${it}") {
                        timeout(time: 3, unit: "MINUTES") {
                            bat """
                                shutdown /r /f /t 0
                            """
                        }
                    }
                }
            }
        }
        
        parallel windowsUpdateTasks
        return 0
    }
}

call()