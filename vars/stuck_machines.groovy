def checkNodes() {
    // Get all the nodes
    def nodes = Jenkins.instance.nodes

    List stuckNodes = []

    nodes.each { nodeObject ->
        String nodeName = checkComputer(nodeObject)

        if (nodeName != null) {
            stuckNodes.add(nodeName)
        }
    }

    return stuckNodes
}


def checkComputer(def nodeObject) {
    def computer = nodeObject.getComputer()
    if (computer.online) {
        def stuckExecutors = 0

        if (computer.countBusy() > 0) {
            def executors = computer.executors
            executors.each { executor ->
                if (executor.isLikelyStuck()) {
                    echo "Executor ${executor.displayName} on node ${nodeObject.displayName} is likely stuck"
                    stuckExecutors += 1
                }
            }
        }

        // Check if all executors on the node are stuck
        if (nodeObject.getNumExecutors() == stuckExecutors) {
            return nodeObject.displayName
        }
    }

    return null
}

def call() {
    // main function
    timestamps {
        stage("Check if node is stuck") {
            List stuckNodes = checkNodes()

            stuckNodes.each() { nodeName ->
                SlackUtils.sendMessageToWorkspaceChannel(this,
                                                         "", 
                                                         "Node ${nodeName} is likely stuck",
                                                         SlackUtils.Color.ORANGE,
                                                         SlackUtils.SlackWorkspace.LUXCIS,
                                                         "cis_notifications")
            }
        }
    }
}