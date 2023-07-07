static def updateDriver(options, osName, computer){
    timeout(time: "60", unit: "MINUTES") {
        try {
            DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
            OLDER_DRIVER_PAGE_URL = "https://www.amd.com/en/support/previous-drivers/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"

            cleanWS()
            def status, driver_path

            switch(osName) {
                case "Windows":
                    driver_path = "C:\\AMD\\driver\\"
                    bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\page.html >> page_download_${computer}.log 2>&1 "
                    bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${OLDER_DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\older_page.html >> older_page_download_${computer}.log 2>&1 "

                    withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
                        python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${computer}.log 2>&1")
                        status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --os win --html_path ${env.WORKSPACE}\\page.html \
                            --installer_dst ${env.WORKSPACE}\\driver.exe --win_driver_path ${driver_path} --driver_version ${options.driverVersion} --older_html_path ${env.WORKSPACE}\\older_page.html >> parse_stage_${computer}.log 2>&1")
                        if (status == 0) {
                            println("[INFO] ${options.driverVersion} driver was found. Trying to install...")
                            bat "${driver_path}\\Setup.exe -INSTALL -BOOT -LOG ${WORKSPACE}\\installation_result_${computer}.log"
                        }
                    }
                    break
                case "Ubuntu20":
                    driver_path = "${env.WORKSPACE}/amdgpu-install.deb"
                    sh "${CIS_TOOLS}/driver_detection/amd_request.sh \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}/page.html >> page_download_${computer}.log 2>&1 "

                    python3("-m pip install -r ${CIS_TOOLS}/driver_detection/requirements.txt >> parse_stage_${computer}.log 2>&1")
                    status = sh(returnStatus: true, script: "python3.9 ${CIS_TOOLS}/driver_detection/parse_driver.py --os ubuntu20 --html_path ${env.WORKSPACE}/page.html --installer_dst ${driver_path} >> parse_stage_${computer}.log 2>&1")
                    if (status == 0) {
                        println("[INFO] Newer driver was found. Uninstalling previous driver...")
                        sh "sudo amdgpu-install -y --uninstall >> uninstallation_${computer}.log 2>&1"

                        println("[INFO] Driver uninstalled. Reboot ${computer}...")
                        utils.reboot(this, "Unix")

                        sh "sudo apt-get purge -y amdgpu-install >> uninstallation_${computer}.log 2>&1"

                        println("[INFO] Trying to install new driver...")
                        sh """
                            sudo apt-get install -y ${driver_path} >> installation_${computer}.log 2>&1 && \
                            sudo amdgpu-install --usecase=workstation -y --vulkan=pro --opencl=rocr,legacy --accept-eula >> installation_${computer}.log 2>&1 \
                        """
                    }
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }
            switch(status) {
                case 0:
                    println("[INFO] ${options.driverVersion} driver was installed")
                    newerDriverInstalled = true
                    utils.reboot(this, isUnix() ? "Unix" : "Windows")
                    break
                case 1:
                    throw new Exception("Error during parsing stage")
                    break
                case 404:
                    println("[INFO] ${options.driverVersion} driver not found")
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


def call(Map options, String osName, String coomputer) {
    updateDriver(options, osName, computer)
}