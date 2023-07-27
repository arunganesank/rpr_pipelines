def addOrUpdateDescription(String buildUrl, String newLine, String jobName) {
    Integer buildNumber = buildUrl.split("/")[-1] as Integer
    String[] jobParts = buildUrl.replace(env.JENKINS_URL + "job/", "").replace("/${buildNumber}/", "").split("/job/")

    def item = Jenkins.instance

    for (part in jobParts) {
        item = item.getItem(part)
    }

    def build = item.getBuildByNumber(buildNumber)

    if (build.description != null) {
        List lines = build.description.split("<br/>") as List

        boolean lineReplaced = false

        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i]
            if (line.contains(jobName)) {
                lines[i] = newLine.replace("<br/>", "")
                build.description = lines.join("<br/>")
                lineReplaced = true
            }
        }

        if (!lineReplaced) {
            build.description += newLine
        }
    }
}


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


def buildDescriptionContent(String jobName, String buildUrl, String reportLink, String logsLink) {
    def buildInfo = checkBuildResult(buildUrl)

    String statusDescription = ""

    if (buildInfo.inProgress) {
        statusDescription = "(autotests are in progress)"
    } else {
        currentBuild.result = buildInfo.result

        Map problems = getProblemsCount(buildUrl, jobName)

        if (problems) {
            if (problems["failed"] > 0 && problems["error"] > 0) {
                statusDescription = "(${problems.failed} failed / ${problems.error} error)"
            } else if (problems["failed"] > 0) {
                statusDescription = "(${problems.failed} failed)"
            } else if (problems["error"] > 0) {
                statusDescription = "(${problems.error} error)"
            }
        } else {
            statusDescription = "(could not receive autotests results)"
        }
    }

    if (buildInfo.inProgress) {
        return "<span style='color: #5FBC34; font-size: 150%'>${jobName} tests are in progress. <a href='${reportLink}'>Test report</a></span><br/><br/>"
    } else {
        if (buildInfo.result == "FAILURE") {
            return "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests are Failed. <a href='${reportLink}'>Test report</a> ${statusDescription}</span><br/><br/>"
        } else if (buildInfo.result == "UNSTABLE") {
            return "<span style='color: #b7950b; font-size: 150%'>${jobName} tests are Unstable. <a href='${reportLink}'>Test report</a> ${statusDescription}</span><br/><br/>"
        } else if (buildInfo.result == "SUCCESS") {
            return "<span style='color: #5FBC34; font-size: 150%'>${jobName} tests are Success. <a href='${reportLink}'>Test report</a> ${statusDescription}</span><br/><br/>"
        } else {
            return "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests with unexpected status. <a href='${reportLink}'>Test report</a> ${statusDescription}</span><br/><br/>"
        }
    }
}


def buildDescriptionLine(String buildUrl, String jobName) {
    String messageContent = ""

    switch (jobName) {
        case "Houdini":
            String reportLink = "${buildUrl}/Test_20Report_2019_2e5_2e640_5fHybridPro"
            String logsLink = "${buildUrl}/artifact"
            return buildDescriptionContent(jobName, buildUrl, reportLink, logsLink)
        default: 
            String reportLink = "${buildUrl}/Test_20Report_20HybridPro"
            String logsLink = "${buildUrl}/artifact"
            return buildDescriptionContent(jobName, buildUrl, reportLink, logsLink)
    }
}


def checkBuildResult(String buildUrl) {
    withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
        buildUrl = buildUrl.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
    }

    def parsedInfo = utils.doRequest(this, "${buildUrl}/api/json?tree=result,description,inProgress")

    return parsedInfo
}


def getTriggeredBuildLink(String jobUrl) {
    String url = "${jobUrl}/api/json?tree=lastBuild[number,url]"

    withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
        url = url.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
    }

    def parsedInfo = utils.doRequest(this, url)

    return parsedInfo.lastBuild.url
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

    build(
        job: targetJobPath,
        parameters: [
            string(name: "projectRepo", value: projectRepo),
            string(name: "projectBranch", value: projectBranch),
            string(name: "pipelineBranch", value: pipelineBranch),
            string(name: "testsPackage", value: testsPackage),
            string(name: "customHybridProWindowsLink", value: customHybridProWindowsLink),
            string(name: "customHybridProUbuntuLink", value: customHybridProUbuntuLink)
        ],
        wait: false,
        quietPeriod : 0
    )

    String targetBuildUrl = getTriggeredBuildLink(targetJobPath)

    while(true) {
        if (!checkBuildResult(targetBuildUrl).inProgress) {
            String description = buildDescriptionLine(targetBuildUrl, jobName)
            addOrUpdateDescription(env.BUILD_URL, description, jobName)
            addOrUpdateDescription(targetBuildUrl, description, jobName)
            break
        }

        sleep(60)
    }
}


def call(List projects) {
    timestamps {
        def tasks = [:]

        for (int i = 0; i < projects.size(); i++) {
            def project = projects[i]
            String stageName = project["jobName"]

            try {
                tasks[stageName] = {
                    stage(stageName) {
                        launchAndWaitBuild(project["jobName"]
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