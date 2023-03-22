import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Implementation of archive artifacts through custom machine
 * 
 * @param params Map with parameters
 * Possible elements:
 *     name - Name of artifact
 *     storeOnNAS - Specify store data on NAS or publish artifacts in Jenkins build
 *     createLink (optional) - Make zip archive for stash (default - true)
 */
def call(Map params) {
    try {
        String artifactName = params["name"]
        Boolean storeOnNAS = params.containsKey("storeOnNAS") ? params["storeOnNAS"] : false
        Boolean createLink = params.containsKey("createLink") ? params["createLink"] : true
        Boolean randomizeArtifactsLinks = params.containsKey("randomizeArtifactsLinks") ? params["randomizeArtifactsLinks"] : false

        String artifactURL

        if (storeOnNAS) {
            String randomString = ""

            if (randomizeArtifactsLinks) {
                randomString = utils.generateRandomString(this, 32) + "/"
            }

            String path = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/${randomString}"
            makeStash(includes: artifactName, name: artifactName, allowEmpty: true, customLocation: path, preZip: false, postUnzip: false, storeOnNAS: true)

            withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
                string(credentialsId: "nasURLFrontend", variable: "REMOTE_URL")]) {
                artifactURL = "${REMOTE_URL}/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/${randomString}${artifactName}"
            }

            if (createLink) {
                rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${artifactURL}">[BUILD: ${BUILD_ID}] ${artifactName}</a></h3>"""
            }
        } else {
            artifactURL = "${BUILD_URL}artifact/${artifactName}"
            
            archiveArtifacts(artifactName)

            if (createLink) {
                rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${artifactURL}">[BUILD: ${BUILD_ID}] ${artifactName}</a></h3>"""
            }
        }

        return artifactURL
    } catch (e) {
        println("[ERROR] Failed to archive artifacts")
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace())
        throw e
    }

}
