def call(Map options){
    try{
        dir ('WebUsdWebServer') {
            filename = "webusd.env.${options.deployEnvironment}"
            downloadFiles("/volume1/CIS/WebUSD/Additional/$filename", ".", "--quiet")
            sh """ chmod -R 775 $filename"""
            
            switch(options.osName) {
                case 'Windows':
                    bat " "
                    break
                case 'Ubuntu20':
                    sh "mv $filename .env.production"
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }

        }
    }catch(e){
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}