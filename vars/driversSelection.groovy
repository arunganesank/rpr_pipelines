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
                    downloadDriverOnWindows(driverVersion, computer)
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
                    println("[INFO] ${driverVersion} driver was installed on ${computer}")
                    newerDriverInstalled = true
                    utils.reboot(this, isUnix() ? "Unix" : "Windows")
                    break
                case 1:
                    throw new Exception("Error during parsing stage")
                    break
                case 404:
                    println("[INFO] ${driverVersion} driver not found for ${computer}")
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

def downloadDriverOnWindows(String driverVersion, computer) {
    if (driverVersion.startsWith("/volume1")) {
        // private driver download
        println("Downloading a private driver")
        downloadFiles(driverVersion, "/mnt/c/jn/ws/StreamingSDK_Test/drivers/amf/stable/tools/tests/StreamingSDKTests")
        println("Private driver was downloaded")

        // unzip driver
        def archiveName = driverVersion.substring(driverVersion.lastIndexOf('/') + 1)
        utils.unzip(this, "${env.WORKSPACE}/drivers/amf/stable/tools/tests/StreamingSDKTests/${archiveName}")

        // driver install
        def parts = archiveName.split("_")
        def dirName = parts[0] + "_" + parts[1]
        status = 0
        if (status == 0) {
            bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
            println("[INFO] ${driverVersion} driver was found. Trying to install on ${computer}...")
            bat "${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\${dirName}\\Setup.exe -INSTALL -BOOT -LOG ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\installation_result_${computer}.log"
        }
    } else if (driverVersion ==~ Constants.DRIVER_VERSION_PATTERN) {
        // public driver download
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\page.html >> page_download_${computer}.log 2>&1 "
        bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${Constants.OLDER_DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\older_page.html >> older_page_download_${computer}.log 2>&1 "

        withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
            python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
            status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\page.html \
                --installer_dst ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\driver.exe --driver_version ${driverVersion} --older_html_path ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\older_page.html >> parse_stage_${computer}.log 2>&1")
        }

        // unzip driver
        utils.unzip(this, "${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\driver.exe")
        
        // driver install
        if (status == 0) {
            bat("start cmd.exe /k \"C:\\Python39\\python.exe ${CIS_TOOLS}\\driver_detection\\skip_warning_window.py && exit 0\"")
            println("[INFO] ${driverVersion} driver was found. Trying to install on ${computer}...")
            bat "${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\Setup.exe -INSTALL -BOOT -LOG ${env.WORKSPACE}\\drivers\\amf\\stable\\tools\\tests\\StreamingSDKTests\\installation_result_${computer}.log"
        }
    } else {
        // other values of driverVersion
        throw new Exception("[WARNING] doesn't match any known pattern")
    }
}

def call(String driverVersion = "", String osName, String computer) {
    // check if driverVersion was given
    if (driverVersion != "") {
        updateDriver(driverVersion, osName, computer)
    } else {
        println("[INFO] Parameter driverVersion was not set. No driver will be installed on ${computer}")
    }
}