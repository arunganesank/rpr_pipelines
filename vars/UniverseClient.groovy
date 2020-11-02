 
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic;
// imports for work with JSONs

/**
 * Client for Universal Monitoring System
 */
class UniverseClient {
    def context;
    def url;
    def product;
    def token;
    def build;
    def env;
    def is_url;
    def is_parent;
    def engine;
    def child_of;

    /**
     * Main constructor for builds without engine
     *
     * @param context
     * @param url Universal Monitoring System API  url (example: "https://umsapi.cis.luxoft.com" )
     * @param env Jenkins environment variables object
     * @param is_url Image Service API url (example: "https://imgs.cis.luxoft.com")
     * @param product Name of product (example: "RPR_Maya")
     */
    UniverseClient(context, url, env, is_url, product) {
        this.url = url;
        this.context = context;
        this.env = env;
        this.is_url = is_url;
        this.product = product;
        this.is_parent = false;
    }

    /**
     * Constructor for creation of parent build
     *
     * @param context
     * @param url Universal Monitoring System API  url (example: "https://umsapi.cis.luxoft.com" )
     * @param env Jenkins environment variables object
     * @param product Name of product (example: "RPR_Maya")
     */
    UniverseClient(context, url, env, product) {
        this.url = url;
        this.context = context;
        this.env = env;
        this.product = product;
        this.is_parent = true;
    }

    /**
     * Constructor for creation of child build
     *
     * @param context
     * @param url Universal Monitoring System API  url (example: "https://umsapi.cis.luxoft.com" )
     * @param env Jenkins environment variables object
     * @param is_url Image Service API url (example: "https://imgs.cis.luxoft.com")
     * @param product Name of product (example: "RPR_Maya")
     * @param engine Is that build a parent of some other build or not
     * @param child_of UniverseClient which is a parent of creating build
     */
    UniverseClient(context, url, env, is_url, product, engine, child_of) {
        this(context, url, env, is_url, product)
        this.is_parent = false;
        this.engine = engine;
        this.child_of = child_of.build.id;
        this.token = child_of.token;
    }

    /**
     * function retry wrapper for request function (required to return response)
     *
     * @param func function to be retried
     * @param validResponseCodes response codes that will be indicated as OK
     */
    def retryWrapper(func, validResponseCodes = [200]) {
        def attempt = 0
        def attempts = 5
        def timeout = 30 // seconds

        this.context.waitUntil {
            if (attempt == attempts) {
                this.context.println("End attemts")
                return true //break the loop
            }

            attempt++
            this.context.println("Attempt: ${attempt}")

            try {
                def response = func.call()
                if( validResponseCodes.contains(response.status)){
                    return true //break the loop
                }
                switch(response.status){
                    case 401:
                        this.tokenSetup()
                        break;
                }
                return false // continue loop

            } catch(error) {
                this.context.println(error)
                this.context.sleep(timeout)
                return false // continue loop
            }
        }
    }

    /**
     * Setup UMS authorization token
     */
    def tokenSetup() {
        def response = this.context.httpRequest(
            consoleLogResponseBody: true,
            authentication: 'universeMonitoringSystem',
            httpMode: 'POST',
            url: "${this.url}/user/login",
            validResponseCodes: '200'
        )
        def token = this.context.readJSON text: "${response.content}"
        this.token = "${token['token']}"
    }

    /**
     * Create build in UMS API and set up build id variable in current client
     *
     * @param envs environment list in format: ["OS-1:GPU-1", ..."OS-N:GPU-N"]
     * @param suites suites names list ["Suite1", "Suite2", ..., "SuiteN"]
     */
    def createBuild(envs = '', suites = '', updRefs = false, options = [:], info = [:]) {
        def request = {
            def splittedJobName = []
            splittedJobName = new ArrayList<>(Arrays.asList(env.JOB_NAME.split("/", 2)))
            this.context.echo "SPLITTED JOB NAME = ${splittedJobName}"
            this.context.echo "JOB_NAME = ${splittedJobName[0]}"

            def tags = []

            String tag = "Other"
            String jobName = splittedJobName[0].toLowerCase()
            def POSSIBLE_TAGS = ["Weekly", "Auto", "Manual"]
            for (tagName in POSSIBLE_TAGS) {
                if (jobName.contains(tagName.toLowerCase())) {
                    tag = tagName
                    break
                }
            }

            tags << tag
            splittedJobName.remove(0)
            splittedJobName.each {
                tags << "${it}"
            }

            def buildBody

            if (this.is_parent) {
                buildBody = [
                    'name': env.BUILD_NUMBER,
                    'parent': true,
                    'tags': tags
                ]
            } else {
                buildBody = [
                    'name': env.BUILD_NUMBER,
                    'envs': envs,
                    'tags': tags,
                    'suites': suites
                ]
                if (this.engine) {
                    buildBody['engine'] = this.engine
                    buildBody['child_of'] = this.child_of
                }
            }

            
            buildBody['upd_baselines'] = updRefs
            buildBody['parameters'] = parameters
            buildBody['info'] = info
            
            

            def res = this.context.httpRequest(
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                customHeaders: [
                    [name: 'Authorization', value: "Token ${this.token}"]
                ],
                httpMode: 'POST',
                requestBody: JsonOutput.toJson(buildBody),
                ignoreSslErrors: true,
                url: "${this.url}/api/build?jobName=${this.product}",
                validResponseCodes: '0:599'
            )
            
            def jsonSlurper = new JsonSlurperClassic()
            def content = jsonSlurper.parseText(res.content);
            this.build = content["build"];
            this.context.echo content["msg"];
            return res;
        }
        retryWrapper(request)
    }

    /**
     * Create stage in UMS API of current build
     *
     * @param name Stage name
     * @param action String type of action (only "begin" or "end" possible)
     */
    def stage(name, action) {
        def request = {
            def buildBody = [
                'stage': [
                    'name': name,
                    'action': action
                ]
            ]

            def res = this.context.httpRequest(
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                customHeaders: [
                    [name: 'Authorization', value: "Token ${this.token}"]
                ],
                httpMode: 'PUT',
                requestBody: JsonOutput.toJson(buildBody),
                ignoreSslErrors: true,
                url: "${this.url}/api/build?id=${this.build["id"]}&jobId=${this.build["job_id"]}",
                validResponseCodes: '0:599'
            )
            return res;
        }
        retryWrapper(request)
    }

    /**
     * Change build status on UMS API
     *
     * @param status String Status name
     */
    def changeStatus(status) {
        def request = {
            def mapStatuses = [
                "FAILURE": "error",
                "FAILED": "error",
                "UNSTABLE": "failed",
                "ABORTED": "aborted",
                "SUCCESS": "passed",
                "SUCCESSFUL": "passed"
            ]

            def buildBody = [
                'status': mapStatuses[status]
            ]

            def res = this.context.httpRequest(
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                customHeaders: [
                    [name: 'Authorization', value: "Token ${this.token}"]
                ],
                httpMode: 'PUT',
                requestBody: JsonOutput.toJson(buildBody),
                ignoreSslErrors: true,
                url: "${this.url}/api/build?id=${this.build["id"]}&jobId=${this.build["job_id"]}",
                validResponseCodes: '0:599'
            )

            return res;
        }
        retryWrapper(request)
    }
}