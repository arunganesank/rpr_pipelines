import groovy.transform.Field

import utils

@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def doRequest(String url) {
    def rawInfo = httpRequest(
        url: url,
        authentication: 'jenkinsCredentials',
        httpMode: 'GET'
    )

    return parseResponse(rawInfo.content)
}


def getJobs(){
    def parsedJobs = doRequest("https://rpr.cis.luxoft.com/view/Weekly%20Jobs/api/json")
    def jobs = []
    for (job in parsedJobs["jobs"]){
        if (!(job["name"].contains("StreamingSDK")) && !(job["name"].contains("QA"))){
            jobs.add(job)
        }
    }
    return jobs
}


def getProblemsCount(String jobName, String buildUrl){
    summaryList = [
        "BlenderHIP-WeeklyCUDA_CPU",
        "BlenderHIP-WeeklyHIP_CPU",
        "BlenderHIP-WeeklyHIP_CUDA",
        "HybridPro-MTLX-Weekly",
        "MaterialXvsHdRPR-Weekly",
        "RenderStudio-Weekly",
        "RPR-Anari-Weekly",
        "USD-InventorPlugin-Weekly",
        "USD-Viewer-Weekly"
        ]

    overviewList = [
        "HdRPR-Weekly",
        "RPR-BlenderPlugin-Weekly",
        "RPR-MayaPlugin-Weekly",
        "USD-BlenderPlugin-Weekly",
        "USD-HoudiniPlugin-Weekly",
        "USD-MayaPlugin-Weekly"
        ]

    try{
        if (jobName == "WML-Weekly"){
            println("First")
            def parsedReport = doRequest("${buildUrl}allure/data/suites.json")
            def failed = 0

            println(parsedReport)
            for (caseInfo in parsedReport){
                if (caseInfo["status"] == "failed"){
                    failed += 1
                }
            }
            return ["_": ["failed": failed, "error": 0]]

        } else if (overviewList.contains(jobName)){
            println("Second")
            def preparedUrl = buildUrl.replaceAll("rpr.cis", "cis.nas")

            def parsedReport = doRequest("${preparedUrl}Test_Report/overview_report.json")
            def problems = []

            for (engine in parsedReport){
                def failed = 0
                def error = 0

                for (platform in engine["platforms"]){
                    println(platform["summary"])
                    failed += platform["summary"]["failed"]
                    error += platform["summary"]["error"]
                }

                problems.add([engine: ["failed": failed, "error": error]])
            }

            return problems
        } else if (summaryList.contains(jobName)){
            println("Third")
            def preparedUrl = buildUrl.replaceAll("rpr.cis", "cis.nas")

            def parsedReport = doRequest("${preparedUrl}Test_Report/summary_report.json")
            def failed = 0
            def error = 0

            for (gpu in parsedReport){
                println(parsedReport["gpu"]["summary"])
                failed += parsedReport[gpu]["summary"]["failed"]
                error += parsedReport[gpu]["summary"]["error"]
            }

            return ["_": ["failed": failed, "error": error]]
        }
    } catch (Exception e){
        println("Can't get report for ${jobName}")
    }
}


def generateInfo(){
    def jobs = getJobs()
    println("Jobs: ${jobs}")
    for (job in jobs){
        def parsedJob = doRequest("${job["url"]}api/json")

        if (parsedJob["lastCompletedBuild"] != null){
            def jobName = parsedJob["name"]
            def buildUrl = parsedJob["lastCompletedBuild"]["url"]
            def parsedBuild = doRequest("${buildUrl}api/json")
            def buildResult = parsedBuild["result"]

            println("Job: ${jobName}. Result: ${buildResult}")

            if (buildResult == "SUCCESS"){
                currentBuild.description += "<span style='color: #5FBC34; font-size: 150%'>${jobName} tests are Success.</span><br/><br/>"
            }

            try{
                problems = getProblemsCount(jobName, buildUrl)

                String problemsDescription = ""

                for (problem in problems){
                    if (problem != "_"){
                        problemsDescription += "${problem}:<br/>"
                    }
                    if (problems[problem]["failed"] > 0 && problems[problem]["error"] > 0) {
                        problemsDescription += "(${problems.failed} failed / ${problems.error} error)"
                    } else if (problems["failed"] > 0) {
                        problemsDescription += "(${problems.failed} failed)"
                    } else if (problems["error"] > 0) {
                        problemsDescription += "(${problems.error} error)"
                    }
                }

                if (buildResult == "FAILURE") {
                    currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests are Failed.<br/>${problemsDescription}</span><br/><br/>"
                } else if (buildResult == "UNSTABLE") {
                    currentBuild.description += "<span style='color: #b7950b; font-size: 150%'>${jobName} tests are Unstable.<br/>${problemsDescription}</span><br/><br/>"
                } else {
                    currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests with unexpected status.<br/>${problemsDescription}</span><br/><br/>"
                }
            } catch (Exception e) {
                currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>Failed to get ${jobName} report.</span><br/><br/>"
                println(e)
            }
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