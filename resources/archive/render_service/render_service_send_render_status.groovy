import groovy.json.JsonBuilder

def call(String status, String id, String django_ip, Integer build_number = -1, String fail_reason = '') {
    String data = "status=${status}&id=${id}"
    if (build_number != -1) {
        data = data + "&build_number=${build_number}"
    }
    if (fail_reason) {
        data = data + "&fail_reason=${fail_reason}"
    }

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
