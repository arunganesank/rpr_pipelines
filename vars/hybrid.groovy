import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import java.util.concurrent.ConcurrentHashMap


def downloadAgilitySDK() {
    String agilitySDKLink = "https://www.nuget.org/api/v2/package/Microsoft.Direct3D.D3D12/1.706.4-preview"
    String archiveName = "AgilitySDK.zip"

    bat """
        curl --retry 5 -L -J -o "${archiveName}" "${agilitySDKLink}"
    """

    unzip dir: "AgilitySDK", glob: "", zipFile: archiveName

    return "${pwd()}\\AgilitySDK\\build\\native"
}


def executeBuildWindows(Map options) {
    String agilitySDKLocation = downloadAgilitySDK()

    withEnv(["AGILITY_SDK=${agilitySDKLocation}"]) {
        String buildType = options["cmakeKeys"].contains("-DCMAKE_BUILD_TYPE=Debug") ? "Debug" : "Release"
        bat """
            echo %AGILITY_SDK%
            mkdir Build
            cd Build
            cmake ${options['cmakeKeys']} -G "Visual Studio 15 2017 Win64" .. >> ..\\${STAGE_NAME}.log 2>&1
            cmake --build . --target PACKAGE --config ${buildType} >> ..\\${STAGE_NAME}.log 2>&1
            rename BaikalNext.zip BaikalNext_${STAGE_NAME}.zip
        """

        dir("Build/bin/${buildType}") {
            downloadFiles("/volume1/CIS/bin-storage/Hybrid/dxcompiler.dll", ".")
        }

        dir("Build") {
            dir("BaikalNext/bin") {
                bat """
                    xcopy /s/y/i ..\\..\\bin\\Release\\dxcompiler.dll .
                """
            }

            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " BaikalNext_${STAGE_NAME}.zip BaikalNext\\bin\\dxcompiler.dll")
        }

        if (env.BRANCH_NAME == "material_x") {
            withNotifications(title: "Windows", options: options, configuration: NotificationConfiguration.UPDATE_BINARIES) {

                hybrid_vs_northstar.updateBinaries(
                    newBinaryFile: "Build\\_CPack_Packages\\win64\\ZIP\\BaikalNext\\bin\\HybridPro.dll", 
                    targetFileName: "HybridPro.dll", osName: "Windows", compareChecksum: true
                )
            }
        }
    }
}


def executeBuildLinux(Map options) {
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 8 >> ../${STAGE_NAME}.log 2>&1
        make package >> ../${STAGE_NAME}.log 2>&1
        mv BaikalNext.tar.xz BaikalNext_${STAGE_NAME}.tar.xz
    """
}


def executeBuild(String osName, Map options) {
    String error_message = ""
    String context = "[BUILD] ${osName}"
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

        outputEnvironmentInfo(osName)
        
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            GithubNotificator.updateStatus("Build", osName, "in_progress", options, "Checkout has been finished. Trying to build...")
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                default:
                    executeBuildLinux(options)
            }
        }

        dir("Build") {
            makeStash(includes: "BaikalNext_${STAGE_NAME}*", name: "app${osName}", storeOnNAS: options.storeOnNAS)
        }
    } catch (e) {
        println(e.getMessage())
        error_message = e.getMessage()
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"

        String artifactName

        switch (osName) {
            case "Windows": 
                artifactName = "BaikalNext_${STAGE_NAME}.zip"
                break
            default: 
                artifactName = "BaikalNext_${STAGE_NAME}.tar.xz"
        }

        dir("Build") {
            makeArchiveArtifacts(name: artifactName, storeOnNAS: options.storeOnNAS)
        }

        String status = error_message ? "action_required" : "success"
        GithubNotificator.updateStatus("Build", osName, status, options, "Build finished as '${status}'", "${env.BUILD_URL}/artifact/${STAGE_NAME}.log")
    }
}

def executePreBuild(Map options) {
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    if ((commitMessage.contains("[CIS:GENREFALL]") || commitMessage.contains("[CIS:GENREF]")) && env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options.updateRefs = true
        println("[CIS:GENREF] or [CIS:GENREFALL] have been found in comment")
    }

    if (env.CHANGE_URL) {
        println("Build was detected as Pull Request")
    }

    options.commitMessage = []
    commitMessage = commitMessage.split('\r\n')
    commitMessage[2..commitMessage.size()-1].collect(options.commitMessage) { it.trim() }
    options.commitMessage = options.commitMessage.join('\n')

    println "Commit list message: ${options.commitMessage}"
    
    // set pending status for all
    if (env.CHANGE_ID) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
        }
        options["platforms"].split(";").each() { platform ->
            List tokens = platform.tokenize(":")
            String osName = tokens.get(0)
            // Statuses for builds
            GithubNotificator.createStatus("Build", osName, "queued", options, "Scheduled", "${env.JOB_URL}")
        }
    }
}

def call(String projectBranch = "",
         String platforms = "Windows:NVIDIA_RTX3080TI,AMD_RadeonVII,AMD_RX6800XT,AMD_RX7900XT,AMD_RX5700XT,AMD_WX9100;Ubuntu20:AMD_RX6700XT",
         String apiValues = "vulkan",
         Boolean updateRefs = false,
         String cmakeKeys = "-DCMAKE_BUILD_TYPE=Release -DBAIKAL_ENABLE_RPR=ON -DBAIKAL_NEXT_EMBED_KERNELS=ON") {

    if (env.CHANGE_URL && env.CHANGE_TARGET == "master") {
        while (jenkins.model.Jenkins.instance.getItem(env.JOB_NAME.split("/")[0]).getItem("master").lastBuild.result == null) {
            println("[INFO] Make a delay because there is a running build in master branch")
            sleep(300)
        }
    } else if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        def buildNumber = env.BUILD_NUMBER as int
        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }
        milestone(buildNumber) 
    }

    List apiList = apiValues.split(",") as List

    println "[INFO] Testing APIs: ${apiList}"

    currentBuild.description = ""

    def processedPlatforms = []

    platforms.split(';').each() { platform ->
        List tokens = platform.tokenize(':')
        String platformName = tokens.get(0)
        processedPlatforms.add(platformName)
    }

    processedPlatforms = processedPlatforms.join(";")

    multiplatform_pipeline(processedPlatforms, this.&executePreBuild, this.&executeBuild, null, null,
                           [platforms:processedPlatforms,
                            originalPlatforms:platforms,
                            projectBranch:projectBranch,
                            updateRefs:updateRefs,
                            PRJ_NAME:"HybridPro",
                            PRJ_ROOT:"rpr-core",
                            projectRepo:"git@github.com:Radeon-Pro/RPRHybrid.git",
                            BUILDER_TAG:"HybridBuilder",
                            executeBuild:true,
                            executeTests:true,
                            cmakeKeys:cmakeKeys,
                            storeOnNAS: true,
                            finishedBuildStages: new ConcurrentHashMap(),
                            apiValues: apiList])

    // build stages finished successfully
    if (currentBuild.result == null) {
        if (env.BRANCH_NAME == "master") {
            build(job: "HybridProMTLX-Auto/master", wait: false)
            build(job: "HybridUEAuto/VictorianTrainsAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ToyShopAuto/rpr_master", wait: false)
            build(job: "HybridUEAuto/ShooterGameAuto/rpr_master", wait: false)
        }

        // TODO: trigger jobs for testing
    }
}
