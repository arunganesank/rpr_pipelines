def getDriverVersionOnWindows() {
    if (revisionNumber != "") {
        // скачать драйвер и получить его нахождение
        try {
            def dirName = driversSelection.downloadDriverOnWindows(revisionNumber, computer)
            return python3("${CIS_TOOLS}\\driver_detection\\get_driver_version.py --driver_path ${dirName}")
        }
        catch (e) {
            println(e.toString());
            println(e.getMessage());
            throw new Exception("[INFO] Cannot get proposed driver revision number")
        }

    } else {
        println("[INFO] Parameter revisionNumber was not set. No driver will be installed on ${computer}")
        return ""
    }
}

def call(revisionNumber, osName, computer = "") {
    // it should work only on Windows and should not on ubuntu
    switch(osName) {
        case "Windows":
            return getDriverVersionOnWindows(revisionNumber, computer)
        case "Ubuntu20":
            throw new Exception("Ubuntu script is not implemented")
        default:
            println("[WARNING] ${osName} is not supported")
    }
}