def getIdentificatorKey(String osName) {
    return "plugin${osName}Identificator"
}

def getStashName(String osName) {
    return "app${osName}"
}

def saveDownloadedInstaller(String artifactNameBase, String extension, String identificatorValue, Boolean cacheInstaller) {
    if (cacheInstaller) {
        if (isUnix()) {
            sh """
                mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                mv ${artifactNameBase}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${identificatorValue}.${extension}"
            """
        } else {
            bat """
                IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                move ${artifactNameBase}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${identificatorValue}.${extension}"
            """
        }
    } else {
        if (isUnix()) {
            sh """
                mv ${artifactNameBase}*.${extension} "${identificatorValue}.${extension}"
            """
        } else {
            bat """
                move ${artifactNameBase}*.${extension} "${identificatorValue}.${extension}"
            """
        }
    }
}


def unpack(String unpackDestination, String identificatorKey, String extension, Map options) {
    if (extension == "tar") {
        sh("mkdir -p ${unpackDestination}; tar xvf ${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension} -C ${unpackDestination}")
    } else if (extension == "zip") {
        unzip zipFile: "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}", dir: unpackDestination, quiet: true
    } else {
        throw new Exception("Unexpected extension '${extension}'")
    }
}


def call(String osName, Map options, String unpackDestination = "", Boolean cacheInstaller = true, Integer oneTryTimeout=90) {
    if (!options["configuration"].supportedOS.contains(osName)) {
        throw new Exception("Unsupported OS")
    }

    String identificatorKey = getIdentificatorKey(osName)
    String stashName = getStashName(osName)
    String extension = options["configuration"]["productExtensions"][osName]
    // the name of the artifact without OS name / version. It must be same for any OS / version
    String artifactNameBase = options["configuration"]["artifactNameBase"]

    if (options["isPreBuilt"]) {

        println "[INFO] Product Identificator (${osName}): ${options[identificatorKey]}"

        if (options[identificatorKey] && fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}")) {
            println "[INFO] The product ${options[identificatorKey]}.${extension} exists in the storage."

            if (unpackDestination) {
                unpack(unpackDestination, identificatorKey, extension, options)
            }
        } else {
            println "[INFO] The product does not exist in the storage. Downloading and copying..."

            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The product does not exist in the storage. Downloading and copying..."
            downloadPlugin(osName, options, "", oneTryTimeout)

            saveDownloadedInstaller(artifactNameBase, extension, options[identificatorKey], cacheInstaller)

            if (unpackDestination) {
                unpack(unpackDestination, identificatorKey, extension, options)
            }
        }

    } else {
        if (!options[identificatorKey]) {
            throw new Exception("Missing identificator key for ${osName}")
        }

        if (fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}")) {
            println "[INFO] The plugin ${options[identificatorKey]}.${extension} exists in the storage."

            if (unpackDestination) {
                unpack(unpackDestination, identificatorKey, extension, options)
            }
        } else {
            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
            makeUnstash(name: stashName, unzip: false, storeOnNAS: options.storeOnNAS)

            saveDownloadedInstaller(artifactNameBase, extension, options[identificatorKey], cacheInstaller)

            if (unpackDestination) {
                unpack(unpackDestination, identificatorKey, extension, options)
            }
        }
    }
}