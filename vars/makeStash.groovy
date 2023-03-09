import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Implementation of stashes through custom machine
 *
 * @param params Map with parameters
 * Possible elements:
 *     name - Name of stash
 *     allowEmpty - Can stash be empty or not (throws exception if it's empty and allowEmpty is false)
 *     includes (optional) - String of comma separated patters of files which must be included
 *     excludes (optional) - String of comma separated patters of files which must be excluded
 *     debug (optional) - Print more info about making of stash (default - false)
 *     preZip (optional) - Make zip archive for stash (default - true)
 *     customLocation (optional) - Custom path for stash
 *     postUnzip (optional) - postUnzip archive after unstash (to set 'postUnzip' parameter as true 'preZip' parameter must be true)
 *     storeOnNAS - Specify store data on NAS or make default Jenkins stash
 */
def call(Map params) {

    String stashName

    try {
        stashName = params["name"]

        Boolean allowEmpty = params.containsKey("allowEmpty") ? params["allowEmpty"] : false
        String includes = params["includes"]
        String excludes = params["excludes"]
        Boolean debug = params["debug"]
        Boolean preZip = params.containsKey("preZip") ? params["preZip"] : true
        Boolean postUnzip = params.containsKey("postUnzip") ? (preZip && params["postUnzip"]) : false
        Boolean storeOnNAS = params.containsKey("storeOnNAS") ? params["storeOnNAS"] : false

        if (storeOnNAS) {
            def includeParams = []
            def excludeParams = []

            if (preZip) {
                if (includes) {
                    for (include in includes.split(",")) {
                        if (isUnix()) {
                            includeParams << "-i '${include.trim()}'"
                        } else {
                            includeParams << "-ir!${include.replace('/**/', '/').replace('**/', '').replace('/**', '').trim()}"
                        }
                    }
                }

                if (excludes) {
                    for (exclude in excludes.split(",")) {
                        if (isUnix()) {
                            excludeParams << "-x '${exclude.trim()}'"
                        } else {
                            excludeParams << "-xr!${exclude.replace('/**/', '/').replace('**/', '').replace('/**', '').trim()}"
                        }
                        
                    }
                }
            }

            includeParams = includeParams.join(" ")
            excludeParams = excludeParams.join(" ")

            String remotePath

            if (params["customLocation"]) {
                remotePath = params["customLocation"]
            } else {
                remotePath = "/volume1/Stashes/${env.JOB_NAME}/${env.BUILD_NUMBER}/${stashName}/"
            }

            String stdout

            String zipName = "stash_${stashName}.zip"

            if (preZip) {
                if (isUnix()) {
                    stdout = sh(returnStdout: true, script: "zip --symlinks -r \"${zipName.replace('(', '\\(').replace(')', '\\)')}\" . ${includeParams} ${excludeParams} -x '*@tmp*'")
                } else {
                    stdout = bat(returnStdout: true, script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"stash_${stashName}.zip\" ${includeParams ?: '.'} ${excludeParams} -xr!*@tmp*")
                }
            }

            if (debug) {
                println(stdout)
            }

            if (stdout?.contains("Nothing to do!") && !allowEmpty) {
                println("[ERROR] Stash is empty")
                throw new Exception("Empty stash")
            }

            int times = 3
            int retries = 0
            int status = 0

            boolean stashUploaded = false
            boolean cantUpload = false

            while (retries++ < times) {
                try {
                    print("Try to make stash №${retries}")
                    withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                        // Escaping of space characters should be done by different ways for local path and remote paths
                        // Read more about it here: https://rsync.samba.org/FAQ.html#9
                        if (isUnix()) {
                            // on MacOS and Ubuntu spaces are processing differently by rsync tool
                            // TODO: replace spaces by some reserved symbol
                            def uname = sh script: "uname", returnStdout: true
                            boolean isMacOS = uname.startsWith("Darwin")

                            if (isMacOS) {
                                if (preZip) {
                                    status = sh(returnStatus: true, script: '$CIS_TOOLS/uploadFiles.sh' + " \"${zipName.replace('(', '\\(').replace(')', '\\)')}\" \"${remotePath.replace(" ", "\\ ")}\" " + '$REMOTE_HOST $SSH_PORT')
                                } else {
                                    status = sh(returnStatus: true, script: '$CIS_TOOLS/uploadFiles.sh' + " ${includes.replace('(', '\\(').replace(')', '\\)')} \"${remotePath.replace(" ", "\\ ")}\" " + '$REMOTE_HOST $SSH_PORT')
                                }
                            } else {
                                if (preZip) {
                                    status = sh(returnStatus: true, script: '$CIS_TOOLS/uploadFiles.sh' + " \"${zipName.replace('(', '\\(').replace(')', '\\)')}\" \"${remotePath}\" " + '$REMOTE_HOST $SSH_PORT')
                                } else {
                                    status = sh(returnStatus: true, script: '$CIS_TOOLS/uploadFiles.sh' + " ${includes.replace('(', '\\(').replace(')', '\\)')} \"${remotePath}\" " + '$REMOTE_HOST $SSH_PORT')
                                }
                            }
                        } else {
                            if (preZip) {
                                status = bat(returnStatus: true, script: '%CIS_TOOLS%\\uploadFiles.bat' + " \"${zipName.replace('(', '\\(').replace(')', '\\)').replace(" ", "\\ ")}\" \"${remotePath.replace(" ", "\\ ")}\" " + '%REMOTE_HOST% %SSH_PORT%')
                            } else {
                                status = bat(returnStatus: true, script: '%CIS_TOOLS%\\uploadFiles.bat' + " ${includes.replace('(', '\\(').replace(')', '\\)').replace(" ", "\\ ")} \"${remotePath.replace(" ", "\\ ")}\" " + '%REMOTE_HOST% %SSH_PORT%')
                            }
                        }
                    }

                    if (status == 23) {
                        println("[ERROR] Failed to upload stash")
                    } else if (status == 24) {
                        print("[ERROR] Partial transfer due to vanished source files")
                    } else if (status != 0) {
                        println("[ERROR] Uploading script returned non-zero code: ${status}")

                        if (status == 255) {
                            cantUpload = true
                        }
                    } else {
                        stashUploaded = true
                        break
                    }
                } catch (FlowInterruptedException e1) {
                    println("[INFO] Making of stash with name '${stashName}' was aborting.")
                    throw e1
                } catch(e1) {
                    println(e1.toString())
                    println(e1.getMessage())
                    println(e1.getStackTrace())
                }
            }

            if (!stashUploaded) {
                // reboot and retry in case of failed uploading
                if (cantUpload) {
                    withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST")]) {
                        try {
                            if (isUnix()) {
                                sh """
                                    ping -c 10 ${REMOTE_HOST}
                                    tracepath ${REMOTE_HOST}
                                """
                            } else {
                                bat """
                                    ping -n 10 ${REMOTE_HOST}
                                    tracert ${REMOTE_HOST}
                                """
                            }
                        } catch(e) {
                            println("Failed to collect traces")
                            println(e.toString())
                            println(e.getMessage())
                            println(e.getStackTrace())
                        }
                    }

                    utils.reboot(this, isUnix() ? "Unix" : "Windows")

                    exception = new ExpectedExceptionWrapper("Failed to create stash. All attempts has been exceeded")
                    exception.retry = true
                    throw exception
                } else {
                    throw new Exception("Failed to create stash. All attempts has been exceeded")
                }
            }

            if (preZip) {
                if (postUnzip) {
                    withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"), string(credentialsId: "nasSSHPort", variable: "SSH_PORT")]) {
                        if (isUnix()) {
                            stdout = sh(returnStdout: true, script: '$CIS_TOOLS/unzipFile.sh $REMOTE_HOST $SSH_PORT' + " \"${remotePath}${zipName}\" \"${remotePath}\" true")
                        } else {
                            stdout = bat(returnStdout: true, script: '%CIS_TOOLS%\\unzipFile.bat %REMOTE_HOST% %SSH_PORT%' + " \"${remotePath.replace(" ", "\\ ")}${zipName.replace(" ", "\\ ")}\" \"${remotePath.replace(" ", "\\ ")}\" true")
                        }
                    }

                    if (debug) {
                        println(stdout)
                    }
                }

                if (isUnix()) {
                    sh "rm -rf \"${zipName}\""
                } else {
                    bat "del \"${zipName}\""
                }
            }
        } else {
            Map stashParams = [name: stashName]

            if (includes) {
                stashParams["includes"] = includes
            }
            if (excludes) {
                stashParams["excludes"] = excludes
            }
            if (allowEmpty) {
                stashParams["allowEmpty"] = allowEmpty
            }

            stash(stashParams)
        }

    } catch (e) {
        println("[ERROR] Failed to make stash with name '${stashName}'")
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace())
        throw e
    }

}
