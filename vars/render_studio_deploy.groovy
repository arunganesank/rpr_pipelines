def deploy(deployEnvironment) {
    node("RenderStudioServer") {
        dir("/usr/share/webusd/${deployEnvironment}") {
            if (fileExists("${deployEnvironment}.yml")) {
                sh "rm ${deployEnvironment}.yml"
            }

            downloadFiles("/volume1/CIS/WebUSD/Additional/templates/docker_file_template.yml", ".", "--quiet")
            sh "mv docker_file_template.yml ${deployEnvironment}.yml"

            String ymlFileContent = readFile("/${deployEnvironment}.yml")
            ymlFileContent = ymlFileContent.replaceAll("<domain_name>", deployEnvironment)
            writeFile(file: "${deployEnvironment}.yml", text: ymlFileContent

            sh """
                docker-compose -f ${deployEnvironment}.yml down --remove-orphans
                docker-compose -f ${deployEnvironment}.yml pull
                docker-compose -f ${deployEnvironment}.yml up -d
            """
        }
    }
}


def remove(deployEnvironment) {
    node("RenderStudioServer") {
        try {
            dir("/usr/share/webusd/${deployEnvironment}") {
                sh """
                    docker-compose -f ${deployEnvironment}.yml down --rmi all
                """
            }
        } catch(Exception e) {
            println("[WARNING] Failed to remove '${deployEnvironment}' instance")
        }
    }
}


def call(
    String deployEnvironment = 'dev',
    String action = 'deploy'
) {
    switch(action) {
        case 'deploy':
            deploy(deployEnvironment)
            break
        case 'remove':
            remove(deployEnvironment)
            break
        default:
            throw new Exception("[ERROR] Unknown Render Studio action '${action}'")
    }
}
