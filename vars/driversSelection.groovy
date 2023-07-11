class Constants {
    static final DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    static final OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
    // regex for Adrenalin driver versions like 23.18.5
    static final DRIVER_VERSION_PATTERN = ~/^\d{2}\.\d{1,2}\.\d$/
}

def status, driverPath

def updateDriver(driverVersion, osName, computer){
    timeout(time: "60", unit: "MINUTES") {
        try {
            cleanWS()
            switch(osName) {
                case "Windows":
                    driverPath = "C:\\AMD\\driver\\"
                    status = downloadDriverOnWindows(driverVersion, driverPath, computer)
                    // driver install
                    if (status == 0) {
                        bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
                        println("[INFO] ${driverVersion} driver was found. Trying to install...")
                        bat "${driverPath}\\Setup.exe -INSTALL -BOOT -LOG ${WORKSPACE}\\installation_result_${computer}.log"
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
                    println("[INFO] ${driverVersion} driver was installed")
                    newerDriverInstalled = true
                    utils.reboot(this, isUnix() ? "Unix" : "Windows")
                    break
                case 1:
                    throw new Exception("Error during parsing stage")
                    break
                case 404:
                    println("[INFO] ${driverVersion} driver not found")
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

def downloadDriverOnWindows(String driverVersion, driverPath, computer) {
    if (driverVersion.startsWith("/volume1")) {
        // private driver download
        driverPath = "/mnt/c/amd/driver"
        println("Downloading a private driver")
        downloadFiles(driverVersion, driverPath)
        println("Privat driver was downloaded")
        status = 0
    } else if (driverVersion ==~ Constants.DRIVER_VERSION_PATTERN) {
        // public driver download
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\page.html >> page_download_${computer}.log 2>&1 "
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.OLDER_DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\older_page.html >> older_page_download_${computer}.log 2>&1 "

        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path ${env.WORKSPACE}\\page.html \
                --installer_dst ${env.WORKSPACE}\\driver.exe --win_driver_path ${driverPath} --driver_version ${driverVersion} --older_html_path ${env.WORKSPACE}\\older_page.html >> parse_stage_${computer}.log 2>&1")
        }
    } else {
        // other values of driverVersion
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
    return status
}

def call(String driverVersion = "", String osName, String computer) {
    // check if driverVersion was given
    if (driverVersion != "") {
        updateDriver(driverVersion, osName, computer)
    } else {
        println("[INFO] Parameter driverVersion was not set. No driver will be installed")
    }
}