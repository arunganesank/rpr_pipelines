import groovy.transform.Field

import utils

def doRequest(String url) {
    def rawInfo = httpRequest(
        url: url,
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    return parseResponse(rawInfo.content)
}


def getJobsUrls(){
    def parsedJobs = doRequest("https://rpr.cis.luxoft.com/view/Weekly%20Jobs/api/json")
    def jobUrls = []
    for (job in parsedJobs["jobs"]){
        jobUrls.add(job["url"])
    }
    return jobUrls
}


def generateInfo(){
    def jobUrls = getJobsUrls()
    for (jobUrl in jobUrls){
        def parsedJob = doRequest("${jobUrl}api/json")
        def jobName = parsedJob["name"]

        def parsedBuild = doRequest("${parsedJob["lastCompletedBuild"]["url"]}api/json")
        def buildResult = parsedBuild["result"]

        try{
            def parsedSummary = doRequest("${parsedJob["lastCompletedBuild"]["url"]}artifact/summary_status.json")
            return ["failed": parsedSummary["failed"], "error": parsedSummary["error"]]

            Map problems = getProblemsCount(jobUrl)
            String problemsDescription = ""

            if (problems["failed"] > 0 && problems["error"] > 0) {
                problemsDescription = "(${problems.failed} failed / ${problems.error} error)"
            } else if (problems["failed"] > 0) {
                problemsDescription = "(${problems.failed} failed)"
            } else if (problems["error"] > 0) {
                problemsDescription = "(${problems.error} error)"
            }

            if (buildResult == "FAILURE") {
                currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests are Failed. ${problemsDescription}</span><br/><br/>"
            } else if (buildResult == "UNSTABLE") {
                currentBuild.description += "<span style='color: #b7950b; font-size: 150%'>${jobName} tests are Unstable. ${problemsDescription}</span><br/><br/>"
            } else if (buildResult == "SUCCESS") {
                currentBuild.description += "<span style='color: #5FBC34; font-size: 150%'>${jobName} tests are Success. ${problemsDescription}</span><br/><br/>"
            } else {
                currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests with unexpected status. ${problemsDescription}</span><br/><br/>"
            }
        } catch (Exception e) {
            currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>Failed to get ${jobName} summary_status.json.</span><br/><br/>"
        }
    }
}


def call() {
    timestamps {
        stage("Parsing weekly results") {
            node("Windows && PreBuild") {
                ws("WS/WeeklyResults") {
                    currentBuild.description = ""
                    generateInfo()
                    }
                }
            }
        }
    }
}