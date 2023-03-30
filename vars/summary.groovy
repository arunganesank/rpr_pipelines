import groovy.transform.Field

import utils

@Field final List jobUrls = [
    "https://rpr.cis.luxoft.com/job/RenderStudio-Weekly/",
    "https://rpr.cis.luxoft.com/job/RPR-BlenderPlugin-Weekly/",
    "https://rpr.cis.luxoft.com/job/RPR-MayaPlugin-Weekly/",
    "https://rpr.cis.luxoft.com/job/RPR-BlenderPlugin-Auto/"
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


def call() {
    timestamps {
        stage("Building summary") {
            node("Windows && PreBuild") {
                ws("WS/Summary") {
                    currentBuild.description = ""
                    for (url in jobUrls) {
                        def parsedJob = doRequest("${url}api/json")
                        def jobClass = parsedJob["_class"]
                        if (jobClass.contains("multibranch")) {
                            def multiJobName = parsedJob["name"]
                            for (branch in parsedJob["jobs"]) {
                                def branchName = branch["name"]
                                def parsedBranch = doRequest("${branch["url"]}api/json")
                                def parsedBuild = doRequest("${parsedBranch["lastCompletedBuild"]["url"]}api/json")
                                def result = parsedBuild["result"]
                                def buildUrl = parsedBuild["url"]
                                def color = getColor(result)
                                currentBuild.description += "<span><a href='${buildUrl}'>${multiJobName} ${branchName}</a> status: <span style='color: ${color}'>${result}</span>.</span><br/><br/>"
                            }
                        }
                        else {
                            def jobName = parsedJob["name"]
                            def parsedBuild = doRequest("${parsedJob["lastCompletedBuild"]["url"]}api/json")
                            def result = parsedBuild["result"]
                            def buildUrl = parsedBuild["url"]
                            def color = getColor(result)
                            currentBuild.description += "<span><a href='${buildUrl}'>${jobName}</a> status: <span style='color: ${color}'>${result}</span>.</span><br/><br/>"
                        }
                    }
                }
            }
        }
    }
}