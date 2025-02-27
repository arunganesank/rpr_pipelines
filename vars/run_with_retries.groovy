def shoudBreakRetries(labels) {
    // retries should be broken if it isn't first try (some other nodes are excluded) and there isn't any suitable online node
    return labels.contains('!') && nodesByLabel(label: labels, offline: false).size() == 0
}

def abortOldBuilds(Map options) {
    if (env.CHANGE_URL) {
        String[] jobParts = env.JOB_NAME.split("/")
        def item = Jenkins.instance

        for (part in jobParts) {
            item = item.getItem(part)
        }

        def lastBuild = item.lastBuild

        if (lastBuild.number > (env.BUILD_NUMBER as int)) {
            currentBuild.build().@result = Result.fromString("ABORTED")
            throw new Exception("Aborted by new commit")
        }
    }
}


def call(String labels, def stageTimeout, def retringFunction, Boolean reuseLastNode, def stageName, def options, Integer maxNumberOfRetries = -1, Boolean checkIsExceptionAllowed = false, String osName = "", Boolean setBuildStatus = false) {
    List nodesList = nodesByLabel label: labels, offline: true
    println "[INFO] Found ${nodesList.size()} suitable nodes"
    // if 0 suitable nodes are found - wait some node in loop
    while (nodesList.size() == 0) {
        println "[INFO] Couldn't find suitable nodes. Search will be retried after pause"
        if (!options.nodeNotFoundMessageSent) {
            node ("Windows") {
                SlackUtils.sendMessageToWorkspaceChannel(this, '', "Failed to find any node with labels '${labels}'", SlackUtils.Color.RED, SlackUtils.SlackWorkspace.LUXCIS, 'zabbix_critical')
                options.nodeNotFoundMessageSent = true
            }
        }
        sleep(time: 5, unit: 'MINUTES')
        nodesList = nodesByLabel label: labels, offline: true
    }
    options.nodeNotFoundMessageSent = false
    println "Found the following PCs: ${nodesList}"
    def nodesCount = nodesList.size()
    def tries = nodesCount
    def closedChannelRetries = 0

    if (reuseLastNode) {
        tries++
    } else {
        // if there is only one suitable machine and reuseLastNode is false - forcibly add one more try
        if (tries == 1) {
            tries++
            reuseLastNode = true
        }
    }

    if (maxNumberOfRetries > 0 && tries > maxNumberOfRetries) {
        tries = maxNumberOfRetries
    }

    Boolean successCurrentNode = false   

    String title = ""
    if (stageName == "Build") {
        title = osName
    } else if (stageName == "Test") {
        title = options['stageName']
    } else if (stageName == "PreBuild") {
        title = "Jenkins build configuration"
    } else {
        title = "Building test report"
    }

    String statusCheckStageName = options.containsKey("customStageName") ? options["customStageName"] : stageName

    // add flexible labels if it's necessary
    String combinedLabels = labels

    boolean preconditionFailed = false

    for (int i = 0; i < tries; i++) {
        successCurrentNode = false

        String nodeName = ""
        options['currentTry'] = i
        options['nodeReallocateTries'] = tries

        abortOldBuilds(options)

        try {
            // check that there is at least one suitable online node and break retries if not (except waiting of first node)
            if (shoudBreakRetries(labels)) {
                // if some nodes were rebooted - they should be up in 10 minutes
                sleep(time: 10, unit: 'MINUTES')
                if (shoudBreakRetries(labels)) {
                    // no one node was found. Try to do last retry with previous set of labels
                    def labelsParts = labels.split("&&") as List
                    labelsParts.removeAt(labelsParts.size() - 1)
                    labels = labelsParts.join("&&")
                    tries = i + 2
                    println("Do last try")
                    continue
                }
            }

            try {
                if (stageName == "Test" && options.containsKey("testsPreCondition")) {
                    while (!options["testsPreCondition"](options)) {
                        sleep(60)
                    }
                }

                if (stageName == "Deploy" && options.containsKey("deployPreCondition")) {
                    while (!options["deployPreCondition"](options)) {
                        sleep(60)
                    }
                }
            } catch (e) {
                preconditionFailed = true
                throw e
            }

            if (options.containsKey("getFlexibleLabels")) {
                String flexibleLabels = options["getFlexibleLabels"](options)
                if (flexibleLabels) {
                    combinedLabels += " && ${flexibleLabels}"
                }
            }

            abortOldBuilds(options)

            def findNode = {
                node(combinedLabels) {
                    try {
                        timeout(time: "${stageTimeout}", unit: 'MINUTES') {
                            String stageNameWS = stageName == "PreBuild" ? "Build" : stageName

                            ws("WS/${options.PRJ_NAME}_${stageNameWS}") {
                                abortOldBuilds(options)

                                nodeName = env.NODE_NAME
                                retringFunction(nodesList, i)
                                successCurrentNode = true

                                if (stageName != 'Test' && options.problemMessageManager) {
                                    options.problemMessageManager.clearErrorReasons(stageName, osName) 
                                }

                                if (stageName == 'Build') {
                                    if (options.containsKey("finishedBuildStages")) {
                                        options["finishedBuildStages"][osName] = [successfully: true]
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (stageName == "Build" && !options["ignoreBuildStageCleanWS"]) {
                            print("[INFO] cleanWS due to failed Build stage")
                            cleanWS(osName)
                        }

                        throw e
                    }
                }
            }

            List requiredLock = null
            if (stageName == "Test" && options["requiredLock"]) {
                requiredLock = options["requiredLock"](options)
            }

            if (requiredLock) {
                lock(label: requiredLock[0], quantity: requiredLock[1], resource : null, skipIfLocked: true, variable: "LIVE_MODE_LOCK") {
                    findNode()
                }
            } else {
                findNode()
            }
        } catch(Exception e) {
            Boolean isExceptionAllowed = !checkIsExceptionAllowed

            if (e instanceof ExpectedExceptionWrapper) {
                if (e.abort) {
                    println("[ERROR] Detected abort flag in catched exception")
                    i = tries - 1
                } else if (e.retry) {
                    println("[INFO] Retry detected. Exception is allowed")
                    isExceptionAllowed = true
                }

                if (e.getCause()) {
                    e = e.getCause()

                    if (e instanceof ExpectedExceptionWrapper) {
                        if (e.abort) {
                            println("[ERROR] Detected abort flag in catched exception")
                            i = tries - 1
                        } else if (e.retry) {
                            println("[INFO] Retry detected. Exception is allowed")
                            isExceptionAllowed = true
                        }

                        if (e.getCause()) {
                            e = e.getCause()
                        }
                    }
                }
            }

            String exceptionClassName = e.getClass().toString()

            if (exceptionClassName.contains("FlowInterruptedException")) {
                e.getCauses().each(){
                    // UserInterruption aborting by user
                    // ExceededTimeout aborting by timeout
                    // CancelledCause for aborting by new commit
                    String causeClassName = it.getClass().toString()
                    println "Interruption cause: ${causeClassName}"
                    if (causeClassName.contains("CancelledCause")) {
                        if (options.problemMessageManager) {
                            options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.BUILD_ABORTED_BY_COMMIT, stageName, osName) 
                        }
                        println "[INFO] GOT NEW COMMIT"
                        GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.BUILD_ABORTED_BY_COMMIT)
                        throw e
                    } else if (causeClassName.contains("UserInterruption") || causeClassName.contains("ExceptionCause")) {
                        if (options.problemMessageManager) {
                            options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.BUILD_ABORTED_BY_USER, stageName, osName) 
                        }
                        println "[INFO] Build was aborted by user"
                        GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.BUILD_ABORTED_BY_USER)
                        throw e
                    }
                }
            } else if (exceptionClassName.contains("RemotingSystemException")) {

                isExceptionAllowed = true
                
                try {
                    // take Windows node for send exception in Slack channel
                    node ("Windows") {
                        SlackUtils.sendMessageToWorkspaceChannel(this, '', "${nodeName}: RemotingSystemException appeared. Node is going to be marked as offline", SlackUtils.Color.RED, SlackUtils.SlackWorkspace.LUXCIS, 'zabbix_critical')
                        utils.markNodeOffline(this, nodeName, "RemotingSystemException appeared. This node was marked as offline")
                        SlackUtils.sendMessageToWorkspaceChannel(this, '', "${nodeName}: Node was marked as offline", SlackUtils.Color.RED, SlackUtils.SlackWorkspace.LUXCIS, 'zabbix_critical')
                    }
                } catch (e2) {
                    node ("Windows") {
                        SlackUtils.sendMessageToWorkspaceChannel(this, '', "Failed to mark node '${nodeName}' as offline", SlackUtils.Color.RED, SlackUtils.SlackWorkspace.LUXCIS, 'zabbix_critical')
                    }
                }

            } else if (exceptionClassName.contains("ClosedChannelException") || exceptionClassName.contains("IOException") || exceptionClassName.contains("RequestAbortedException")
                || exceptionClassName.contains("InterruptedException") || exceptionClassName.contains("AgentOfflineException")) {

                isExceptionAllowed = true
                GithubNotificator.updateStatus(statusCheckStageName, title, "failure", options, NotificationConfiguration.LOST_CONNECTION_WITH_MACHINE)
            }

            println("[ERROR] Failed on ${env.NODE_NAME} node")
            println("Exception: ${e.toString()}")
            println("Exception message: ${e.getMessage()}")
            println("Exception cause: ${e.getCause()}")
            println("Exception stack trace: ${e.getStackTrace()}")

            if (utils.isTimeoutExceeded(e)) {
                GithubNotificator.updateStatus(statusCheckStageName, title, "timed_out", options, NotificationConfiguration.STAGE_TIMEOUT_EXCEEDED)
            }

            if (!isExceptionAllowed || preconditionFailed) {
                if (!isExceptionAllowed) {
                    println("[INFO] Exception isn't allowed")
                } else {
                    println("[INFO] PreCondition failed")
                }

                if (options.problemMessageManager) {
                    if (stageName == 'Test') {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.SOME_TESTS_FAILED, stageName, osName)
                    } else if (utils.isTimeoutExceeded(e)) {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.TIMEOUT_EXCEEDED, stageName, osName)
                    } else {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.UNKNOWN_REASON, stageName, osName)
                    }
                }
                GithubNotificator.updateStatus(statusCheckStageName, title, "action_required", options)
                if (stageName == 'Build') {
                    GithubNotificator.failPluginBuilding(options, osName)

                    if (options.containsKey("finishedBuildStages")) {
                        options["finishedBuildStages"][osName] = [successfully: false]
                    }
                }
                if (setBuildStatus) {
                    currentBuild.result = "FAILURE"
                }
                throw e
            } else {
                println("[INFO] Exception found in allowed exceptions")
            }

            if (i == tries - 1) {
                if (options.problemMessageManager) {
                    if (stageName == 'Test') {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.SOME_TESTS_FAILED, stageName, osName)
                    } else if (utils.isTimeoutExceeded(e)) {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.TIMEOUT_EXCEEDED, stageName, osName)
                    } else {
                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.UNKNOWN_REASON, stageName, osName)
                    }
                }
                GithubNotificator.updateStatus(statusCheckStageName, title, "action_required", options)
                if (stageName == 'Build') {
                    GithubNotificator.failPluginBuilding(options, osName)

                    if (options.containsKey("finishedBuildStages")) {
                        options["finishedBuildStages"][osName] = [successfully: false]
                    }
                }
                println "[ERROR] All nodes on ${stageName} stage with labels ${combinedLabels} failed."
                if (setBuildStatus) {
                    currentBuild.result = "FAILURE"
                }
                throw e
            }
        }

        if (successCurrentNode) {
            i = tries + 1
        // exclude label of failed machine only if it isn't necessary to reuse last node and if it isn't last try
        } else if (!(reuseLastNode && i == nodesCount + closedChannelRetries - 1) && nodeName) {
            println "[EXCLUDE] ${nodeName} from nodes pool (Labels: ${combinedLabels})"
            combinedLabels += " && !${nodeName}"
        } else if (!nodeName) {
            // if ClosedChannelException appeared on 'node' block - add additional try
            tries++
            closedChannelRetries++
            options['nodeReallocateTries']++
            println("[INFO] Additional retry failed")
        }
    }
}