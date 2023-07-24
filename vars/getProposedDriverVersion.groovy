def getDriverVersionOnWindows(driverIdentificator, computer) {
    if (driverIdentificator != "") {
        try {
            def dirName = driversSelection.downloadDriverOnWindows(driverIdentificator, computer)
            withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
                return bat(script: "@ python \"${CIS_TOOLS}\\driver_detection\\get_driver_version.py\" --driver_path \"${dirName}\"", returnStdout: true)
            }
        }
        catch (e) {
            println(e.toString());
            println(e.getMessage());
            throw new Exception("[INFO] Cannot get proposed driver revision number")
        }

    } else {
        println("[INFO] Parameter driverIdentificator was not set.")
        return ""
    }
}

def call(driverIdentificator, osName, computer = "") {
    switch(osName) {
        case "Windows":
            return getDriverVersionOnWindows(driverIdentificator, computer)
        case "Ubuntu20":
            throw new Exception("Ubuntu script is not implemented")
        default:
            println("[WARNING] ${osName} is not supported")
    }
}