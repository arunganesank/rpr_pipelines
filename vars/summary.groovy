import groovy.transform.Field

import utils

@Field final List jobUrls = [
    "https://rpr.cis.luxoft.com/job/RenderStudio-Weekly",
    "https://rpr.cis.luxoft.com/job/RPR-BlenderPlugin-Weekly",
    "https://rpr.cis.luxoft.com/job/RPR-MayaPlugin-Weekly"
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


def call() {
    timestamps {
        stage("Building summary") {
            node("Windows && PreBuild") {
                ws("WS/Summary") {
                    for (url in jobUrls) {
                        def parsedJob = doRequest("${url}/api/json")
                        def jobName = parsedJob["name"]
                        def parsedBuild = doRequest("${parsedJob["lastCompletedBuild"]["url"]}/api/json")
                        def result = parsedBuild["result"]
                        def buildUrl = parsedBuild["url"]
                        currentBuild.description += "<span><a href='${buildUrl}'>${jobName}</a> status: ${result}.</span><br/><br/>"
                    }
                }
            }
        }
    }
}