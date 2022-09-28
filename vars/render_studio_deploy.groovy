def call(
    String deployEnvironment = 'dev'
) {
    node("RenderStudioServer") {
        dir("/usr/share/webusd/${options.deployEnvironment}") {
            sh """
                docker-compose -f ${options.deployEnvironment} down
                docker-compose -f ${options.deployEnvironment} pull
                docker-compose -f ${options.deployEnvironment} up -d
            """
        }
    } 
}
