import groovy.transform.Field
import utils


@Field final String QA_REPORTS_REPO = "https://github.com/EvgeniyaKornishova/streaming_sdk_report_generator.git"


def doReportGeneration(String qaReportsBranch) {
    checkoutScm(branchName: qaReportsBranch, repositoryUrl: QA_REPORTS_REPO)

    withCredentials([
        string(credentialsId: 'QAReports_TasksExport', variable: 'CONFLUENCE_TOKEN'),
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkinsCredentials', usernameVariable: 'JENKINS_USERNAME', passwordVariable: 'JENKINS_TOKEN'],
        string(credentialsId: 'QAReports_PRStatusesExport', variable: 'GITHUB_TOKEN'),
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'QAReports_BugsListExport', usernameVariable: 'LUXOFT_JIRA_USERNAME', passwordVariable: 'LUXOFT_JIRA_TOKEN'],
        string(credentialsId: 'QAReports_StreamingSDKRecipientsTo', variable: 'STREAMING_SDK_EMAIL_RECIPIENTS_TO'),
        string(credentialsId: 'QAReports_StreamingSDKRecipientsCC', variable: 'STREAMING_SDK_EMAIL_RECIPIENTS_CC')
    ]) {
        python3("-m pip install -r requirements.txt")
        python3("gen_report.py")
        python3("gen_emails.py")
    }

    String REPORT_NAME = "report.docx"
    makeArchiveArtifacts(name: REPORT_NAME, storeOnNAS: true)

    String LETTER_1_NAME = "Letter_1.oft"
    makeArchiveArtifacts(name: LETTER_1_NAME, storeOnNAS: true)
    String LETTER_2_NAME = "Letter_2.oft"
    makeArchiveArtifacts(name: LETTER_2_NAME, storeOnNAS: true)
}


def call(String qaReportsBranch) {
    timestamps {
        stage("Report generation") {
            node("Windows && Builder") {
                ws("WS/QA_Report") {
                    doReportGeneration(qaReportsBranch)
                }
            }
        }
    }
}
