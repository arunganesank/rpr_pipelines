import groovy.json.JsonBuilder

def call(Integer buildNumber, String id, String django_ip) {
    String data = "status=set_build_number&build_number=${buildNumber}&id=${id}"

    httpRequest(
        url: django_ip,
        authentication: 'renderServiceCredentials',
        requestBody: data,
        httpMode: 'POST',
        customHeaders: [
            [name: 'Content-type', value: 'application/x-www-form-urlencoded']
        ]
    )
}
