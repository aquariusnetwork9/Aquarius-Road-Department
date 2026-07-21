package com.aquariusnetwork.highwayconditions.net;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single shared holder for the fetched {@link Geo} geometry, read by both
 * {@code HighwayReporterModule} (for snapping/reporting) and {@code HazardHudElement} (for
 * road-matching). Each module porting its own proxy-plugin-style "fetch on null, retry on a
 * timer" logic independently would mean two redundant {@code /geometry} fetches racing at
 * startup and two {@link Geo} instances that could theoretically diverge for a tick -- this
 * class exists purely to avoid that, not to add behavior beyond what one fetch-with-retry loop
 * already needs.
 */
public final class GeoCache {

    private static final long RETRY_INTERVAL_MS = 10_000;         // while not yet loaded
    // Once loaded, keep re-fetching on a much slower cadence -- geometry/road-set policy CAN
    // change server-side (e.g. a NEAR_SPAWN_RADIUS widening) and an already-running client
    // shouldn't need a restart to pick that up. A failed refresh keeps the last-known-good Geo;
    // it never falls back to null just because one attempt failed.
    private static final long REFRESH_INTERVAL_MS = 600_000;       // 10 min

    private volatile Geo geo;
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private volatile long lastAttemptMs = 0;

    public Geo get() {
        return geo;
    }

    /** Call every client tick. Kicks off an async fetch (off {@code executor}) when there's no
     *  geometry yet (retrying on a short cooldown) or the last successful load is old enough to
     *  refresh, and no fetch is already in flight. {@code onLoaded}/{@code onError} run on the
     *  network executor, not the tick thread -- callers that touch chat/HUD state from them must
     *  hop back via {@code client.execute(...)}. */
    public void poll(String server, IngestClient client, ExecutorService executor,
                     Consumer<Geo> onLoaded, Consumer<Exception> onError) {
        long now = System.currentTimeMillis();
        long interval = geo == null ? RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
        if (now - lastAttemptMs < interval) {
            return;
        }
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        lastAttemptMs = now;
        executor.execute(() -> {
            try {
                Geo g = client.fetchGeometry(server);
                if (g != null && g.roads != null && g.map != null) {
                    this.geo = g;
                    onLoaded.accept(g);
                } else if (this.geo == null) {
                    onError.accept(new IllegalStateException("geometry response missing roads/map"));
                }
            } catch (Exception ex) {
                onError.accept(ex);
            } finally {
                fetching.set(false);
            }
        });
    }

    /** Reset when the configured server changes -- forces a fresh fetch instead of trusting the
     *  previous server's stale geometry. */
    public void invalidate() {
        this.geo = null;
    }
}
