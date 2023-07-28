import groovy.json.JsonBuilder

def call(Integer attempt, String id, String django_ip) {
    String data = "status=set_attempt&attempt=${attempt}&id=${id}"

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
