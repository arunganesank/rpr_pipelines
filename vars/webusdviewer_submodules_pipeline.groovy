

def call(String projectName, String projectRepo) {
    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/${projectName}_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: projectRepo)

                def script = """
                    @echo off
                    FOR /F %%i IN (VERSION.txt) DO @echo %%i
                """
                
                String version = bat(script: script, returnStdout: true).trim() as String

                println("Current version of submodule: " + version)
                def splitted = version.split("\\.")

                if (splitted.size() == 3) {
                    int major = splitted[0] as Integer
                    int firstMinor = splitted[1] as Integer
                    int lastMinor = splitted[2] as Integer
                    
                    switch(env.BRANCH_NAME) {
                        /*
                        case "master":
                            firstMinor += 1
                            break
                        case "develop":
                            lastMinor += 1
                            break
                        */
                        case "test":   // test branch
                            lastMinor += 1
                            break
                    }

                    version = bat(script: "FOR /F %%i IN (VERSION.txt) DO @echo %%i", returnStdout: true).trim() as String
                    println("Newest version of submodule: " + version)

                    bat """
                            break > VERSION.txt
                            echo "${major}.${firstMinor}.${lastMinor}" > VERSION.txt
                            git commit VERSION.txt -m "buildmaster: version update to ${major}.${firstMinor}.${lastMinor}"
                            git push origin HEAD:${env.BRANCH_NAME}
                        """
                } else {
                    throw new Exception("Wrong version formatting")
                }
            }
        }
    }
}
