def executeGenTestRefCommand(String osName, Map options)
{
    //TODO: execute genref command
}

def executeTestCommand(String osName, Map options)
{
    //TODO: execute test command
}

def executeTests(String osName, String asicName, Map options)
{
    String REF_PATH_PROFILE="${options.REF_PATH}/${asicName}-${osName}"
    
    //TODO: update test resources
    
    try {        
        outputEnvironmentInfo(osName)
        //TODO: unstash "app${osName}"          
        
        if(options['updateRefs']) {
            echo "Updating Reference Images"
            executeGenTestRefCommand(osName, options)
            //TODO: sendFiles()
        } else {
            echo "Execute Tests"
            //TODO: receiveFiles()
            executeTestCommand(osName, options)
        }
    }
    catch (e) {
        println(e.toString());
        println(e.getMessage());
        currentBuild.result = "FAILED"
        throw e
    }
    finally {
        archiveArtifacts "*.log"
    }
}

def executeBuildWindows(Map options)
{
}

def executeBuildOSX(Map options)
{
}

def executeBuildLinux(Map options)
{
}

def executePreBuild(Map options)
{
    checkOutBranchOrScm(options['projectBranch'], options['projectRepo'])

    AUTHOR_NAME = bat (
            script: "git show -s --format=%%an HEAD ",
            returnStdout: true
            ).split('\r\n')[2].trim()

    echo "The last commit was written by ${AUTHOR_NAME}."
    options.AUTHOR_NAME = AUTHOR_NAME

    commitMessage = bat ( script: "git log --format=%%B -n 1", returnStdout: true ).split('\r\n')[2].trim()
    echo "Commit message: ${commitMessage}"
    options.commitMessage = commitMessage
}

def executeBuild(String osName, Map options)
{
    try {
        checkOutBranchOrScm(options['projectBranch'], options['projectRepo'])
        outputEnvironmentInfo(osName)

        //TODO: add build commands
        switch(osName)
        {
        case 'Windows': 
            executeBuildWindows(options); 
            break;
        case 'OSX':
            executeBuildOSX(options);
            break;
        default: 
            executeBuildLinux(options);
        }
        
        //TODO: stash build result
    }
    catch (e) {
        currentBuild.result = "FAILED"
        throw e
    }
    finally {
        archiveArtifacts "${STAGE_NAME}.log"
    }
}

def executeDeploy(Map options, List platformList, List testResultList)
{
}

def call(String projectBranch = "", 
         String testsBranch = "",
         String platforms = 'Windows',
         Boolean updateRefs = false,
         Boolean enableNotifications = true) {

    String PRJ_ROOT='rpr-core'
    String PRJ_NAME='RadeonProViewer'
    String projectRepo='https://github.com/Radeon-Pro/RPRHybrid.git'

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
                           [projectBranch:projectBranch,
                            updateRefs:updateRefs, 
                            enableNotifications:enableNotifications,
                            PRJ_NAME:PRJ_NAME,
                            PRJ_ROOT:PRJ_ROOT,
                            projectRepo:projectRepo,
                            BUILDER_TAG:'BuilderS',
                            executeBuild:true,
                            executeTests:true)
}
