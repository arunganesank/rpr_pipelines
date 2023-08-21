import groovy.transform.Field

@Field def newerDriverInstalled = false

def main(Map options) {
    timestamps {
        def updateTasks = [:]

        options.platforms.split(';').each() {
            if (it) {
                List tokens = it.tokenize(':')
                String osName = tokens.get(0)
                String gpuNames = ""

                Map newOptions = options.clone()
                newOptions["osName"] = osName

                if (tokens.size() > 1) {
                    gpuNames = tokens.get(1)
                }

                // utils.planUpdate(osName, gpuNames, newOptions, updateTasks)
                utils.planUpdate(options, osName, "here will be a computer name")
            }
        }
        
        parallel updateTasks

/*        if (newerDriverInstalled) {
            withCredentials([string(credentialsId: "TAN_JOB_NAME", variable: "jobName"), string(credentialsId: "TAN_DEFAULT_BRANCH", variable: "defaultBranch")]) {
                build(
                    job: jobName + "/" + defaultBranch,
                    quietPeriod: 0,
                    wait: false
                )
            }
        }*/

        return 0
    }
}


def call(Boolean productionDriver = False,
        String platforms = "",
        String tags = "")
{
    main([productionDriver:productionDriver,
        platforms:platforms,
        tags:tags])
}