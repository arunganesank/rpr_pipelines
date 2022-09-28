def call(
    String deployEnvironment = 'dev'
) {
    node("RenderStudioServer") {
        dir("/usr/share/webusd/${options.deployEnvironment}") {
            sh """
                docker-compose -f ${options.deployEnvironment}.yml down
                docker-compose -f ${options.deployEnvironment}.yml pull
                docker-compose -f ${options.deployEnvironment}.yml up -d
            """
        }
    } 
}
