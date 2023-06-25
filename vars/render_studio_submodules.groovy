

def call(String projectName, String projectRepo) {
    stage("Increment version") {
        node("GitPublisher") {
            ws("WS/${projectName}_increment") {
                checkoutScm(branchName: env.BRANCH_NAME, repositoryUrl: projectRepo)

                String version = this.readFile("VERSION.txt").trim()

                println("Current version of submodule: " + version)

                if (env.BRANCH_NAME == "main") {
                    increment_version("${projectName}", "Patch", true)
                }

                def newVersion = this.readFile("VERSION.txt")
                println("Newest version of submodule: " + newVersion)

            }
        }
    }
}
