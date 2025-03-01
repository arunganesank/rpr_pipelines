import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import static groovy.io.FileType.FILES
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig

def getAmfTool(String osName, String build_name, Map options)
{
    switch(osName) {
        case 'Windows':

            if (!fileExists("${CIS_TOOLS}\\..\\PluginsBinaries\\${options[build_name + 'sha']}.zip")) {

                clearBinariesWin()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "AMF_Windows_${build_name}", unzip: false)
                
                bat """
                    IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                    copy binWindows.zip "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[build_name + 'sha']}.zip"
                """

            } else {
                println "[INFO] The plugin ${options[build_name + 'sha']}.zip exists in the storage."
                bat """
                    copy "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[build_name + 'sha']}.zip" binWindows.zip
                """
            }

            unzip zipFile: "binWindows.zip", dir: "AMF", quiet: true
            break

        case 'OSX':

            if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip")) {

                clearBinariesUnix()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "AMF_OSX_${build_name}", unzip: false)
                
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    cp binOSX.zip "${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip"
                """ 

            } else {
                println "[INFO] The plugin ${options[build_name + 'sha']}.zip exists in the storage."
                sh """
                    cp "${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip" binOSX.zip
                """
            }

            unzip zipFile: "binOSX.zip", dir: "AMF", quiet: true
            break

        default:

            if (!fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip")) {

                clearBinariesUnix()

                println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
                makeUnstash(name: "AMF_Linux_${build_name}", unzip: false)
                
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    cp binLinux.zip "${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip"
                """ 

            } else {
                println "[INFO] The plugin ${options[build_name + 'sha']}.zip exists in the storage."
                sh """
                    cp "${CIS_TOOLS}/../PluginsBinaries/${options[build_name + 'sha']}.zip" binLinux.zip
                """
            }

            unzip zipFile: "binLinux.zip", dir: "AMF", quiet: true
            break
    }
}


def executeTestCommand(String osName, String build_name, Map options)
{
    switch(osName) {
        case 'Windows':
            dir('AMF') {
                bat """
                    autotests.exe --gtest_output=json:../${STAGE_NAME}.${build_name}.json --gtest_filter=\"${options.testsFilter}\" >> ../${STAGE_NAME}.${build_name}.log 2>&1
                """
            }
            break
        case 'OSX':
            dir('AMF') {
                sh """
                    chmod u+x autotests
                    ./autotests --gtest_output=json:../${STAGE_NAME}.${build_name}.json --gtest_filter=\"${options.testsFilter}\" >> ../${STAGE_NAME}.${build_name}.log 2>&1
                """
            }
            break
        default:
            // TODO implement tests for Linux
            break
    }
}


def renameLog(String osName, String build_name) 
{
    switch(osName) {
        case 'Windows':
            bat """
                move AMF\\out.log .
                rename out.log ${STAGE_NAME}.${build_name}.out.log
            """
            break
        case 'OSX':
            sh """
                mv AMF/out.log ${STAGE_NAME}.${build_name}.out.log
            """
            break
        default:
            // TODO implement tests for Linux
            break
    }
}


def updateTestResults(String osName, String configuration) {
    try {
        String outputJsonName = "${STAGE_NAME}.${configuration}.json"
        def outputJson = readJSON file: outputJsonName
        outputJson["platform"] = env.STAGE_NAME.replace("Test-", "")
        outputJson["configuration"] = configuration
        if (outputJson["failures"] > 0) {
            currentBuild.result = "UNSTABLE"
        }
        dir("jobs_launcher") {
            checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/jobs_launcher.git")
            def machineInfoJson
            String machineInfoRaw, renderDevice
            dir('core') {
                switch(osName) {
                    case 'Windows':
                        machineInfoRaw = bat(
                            script: "set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH% & python -c \"from system_info import get_machine_info; print(get_machine_info())\"", 
                            returnStdout: true
                        ).split('\r\n')[2].trim()
                        renderDevice = bat(
                            script: "set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH% & python -c \"from system_info import get_gpu; print(get_gpu())\"", 
                            returnStdout: true
                        ).split('\r\n')[2].trim()
                        break
                    default:
                        machineInfoRaw = sh(
                            script: "python3.9 -c \"from system_info import get_machine_info; print(get_machine_info())\"", 
                            returnStdout: true
                        )
                        renderDevice = sh(
                            script: "python3.9 -c \"from system_info import get_gpu; print(get_gpu())\"", 
                            returnStdout: true
                        )
                        break
                }
            }
            machineInfoJson = utils.parseJson(this, machineInfoRaw.replaceAll("\'", "\""))
            machineInfoJson["gpu"] = renderDevice
            outputJson["machine_info"] = machineInfoJson
        }
        JSON serializedJson = JSONSerializer.toJSON(outputJson, new JsonConfig());
        writeJSON file: outputJsonName, json: serializedJson, pretty: 4
    } catch (e) {
        println("[ERROR] Failed to save additional information")
        println(e.toString())
    }
}


def executeTests(String osName, String asicName, Map options) {
    try {
        switch(osName) {
            case 'Windows':
                executeTestsWindows(osName, asicName, options)
                break
            case 'OSX':
                executeTestsOSX(osName, asicName, options)
                break
            default:
                // TODO implement tests for Linux
                println("Unsupported OS")
        }
    } catch (e) {
        println(e.toString());
        println(e.getMessage());
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
        makeStash(includes: "${STAGE_NAME}.*.json, *.log", name: "${options.testResultsName}", allowEmpty: true)
        try {
            archiveArtifacts artifacts: "*.json", allowEmptyArchive: false
        } catch (e) {
            println("[ERROR] Failed to save json with results")
            println(e.toString())
            currentBuild.result = "FAILURE"
        }
    }
}


def executeTestsWindows(String osName, String asicName, Map options) {
    cleanWS(osName)
    options.buildConfiguration.each() { win_build_conf ->
        options.winVisualStudioVersion.each() { win_vs_ver ->
            options.winLibraryType.each() { win_lib_type ->

                println "Current build configuration: ${win_build_conf}."
                println "Current VS version: ${win_vs_ver}."
                println "Current library type: ${win_lib_type}."

                String win_build_name = generateBuildNameWindows(win_build_conf, win_vs_ver, win_lib_type)

                try {
                    if (!options[win_build_name + 'sha']) {
                        println("[ERROR] Can't find info for saved stash of this configuration. It'll be skipped")
                        return
                    }

                    timeout(time: "5", unit: 'MINUTES') {
                        try {
                            getAmfTool(osName, win_build_name, options)
                        } catch(e) {
                            println("[ERROR] Failed to prepare tests on ${env.NODE_NAME}")
                            println(e.toString())
                            throw e
                        }
                    }

                    executeTestCommand(osName, win_build_name, options)

                } catch (e) {
                    println(e.toString())
                    println(e.getMessage())
                    options.failureMessage = "Failed during testing ${win_build_name} on ${asicName}-${osName}"
                    options.failureError = e.getMessage()
                } finally {
                    try {
                        renameLog(osName, win_build_name)
                    } catch (e) {
                        println("[ERROR] Failed to copy logs")
                        println(e.toString())
                    }
                    updateTestResults(osName, win_build_name)
                    bat """
                        if exist AMF rmdir /Q /S AMF
                        if exist binWindows.zip del binWindows.zip
                    """
                }
            }
        }
    }
}


def executeTestsOSX(String osName, String asicName, Map options) {
    cleanWS(osName)
    options.buildConfiguration.each() { osx_build_conf ->
        options.osxTool.each() { osx_tool ->
            options.osxLibraryType.each() { osx_lib_type ->

                println "Current build configuration: ${osx_build_conf}."
                println "Current tool: ${osx_tool}."
                println "Current library type: ${osx_lib_type}."

                String osx_build_name = generateBuildNameOSX(osx_build_conf, osx_tool, osx_lib_type)

                try {
                    if (!options[osx_build_name + 'sha']) {
                        println("[ERROR] Can't find info for saved stash of this configuration. It'll be skipped")
                        return
                    }

                    timeout(time: "5", unit: 'MINUTES') {
                        try {
                            getAmfTool(osName, osx_build_name, options)
                        } catch(e) {
                            println("[ERROR] Failed to prepare tests on ${env.NODE_NAME}")
                            println(e.toString())
                            throw e
                        }
                    }

                    executeTestCommand(osName, osx_build_name, options)

                } catch (e) {
                    println(e.toString())
                    println(e.getMessage())
                    options.failureMessage = "Failed during testing ${osx_build_name} on ${asicName}-${osName}"
                    options.failureError = e.getMessage()
                } finally {
                    try {
                        renameLog(osName, osx_build_name)
                    } catch (e) {
                        println("[ERROR] Failed to copy logs")
                        println(e.toString())
                    }
                    updateTestResults(osName, osx_build_name)
                    sh """
                        rm -rf AMF
                        rm -rf binOSX.zip
                    """
                }
            }
        }
    }
}


def executeTestsLinux(String osName, String asicName, Map options) {
    cleanWS(osName)
    options.buildConfiguration.each() { linux_build_conf ->
        options.osxLibraryType.each() { linux_lib_type ->

            println "Current build configuration: ${linux_build_conf}."
            println "Current library type: ${linux_lib_type}."

            String linux_build_name = generateBuildNameLinux(linux_build_conf, linux_lib_type)

            try {
                if (!options[linux_build_name + 'sha']) {
                    println("[ERROR] Can't find info for saved stash of this configuration. It'll be skipped")
                    return
                }

                timeout(time: "5", unit: 'MINUTES') {
                    try {
                        getAmfTool(osName, linux_build_name, options)
                    } catch(e) {
                        println("[ERROR] Failed to prepare tests on ${env.NODE_NAME}")
                        println(e.toString())
                        throw e
                    }
                }

                executeTestCommand(osName, linux_build_name, options)

            } catch (e) {
                println(e.toString())
                println(e.getMessage())
                options.failureMessage = "Failed during testing ${linux_build_name} on ${asicName}-${osName}"
                options.failureError = e.getMessage()
                currentBuild.result = "FAILURE"
            } finally {
                try {
                    renameLog(osName, linux_build_name)
                } catch (e) {
                    println("[ERROR] Failed to copy logs")
                    println(e.toString())
                }
                updateTestResults(osName, linux_build_name)
                sh """
                    rm -rf AMF
                    rm -rf binLinux.zip
                """
            }
        }
    }
}


def generateBuildNameWindows(String win_build_conf, String win_vs_ver, String win_lib_type) {
    return "${win_build_conf}_vs${win_vs_ver}_${win_lib_type}"
}


def executeBuildWindows(Map options) {
    options.buildConfiguration.each() { win_build_conf ->
        options.winVisualStudioVersion.each() { win_vs_ver ->
            options.winLibraryType.each() { win_lib_type ->

                println "Current build configuration: ${win_build_conf}."
                println "Current VS version: ${win_vs_ver}."
                println "Current library type: ${win_lib_type}."

                win_build_name = generateBuildNameWindows(win_build_conf, win_vs_ver, win_lib_type)

                switch(win_vs_ver) {
                    case '2017':
                        options.visualStudio = "Visual Studio 15 2017"
                        options.msBuildPath = "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe"
                        break;
                    case '2019':
                        options.visualStudio = "Visual Studio 16 2019"
                        options.msBuildPath = bat(script: "echo %VS2019_MSBUILD_PATH%",returnStdout: true).split('\r\n')[2].trim()
                }

                dir("amf\\public\\proj\\OpenAMF_Autotests") {

                    try {

                        if (!fileExists("generate-${win_lib_type}-vs${win_vs_ver}.bat")) {
                            println("[INFO] This configuration isn't supported now. It'll be skipped")
                            return
                        }

                        bat """
                            generate-${win_lib_type}-vs${win_vs_ver}.bat >> ..\\..\\..\\..\\${STAGE_NAME}.${win_build_name}.log 2>&1
                        """
  
                        String sourceCodeLocation = "vs${win_vs_ver}"

                        dir (sourceCodeLocation) {
                            bat """
                                set msbuild="${options.msBuildPath}"
                                %msbuild% autotests.sln /target:build /maxcpucount /property:Configuration=${win_build_conf};Platform=x64 >> ..\\..\\..\\..\\..\\${STAGE_NAME}.${win_build_name}.log 2>&1
                            """
                        }

                        try {
                            bat """
                                mkdir binWindows
                                xcopy /s/y/i ${sourceCodeLocation}\\${win_build_conf.capitalize()}\\autotests.exe binWindows
                            """
                        } catch (e) {
                            println "[ERROR] Failed to copy autotests"
                            println(e.toString())
                            println(e.getMessage())
                            currentBuild.result = "FAILURE"
                        }

                        if (win_lib_type == 'shared') {
                            bat """
                                xcopy /s/y/i ${sourceCodeLocation}\\openAmf\\${win_build_conf}\\openAmfLoader.lib binWindows
                            """
                        } else if (win_lib_type == 'static') {
                            bat """
                                xcopy /s/y/i ${sourceCodeLocation}\\openAmf\\${win_build_conf}\\openAmf.lib binWindows
                            """
                        }
                        
                        zip archive: true, dir: "binWindows", glob: '', zipFile: "Windows_${win_build_name}.zip"

                        bat """
                            rename Windows_${win_build_name}.zip binWindows.zip
                        """
                        makeStash(includes: "binWindows.zip", name: "AMF_Windows_${win_build_name}", preZip: false)
                        options[win_build_name + 'sha'] = sha1 "binWindows.zip"
                        println "[INFO] Saved sha: ${options[win_build_name + 'sha']}"

                    } catch (FlowInterruptedException error) {
                        println "[INFO] Job was aborted during build stage"
                        throw error
                    } catch (e) {
                        println "[ERROR] Failed to build AMF on Windows"
                        println(e.toString())
                        println(e.getMessage())
                        currentBuild.result = "FAILED"
                    } finally {
                        bat """
                            if exist binWindows rmdir /Q /S binWindows
                            if exist binWindows.zip del binWindows.zip
                        """
                    }
                }
            }
        }
    }
}


def generateBuildNameOSX(String osx_build_conf, String osx_tool, String osx_lib_type) {
    if (osx_tool == "xcode") {
        return "${osx_tool}_${osx_lib_type}"
    } else {
        return "${osx_build_conf}_${osx_tool}_${osx_lib_type}"
    }
}


def executeBuildOSX(Map options) {
    options.buildConfiguration.each() { osx_build_conf ->
        options.osxTool.each() { osx_tool ->
            options.osxLibraryType.each() { osx_lib_type ->

                println "Current build configuration: ${osx_build_conf}."
                println "Current tool: ${osx_tool}."
                println "Current library type: ${osx_lib_type}."

                osx_build_name = generateBuildNameOSX(osx_build_conf, osx_tool, osx_lib_type)

                dir("amf/public/proj/OpenAMF_Autotests") {

                    try {

                        if (osx_tool == "cmake") {
                            if (!fileExists("generate-mac-${osx_lib_type}.sh")) {
                                println("[INFO] This configuration isn't supported now. It'll be skipped")
                                return
                            }
                            sh """
                                chmod u+x generate-mac-${osx_lib_type}.sh
                                ./generate-mac-${osx_lib_type}.sh ${osx_build_conf.capitalize()} \"-DCMAKE_OSX_SYSROOT=$MACOS_SDK_10_15\" >> ../../../../${STAGE_NAME}.${osx_build_name}.log 2>&1
                            """
                        } else if (osx_tool == "xcode") {
                            // skip double building if release and debug were chosen
                            if (!fileExists("generate-xcode-${osx_lib_type}.sh") || (osx_build_conf == 'debug' && buildConfiguration.size() == 2)) {
                                println("[INFO] This configuration isn't supported now. It'll be skipped")
                                return
                            }
                            sh """
                                chmod u+x generate-xcode-${osx_lib_type}.sh
                                ./generate-xcode-${osx_lib_type}.sh >> ../../../../${STAGE_NAME}.${osx_build_name}.log 2>&1
                            """
                        }

                        sh """
                            chmod u+x build-mac.sh
                            ./build-mac.sh >> ../../../../${STAGE_NAME}.${osx_build_name}.log 2>&1
                        """

                        try {
                            sh """
                                mkdir binOSX
                                cp build/autotests binOSX/autotests
                            """
                        } catch (e) {
                            println "[ERROR] Failed to copy autotests"
                            println(e.toString())
                            println(e.getMessage())
                            currentBuild.result = "FAILURE"
                        }

                        if (osx_lib_type == 'shared') {
                            sh """
                                cp build/openAmf/libopenAmf.dylib binOSX/libopenAmf.dylib
                            """
                        } else if (osx_lib_type == 'static') {
                            sh """
                                cp build/openAmf/libopenAmf.a binOSX/libopenAmf.a
                            """
                        }
                        
                        zip archive: true, dir: "binOSX", glob: '', zipFile: "OSX_${osx_build_name}.zip"

                        sh """
                            mv OSX_${osx_build_name}.zip binOSX.zip
                        """
                        makeStash(includes: "binOSX.zip", name: "AMF_OSX_${osx_build_name}", preZip: false)
                        options[osx_build_name + 'sha'] = sha1 "binOSX.zip"
                        println "[INFO] Saved sha: ${options[osx_build_name + 'sha']}"

                    } catch (FlowInterruptedException error) {
                        println "[INFO] Job was aborted during build stage"
                        throw error
                    } catch (e) {
                        println "[ERROR] Failed to build AMF on OSX"
                        println(e.toString())
                        println(e.getMessage())
                        currentBuild.result = "FAILED"
                    } finally {
                        sh """
                            rm -rf binOSX
                            rm -f binOSX.zip
                        """
                    }
                }
            }
        }
    }
}


def generateBuildNameLinux(String linux_build_conf, String linux_lib_type) {
    return "${linux_build_conf}_${linux_lib_type}"
}


def executeBuildLinux(String osName, Map options) {
    options.buildConfiguration.each() { linux_build_conf ->
        options.linuxLibraryType.each() { linux_lib_type ->

            println "Current build configuration: ${linux_build_conf}."
            println "Current library type: ${linux_lib_type}."

            linux_build_name = generateBuildNameLinux(linux_build_conf, linux_lib_type)

            dir("amf/public/proj/OpenAMF_Autotests") {

                try {

                    if (!fileExists("generate-${linux_lib_type}-linux.sh")) {
                        println("[INFO] This configuration isn't supported now. It'll be skipped")
                        return
                    }

                    sh """
                        chmod u+x generate-${linux_lib_type}-linux.sh
                        ./generate-${linux_lib_type}-linux.sh ${linux_build_conf.capitalize()} >> ../../../../${STAGE_NAME}.${linux_build_name}.log 2>&1
                    """

                    sh """
                        chmod u+x build-linux.sh
                        ./build-linux.sh >> ../../../../${STAGE_NAME}.${linux_build_name}.log 2>&1
                    """

                    try {
                        sh """
                            mkdir binLinux
                            cp linux/autotests binOSX/autotests
                        """
                    } catch (e) {
                        println "[ERROR] Failed to copy autotests"
                        println(e.toString())
                        println(e.getMessage())
                        currentBuild.result = "FAILURE"
                    }

                    if (linux_lib_type == 'shared') {
                        sh """
                            cp linux/openAmf/libopenAmf.so binLinux/libopenAmf.so
                        """
                    } else if (linux_lib_type == 'static') {
                        sh """
                            cp linux/openAmf/libopenAmf.a binLinux/libopenAmf.a
                        """
                    }
                    
                    zip archive: true, dir: "binLinux", glob: '', zipFile: "Linux_${linux_build_name}.zip"

                    sh """
                        mv Linux_${linux_build_name}.zip binLinux.zip
                    """
                    makeStash(includes: "binLinux.zip", name: "AMF_Linux_${linux_build_name}", preZip: false)
                    options[linux_build_name + 'sha'] = sha1 "binLinux.zip"
                    println "[INFO] Saved sha: ${options[linux_build_name + 'sha']}"

                } catch (FlowInterruptedException error) {
                    println "[INFO] Job was aborted during build stage"
                    throw error
                } catch (e) {
                    println "[ERROR] Failed to build AMF on ${osName}"
                    println(e.toString())
                    println(e.getMessage())
                    currentBuild.result = "FAILED"
                } finally {
                    sh """
                        rm -rf binLinux
                        rm -f binLinux.zip
                    """
                }
            }
        }
    }
}

def executeBuild(String osName, Map options) {
    try {

        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        
        switch(osName) {
            case 'Windows':
                executeBuildWindows(options)
                break
            case 'OSX':
                withEnv(["PATH=$WORKSPACE:$PATH"]) {
                    executeBuildOSX(options)
                }
                break
            default:
                withEnv(["PATH=$PWD:$PATH"]) {
                    executeBuildLinux(osName, options)
                }
        }
    } catch (e) {
        options.failureMessage = "[ERROR] Failed to build plugin on ${osName}"
        options.failureError = e.getMessage()
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def executePreBuild(Map options) {

    // manual job
    if (options.forceBuild) {
        options.executeBuild = true
        options.executeTests = true
    // auto job
    } else {
        options.executeBuild = true
        options.executeTests = true
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
        } else if("${env.BRANCH_NAME}" == "master") {
           println "[INFO] master branch was detected"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
        }
    }

    if (!env.CHANGE_URL) {

        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

        if (options.projectBranch) {
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitDatetime = bat(script: "git show -s --format=%%ci HEAD", returnStdout: true).split('\r\n')[2].trim()

        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commmit datetime: ${options.commitDatetime}"

        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
        
        if (options.incrementVersion) {
            // TODO implement incrementing of version 
        }
    }
}


def executeDeploy(Map options, List platformList, List testResultList) {
    cleanWS()

    if (options['executeTests'] && testResultList) {
        dir("testResults") {
            testResultList.each() {

                try {
                    makeUnstash(name: "$it")
                } catch(e) {
                    println("[ERROR] Failed to unstash ${it}")
                    println(e.toString())
                    println(e.getMessage())
                }

            }
        }

        dir("amf-report") {
            String branchName = env.BRANCH_NAME ?: options.projectBranch
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

            dir("amf/public/proj/OpenAMF_Autotests/Reports") {
                bat """
                    set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH%
                    pip install --user -r requirements.txt >> ${STAGE_NAME}.requirements.log 2>&1
                    python MakeReport.py --commit_hash "${options.commitSHA}" --branch_name "${branchName}" --commit_datetime "${options.commitDatetime}" --commit_message "${utils.escapeCharsByUnicode(options.commitMessage)}" --test_results ..\\..\\..\\..\\..\\..\\testResults\\
                """
            }
        }

        utils.publishReport(this, "${BUILD_URL}", "testResults", "mainPage.html", "Test Report", "Summary Report")
    }
}


def call(String projectBranch = "",
    String projectRepo = "git@github.com:luxteam/AMF.git",
    String platforms = 'Windows:AMD_RadeonVII,AMD_RX5700XT,NVIDIA_RTX3080TI;OSX:AMD_RXVEGA',
    String buildConfiguration = "release",
    String winVisualStudioVersion = "2017,2019",
    String winLibraryType = "static",
    String osxTool = "cmake",
    String osxLibraryType = "static",
    String linuxLibraryType = "static",
    Boolean incrementVersion = true,
    Boolean forceBuild = false,
    String testsFilter = "*") {

    try {

        String PRJ_NAME="AMF"
        String PRJ_ROOT="gpuopen"

        gpusCount = 0
        platforms.split(';').each() { platform ->
            List tokens = platform.tokenize(':')
            if (tokens.size() > 1) {
                gpuNames = tokens.get(1)
                gpuNames.split(',').each() {
                    gpusCount += 1
                }
            }
        }

        buildConfiguration = buildConfiguration.split(',')
        winVisualStudioVersion = winVisualStudioVersion.split(',')
        winLibraryType = winLibraryType.split(',')
        osxTool = osxTool.split(',')
        osxLibraryType = osxLibraryType.split(',')
        linuxLibraryType = linuxLibraryType.split(',')

        println "Win build configuration: ${buildConfiguration}"
        println "Win visual studio version: ${winVisualStudioVersion}"
        println "Win library type: ${winLibraryType}"

        println "OSX visual studio version: ${osxTool}"
        println "OSX library type: ${osxLibraryType}"

        println "Linux library type: ${linuxLibraryType}"

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                               [projectBranch:projectBranch,
                                projectRepo:projectRepo,
                                incrementVersion:incrementVersion,
                                forceBuild:forceBuild,
                                PRJ_NAME:PRJ_NAME,
                                PRJ_ROOT:PRJ_ROOT,
                                buildConfiguration:buildConfiguration,
                                winVisualStudioVersion:winVisualStudioVersion,
                                winLibraryType:winLibraryType,
                                osxTool:osxTool,
                                osxLibraryType:osxLibraryType,
                                linuxLibraryType:linuxLibraryType,
                                gpusCount:gpusCount,
                                TEST_TIMEOUT:90,
                                DEPLOY_TIMEOUT:150,
                                testsFilter:testsFilter
                                ])
    } catch(e) {
        currentBuild.result = "FAILED"
        failureMessage = "INIT FAILED"
        failureError = e.getMessage()
        println(e.toString())
        println(e.getMessage())

        throw e
    }
}
