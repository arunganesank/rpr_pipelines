def call()
{
    timestamps {
        def unixUpdateTasks = [:]
        def unixNodes = nodesByLabel "(Ubuntu || Ubuntu20 || Ubuntu22 || OSX || MacOS) && !StreamingSDK && !NoReboot"

        println("---SELECTED NODES:")
        println(unixNodes)

        unixNodes.each() {
            unixUpdateTasks["${it}"] = {
                stage("Update ${it}") {
                    node("${it}") {
                        timeout(time: 3, unit: "MINUTES") {
                            sh """
                                echo "Restarting Unix Machine...."
                                hostname
                                sudo reboot &
                            """
                        }
                    }
                }
            }
        }
        
        parallel unixUpdateTasks
        return 0
    }
}

call()