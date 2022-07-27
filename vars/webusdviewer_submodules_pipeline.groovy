

def call(String projectName, String projectRepo) {
    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/${projectName}_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: projectRepo)

                String version = this.readFile("VERSION.txt").trim()

                println("Current version of submodule: " + version)
                def splitted = version.split("\\.")

                if (splitted.size() == 3 && env.BRANCH_NAME == "test") { // debug delete it: "&& env.BRANCH_NAME == "test""

                    switch(env.BRANCH_NAME) {
                        /*
                        case "master":
                            version = version_inc(version, 2)
                            break
                        case "develop":
                            version = version_inc(version, 3)
                            break
                        */
                        case "test":   // test branch
                            version = version_inc(version, 3)
                            break
                    }

                    bat """
                            break > VERSION.txt
                            echo ${version} > VERSION.txt 
                            git commit VERSION.txt -m "buildmaster: version update to ${version}"
                            git push origin HEAD:${env.BRANCH_NAME}
                        """

                    def newVersion = this.readFile("VERSION.txt")
                    println("Newest version of submodule: " + newVersion)
                } else {
                    throw new Exception("Wrong version formatting")
                }
            }
        }
    }
}
