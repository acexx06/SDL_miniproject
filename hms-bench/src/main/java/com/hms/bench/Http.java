package com.hms.bench;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Minimal HTTP client for the HMS API. One instance is shared by all load-generating threads. */
public final class Http {

    /**
     * @param status   HTTP status, or -1 if the request never got a response
     * @param location the Location header of a redirect, if any
     */
    public record Response(int status, String body, String location) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public Object json() {
            return Json.parse(body);
        }

        public Response require() {
            if (!ok()) {
                throw new IllegalStateException("HTTP " + status + ": " + body);
            }
            return this;
        }
    }

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Http(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * @param token    bearer token, or null
     * @param clientIp sent as X-Forwarded-For so each simulated user is rate-limited separately, or null
     * @param body     object sent as JSON, or null for no body
     */
    public Response send(String method, String path, String token, String clientIp, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (clientIp != null) {
            request.header("X-Forwarded-For", clientIp);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            return new Response(-1, e.toString(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, "interrupted", null);
        }
    }

    public Response get(String path, String token) {
        return send("GET", path, token, null, null);
    }

    public Response post(String path, String token, Object body) {
        return send("POST", path, token, null, body);
    }
}
