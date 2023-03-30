import groovy.transform.Field

import utils

@Field final List jobUrls = [
    "https://rpr.cis.luxoft.com/job/RPR-BlenderPlugin-Auto/",
    "https://rpr.cis.luxoft.com/job/RPR-MayaPlugin-Auto/",
    "https://rpr.cis.luxoft.com/job/USD-BlenderPlugin-Auto/",
    "https://rpr.cis.luxoft.com/job/USD-MayaPlugin-Auto/",
    "https://rpr.cis.luxoft.com/job/RenderStudio-Auto/",
    "https://rpr.cis.luxoft.com/job/USD-HoudiniPlugin-Auto/",
    "https://rpr.cis.luxoft.com/job/HdRPR-Auto/",
    "https://rpr.cis.luxoft.com/job/HybridPro-Build-Auto/",
    "https://rpr.cis.luxoft.com/job/HybridPro-Unit-Auto/",
    "https://rpr.cis.luxoft.com/job/HybridPro-MTLX-Auto/",
    "https://rpr.cis.luxoft.com/job/HybridPro-SDK-Auto/",
    "https://rpr.cis.luxoft.com/job/RPR-SDK-Auto/",
    "https://rpr.cis.luxoft.com/job/GithubReleases/"
    ]


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


def getColor(String result) {
    def colors = [
        "SUCCESS": "#5fbc34",
        "UNSTABLE": "#b7950b",
        "FAILURE": "#b03a2e",
        "ABORTED": "#14141f"
    ]
    return colors[result]
}


def processUrl(String url) {
    def parsedJob = doRequest("${url}api/json")
    def jobClass = parsedJob["_class"]
    if (jobClass.contains("multibranch")) {
        def multiJobName = parsedJob["name"]
        for (branch in parsedJob["jobs"]) {
            def branchName = branch["name"]
            def parsedBranch = doRequest("${branch["url"]}api/json")
            def parsedBuild = doRequest("${parsedBranch["lastCompletedBuild"]["url"]}api/json")
            def buildUrl = parsedBuild["url"]
            def result = parsedBuild["result"]
            if (result == "FAILURE"){
                def color = getColor(result)
                currentBuild.description += "<span><a href='${buildUrl}'>${multiJobName} ${branchName}</a> status: <span style='color: ${color}'>${result}</span>.</span><br/><br/>"
            }
        }
    } else {
        def jobName = parsedJob["name"]
        if (parsedJob["lastCompletedBuild"]["url"] != null){
            def parsedBuild = doRequest("${parsedJob["lastCompletedBuild"]["url"]}api/json")
            def buildUrl = parsedBuild["url"]
            def result = parsedBuild["result"]
            if (result == "FAILURE"){
                def color = getColor(result)
                currentBuild.description += "<span><a href='${buildUrl}'>${jobName}</a> status: <span style='color: ${color}'>${result}</span>.</span><br/><br/>"
            }
        }
    }
}


def call() {
    timestamps {
        stage("Building summary") {
            node("Windows && PreBuild") {
                ws("WS/Summary") {
                    currentBuild.description = ""
                    for (url in jobUrls) {
                        processUrl(url)
                        currentBuild.description += "<br/>"
                    }
                }
            }
        }
    }
}