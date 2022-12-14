

def call(String projectName, String projectRepo) {
    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/${projectName}_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: projectRepo)

                String version = this.readFile("VERSION.txt").trim()

                println("Current version of submodule: " + version)

                if (env.BRANCH_NAME == "main") {
                    version = version_inc(version, 3)
                }

                bat """
                        break > VERSION.txt
                        echo ${version} > VERSION.txt 
                        git commit VERSION.txt -m "buildmaster: version update to ${version}"
                        git push origin HEAD:${env.BRANCH_NAME}
                    """

                def newVersion = this.readFile("VERSION.txt")
                println("Newest version of submodule: " + newVersion)

            }
        }
    }
}
