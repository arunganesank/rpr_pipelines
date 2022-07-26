
def call() {
    String PROJECT_REPO = "git@github.com:s1lentssh/WebUsdLiveServer.git"
    println("He-he")

    stage("Increment version") {
        node("GitPublisher") {
            WS("Increment version"){
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: PROJECT_REPO)

                String version = bat(script: "FOR /F %i IN (VERSION.txt) DO @echo %i", returnStdout: true).trim()
                println("Get version: ", version)

                switch(env.BRANCH_NAME) {
                    case "master":
                        break
                    case "develop":
                        break
                }
            }
        }
    }
}
