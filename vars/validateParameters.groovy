import java.lang.IllegalStateException


def call(Map options) {
    if (env.BRANCH_NAME) {
        // do not validate auto jobs
        return
    }

    Boolean validationPassed = true

    if (options.containsKey("projectRepo") && !options["projectRepo"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_PROJECT_REPO, "Init")
        validationPassed = false
    }

    if (options.containsKey("projectBranch") && !options["projectBranch"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_PROJECT_BRANCH, "Init")
        validationPassed = false
    }

    List customLinkParams = [
        "customBuildLinkWindows",
        "customBuildLinkOSX",
        "customBuildLinkMacOS",
        "customBuildLinkMacOSARM",
        "customBuildLinkLinux",
        "customBuildLinkUbuntu18",
        "customBuildLinkUbuntu20",
        "customHipBin"
    ]

    Boolean isPreBuilt = false

    for (paramName in customLinkParams) {
        if (options.containsKey(paramName) && options[paramName]) {
            isPreBuilt = true
            // check only that URL is valid (ignore status code and timeout exceptions)
            try {
                def response = httpRequest(
                    url: options[paramName],
                    httpMode: 'GET',
                    validResponseCodes: '0:500',
                    timeout: 5
                )
            } catch (IllegalStateException e) {
                if (e.getCause().getClass().toString().contains("MalformedURLException")) {
                    options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.INVALID_PREBUILD_LINK.replace("<paramName>", paramName), "Init")
                    validationPassed = false
                }
            } catch (Exception e) {
                // ignore any other exception
            }
        }
    }

    if (isPreBuilt && options.containsKey("customHipBin") && !options["customHipBin"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_HIPBIN_LINK, "Init")
        validationPassed = false
    }

    if (options.containsKey("engines") && (options["engines"].size() == 0 || options["engines"][0] == "")) {
        if (env.JOB_NAME.contains("USD-Blender")) {
            options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_DELEGATES, "Init")
        } else {
            options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_ENGINES, "Init")
        }

        validationPassed = false
    }

    if (options.containsKey("houdiniVersions") && (options["houdiniVersions"].size() == 0 || options["houdiniVersions"][0] == "")) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_HOUDINI_VERSIONS, "Init")
        validationPassed = false
    }

    if (options.containsKey("rebuildDeps") && options.containsKey("updateDeps") && !options["rebuildDeps"] && options["updateDeps"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.UPDATE_DEPS_WITHOUT_REBUILD, "Init")
        validationPassed = false
    }

    if (options.containsKey("rebuildUSD") && options.containsKey("saveUSD") && !options["rebuildUSD"] && options["saveUSD"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.UPDATE_USD_WITHOUT_REBUILD, "Init")
        validationPassed = false
    }

    if (!validationPassed) {
        throw new Exception("Validation failed")
    }
}

