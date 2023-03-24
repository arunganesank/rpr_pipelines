import groovy.transform.Field
import utils


@Field final String QA_REPORTS_REPO = "https://github.com/EvgeniyaKornishova/qa_reports_generator.git"


def doReportGeneration(String qaReportsBranch) {
    checkoutScm(branchName: qaReportsBranch, repositoryUrl: QA_REPORTS_REPO)

    withCredentials([
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'QAReports_ChartsExport', usernameVariable: 'JIRA_AMD_USERNAME', passwordVariable: 'JIRA_AMD_PASSWORD'],
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'QAReports_BugsListExport', usernameVariable: 'JIRA_USERNAME', passwordVariable: 'JIRA_TOKEN'],
        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkinsCredentials', usernameVariable: 'JENKINS_USERNAME', passwordVariable: 'JENKINS_TOKEN'],
        string(credentialsId: 'QAReports_PRStatusesExport', variable: 'GITHUB_TOKEN'),
        string(credentialsId: 'QAReports_TasksExport', variable: 'CONFLUENCE_TOKEN')
    ]) {
        python3("-m pip install -r requirements.txt")
        python3("main.py")
    }

    String ARTIFACT_NAME = "report.docx"
    makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: true)
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
