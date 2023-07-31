class utils_description {
    /**
     * Function that update description with test results in set of builds
     *
     * @param params Map with parameters
     * Possible elements:
     *     context - context of executable pipeline (this keyword)
     *     buildUrls - set of builds where description should be updated
     *     newLine - line that should replace the existing line for specified tests
     *     testsName - name of target tests
     */
    static def addOrUpdateDescription(def context, List buildUrls, String newLine, String testsName) {
        for (buildUrl in buildUrls) {
            Integer buildNumber = buildUrl.split("/")[-1] as Integer
            String[] jobParts = buildUrl.replace(context.env.JENKINS_URL + "job/", "").replace("/${buildNumber}/", "").split("/job/")

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
                    if (line.contains(testsName)) {
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
    }

    static private def buildDescriptionContent(def context,
                                               String testsName,
                                               Map problems,
                                               String buildUrl,
                                               String reportLink,
                                               String logsLink = null) {
        def buildInfo = context.utils.getBuildInfo(context, buildUrl)

        String statusDescription = ""

        if (buildInfo.inProgress) {
            statusDescription = "(autotests are in progress)"
        } else {
            context.currentBuild.result = buildInfo.result

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

        String textColorCode = ""
        String statusText = ""

        if (buildInfo.inProgress) {
            textColorCode = "#5fbc34"
            statusText = "are in progress"
        } else {
            if (buildInfo.result == "FAILURE") {
                textColorCode = "#b03a2e"
                statusText = "are Failed"
            } else if (buildInfo.result == "UNSTABLE") {
                textColorCode = "#b7950b"
                statusText = "are Unstable"
            } else if (buildInfo.result == "SUCCESS") {
                textColorCode = "#5fbc34"
                statusText = "are Success"
            } else {
                textColorCode = "#b03a2e"
                statusText = "finished with unexpected status"
            }
        }

        if (testsName == "Original") {
            return "<span style='color: #5FBC34; font-size: 150%'>Original build. <a href='${buildUrl}'>Build link</a></span><br/><br/>"
        } else if (logsLink) {
            return "<span style='color: ${textColorCode}; font-size: 150%'>${testsName} tests ${statusText}. <a href='${reportLink}'>Test report</a> / <a href='${logsLink}'>Logs link</a> ${statusDescription}</span><br/><br/>"
        } else {
            return "<span style='color: ${textColorCode}; font-size: 150%'>${testsName} tests ${statusText}. <a href='${reportLink}'>Test report</a> ${statusDescription}</span><br/><br/>"
        }
    }

    /**
     * Function that builds description with status for a custom build with tests
     *
     * @param params Map with parameters
     * Possible elements:
     *     context - context of executable pipeline (this keyword)
     *     buildUrl - link to the build
     *     testsName - name of target tests
     *     problems - map with test results: key - test status (failed, error, passed), value - number of cases with this status
     *     reportEndpoint (optional) - endpoint of report (default - /Test_20Report)
     *     logsEndpoint (options) - endpoint of logs (default - /artifacts). Empty value indicates that no logs link is required
     */
    static def buildDescriptionLine(Map params) {
        def context = params["context"]
        String buildUrl = params["buildUrl"]
        String testsName = params["testsName"]
        Map problems = params["problems"]
        String reportEndpoint = params.containsKey("reportEndpoint") ? params["reportEndpoint"] : "/Test_20Report"
        String logsEndpoint = params.containsKey("logsEndpoint") ? params["logsEndpoint"] : "/artifacts"

        String reportLink = "${buildUrl}${reportEndpoint}"
        String logsLink = ""

        if (logsEndpoint) {
            logsLink = "${buildUrl}${logsEndpoint}"
        } else {
            logsLink = ""
        }

        switch (testsName) {
            case "Original":
                reportLink = "${buildUrl}"
                logsLink = "${buildUrl}/artifact"
                return "<span style='color: #5FBC34; font-size: 150%'>Original build. <a href='${reportLink}'>Build link</a> / <a href='${logsLink}'>Logs link</a></span><br/><br/>"
            default:
                return buildDescriptionContent(context, testsName, problems, buildUrl, reportLink, logsLink)
        }
    }
}