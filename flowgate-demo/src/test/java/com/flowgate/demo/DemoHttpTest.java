package com.flowgate.demo;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

/** 在真实 MVC 端口验证 JSON 对象限制和参数转换的框架行为。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoHttpTest {
    @LocalServerPort
    int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rejectsNonObjectOrMalformedJson() throws Exception {
        for (String invalid : new String[]{"[]", "null", "123", "\"text\"", "{"}) {
            assertEquals(400, send("/demo/echo", invalid).statusCode(), invalid);
        }
        assertEquals(201, send("/demo/echo", "{\"nested\":{\"ok\":true},\"items\":[1,null]}").statusCode());
    }

    @Test
    void boundsDelayAndRejectsBadParameters() throws Exception {
        for (String invalid : new String[]{"-1", "2001", "abc", "9223372036854775808"}) {
            assertEquals(400, send("/demo/slow?delayMs=" + invalid, null).statusCode(), invalid);
        }
        assertEquals(200, send("/demo/slow?delayMs=0", null).statusCode());
        assertEquals(200, send("/demo/slow?delayMs=2000", null).statusCode());
        assertTrue(send("/demo/slow", null).body().contains("100"));
    }
}
