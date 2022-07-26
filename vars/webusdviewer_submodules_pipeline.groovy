
def call() {
    String PROJECT_REPO = "git@github.com:s1lentssh/WebUsdLiveServer.git"

    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/WebUsdLiveServer_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: PROJECT_REPO)

                def script = """
                    @echo off
                    FOR /F %%i IN (VERSION.txt) DO @echo %%i
                """
                
                String version = bat(script: script, returnStdout: true).trim() as String

                println("Current version of: " + version)
                def splitted = version.split("\\.")

                if (splitted.size() == 3) {
                    def major = splitted[0]
                    def firstMinor = splitted[1]
                    def lastMinor = splitted[2]
                    
                    switch(env.BRANCH_NAME) {
                        case "master":
                            firstMinor += 1
                            break
                        case "develop":
                            lastMinor += 1
                            break
                        default :
                            lastMinor += 1
                            break
                    }

                    bat """
                        break > VERSION.txt
                        echo "${major}.${firstMinor}.${lastMinor}" > VERSION.txt
                    """

                    version = bat(script: "FOR /F %%i IN (VERSION.txt) DO @echo %%i", returnStdout: true).trim() as String

                    println("Newest version of: " + version)
                }
            }
        }
    }
}
