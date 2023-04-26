def call(Map options) {
    Boolean validationPassed = true

    if (options.containsKey("projectRepo") && !options["projectRepo"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_PROJECT_REPO, "Init")
        validationPassed = false
    }

    if (options.containsKey("projectBranch") && !options["projectBranch"]) {
        options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.EMPTY_PROJECT_BRANCH, "Init")
        validationPassed = false
    }

    if (!validationPassed) {
        throw new Exception("Validation failed")
    }
}
