import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import org.apache.http.NoHttpResponseException


/**
 * Class which implements some requests to GithubApi
 */
class GithubApiProvider {

    static final List COMPLETED_STATUSES = ["success", "failure", "neutral", "cancelled", "skipped", "timed_out", "action_required"]

    def context
    volatile def installation_token

    /**
     * Main constructor
     *
     * @param context
     */
    GithubApiProvider(def context) {
        this.context = context
    }

    private def parseResponse(String response) {
        def jsonSlurper = new JsonSlurperClassic()

        return jsonSlurper.parseText(response)
    }

    private def buildQueryParams(Map params) {
        List paramPairs = []
        for (param in params) {
            paramPairs << "${param.key}=${param.value.replaceAll(' ', '%20')}"
        }
        return paramPairs.join("&")
    }

    private String receiveInstallationToken(String organization_name) {
        try {
            context.withCredentials([context.string(credentialsId: "githubNotificationAppKey", variable: "GITHUB_APP_KEY"),
                context.string(credentialsId: "githubNotificationAppId", variable: "GITHUB_APP_ID")]) {
    
                context.withEnv(["GITHUB_APP_KEY=${context.GITHUB_APP_KEY}"]) {
                    if (context.isUnix()) {
                        installation_token = context.python3("${context.CIS_TOOLS}/auth_github.py --github_app_id ${context.GITHUB_APP_ID} --organization_name ${organization_name} --duration 540").split("\n")[-1]
                    } else {
                        installation_token = context.python3("${context.CIS_TOOLS}\\auth_github.py --github_app_id ${context.GITHUB_APP_ID} --organization_name ${organization_name} --duration 540").split("\n")[-1]
                    }
                }
            }
        } catch (Exception e) {
            context.println("[ERROR] Failed to get installation token")
            context.println(e.toString())
            context.println(e.getMessage())
        }
    }

    private def withInstallationAuthorization(String organization_name, Closure function) {
        int times = 3
        int retries = 0

        while (retries++ < times) {
            try {
                if (!installation_token) {
                    receiveInstallationToken(organization_name)
                }
                def response = function()
                if (response.status == 401) {
                    receiveInstallationToken(organization_name)
                } else {
                    return parseResponse(response.content)
                }
                response = function()
                if (response.status == 401) {
                    throw new Exception("Could not authorize request with token ${installation_token}")
                } else {
                    return parseResponse(response.content)
                }
            } catch(NoHttpResponseException e) {
                context.println("[WARNING] No http response exception appeared")
                context.println(e.toString())
                context.println(e.getMessage())
                sleep(90)
            }
        }
    }

    /**
     * Function for create or update status check (see https://docs.github.com/en/rest/reference/checks#create-a-check-run)
     *
     * @param params params of request (all params are specified by documentation except additional required params: repositoryUrl and status(set always insted of conslusion))
     */
    def createOrUpdateStatusCheck(Map params) {
        params = params.clone()
        def repositoryUrl = params["repositoryUrl"]
        params.remove("repositoryUrl")
        def organization_name = repositoryUrl.split("/")[-2]
        if (COMPLETED_STATUSES.contains(params["status"])) {
            params["conclusion"] = params["status"]
            params["status"] = "completed"
        }

        return withInstallationAuthorization(organization_name) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/check-runs",
                httpMode: "POST",
                requestBody: JsonOutput.toJson(params),
                customHeaders: [
                    [name: "Content-type", value: "application/json"],
                    [name: "Authorization", value: "token ${installation_token}"]
                ],
                validResponseCodes: '0:401'
            )

            return response
        }
    }

    /**
     * Function for get list of status checks (see https://docs.github.com/en/rest/reference/checks#list-check-runs-for-a-git-reference)
     *
     * @param params params of request (all params are specified by documentation except additional required params: repositoryUrl and head_sha)
     */
    def getStatusChecks(Map params) {
        params = params.clone()
        def repositoryUrl = params["repositoryUrl"]
        params.remove("repositoryUrl")
        def organization_name = repositoryUrl.split("/")[-2]
        def commitSHA = params["head_sha"]
        params.remove("head_sha")

        String queryParams = "${buildQueryParams(params)}"

        return withInstallationAuthorization(organization_name) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/commits/${commitSHA}/check-runs?${queryParams}",
                httpMode: "GET",
                customHeaders: [
                    [name: "Authorization", value: "token ${installation_token}"]
                ],
                validResponseCodes: '0:401'
            )

            return response
        }
    }

    /**
     * Function for get info about specified pull request (see https://docs.github.com/en/rest/reference/pulls#get-a-pull-request)
     *
     * @param repositoryUrl url to target pull request
     */
    def getPullRequest(String repositoryUrl) {
        def response = context.httpRequest(
            url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos').replace('/pull/', '/pulls/')}",
            authentication: 'radeonprorender',
            httpMode: "GET",
        )

        return parseResponse(response.content)
    }

    /**
     * Function to create a comment inside a pull request (see https://docs.github.com/en/rest/reference/issues#create-an-issue-comment)
     *
     * @param pullUrl url to target pull request
     * @param message
     */
    def createPullRequestComment(String pullUrl, String message) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${pullUrl.replace('https://github.com', 'https://api.github.com/repos').replace('/pull/', '/issues/')}/comments",
                httpMode: "POST",
                contentType: "APPLICATION_JSON",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ],
                requestBody: "{\"body\" : \"${message}\"}"
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to get all releases in repository (see https://docs.github.com/en/rest/reference/repos#list-releases)
     *
     * @param repositoryUrl url to the target repository
     */
    def getReleases(String repositoryUrl) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/releases",
                httpMode: "GET",
                contentType: "APPLICATION_JSON",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to create a new release (see https://docs.github.com/en/rest/reference/repos#create-a-release)
     *
     * @param repositoryUrl url to the target repository
     * @param tagName name of the target tag
     * @param name name of the new release
     * @param targetCommitish target commit to create a new tag if it's required
     */
    def createRelease(String repositoryUrl, String tagName, String name, String targetCommitish = "") {
        def releaseData = [
            tag_name: tagName,
            name: name
        ]

        if (targetCommitish) {
            releaseData["target_commitish"] = targetCommitish
        }

        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/releases",
                httpMode: "POST",
                contentType: "APPLICATION_JSON",
                customHeaders: [
                    [name: 'Content-type', value: 'application/json'],
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ],
                requestBody: JsonOutput.toJson(releaseData),
                validResponseCodes: '0:401,422'
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to delete specific release (see https://docs.github.com/en/rest/releases/releases)
     *
     * @param repositoryUrl url to the target repository
     * @param releaseId id of the release which has to be deleted
     */
    def deleteRelease(String repositoryUrl, def releaseId) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/releases/${releaseId}",
                httpMode: "DELETE",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to get all assets of some release (see https://docs.github.com/en/rest/reference/repos#list-release-assets)
     *
     * @param repositoryUrl url to the target repository
     * @param releaseId id of the target release
     */
    def getAssets(String repositoryUrl, String releaseId) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/releases/${releaseId}/assets",
                httpMode: "GET",
                contentType: "APPLICATION_JSON",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to create a new release (see https://docs.github.com/en/rest/reference/repos#upload-a-release-asset)
     *
     * @param repositoryUrl url to the target repository
     * @param releaseId id of the target release
     * @param assetName name of the asset which should be uploaded
     */
    def addAsset(String repositoryUrl, String releaseId, String assetName) {
        context.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'radeonprorender', usernameVariable: 'GITHUB_USERNAME', passwordVariable: 'GITHUB_PASSWORD']]) {
            context.bat """
                curl -X POST --retry 5 -H "Content-Type: application/octet-stream" --data-binary @${assetName} -u %GITHUB_USERNAME%:%GITHUB_PASSWORD% "${repositoryUrl.replace('https://github.com', 'https://uploads.github.com/repos')}/releases/${releaseId}/assets?name=${assetName}"
            """
        }
    }

    /**
     * Function to remove specified asset of some release (see https://docs.github.com/en/rest/reference/repos#delete-a-release-asset)
     *
     * @param repositoryUrl url to the target repository
     * @param assetId id of the target asset
     */
    def removeAsset(String repositoryUrl, String assetId) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/releases/assets/${assetId}",
                httpMode: "DELETE",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )
        }
    }

    /**
     * Function to get information about specific branch (see https://docs.github.com/en/rest/reference/repos#get-a-branch)
     *
     * @param repositoryUrl url to the target repository
     * @param branchName name of the target branch
     */
    def getBranch(String repositoryUrl, String branchName) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/branches/${branchName}",
                httpMode: "GET",
                contentType: "APPLICATION_JSON",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to create PR in specified repository (see https://docs.github.com/en/rest/reference/pulls#create-a-pull-request)
     *
     * @param repositoryUrl url to the target repository
     * @param head the name of the branch which should be merged
     * @param base the name of the branch where the head should be merged
     * @param title the title of the new pull request
     * @param body the contents of the pull request.
     */
    def createPR(String repositoryUrl, String head, String base, String title = "", String body = "") {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def prData = [
                head: head,
                base: base
            ]

            if (title) {
                prData["title"] = title
            }

            if (body) {
                prData["body"] = body
            }

            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/pulls",
                httpMode: "POST",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ],
                requestBody: JsonOutput.toJson(prData)
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to request review (see https://docs.github.com/en/rest/reference/pulls#request-reviewers-for-a-pull-request)
     *
     * @param repositoryUrl url to the target repository
     * @param prNumber number of the target PR
     * @param reviewers list of strings which represents usernames of reviewers
     */
    def addReviewers(String repositoryUrl, String prNumber, List reviewers) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/pulls/${prNumber}/requested_reviewers",
                httpMode: "POST",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ],
                requestBody: JsonOutput.toJson(["reviewers": reviewers])
            )

            return parseResponse(response.content)
        }
    }

    /**
     * Function to request review (see https://docs.github.com/en/rest/repos/contents)
     *
     * @param repositoryUrl url to the target repository
     * @param branchOrHash target branch name / commit hash
     * @param path necessary location in the target repository
     */
    def getContentInfo(String repositoryUrl, String branchOrHash, String path) {
        context.withCredentials([context.string(credentialsId: "github", variable: "GITHUB_TOKEN")]) {
            def response = context.httpRequest(
                url: "${repositoryUrl.replace('https://github.com', 'https://api.github.com/repos')}/contents/${path}?ref=${branchOrHash}",
                httpMode: "GET",
                customHeaders: [
                    [name: "Authorization", value: "Bearer ${context.GITHUB_TOKEN}"]
                ]
            )

            return parseResponse(response.content)
        }
    }
}