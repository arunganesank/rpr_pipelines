import groovy.transform.Synchronized


def getReportEndpoint(String testsName) {
    switch (testsName) {
        case "USD-HoudiniPlugin-Release":
            return "/Test_20Report_2019_2e5_2e640_5fHybridPro"
        default:
            return "/Test_20Report_20HybridPro"
    }
}


def getProblemsCount(String buildUrl, String jobName) {
    try {
        withCredentials([string(credentialsId: "jenkinsInternalURL", variable: "JENKINS_INTERNAL_URL")]) {
            buildUrl = buildUrl.replace(env.JENKINS_URL, JENKINS_INTERNAL_URL)
        }

        def parsedInfo = utils.doRequest(this, "${buildUrl}/artifact/summary_status.json")
        return ["failed": parsedInfo["failed"], "error": parsedInfo["error"], "total": parsedInfo["total"]]
    } catch(e) {
        return null
    }
}


@NonCPS
@Synchronized
def addOrUpdateDescription(List builds, String description, String jobName) {
    utils_description.addOrUpdateDescription(context: this,
                                             buildUrls: builds,
                                             newLine: description,
                                             testsName: jobName)
}


def launchAndWaitBuild(String jobName,
                       String projectRepo,
                       String projectBranch,
                       String pipelineBranch,
                       String testsPackage,
                       String customHybridProWindowsLink,
                       String customHybridProUbuntuLink,
                       List trackingBuilds,
                       int buildsExpecting) {
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

    trackingBuilds.add(targetBuildUrl)

    while (trackingBuilds.size() < buildsExpecting) {
        // waiting until all builds will be created
        sleep(60)
    }
 
    String description = utils_description.buildDescriptionLine(context: this,
                                                                buildUrl: env.BUILD_URL,
                                                                testsName: "Original")
    addOrUpdateDescription(trackingBuilds, description, "Original")

    description = utils_description.buildDescriptionLine(context: this,
                                                         buildUrl: targetBuildUrl,
                                                         testsName: jobName,
                                                         problems: null,
                                                         reportEndpoint: getReportEndpoint(jobName))
    addOrUpdateDescription(trackingBuilds, description, jobName)

    while(true) {
        if (!utils.getBuildInfo(this, targetBuildUrl).inProgress) {
            Map problems = getProblemsCount(targetBuildUrl, jobName)
            description = utils_description.buildDescriptionLine(context: this,
                                                                 buildUrl: targetBuildUrl,
                                                                 testsName: jobName,
                                                                 problems: problems,
                                                                 reportEndpoint: getReportEndpoint(jobName))
            addOrUpdateDescription(trackingBuilds, description, jobName)
            return problems
        }

        sleep(60)
    }
}


def call(String pipelineBranch, 
         String testsPackage,
         String customHybridProWindowsLink,
         String customHybridProUbuntuLink,
         String tagName,
         List projects) 
{

    timestamps {
        currentBuild.description = ""

        String failsCount = 0
        String totalCount = 0

        def tasks = [:]

        List trackingBuilds = [env.BUILD_URL]

        for (int i = 0; i < projects.size(); i++) {
            def project = projects[i]
            String stageName = project["jobName"]

            tasks[stageName] = {
                try {
                    Map problems = stage(stageName) {
                        launchAndWaitBuild(project["jobName"],
                                           project["projectRepo"], 
                                           project["projectBranch"],
                                           pipelineBranch,
                                           testsPackage,
                                           customHybridProWindowsLink,
                                           customHybridProUbuntuLink,
                                           trackingBuilds,
                                           // +1 is for the original build
                                           projects.size() + 1)
                    }

                    failsCount += problems["failed"]
                    totalCount += problems["total"]
                } catch (e) {
                    currentBuild.result = "FAILURE"
                    println "Exception: ${e.toString()}"
                    println "Exception message: ${e.getMessage()}"
                    println "Exception stack trace: ${e.getStackTrace()}"
                }
            }
        }

        parallel tasks

        withCredentials([string(credentialsId: "ReleasesNotifiedEmails", variable: "RELEASES_NOTIFIED_EMAILS")]) {
            String emailBody = "<span style='font-size: 150%'>Results (regression builds):</span><br/><br/>${currentBuild.description}<br/><br/><br/>"

            if (currentBuild.result == "FAILURE" || (failsCount > totalCount * 0.2)) {
                // errors appeared or more that 20% of tests are failed
                String currentBuildRestartUrl = "${env.JOB_URL}/buildWithParameters?pipelineBranch=${pipelineBranch}&TestsPackage=${testsPackage}&customHybridProWindowsLink=${customHybridProWindowsLink}"
                currentBuildRestartUrl += "&customHybridProUbuntuLink=${customHybridProUbuntuLink}&tagName=${tagName}&delay=0sec"
                String nextBuildStartUrl = ""

                if (testsPackage == "regression") {
                    nextBuildStartUrl = "${env.JOB_URL}/buildWithParameters?pipelineBranch=${pipelineBranch}&TestsPackage=Full&customHybridProWindowsLink=${customHybridProWindowsLink}"
                    nextBuildStartUrl += "&customHybridProUbuntuLink=${customHybridProUbuntuLink}&tagName=${tagName}&delay=0sec"
                }

                emailBody += "<span style='font-size: 150%'>Actions:</span><br/><br/>"
                emailBody += "<span style='font-size: 150%'>1. <a href='${currentBuildRestartUrl}'>Restart current builds</a></span><br/><br/>"

                if (nextBuildStartUrl) {
                    emailBody += "<span style='font-size: 150%'>2. <a href='${nextBuildStartUrl}'>Start Full builds for plugins</a></span><br/><br/>"
                }
            } else {
                build(
                    job: env.JOB_URL,
                    parameters: [
                        string(name: "pipelineBranch", value: pipelineBranch),
                        string(name: "TestsPackage", value: "Full"),
                        string(name: "customHybridProWindowsLink", value: customHybridProWindowsLink),
                        string(name: "customHybridProUbuntuLink", value: customHybridProUbuntuLink),
                        string(name: "tagName", value: tagName)
                    ],
                    wait: false,
                    quietPeriod : 0
                )

                sleep(60)

                String nextBuildUrl = utils.getTriggeredBuildLink(this, env.JOB_URL)
                emailBody += "<span style='font-size: 150%'>No errors appeared and only a small part of tests failed. Full tests for plugins were started automatically: <a href='${nextBuildUrl}'>Build link</a></span><br/><br/>"
            }

            if (testsPackage == "regression") {
                mail(to: RELEASES_NOTIFIED_EMAILS, subject: "[HYBRIDPRO RELEASE: REGRESSION] ${tagName} autotests results", mimeType: 'text/html', body: emailBody)
            } else {
                mail(to: RELEASES_NOTIFIED_EMAILS, subject: "[HYBRIDPRO RELEASE: FULL] ${tagName} autotests results", mimeType: 'text/html', body: emailBody)
            }
        }
    }
}