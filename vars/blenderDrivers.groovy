// delete after review for psergeev/driver_selection
class Constants {
    static final DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    static final OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    // regex for Adrenalin driver revision number like "23.18.5"
    static final REVISION_NUMBER_PATTERN = ~/^\d{2}\.\d{1,2}\.\d$/
}

// delete after review for psergeev/driver_selection
def status, driverPath, dirName

// delete after review for psergeev/driver_selection
def downloadDriverOnWindows(String revisionNumber, computer) {
    if (revisionNumber.startsWith("/volume1")) {
        // private driver download
        println("[INFO] Downloading a private driver")
        downloadFiles(revisionNumber, ".")
        println("[INFO] Private driver was downloaded")
        status = 0

        // private driver unzip
        def archiveName = revisionNumber.substring(revisionNumber.lastIndexOf('/') + 1)
        def parts = archiveName.split("_")
        dirName = parts[0] + "_" + parts[1]
        utils.unzip(this, "${archiveName}")

        // return driver's Setup.exe directory
        return ".//${dirName}"
    } else if (revisionNumber ==~ Constants.REVISION_NUMBER_PATTERN) {
        // public driver download
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.DRIVER_PAGE_URL}\" page.html >> page_download_${computer}.log 2>&1 "
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.OLDER_DRIVER_PAGE_URL}\" older_page.html >> older_page_download_${computer}.log 2>&1 "

        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path page.html --installer_dst driver.exe --driver_version ${revisionNumber} --older_html_path older_page.html >> parse_stage_${computer}.log 2>&1")
        }

        // public driver unzip
        utils.unzip(this, "driver.exe")

        // return driver's Setup.exe directory
        return "."
    } else {
        // other values of revisionNumber
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
}


// delete after review for psergeev/driver_selection
def installDriverOnWindows(String revisionNumber, computer) {
    if (revisionNumber.startsWith("/volume1")) {
        // private driver install
        try {
            if (status == 0) {
                bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
                println("[INFO] ${revisionNumber} driver was found. Trying to install on ${computer}...")
                bat "${dirName}\\Setup.exe -INSTALL -BOOT -LOG ${env.WORKSPACE}\\installation_result_${computer}.log"
            }
        } catch (e) {
            String installationResultLogContent = readFile("installation_result_${computer}.log")
            if (installationResultLogContent.contains("32 remove_all")) {
                // pass this case
            } else if (installationResultLogContent.contains("error code") || installationResultLogContent.contains("Error code")) {
                // other errors should be visible as exceptions
                throw e
            }
            else {
                // pass other cases including clear ones
            }
        }
    } else if (revisionNumber ==~ Constants.REVISION_NUMBER_PATTERN) {
        // public driver install
        try{
            if (status == 0) {
                bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
                println("[INFO] ${revisionNumber} driver was found. Trying to install on ${computer}...")
                bat "Setup.exe -INSTALL -BOOT -LOG ${env.WORKSPACE}\\installation_result_${computer}.log"
            }
        } catch (e) {
            // in some cases appears non-zero exit code,
            // while in the logs is no reason for it
            // it may be caused by force closing of skip_warning_window.py during reboot
            println(e.toString());
            println(e.getMessage());
        }
    } else {
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
}


def call()
{
    def windowsUpdateTasks = [:]
    windowsNodes = nodesByLabel "${params.Labels}"
    
    windowsNodes.each() { machine ->
        windowsUpdateTasks["${machine}"] = {
            
            stage("Install_${machine}") {
                node("${machine}") {
                    ws("Zip_Installer") {
                        cleanWs()
                        // after review for psergeev/driver_selection
                        // use driversSelection.downloadDriverOnWindows("${params.revisionNumber}", "${machine}")
                        // use driversSelection.installDriverOnWindows("${params.revisionNumber}", "${machine}")
                        downloadDriverOnWindows("${params.revisionNumber}", "${machine}")
                        installDriverOnWindows("${params.revisionNumber}", "${machine}")
                    }
                }
            }
        }
    }
    
    parallel windowsUpdateTasks
    return 0
}
