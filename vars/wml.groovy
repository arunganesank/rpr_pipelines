import utils


def installAllure(String osName, Map options) {
    if (!fileExists("C:\\allure")) {
        bat """
            mkdir C:\\allure
            cd C:\\allure
            curl --retry 5 -L -o allure.zip -J https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/2.20.1/allure-commandline-2.20.1.zip
            %CIS_TOOLS%\\7-Zip\\7z.exe x -aoa allure.zip
            xcopy allure-2.20.1\\* . /s/y/i
        """
    }
}


def executeTestCommand(String osName, String asicName, Map options) {
    withEnv(["PATH=C:\\allure\\bin;C:\\Python39;${PATH}"]) {
        dir("scripts") {
            try {
                bat """
                    run.bat ${options.testsType} ${options.testsSelector} ${options.testsEnvironment} >> \"../${STAGE_NAME}_${options.currentTry}.log\" 2>&1
                """
            } catch (e) {
                currentBuild.result = "FAILURE"
                options.problemMessageManager.saveGlobalFailReason("Run script retuned non-zero exit code")
            }
        }
    }
}


def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true

    dir("jobs_test_wml") {
        try {
            withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                timeout(time: "5", unit: "MINUTES") {
                    cleanWS(osName)
                    checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
                    println "[INFO] Preparing on ${env.NODE_NAME} successfully finished."
                }
            }

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.INSTALL_ALLURE) {
                timeout(time: "10", unit: "MINUTES") {
                    installAllure(osName, options)
                }
            }

            outputEnvironmentInfo(osName, options.stageName, options.currentTry)

            withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
                executeTestCommand(osName, asicName, options)
            }

            options.executeTestsFinished = true
        } catch (e) {
            if (options.currentTry < options.nodeReallocateTries) {
                stashResults = false
            }
            println e.toString()
            if (e instanceof ExpectedExceptionWrapper) {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                throw new ExpectedExceptionWrapper(e.getMessage(), e.getCause())
            } else {
                GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, "${BUILD_URL}")
                throw new ExpectedExceptionWrapper(NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, e)
            }
        } finally {
            try {
                dir(options.stageName) {
                    utils.moveFiles(this, osName, "../*.log", ".")
                }

                archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

                if (stashResults) {
                    allure([
                        includeProperties: false,
                        jdk: "",
                        properties: [],
                        reportBuildPolicy: "ALWAYS",
                        results: [[path: "allure/results"]]
                    ])
                } else {
                    println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
                }
            } catch (e) {
                // throw exception in finally block only if test stage was finished
                if (options.executeTestsFinished) {
                    if (e instanceof ExpectedExceptionWrapper) {
                        GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                        throw e
                    } else {
                        GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${BUILD_URL}")
                        throw new ExpectedExceptionWrapper(NotificationConfiguration.FAILED_TO_SAVE_RESULTS, e)
                    }
                }
            }
        }
    }
}


def executePreBuild(Map options) {
    options.executeBuild = false
    options.executeTests = true

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir("jobs_test_repo") {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            options["testsBranch"] = utils.getBatOutput(this, "git log --format=%%H -1 ")
            println "[INFO] Test branch hash: ${options['testsBranch']}"
        }
    }
}


def call(String testsBranch = "master",
        String platforms = "Windows:NVIDIA_RTX3080TI",
        String testsType = "",
        String testsSelector = "",
        String testsEnvironment = ""
    ) {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {

            options << [testRepo:"git@github.com:luxteam/jobs_test_wml.git",
                        testsBranch: testsBranch,
                        PRJ_NAME: "WML",
                        PRJ_ROOT:"rpr-plugins",
                        TEST_TIMEOUT: 60,
                        nodeRetry: [],
                        problemMessageManager: problemMessageManager,
                        platforms: platforms,
                        testsType: testsType,
                        testsSelector: testsSelector,
                        testsEnvironment: testsEnvironment
                        ]
        }
        multiplatform_pipeline(platforms, this.&executePreBuild, null, this.&executeTests, null, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println e.toString()
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
