import groovy.transform.Field

@Field final String DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
@Field final String OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
// regex for Adrenalin driver revision number like "23.18.5"
@Field final String REVISION_NUMBER_PATTERN = ~/^\d{2}\.\d{1,2}\.\d$/


def updateDriver(driverIdentificator, osName, computer, driverVersion, logPath = ""){
    Boolean newerDriverInstalled = false
    if (!logPath) {
        logPath = "${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests"
    }

    timeout(time: "20", unit: "MINUTES") {
        try {
            switch(osName) {
                case "Windows":
                    if(driverVersion != getCurrentDriverVersion()) {
                        def setupDir = downloadDriverOnWindows(driverIdentificator, computer)
                        newerDriverInstalled = installDriverOnWindows(driverIdentificator, computer, setupDir, logPath, driverVersion)
                    } else {
                        newerDriverInstalled = true
                        println("Proposed driver is already installed")
                    }
                    break
                case "Ubuntu20":
                    throw new Exception("Ubuntu is not supported")
                default:
                    println "[WARNING] ${osName} is not supported"
            }
            
        } catch(e) {
            println(e.toString());
            println(e.getMessage());
        }
    }
    return newerDriverInstalled
}

def downloadDriverOnWindows(String driverIdentificator, computer) {
    def status = 0
    def setupDir = "."

    if (driverIdentificator.startsWith("/volume1")) {
        // private driver download
        println("[INFO] Downloading a private driver")
        downloadFiles(driverIdentificator, setupDir)
        println("[INFO] Private driver was downloaded")

        // private driver unzip
        def archiveName = driverIdentificator.substring(driverIdentificator.lastIndexOf('/') + 1)
        utils.unzip(this, "${archiveName}")

        // make path to setup directory
        if (driverIdentificator.contains("StreamingSDK")) {
            def parts = archiveName.split("_")
            setupDir = setupDir + "\\" + parts[0] + "_" + parts[1]
        } else {
            setupDir = "Bin64"
        }
    } else if (driverIdentificator ==~ REVISION_NUMBER_PATTERN) {
        // public driver download
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${DRIVER_PAGE_URL}\" page.html >> page_download_${computer}.log 2>&1 "
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${OLDER_DRIVER_PAGE_URL}\" older_page.html >> older_page_download_${computer}.log 2>&1 "

        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path page.html \
                --installer_dst driver.exe --driver_version ${driverIdentificator} --older_html_path older_page.html >> parse_stage_${computer}.log 2>&1")
        }

        // public driver unzip
        utils.unzip(this, "driver.exe")
    } else {
        // other values of driverIdentificator
        throw new Exception("[WARNING] doesn't match any known pattern")
    }

    switch(status) {
        case 0:
            // return driver's Setup.exe directory
            return setupDir
        case 1:
            throw new Exception("Error during parsing stage")
            break
        case 404:
            println("[INFO] ${driverIdentificator} driver not found for ${computer}")
            break
        default:
            throw new Exception("Unknown exit code")
    }
}


def installDriverOnWindows(String driverIdentificator, computer, setupDir, logPath, driverVersion) {
    Boolean newerDriverInstalled = false
    try {
        bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
        println("[INFO] ${driverIdentificator} driver was found. Trying to install on ${computer}...")
        bat "${setupDir}\\Setup.exe -INSTALL -LOG ${logPath}\\installation_result_${computer}.log"
    } catch (e) {
        String installationResultLogContent = readFile("installation_result_${computer}.log")
        if (installationResultLogContent.contains("error code 32 remove_all")) {
            // driver failed environment clearing, ignore this error
        } else {
            println(e.toString());
            println(e.getMessage());
            throw e
        }
    }

    if(driverVersion == getCurrentDriverVersion()) {
        println("[INFO] ${driverIdentificator} driver was installed on ${computer}")
        newerDriverInstalled = true
    }
    utils.reboot(this, isUnix() ? "Unix" : "Windows")

    return newerDriverInstalled
}


def getCurrentDriverVersion() {
    // possible variation instead of using extra python script
    def command = """
        (Get-WmiObject Win32_PnPSignedDriver  |
        Where-Object { \$_.Description -like \"*Radeon*\" -and \$_.DeviceClass -like \"*DISPLAY*\" }  |
        Select-Object DriverVersion).DriverVersion
    """
    def out = powershell(script: command, returnStdout: true).trim()
    return out
}


def call(String driverIdentificator = "", String osName, String computer, String driverVersion) {
    Boolean installationResult = false
    if(driverIdentificator == "") {
        // check if driverIdentificator was given
        println("[INFO] Parameter driverIdentificator was not set.")
        return
    }

    for(int i=0; i < 1; i++) {
        // additional attempt to install the driver if it was not installed
        installationResult = updateDriver(driverIdentificator, osName, computer, driverVersion)

        if(installationResult) {
            break
        } else if(!installationResult && i == 1) {
            throw new Exception("After two attempts driver was not installed")
        }
    }
}