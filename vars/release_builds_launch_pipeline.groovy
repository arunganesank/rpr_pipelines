def getProblemsCount(String buildUrl, String jobName) {
    try {
        withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
            buildUrl = buildUrl.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
        }

        def parsedInfo = utils.doRequest(this, "${buildUrl}/artifact/summary_status.json")
        return ["failed": parsedInfo["failed"], "error": parsedInfo["error"]]
    } catch(e) {
        return null
    }
}


def launchAndWaitBuild(String jobName,
                       String projectRepo,
                       String projectBranch,
                       String pipelineBranch,
                       String testsPackage,
                       String customHybridProWindowsLink,
                       String customHybridProUbuntuLink) {
    String[] jobNameParts = env.JOB_NAME.split("/")
    jobNameParts[-1] = jobName
    String targetJobPath = jobNameParts.join("/")

    String[] jobUrlParts = env.JOB_URL.split("/")
    jobUrlParts[-1] = jobName
    String targetJobUrl = jobUrlParts.join("/")

    build(
        job: targetJobPath,
        parameters: [
            string(name: "projectRepo", value: projectRepo),
            string(name: "Branch", value: projectBranch),
            string(name: "PipelineBranch", value: pipelineBranch),
            string(name: "TestsPackage", value: testsPackage),
            string(name: "customHybridProWindowsLink", value: customHybridProWindowsLink),
            string(name: "customHybridProUbuntuLink", value: customHybridProUbuntuLink)
        ],
        wait: false,
        quietPeriod : 0
    )

    String targetBuildUrl = utils.getTriggeredBuildLink(this, targetJobUrl)

    while (utils.getBuildInfo(this, targetBuildUrl).inProgress 
        && (utils.getBuildInfo(this, targetBuildUrl).description == null || !utils.getBuildInfo(this, targetBuildUrl).description.contains("Version"))) {
        // waiting until the triggered build initialize description
        sleep(60)
    }
 
    String description = buildDescriptionLine(env.BUILD_URL, "Original")
    utils_description.addOrUpdateDescription(this, [env.BUILD_URL, targetBuildUrl], description, "Original")

    description = buildDescriptionLine(targetBuildUrl, jobName)
    utils_description.addOrUpdateDescription(this, [env.BUILD_URL, targetBuildUrl], description, jobName)

    while(true) {
        if (!utils.getBuildInfo(this, targetBuildUrl).inProgress) {
            description = buildDescriptionLine(targetBuildUrl, jobName)
            utils_description.addOrUpdateDescription(this, [env.BUILD_URL, targetBuildUrl], description, jobName)
            break
        }

        sleep(60)
    }
}


def call(List projects) {
    timestamps {
        currentBuild.description = ""

        def tasks = [:]

        for (int i = 0; i < projects.size(); i++) {
            def project = projects[i]
            String stageName = project["jobName"]

            try {
                tasks[stageName] = {
                    stage(stageName) {
                        launchAndWaitBuild(project["jobName"],
                                           project["projectRepo"], 
                                           project["projectBranch"],
                                           project["pipelineBranch"],
                                           project["testsPackage"],
                                           project["customHybridProWindowsLink"],
                                           project["customHybridProUbuntuLink"])
                    }
                }
            } catch (e) {
                currentBuild.result = "FAILURE"
                println "Exception: ${e.toString()}"
                println "Exception message: ${e.getMessage()}"
                println "Exception stack trace: ${e.getStackTrace()}"
            }
        }

        parallel tasks
    }
}