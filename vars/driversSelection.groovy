class Constants {
    static final DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    static final OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    // regex for Adrenalin driver revision number like "23.18.5"
    static final REVISION_NUMBER_PATTERN = ~/^\d{2}\.\d{1,2}\.\d$/
}

def status, driverPath, dirName
// def workdir = "${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests"


def updateDriver(revisionNumber, osName, computer, driverVersion){
    timeout(time: "60", unit: "MINUTES") {
        try {
            cleanWS()
            switch(osName) {
                case "Windows":
                    if driverVersion != getCurrentDriverVersion(driverVersion) {
                        downloadDriverOnWindows(revisionNumber, computer)
                        installDriverOnWindows(revisionNumber, computer)
                    }
                    break
                case "Ubuntu20":
                    driverPath = "${env.WORKSPACE}/amdgpu-install.deb"
                    sh "${CIS_TOOLS}/driver_detection/amd_request.sh \"${Constants.DRIVER_PAGE_URL}\" ${env.WORKSPACE}/page.html >> page_download_${computer}.log 2>&1 "

                    python3("-m pip install -r ${CIS_TOOLS}/driver_detection/requirements.txt >> parse_stage_${computer}.log 2>&1")
                    status = sh(returnStatus: true, script: "python3.9 ${CIS_TOOLS}/driver_detection/parse_driver.py --os ubuntu20 --html_path ${env.WORKSPACE}/page.html --installer_dst ${driverPath} >> parse_stage_${computer}.log 2>&1")
                    if (status == 0) {
                        println("[INFO] Newer driver was found. Uninstalling previous driver...")
                        sh "sudo amdgpu-install -y --uninstall >> uninstallation_${computer}.log 2>&1"

                        println("[INFO] Driver uninstalled. Reboot ${computer}...")
                        utils.reboot(this, "Unix")

                        sh "sudo apt-get purge -y amdgpu-install >> uninstallation_${computer}.log 2>&1"

                        println("[INFO] Trying to install new driver...")
                        sh """
                            sudo apt-get install -y ${driverPath} >> installation_${computer}.log 2>&1 && \
                            sudo amdgpu-install --usecase=workstation -y --vulkan=pro --opencl=rocr,legacy --accept-eula >> installation_${computer}.log 2>&1 \
                        """
                    }
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }
            switch(status) {
                case 0:
                    println("[INFO] ${revisionNumber} driver was installed on ${computer}")
                    newerDriverInstalled = true
                    utils.reboot(this, isUnix() ? "Unix" : "Windows")
                    break
                case 1:
                    throw new Exception("Error during parsing stage")
                    break
                case 404:
                    println("[INFO] ${revisionNumber} driver not found for ${computer}")
                    break
                default:
                    throw new Exception("Unknown exit code")
            }
        } catch(e) {
            println(e.toString());
            println(e.getMessage());
            currentBuild.result = "FAILURE";
        } finally {
            archiveArtifacts "*.log, *.LOG"
        }
    }
}

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
        return "${env.WORKSPACE}//${dirName}"
    } else if (revisionNumber ==~ Constants.DRIVER_VERSION_PATTERN) {
        // public driver download
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.DRIVER_PAGE_URL}\" page.html >> page_download_${computer}.log 2>&1 "
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.OLDER_DRIVER_PAGE_URL}\" older_page.html >> older_page_download_${computer}.log 2>&1 "

        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path page.html \
                --installer_dst driver.exe --driver_version ${revisionNumber} --older_html_path older_page.html >> parse_stage_${computer}.log 2>&1")
        }

        // public driver unzip
        utils.unzip(this, "\\driver.exe")

        // return driver's Setup.exe directory
        return "."
    } else {
        // other values of revisionNumber
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
}


def installDriverOnWindows(String revisionNumber, computer) {
    if (revisionNumber.startsWith("/volume1")) {
        // private driver install
        if (status == 0) {
            bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
            println("[INFO] ${revisionNumber} driver was found. Trying to install on ${computer}...")
            bat "${dirName}\\Setup.exe -INSTALL -BOOT -LOG installation_result_${computer}.log"
        }
    } else if (revisionNumber ==~ Constants.DRIVER_VERSION_PATTERN) {
        // public driver install
        if (status == 0) {
            bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
            println("[INFO] ${revisionNumber} driver was found. Trying to install on ${computer}...")
            bat "\\Setup.exe -INSTALL -BOOT -LOG installation_result_${computer}.log"
        }
    } else {
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
}


def getCurrentDriverVersion(String newDriverVersion) {
    // possible variation instead of using extra python script
    return powershell("Get-WmiObject Win32_PnPSignedDriver | Where-Object { $_.Description -like \"*Radeon*\" -and $_.DeviceClass -like \"*DISPLAY*\" } | Select-Object DriverVersion | Format-Table -HideTableHeaders", returnStdout=true)
}


def call(String revisionNumber = "", String osName, String computer, String driverVersion, String workdirPath) {
    // check if revisionNumber was given
    if (revisionNumber != "") { 
        updateDriver(revisionNumber, osName, computer, driverVersion)
    } else {
        println("[INFO] Parameter revisionNumber was not set. No driver will be installed on ${computer}")
    }
}