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

    private static final long RETRY_INTERVAL_MS = 10_000;

    private volatile Geo geo;
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private volatile long lastAttemptMs = 0;

    public Geo get() {
        return geo;
    }

    /** Call every client tick. Kicks off an async fetch (off {@code executor}) when there's no
     *  geometry yet and no fetch already in flight, retrying on a cooldown if the last attempt
     *  failed. {@code onLoaded} runs on the network executor, not the tick thread -- callers
     *  that touch chat/HUD state from it must hop back via {@code client.execute(...)}. */
    public void poll(String server, IngestClient client, ExecutorService executor,
                     Consumer<Geo> onLoaded, Consumer<Exception> onError) {
        if (geo != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < RETRY_INTERVAL_MS) {
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
