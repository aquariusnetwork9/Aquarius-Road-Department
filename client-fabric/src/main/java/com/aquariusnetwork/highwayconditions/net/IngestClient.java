package com.aquariusnetwork.highwayconditions.net;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to the highway-conditions ingest service over HTTP (JDK client; nothing shaded).
 * Serialization uses a local {@link Gson} instance -- Minecraft already bundles Gson on the
 * classpath, so this needs no new dependency (the AquariusProxy plugin's equivalent class uses
 * a shared {@code Globals.GSON} instance instead, since it isn't standalone). The token is sent
 * as the raw {@code Authorization} header and is never logged.
 */
public final class IngestClient {

    private final Gson gson = new Gson();
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
        return gson.fromJson(resp.body(), Geo.class);
    }

    /** POST /report with a batch of reports (each a plain map -- see {@link Report}). */
    public int postReports(List<Map<String, Object>> batch) throws Exception {
        String body = gson.toJson(batch);
        HttpRequest req = base("/report")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode();
    }

    /**
     * GET /conditions/&lt;server&gt;?road=&lt;road&gt; -- fully public, no token required
     * (PROTOCOL.md SS7, the 2026-07-19 read-policy lock-in). Used by the hazard-ahead HUD, never
     * by report submission. Filtered server-side to one road (PROTOCOL.md SS7's documented
     * {@code ?road=} query param) rather than fetching the whole network's conditions and
     * filtering client-side -- both lighter on the ingest service and on this mod's own poll.
     */
    public List<Condition> fetchConditions(String server, int road) throws Exception {
        HttpRequest req = base("/conditions/" + server + "?road=" + road).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("conditions HTTP " + resp.statusCode());
        }
        Condition[] arr = gson.fromJson(resp.body(), Condition[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    /** A published condition as returned by GET /conditions/&lt;server&gt;. The server already
     *  re-derives {@code x}/{@code z} from (road,seg,along) for consumers like the map website
     *  -- see {@code highway_conditions.py::_row_view} -- so the HUD reuses that directly rather
     *  than re-implementing segment interpolation client-side. Either may be {@code null} if the
     *  server's road/seg lookup missed (a stale geometry edge case). */
    public static final class Condition {
        public Integer road;
        public int seg;
        public int along;
        public String cond;
        public boolean published;
        public Double x;
        public Double z;
    }

    /** POST /link/init {mcUid, server} -- unauthenticated; mints a short-lived link code the
     *  human then completes via the website's Discord OAuth flow (PROTOCOL.md SS6.2), plus a
     *  {@code verifyServerId} nonce for the Mojang ownership-proof handshake (SS6.2 step 1.5):
     *  the caller performs its own {@code session/minecraft/join} against Mojang using that
     *  nonce, then calls {@link #verifyOwnership}. */
    public LinkInit initLink(String server, UUID mcUid) throws Exception {
        Map<String, Object> body = Map.of("mcUid", mcUid.toString(), "server", server);
        HttpRequest req = base("/link/init")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("link/init HTTP " + resp.statusCode() + ": " + resp.body());
        }
        LinkInit parsed = gson.fromJson(resp.body(), LinkInit.class);
        if (parsed == null || parsed.code == null) {
            throw new RuntimeException("link/init returned no code");
        }
        return parsed;
    }

    public static final class LinkInit {
        public String code;
        public String verifyServerId;
    }

    /** POST /link/verify-ownership {linkCode} -- confirms the ownership proof the caller just
     *  performed against Mojang directly (see {@link #initLink}'s {@code verifyServerId}).
     *  Required before {@code /link/complete}/{@code /link/bot-complete} will mint a token,
     *  unless the deployment has the gate disabled (PROTOCOL.md SS6.2 step 1.5). */
    public void verifyOwnership(String linkCode) throws Exception {
        Map<String, Object> body = Map.of("linkCode", linkCode);
        HttpRequest req = base("/link/verify-ownership")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("link/verify-ownership HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    /** Releases the underlying HTTP client's connection pool/threads. Safe to call from any
     *  thread; non-blocking (initiates shutdown without waiting for in-flight exchanges). */
    public void close() {
        http.shutdown();
    }
}
