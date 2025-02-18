import java.util.concurrent.atomic.AtomicBoolean

/**
 * Class which provides flexible rebuilding of test reports
 */
public class ReportUpdater {

    def context
    def env
    def options
    def reportType
    def jobsLauncherPath

    // locks for each report (to prevent updating of report by two parallel branches of build)
    Map locks = [:]

    /**
     * Main constructor
     *
     * @param context
     * @param env env variable of the current pipeline
     * @param options map with options
     */
    ReportUpdater(context, env, options, reportType=ReportType.DEFAULT) {
        this.context = context
        this.env = env
        this.options = options
        this.reportType = reportType
        this.jobsLauncherPath = options.containsKey("jobsLauncherPath") ? options["jobsLauncherPath"] : "jobs_launcher"
    }

    /**
     * Init function (it prepares necessary environment to rebuild reports in future)
     * 
     * @param buildArgsFunc fuction to get string with arguments for build script
     */
    def init(def buildArgsFunc) {
        String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/".replace(" ", "_")
        String matLibUrl

        context.withCredentials([
            context.string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
            context.string(credentialsId: "nasSSHPort", variable: "SSH_PORT"),
            context.string(credentialsId: "matLibUrl", variable: "MATLIB_URL")
        ]) {
            String testRepo = options.testRepo.contains("git@github.com:") ? options.testRepo.replace("git@github.com:", "https://github.com/") : options.testRepo
            context.bat('%CIS_TOOLS%\\clone_test_repo.bat' + ' %REMOTE_HOST% %SSH_PORT%' + " ${remotePath} ${testRepo} ${options.testsBranch} ${jobsLauncherPath}")
            matLibUrl = context.MATLIB_URL
        }

        String locations = ""
        String reportFiles = "summary_report.html"
        String reportFilesNames = "Overview Report"
        String rebuiltScript

        switch(reportType) {
            case ReportType.DEFAULT:
                rebuiltScript = context.libraryResource(resource: "update_report_template_default.sh")
                break
            case ReportType.COMPARISON:
                rebuiltScript = context.libraryResource(resource: "update_report_template_comparison.sh")
                break
            default:
                throw Exception("Unknown report type: ${reportType}")
        }

        String publishReportFiles = options.containsKey("reportFiles") ? options["reportFiles"] : "summary_report.html, performance_report.html, compare_report.html"
        String publishReportTitles = options.containsKey("reportTitles") ? options["reportTitles"] : "Summary Report, Performance Report, Compare Report"

        if (options.testProfiles) {
            options.testProfiles.each { profile ->
                String profileName

                if (options.containsKey("displayingTestProfiles")) {
                    profileName = options.displayingTestProfiles[profile]
                } else {
                    profileName = profile
                }

                String reportName = "Test Report ${profileName}"

                if (options.testProfiles.size() > 1  || options.streamingTools) {
                    // publish, but do not create links to reports (they'll be accessible through overview report)
                    context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", publishReportFiles, \
                        reportName, publishReportTitles, options.storeOnNAS, \
                        ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName, "updatable": true])
                } else {
                    // pass links for builds with only one profile
                    context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", publishReportFiles, \
                        reportName, publishReportTitles, options.storeOnNAS, \
                        ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])
                }

                String rebuildScriptCopy = rebuiltScript

                rebuildScriptCopy = rebuildScriptCopy.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                    .replace("<report_path>", ("../" * (jobsLauncherPath.split("/").length + 1)) + reportName.replace(" ", "_")) \
                    .replace("<build_script_args>", buildArgsFunc(profileName, options)) \
                    .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", context.env.JENKINS_URL) \
                    .replace("<matlib_url>", matLibUrl)

                // replace DOS EOF by Unix EOF
                rebuildScriptCopy = rebuildScriptCopy.replaceAll("\r\n", "\n")

                context.writeFile(file: "update_report_${profile}.sh", text: rebuildScriptCopy)

                context.uploadFiles("update_report_${profile}.sh", "${remotePath}/jobs_test_repo/${jobsLauncherPath}")

                locks[profile] = new AtomicBoolean(false)

                updateReport(profile, false)

                String publishedReportName = reportName.replace(" ", "_")
                locations = locations ? "${locations}::${publishedReportName}" : "${publishedReportName}"

                reportFiles += ",../${reportName.replace(' ', '_')}/summary_report.html"
                reportFilesNames += ",${profileName} Report"
            }

            // do not build an overview report for builds with only one profile
            // exception: StreamingSDK Latency reports
            if (options.testProfiles.size() > 1 || options.streamingTools) {
                context.println("[INFO] Publish overview report")

                // add overview report
                String reportName = "Test Report"

                context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", reportFiles, \
                    reportName, reportFilesNames, options.storeOnNAS, \
                    ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

                if (options.streamingTools) {
                    rebuiltScript = context.libraryResource(resource: "update_overview_latency_report_template.sh")
                } else {
                    rebuiltScript = context.libraryResource(resource: "update_overview_report_template.sh")
                }

                // take only first 4 arguments: tool name, commit sha, project branch name and commit message
                String buildScriptArgs = (buildArgsFunc("", options ).split() as List).subList(0, 4).join(" ")

                rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                    .replace("<report_path>", ("../" * (jobsLauncherPath.split("/").length + 1)) + reportName.replace(" ", "_")) \
                    .replace("<locations>", locations).replace("<build_script_args>", buildScriptArgs) \
                    .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", context.env.JENKINS_URL).replace("<credentials>", "none")

                // replace DOS EOF by Unix EOF
                rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

                context.writeFile(file: "update_report.sh", text: rebuiltScript)

                context.uploadFiles("update_report.sh", "${remotePath}/jobs_test_repo/${jobsLauncherPath}")

                locks["default"] = new AtomicBoolean(false)

                updateReport()
            }
        } else {
            String reportName = "Test Report"

            context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", publishReportFiles, \
                reportName, publishReportTitles, options.storeOnNAS, \
                ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

            rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                .replace("<report_path>", ("../" * (jobsLauncherPath.split("/").length + 1)) + reportName.replace(" ", "_")).replace("<build_script_args>", buildArgsFunc(options)) \
                .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", context.env.JENKINS_URL) \
                .replace("<matlib_url>", matLibUrl)

            // replace DOS EOF by Unix EOF
            rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

            context.writeFile(file: "update_report.sh", text: rebuiltScript)

            context.uploadFiles("update_report.sh", "${remotePath}/jobs_test_repo/${jobsLauncherPath}")

            locks["default"] = new AtomicBoolean(false)

            updateReport()
        }
    }

    /**
     * Function to update report
     * 
     * @param profile (optional) profile of the target report (if project supports splitting by profiles)
     * @param updateOverviewReport (optional) specify should overview report be updated or not (default - true). For builds with only one profile this param is ignored
     */
    def updateReport(String profile = "", Boolean updateOverviewReport = true) {
        String lockKey = profile ?: "default"
        String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/jobs_test_repo/${jobsLauncherPath}".replace(" ", "_")

        try {
            if (locks[lockKey].compareAndSet(false, true)) {
                String scriptName = profile ? "update_report_${profile}.sh" : "update_report.sh"

                context.withCredentials([context.string(credentialsId: "nasURL", variable: "REMOTE_HOST"), context.string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                    if (context.isUnix()) {
                        context.sh(script: '$CIS_TOOLS/update_report.sh' + ' $REMOTE_HOST $SSH_PORT' + " ${remotePath} ${scriptName}")
                    } else {
                        context.bat(script: '%CIS_TOOLS%\\update_report.bat' + ' %REMOTE_HOST% %SSH_PORT%' + " ${remotePath} ${scriptName}")
                    }
                }

                locks[lockKey].getAndSet(false)
            } else {
                context.println("[INFO] Report update skipped")
            }
        } catch (e) {
            context.println("[ERROR] Can't update test report")
            context.println(e.toString())
            context.println(e.getMessage())

            locks[lockKey].getAndSet(false)
        }

        if (profile && (options.testProfiles.size() > 1 || options.streamingTools) && updateOverviewReport) {
            // update overview report
            updateReport()
        }
    }

}
