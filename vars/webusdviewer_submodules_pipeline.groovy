

def call(String projectName, String projectRepo) {
    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/${projectName}_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: projectRepo)

                String version = this.readFile("VERSION.txt") 

                println("Current version of submodule: " + version)
                def splitted = version.split("\\.")

                if (splitted.size() == 3 && env.BRANCH_NAME == "test") { // debug delete it: "&& env.BRANCH_NAME == "test""
                    switch(env.BRANCH_NAME) {
                        /*
                        case "master":
                            def new_version = version_inc(version, 2, '.')
                            break
                        case "develop":
                            def new_version = version_inc(version, 3, '.')
                            break
                        */
                        case "test":   // test branch
                            def new_version = version_inc(version, 3, '.')
                            break
                    }

                    bat """
                            break > VERSION.txt
                            echo ${new_version} > VERSION.txt 
                            git commit VERSION.txt -m "buildmaster: version update to ${new_version}"
                            git push origin HEAD:${env.BRANCH_NAME}
                        """

                    version = this.readFile("VERSION.txt")
                    println("Newest version of submodule: " + version)
                } else {
                    throw new Exception("Wrong version formatting")
                }
            }
        }
    }
}
