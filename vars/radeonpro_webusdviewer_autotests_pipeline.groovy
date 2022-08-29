import groovy.transform.Field

@Field final String AUTOTESTS_REPO = "git@github.com:luxteam/jobs_test_web_viewer.git"


def call(String testsBranch, String tests, String instance) {
    timestamps {
        stage("Run autotests") {
            node("Windows && WebUSDViewer") {
                timeout(time: 20, unit: 'MINUTES') {
                    ws("WS/WebUsdViewer_Autotests") {
                        cleanWS("Windows")

                        checkoutScm(branchName: testsBranch, repositoryUrl: AUTOTESTS_REPO)

                        String configPrefix = "testing"

                        if (instance != "prod") {
                            dir("Utils/ConfigurationFiles") {
                                String content = readFile("${configPrefix}Config.yaml")
                                content = content.replace("webusd", "${instance}.webusd")
                                writeFile(file: "${configPrefix}Config.yaml", text: content)
                            }
                        }

                        dir("scripts") {
                            withEnv(["PATH=c:\\python37\\;${PATH}"]) {
                                bat """
                                    run.bat ${tests} ${configPrefix} >> ..\\autotests.log 2>&1
                                """

                                bat """
                                    save_report.bat >> ..\\build_report.log 2>&1
                                """
                            }
                        }

                        publishHTML([allowMissing: false,
                            alwaysLinkToLastBuild: false,
                            keepAll: true,
                            reportDir: "Report",
                            reportFiles: "index.html",
                            reportName: "Test Report"]
                        )
                    }
                }
            }
        }
    }
}