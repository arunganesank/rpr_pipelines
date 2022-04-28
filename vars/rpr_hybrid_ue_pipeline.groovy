import groovy.transform.Field


@Field projectsInfo = [
    "ShooterGame": [
        "targetDir": "PARAGON_BINARY",
        "svnRepoName": "ParagonGame"
    ],
    "ToyShop": [
        "targetDir": "TOYSHOP_BINARY",
        "svnRepoName": "ToyShopUnreal"
    ]
]

@Field finishedProjects = []


def getUE(Map options, String projectName) {
    if (!options.cleanBuild) {
        println("[INFO] Do incremental build")

        dir("RPRHybrid-UE") {
            checkoutScm(branchName: options.ueBranch, repositoryUrl: options.ueRepo, cleanCheckout: options.cleanBuild)
        }

        // start script which presses enter to register UE file types
        bat("start cmd.exe /k \"C:\\Python39\\python.exe %CIS_TOOLS%\\register_ue_file_types.py && exit 0\"")
        bat("0_SetupUE.bat > \"0_SetupUE_${projectName}.log\" 2>&1")
    } else {
        println("[INFO] UnrealEngine will be downloaded and configured")

        dir("RPRHybrid-UE") {
            checkoutScm(branchName: options.ueBranch, repositoryUrl: options.ueRepo)
        }

        // start script which presses enter to register UE file types
        bat("start cmd.exe /k \"C:\\Python39\\python.exe %CIS_TOOLS%\\register_ue_file_types.py && exit 0\"")
        bat("0_SetupUE.bat > \"0_SetupUE_${projectName}.log\" 2>&1")
    }

    println("[INFO] Prepared UE is ready.")
}


def executeBuildWindows(String projectName, Map options) {
    // clear unused directories (Hybrid UE workspace takes a lot of disk space)
    String unusedWorkspacePath = env.WORKSPACE.contains("@") ? env.WORKSPACE.split('@')[0] : env.WORKSPACE + "@2"
    bat("if exist ${unusedWorkspacePath} rmdir /Q /S ${unusedWorkspacePath}")

    if (!projectsInfo.containsKey(projectName)) {
        throw new Exception("Unknown project name: ${projectName}")
    }

    String targetDir = projectsInfo[projectName]["targetDir"]
    String svnRepoName = projectsInfo[projectName]["svnRepoName"]

    bat("if exist \"${targetDir}\" rmdir /Q /S ${targetDir}")

    if (options.cleanBuild) {
        bat("if exist \"RPRHybrid-UE\" rmdir /Q /S RPRHybrid-UE")
    }

    utils.removeFile(this, "Windows", "*.log")

    dir(svnRepoName) {
        withCredentials([string(credentialsId: "artNasIP", variable: 'ART_NAS_IP')]) {
            String paragonGameURL = "svn://" + ART_NAS_IP + "/${svnRepoName}"
            checkoutScm(checkoutClass: "SubversionSCM", repositoryUrl: paragonGameURL, credentialsId: "artNasUser")
        }

        if (projectName == "ToyShop") {
            dir("Config") {
                downloadFiles("/volume1/CIS/bin-storage/HybridParagon/BuildConfigs/DefaultEngine.ini", ".", "", false)
            }
        }
    }

    dir("RPRHybrid") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, cleanCheckout: options.cleanBuild)
    }

    // download build scripts
    downloadFiles("/volume1/CIS/bin-storage/HybridParagon/BuildScripts/*", ".")

    // prepare UE
    getUE(options, projectName)

    // download textures
    downloadFiles("/volume1/CIS/bin-storage/HybridParagon/textures/*", "textures")

    bat("mkdir ${targetDir}")

    bat("1_UpdateRPRHybrid.bat > \"1_UpdateRPRHybrid_${projectName}.log\" 2>&1")
    bat("2_CopyDLLsFromRPRtoUE.bat > \"2_CopyDLLsFromRPRtoUE_${projectName}.log\" 2>&1")
    bat("3_UpdateUE4.bat > \"3_UpdateUE4_${projectName}.log\" 2>&1")

    // the last script can return non-zero exit code, but build can be ok
    try {
        bat("4_Package_${projectName}.bat > \"4_Package_${projectName}.log\" 2>&1")
    } catch (e) {
        println(e.getMessage())
    }

    dir("${targetDir}\\WindowsNoEditor") {
        String ARTIFACT_NAME = "${projectName}.zip"
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" .")
        makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
    }

    if (options.saveEngine) {
        dir("RPRHybrid-UE") {
            String ARTIFACT_NAME = "${projectName}_editor.zip"
            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" . -xr!*.obj -xr!*.pdb -xr!*.vs -xr!*.git -xr!*@tmp*")
            makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
            utils.removeFile(this, "Windows", ARTIFACT_NAME)

            ARTIFACT_NAME = "${projectName}_debug.zip"
            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" -ir!*.pdb -xr!*@tmp*")
            makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
            utils.removeFile(this, "Windows", ARTIFACT_NAME)
        }
    }
}


def executeBuild(String osName, Map options) {
    for (projectName in options["projects"]) {
        // skip projects which can be built on the previous try
        if (finishedProjects.contains(projectName)) {
            continue
        }

        timeout(time: options["PROJECT_BUILD_TIMEOUT"], unit: "MINUTES") {
            try {
                utils.reboot(this, osName)

                outputEnvironmentInfo(osName)
                
                withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
                    switch(osName) {
                        case "Windows":
                            executeBuildWindows(projectName, options)
                            break
                        default:
                            println("${osName} is not supported")
                    }
                }

                finishedProjects.add(projectName)
            } catch (e) {
                println(e.getMessage())
                throw e
            } finally {
                archiveArtifacts "*.log"
            }
        }
    }
}

def executePreBuild(Map options) {
    dir("RPRHybrid") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true, cleanCheckout: options.cleanBuild)
    
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${commitMessage}"
        println "Commit SHA: ${options.commitSHA}"

        options.commitMessage = []
        commitMessage = commitMessage.split('\r\n')
        commitMessage[2..commitMessage.size()-1].collect(options.commitMessage) { it.trim() }
        options.commitMessage = options.commitMessage.join('\n')

        println "Commit list message: ${options.commitMessage}"

        options.githubApiProvider = new GithubApiProvider(this)

        // get UE hash to know it should be rebuilt or not
        options.ueSha = options.githubApiProvider.getBranch(
            options.ueRepo.replace("git@github.com:", "https://github.com/"). replace(".git", ""),
            options.ueBranch.replace("origin/", "")
        )["commit"]["sha"]

        println("UE target commit hash: ${options.ueSha}")
    }
}


def call(String projectBranch = "",
         String ueBranch = "rpr_material_serialization_particles",
         String platforms = "Windows",
         String projects = "ShooterGame,ToyShop",
         Boolean saveEngine = false,
         Boolean cleanBuild = false
) {

    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)

    try {
        println "Projects: ${projects}"

        if (!projects) {
            problemMessageManager.saveGlobalFailReason("Missing 'projects' param")
            throw new Exception("Missing 'projects' param")
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
                               [platforms:platforms,
                                PRJ_NAME:"HybridParagon",
                                projectRepo:"git@github.com:Radeon-Pro/RPRHybrid.git",
                                projectBranch:projectBranch,
                                ueRepo:"git@github.com:Radeon-Pro/RPRHybrid-UE.git",
                                ueBranch:ueBranch,
                                BUILDER_TAG:"BuilderU",
                                TESTER_TAG:"HybridTester",
                                executeBuild:true,
                                executeTests:true,
                                // TODO: ignore timeout in run_with_retries func. Need to implement more correct solution
                                BUILD_TIMEOUT: 3000,
                                PROJECT_BUILD_TIMEOUT: 360,
                                retriesForTestStage:1,
                                storeOnNAS: true,
                                projects: projects.split(","),
                                problemMessageManager: problemMessageManager,
                                saveEngine:saveEngine,
                                cleanBuild:cleanBuild])
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }
}
