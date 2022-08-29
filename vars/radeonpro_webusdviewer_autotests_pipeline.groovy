import groovy.transform.Field

@Field final String AUTOTESTS_REPO = "git@github.com:luxteam/jobs_test_web_viewer.git"


def call(String testsBranch, String tests, String instance) {
    timestamps {
        stage("Run autotests") {
            node("Windows && WebUSDViewer") {
                timeout(time: 20, unit: 'MINUTES') {
                    ws("WS/WebUsdViewer_Autotests") {
                        ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

                        cleanWS("Windows")

                        dir("jobs_test_web_viewer") {
                            try {
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
                                    withEnv(["PATH=c:\\python39\\;${PATH}"]) {
                                        try {
                                            bat """
                                                run.bat ${tests} ${configPrefix} >> ..\\autotests.log 2>&1
                                            """
                                        } catch (e) {
                                            println("[ERROR] Errors occured during autotests execution")
                                            problemMessageManager.saveGlobalFailReason("Some tests were marked as failed")
                                            currentBuild.result = "FAILURE"
                                        }
                                    }
                                }

                                allure([
                                    includeProperties: false,
                                    jdk: "",
                                    properties: [],
                                    reportBuildPolicy: "ALWAYS",
                                    results: [[path: "allure/results"]]
                                ])
                            } catch (e) {
                                println("[ERROR] Failed to execute autotests")
                                throw e
                            } finally {
                                problemMessageManager.publishMessages()
                                archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
                            }
                        }
                    }
                }
            }
        }
    }
}