public class NotificationConfiguration {

    def static PLUGIN_NOT_FOUND = "Plugin not found during cache building."

    def static FAILED_TO_VERIFY_MATLIB = "Failed to verify installed MatLib."

    def static FAILED_TO_INSTALL_MATLIB = "Failed to install MatLib."

    def static NO_OUTPUT_IMAGE = "No output image after cache building."

    def static NO_OUTPUT_IMAGE_SANITY_CHECK = "No output image after sanity check."
    
    def static VALIDATION_FAILED = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Parameters validation isn't passed.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static HYBRIDPRO_PERF_HISTORY_NOT_FOUND = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Required HybirdPro perf history not found. Please, use HybridPro commit that has tested by HybridPro-SDK-Auto in the master branch.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static INITIALIZATION = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed initialization.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.GENERAL
            ]
        ]
    ]

    def static DOWNLOAD_SOURCE_CODE_REPO = [
        "begin": ["message": "Downloading source code repository."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Merge conflict detected. Please, contact the developers.",
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": ["Not mergeable"], "abort": true,
                "githubNotification": ["status": "failure"]
            ],
            [
                "class": Exception, "problemMessage": "Some submodule commits are missing. Please, contact the developers.",
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "getCauseMessage": ["it did not contain"], "abort": true,
                "githubNotification": ["status": "failure"]
            ],
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to download source code repository due to timeout. Please, contact the CIS command.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to merge branches. Please, contact the CIS command.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": ["Branch not suitable for integration"], "abort": true,
                "githubNotification": ["status": "failure"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to download source code repository. Please, contact the CIS command.",
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static CLEAN_ENVIRONMENT = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to clean environment.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC
            ],
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to clean environment due to timeout.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ]
        ]
    ]

    def static DOWNLOAD_BLENDER = [
        "begin": ["message": "Downloading Blender."],

        "end": ["message": "Blender was successfully downloaded."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to download Blender due to timeout.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to download Blender.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DOWNLOAD_UNIT_TESTS_REPO = [
        "begin": ["message": "Downloading unit tests repository."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to download unit tests repository due to timeout.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to download unit tests repository.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DOWNLOAD_USD_REPO = [
        "begin": ["message": "Downloading the USD repository."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to download the USD repository.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DOWNLOAD_SVN_REPO = [
        "begin": ["message": "Downloading the SVN repository."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to download the SVN repository.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DOWNLOAD_APPPLICATION = [
        "begin": ["message": "Downloading the application."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to download the application due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to download the application.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static SANITY_CHECK = [
        "begin": ["message": "Sanity check started."],
        "end": ["message": "Sanity check stage was successfully finished."],
        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Sanity check failed due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": NO_OUTPUT_IMAGE_SANITY_CHECK, 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [NO_OUTPUT_IMAGE_SANITY_CHECK]
            ],
            [
                "class": Exception, "problemMessage": "Sanity check failed.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INCREMENT_VERSION = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to increment version.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "action_required"]
            ]
        ]
    ]

    def static CONFIGURE_TESTS = [
        "end": ["message": "PreBuild stage was successfully finished."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to configurate tests.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "action_required"]
            ]
        ]
    ]

    def static DOWNLOAD_JOBS_LAUNCHER = [
        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "[WARNING] Failed to download jobs launcher due to timeout.", 
                "rethrow": ExceptionThrowType.NO, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "[WARNING] Failed to download jobs launcher.", 
                "rethrow": ExceptionThrowType.NO, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static UPDATE_BINARIES = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "[WARNING] Failed to update binaries.", 
                "rethrow": ExceptionThrowType.NO, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]
    
    def static BUILD_SOURCE_CODE = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to build the project.", 
                "rethrow": ExceptionThrowType.RETHROW,
                "githubNotification": ["status": "failure"]
            ]
        ],

        "rebootConfiguration": [
            "AnyTool": false,
            "Tools": [
                "USDViewer": ["Windows"],
                "StandaloneUSDViewer": ["Windows"],
                "RPRMayaUSD": ["Windows"]
            ]
        ]
    ]

    def static BUILD_SOURCE_CODE_RENDER_STUDIO = [
        "begin": ["message": "Render Studio build started."],
        "end": ["message": "Render Studio build finished."],
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to build the project.", 
                "rethrow": ExceptionThrowType.RETHROW,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static BUILD_SOURCE_CODE_NO_THROW = [
        "begin": ["message": "Building the project."],

        "end": ["message": "The project was successfully built and published."],
    
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to build the project.", 
                "rethrow": ExceptionThrowType.NO,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static BUILD_SOURCE_CODE_IGNORE_FAIL = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to build the project.", 
                "rethrow": ExceptionThrowType.NO,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static BUILD_PACKAGE = [
        "begin": ["message": "Creating package."],

        "end": ["message": "Package was successfully created."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to create package.", 
                "rethrow": ExceptionThrowType.RETHROW,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static BUILD_PACKAGE_USD_VIEWER = [
        "begin": ["message": "Creating USD Viewer package."],

        "end": ["message": "USD Viewer package was successfully created."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to create USD Viewer package.", 
                "rethrow": ExceptionThrowType.RETHROW,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DOWNLOAD_SCENES = [
        "begin": ["message": "Downloading test scenes."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to download test scenes.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static DOWNLOAD_PREFERENCES = [
        "begin": ["message": "Downloading preferences."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to download preferences.", 
                "rethrow": ExceptionThrowType.NO
            ]
        ]
    ]

    def static DOWNLOAD_TESTS_REPO = [
        "begin": ["message": "Downloading tests repository."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to download tests repository due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to download tests repository.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static PREPARE_DRIVERS = [
        "begin": ["message": "Preparing drivers."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to prepare drivers.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
            ]
        ]
    ]

    def static DOWNLOAD_PACKAGE = [
        "begin": ["message": "Downloading package."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to download package.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
            ]
        ]
    ]

    def static INSTALL_PLUGIN = [
        "begin": ["message": "Installing the plugin."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install the plugin due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"],
                "rebootConfiguration": [
                    "AnyTool": false,
                    "Tools": [
                        "RadeonProRenderMayaPlugin": ["AMD_RX5700XT-OSX"]
                    ]
                ]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install the plugin.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_PLUGIN_DIRT = [
        "begin": ["message": "Installing the plugin (dirt install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install the plugin due to timeout (dirt install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install new plugin (dirt install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_PLUGIN_CLEAN = [
        "begin": ["message": "Installing the plugin (clean install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install the plugin due to timeout (clean install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to uninstall old plugin (clean install).", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": ["Failed to uninstall old plugin"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install the plugin (clean install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_PLUGIN_CUSTOM_PATH = [
        "begin": ["message": "Installing the plugin (custom path install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install the plugin due to timeout (custom path install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to uninstall old plugin (custom path install).", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": ["Failed to uninstall old plugin"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install the plugin (custom path install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_APPPLICATION = [
        "begin": ["message": "Installing the application."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install the application due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install the application.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_ALLURE = [
        "begin": ["message": "Installing Allure."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to install Allure due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to install Allure.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static RUN_APPLICATION_TESTS = [
        "begin": ["message": "Running application tests."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to run application tests.", 
                "rethrow": ExceptionThrowType.RETHROW
            ]
        ]
    ]

    def static UNINSTALL_APPPLICATION = [
        "begin": ["message": "Uninstalling the application."],
        "end": ["message": "App uninstalled"],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to uninstall the application due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to uninstall the application.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static INSTALL_HOUDINI = [
        "begin": ["message": "Installing Houdini."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to validate installed Houdini or install houdini due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "Failed to validate installed houdini or install houdini.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static BUILD_CACHE = [
        "begin": ["message": "Building cache."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to build cache due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"],
                "rebootConfiguration": [
                    "AnyTool": false,
                    "Tools": [
                        "RadeonProRenderMayaPlugin": ["AMD_RX5700XT-OSX"]
                    ]
                ]
            ],
            [
                "class": Exception, "problemMessage": PLUGIN_NOT_FOUND, 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [PLUGIN_NOT_FOUND]
            ],
            [
                "class": Exception, "problemMessage": FAILED_TO_VERIFY_MATLIB, 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [FAILED_TO_VERIFY_MATLIB]
            ],
            [
                "class": Exception, "problemMessage": FAILED_TO_INSTALL_MATLIB, 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [FAILED_TO_INSTALL_MATLIB]
            ],
            [
                "class": Exception, "problemMessage": NO_OUTPUT_IMAGE, 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [NO_OUTPUT_IMAGE],
                "rebootConfiguration": [
                    "AnyTool": false,
                    "Tools": [
                        "RadeonProRenderMayaPlugin": ["AMD_RX5700XT-OSX"]
                    ]
                ]
            ],
            [
                "class": Exception, "problemMessage": "Failed to build cache.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static BUILD_CACHE_DIRT = [
        "begin": ["message": "Building cache (dirt install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to build cache due to timeout (dirt install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "No output image after cache building (dirt install).", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [NO_OUTPUT_IMAGE]
            ],
            [
                "class": Exception, "problemMessage": "Failed to build cache (dirt install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static BUILD_CACHE_CLEAN = [
        "begin": ["message": "Building cache (clean install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to build cache due to timeout (clean install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "No output image after cache building (clean install).", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [NO_OUTPUT_IMAGE]
            ],
            [
                "class": Exception, "problemMessage": "Failed to build cache (clean install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static BUILD_CACHE_CUSTOM_PATH = [
        "begin": ["message": "Building cache (clean install)."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to build cache due to timeout (custom path install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "No output image after cache building (custom path install).", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "getMessage": [NO_OUTPUT_IMAGE]
            ],
            [
                "class": Exception, "problemMessage": "Failed to build cache (custom path install).", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static COPY_BASELINES = [
        "begin": ["message": "Downloading reference images."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "[WARNING] Problem when copying baselines.", 
                "rethrow": ExceptionThrowType.NO, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static EXECUTE_TESTS = [
        "begin": ["message": "Executing tests."],

        "exceptions": [
            [
                "class": "TimeoutExceeded", "problemMessage": "Failed to execute tests due to timeout.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER,
                "githubNotification": ["status": "timed_out"]
            ],
            [
                "class": Exception, "problemMessage": "An error occurred while executing tests. Please contact support.", 
                "rethrow": ExceptionThrowType.THROW_IN_WRAPPER
            ]
        ]
    ]

    def static PUBLISH_REPORT = [
        "begin": ["message": "Publishing test report."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to publish test report.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static DEPLOY_APPLICATION = [
        "begin": ["message": "Deploy stage started."],
        "end": ["message": "Deploy stage successfully finished."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to deploy application.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static NOTIFY_BY_TG = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to notify by Telegram.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static BUILDING_UNIT_TESTS_REPORT = [
        "begin": ["message": "Building test report for unit tests."],

        "exceptions": [
            [
                "class": Exception, "problemMessage": "Failed to build test report for unit tests.", 
                "rethrow": ExceptionThrowType.RETHROW, "scope": ProblemMessageManager.SPECIFIC,
                "githubNotification": ["status": "failure"]
            ]
        ]
    ]

    def static CREATE_GITHUB_NOTIFICATOR = [
        "exceptions": [
            [
                "class": Exception, "problemMessage": "[WARNING] Failed to create GithubNotificator.", 
                "rethrow": ExceptionThrowType.NO, "scope": ProblemMessageManager.SPECIFIC
            ]
        ]
    ]

    def static PRE_BUILD_STAGE_FAILED = "PreBuild stage was failed."

    def static BUILD_SOURCE_CODE_START_MESSAGE = "Building the project."

    def static BUILD_SOURCE_CODE_END_MESSAGE = "The project was successfully built and published."

    def static REASON_IS_NOT_IDENTIFIED = "The reason is not automatically identified. Please contact support."

    def static SOME_TESTS_ERRORED = "Some tests were marked as error. Check the report for details."

    def static SOME_TESTS_FAILED = "Some tests were marked as failed. Check the report for details."

    def static ALL_TESTS_PASSED = "Tests completed successfully."

    def static EXECUTE_TEST = "Executing test..."

    def static TEST_PASSED = "Test completed successfully."

    def static TEST_FAILED = "Test execution failed."

    def static TEST_NOT_FOUND = "Test not found."

    def static EXECUTE_UNIT_TESTS = "Executing unit tests."

    def static UNIT_TESTS_PASSED = "Unit tests passed."

    def static UNIT_TESTS_FAILED = "Unit tests failed."

    def static FAILED_TO_SAVE_RESULTS = "An error occurred while saving test results. Please contact support."

    def static BUILDING_REPORT = "Building test report."

    def static REPORT_PUBLISHED = "Report was published successfully."

    def static TIMEOUT_EXCEEDED = "Timeout exceeded."

    def static UNKNOWN_REASON = "Unknown reason."

    def static STAGE_TIMEOUT_EXCEEDED = "Stage timeout exceeded."

    def static BUILD_ABORTED_BY_COMMIT = "Build was aborted by new commit."

    def static BUILD_ABORTED_BY_USER = "Build was aborted by user."

    def static LOST_CONNECTION_WITH_MACHINE = "Lost connection with machine. Please contact support."

    def static CAN_NOT_GET_TESTS_STATUS = "Can't get tests status."

    def static INVALID_PLUGIN_SIZE = "Invalid plugin (size is less than 10Mb)."

    def static CORRUPTED_ZIP_PLUGIN = "Corrupted .zip plugin."

    def static CORRUPTED_MSI_PLUGIN = "Corrupted .msi plugin."

    def static SEGMENTATION_FAULT = "Segmentation fault detected."

    def static USD_GLTF_BUILD_ERROR = "Failed to build usdGltf library."

    def static FILES_CRASHED = "Corrupted autotest JSON files detected."

    def static FAILED_UPDATE_BASELINES_NAS = "Failed to update baselines on NAS"

    def static FAILED_UPDATE_BASELINES_UMS = "Failed to update baselines on UMS <name>"

    def static SOME_STAGES_FAILED = "Some stages failed"

    def static SANITY_CHECK_FAILED = "Sanity check failed on <gpuName> (<osName>)."

    def static FAILED_FT_TESTS = "Some functional tests were marked as failed"

    def static FAILED_UNIT_TESTS = "Some unit tests were marked as failed"

    def static SOME_SCENARIOS_CRASHED = "Some scenarios crashed"


    // messages for validation problems
    def static EMPTY_PROJECT_REPO = "<b>The projectRepo parameter is empty.</b> Please, check the correctness of the selected values and restart the build."

    def static EMPTY_PROJECT_BRANCH = "<b>The projectBranch parameter is empty.</b> Please, check the correctness of the selected values and restart the build."

    def static INVALID_PREBUILD_LINK = "<b>The link in the <paramName> parameter is invalid.</b> Please, check the correctness of the provided data and restart the build."

    def static EMPTY_ENGINES = "<b>The engines parameter is empty.</b> Please, check the correctness of the selected values and restart the build."

    def static EMPTY_DELEGATES = "<b>The delegates parameter is empty.</b> Please, check the correctness of the selected values and restart the build."

    def static EMPTY_HOUDINI_VERSIONS = "<b>The houdiniVersions parameter is empty.</b> Please, check the correctness of the selected values and restart the build."

    def static UPDATE_DEPS_WITHOUT_REBUILD = "<b>The updateDeps parameter is selected, but the rebuildDeps parameter is unselected.</b> Please, check the correctness of the selected values and restart the build."

    def static UPDATE_USD_WITHOUT_REBUILD = "<b>The saveUSD parameter is selected, but the rebuildUSD parameter is unselected.</b> Please, check the correctness of the selected values and restart the build."

    def static EMPTY_HIPBIN_LINK = "<b>The customHipBin parameter is empty</b> This parameter is mandatory in case of prebuilt RPR SDK. Please, provide it in a new build."
}
