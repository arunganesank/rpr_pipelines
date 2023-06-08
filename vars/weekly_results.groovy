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
        def problems = []
        if (jobName == "WML-Weekly"){
            def parsedReport = doRequest("${buildUrl}allure/data/suites.json")
            def parsedCases = parsedReport["children"][0]["children"][0]["children"][0]["children"]
            def failed = 0

            for (caseInfo in parsedCases){
                if (caseInfo["status"] == "failed"){
                    failed += 1
                }
            }
            println(["Results": ["failed": failed, "error": 0]])
            problems.add(["Results": ["failed": failed, "error": 0]])

        } else if (overviewList.contains(jobName)){
            def preparedUrl = buildUrl.replaceAll("rpr.cis.luxoft.com/job", "cis.nas.luxoft.com")

            def parsedReport = doRequest("${preparedUrl}Test_Report/overview_report.json")

            parsedReport.each { engine, value ->
                println("Engine: ${engine}")
                def failed = 0
                def error = 0
                value.platforms.each { platform, info ->
                    failed += info.summary.failed
                    error += info.summary.error
                }

                println([(engine): ["failed": failed, "error": error]])
                problems.add([(engine): ["failed": failed, "error": error]])
                
            }

        } else if (summaryList.contains(jobName)){
            def preparedUrl = buildUrl.replaceAll("rpr.cis.luxoft.com/job", "cis.nas.luxoft.com")
            def parsedReport = null

            if (jobName == "RenderStudio-Weekly"){
                parsedReport = doRequest("${preparedUrl}Test_Report_Desktop/summary_report.json")
            } else {
                parsedReport = doRequest("${preparedUrl}Test_Report/summary_report.json")
            }
            def failed = 0
            def error = 0

            if (jobName == "MaterialXvsHdRPR-Weekly"){
                parsedReport.each { gpu, value ->
                    println("GPU: ${gpu}")
                    value.each { engine, results ->
                        failed += results.failed
                        error += results.error
                    }              
                }
            } else {
                parsedReport.each { gpu, value ->
                    println("GPU: ${gpu}")
                    failed += value.summary.failed
                    error += value.summary.error               
                }
            }

            println(["Results": ["failed": failed, "error": error]])
            problems.add(["Results": ["failed": failed, "error": error]])
        }
        return problems
    } catch (Exception e){
        println("Can't get report for ${jobName}")
        println(e)
    }
}


def generateInfo(){
    def jobs = getJobs()
    println("Jobs: ${jobs}")
    for (job in jobs){
        def parsedJob = doRequest("${job.url}api/json")

        if (parsedJob.lastCompletedBuild != null){
            def jobName = parsedJob.name
            def buildUrl = parsedJob.lastCompletedBuild.url
            def parsedBuild = doRequest("${buildUrl}api/json")
            def buildResult = parsedBuild.result

            println("Job: ${jobName}. Result: ${buildResult}")

            if (buildResult == "SUCCESS"){
                currentBuild.description += "<span style='color: #5FBC34; font-size: 150%'>${jobName} tests are Success.</span><br/><br/>"
                continue
            }

            if (jobName.startsWith("BlenderHIP")) {
                continue
            }
            
            try {
                problems = getProblemsCount(jobName, buildUrl)
                println(problems)

                String problemsDescription = ""

                problems.each { result ->
                    result.each { key, value ->
                        println(value)
                        if (value.failed > 0 && value.error > 0) {
                            if (key != "Results"){
                                problemsDescription += "${key}: "
                            } else {
                                problemsDescription += "Results: "
                            }
                            problemsDescription += "${value.failed} failed / ${value.error} error<br/>"
                        } else if (value.failed > 0) {
                            if (key != "Results"){
                                problemsDescription += "${key}: "
                            } else {
                                problemsDescription += "Results: "
                            }
                            problemsDescription += "${value.failed} failed<br/>"
                        } else if (value.error > 0) {
                            if (key != "Results"){
                                problemsDescription += "${key}: "
                            } else {
                                problemsDescription += "Results: "
                            }
                            problemsDescription += "${value.error} error<br/>"
                        }
                    }
                }

                if (buildResult == "FAILURE") {
                    currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests are Failed.</span><br/><span style='color: #b03a2e'>${problemsDescription}</span><br/><br/>"
                } else if (buildResult == "UNSTABLE") {
                    currentBuild.description += "<span style='color: #b7950b; font-size: 150%'>${jobName} tests are Unstable.</span><br/><span style='color: #b7950b'>${problemsDescription}</span><br/><br/>"
                } else {
                    currentBuild.description += "<span style='color: #b03a2e; font-size: 150%'>${jobName} tests with unexpected status.</span><br/><span style='color: #b03a2e'>${problemsDescription}</span><br/><br/>"
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