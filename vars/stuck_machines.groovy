def checkNodes() {
    // Get all the nodes
    def nodes = Jenkins.instance.nodes

    nodes.each { node ->
        checkComputer(node)
    }
}


def checkComputer(node) {
    def computer = node.getComputer()
    if (computer.online) {
        def stuckExecutors = 0

        if (computer.countBusy() > 0) {
            def executors = computer.executors
            executors.each { executor ->
                if (executor.isLikelyStuck()) {
                    echo "Executor ${executor.displayName} on node ${node.displayName} is likely stuck"
                    stuckExecutors += 1
                }
            }
        }

        // Check if all executors on the node are stuck
        if (node.getNumExecutors() == stuckExecutors) {
            String def message = "Node ${node.displayName} is likely stuck"
            echo message
            SlackUtils.sendMessageToWorkspaceChannel(this, '', message, SlackUtils.Color.ORANGE, SlackUtils.SlackWorkspace.LUXCIS, 'cis_notifications')
        }
    }
}

def call() {
    // main function
    timestamps {
        stage("Check if node is stuck") {
            checkNodes()
        }
    }
}