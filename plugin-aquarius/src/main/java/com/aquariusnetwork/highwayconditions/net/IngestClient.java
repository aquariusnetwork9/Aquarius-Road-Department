package com.aquariusnetwork.highwayconditions.net;

import com.aquarius.Globals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to the highway-conditions ingest service over HTTP (JDK client; nothing shaded).
 * Serialization uses the proxy's bundled Gson ({@link Globals#GSON}). The token is sent as
 * the raw {@code Authorization} header and is never logged.
 */
public final class IngestClient {

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final String baseUrl;
    private final String token;

    public IngestClient(String baseUrl, String token) {
        this.baseUrl = (baseUrl != null && baseUrl.endsWith("/"))
            ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    private HttpRequest.Builder base(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15));
        if (token != null && !token.isBlank()) {
            b.header("Authorization", token);
        }
        return b;
    }

    /** GET /geometry/&lt;server&gt; -&gt; the authoritative road table + map hash. */
    public Geo fetchGeometry(String server) throws Exception {
        HttpRequest req = base("/geometry/" + server).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("geometry HTTP " + resp.statusCode());
        }
        return Globals.GSON.fromJson(resp.body(), Geo.class);
    }

    /** POST /report with a batch of reports (each a plain map — see {@link Report}). */
    public int postReports(List<Map<String, Object>> batch) throws Exception {
        String body = Globals.GSON.toJson(batch);
        HttpRequest req = base("/report")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode();
    }

    /** Releases the underlying HTTP client's connection pool/threads. Safe to call from any
     *  thread; non-blocking (initiates shutdown without waiting for in-flight exchanges). */
    public void close() {
        http.shutdown();
    }
}
