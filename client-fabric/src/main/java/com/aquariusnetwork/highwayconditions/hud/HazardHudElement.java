package com.aquariusnetwork.highwayconditions.hud;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.net.Geo;
import com.aquariusnetwork.highwayconditions.net.GeoCache;
import com.aquariusnetwork.highwayconditions.net.IngestClient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The hazard-ahead HUD -- the one feature nothing else in this project provides (the proxy
 * plugin's bots have no human watching a screen; the public map website needs alt-tabbing out
 * of the game). Surfaces the nearest reported condition on the road the player is currently
 * standing on.
 *
 * <p>Deliberately independent of {@code cfg.reporter.enabled}: this is a pure read against the
 * fully-public {@code GET /conditions/<server>} route (PROTOCOL.md SS7, the 2026-07-19 read-
 * policy lock-in -- no token needed), zero privacy cost, and works even with report submission
 * toggled off.
 *
 * <p>Uses the classic {@code HudRenderCallback} API (correct for the MC 1.21.4 target -- the
 * newer {@code HudElementRegistry} wasn't introduced until 1.21.6, see the plan's version notes
 * for the migration this needs when the mod is later brought forward past that version).
 *
 * <p>Reuses the server's own re-derived {@code x}/{@code z} on each condition (see
 * {@code IngestClient.Condition}) rather than re-implementing (road,seg,along)->(x,z)
 * interpolation client-side -- the server already does this for every consumer of
 * {@code /conditions}, the same data the public map website plots directly.
 */
public final class HazardHudElement {

    private static final int MIN_POLL_SECONDS = 2;
    private static final int TEXT_COLOR = 0xFFFF5555;
    private static final double MOVING_EPSILON_SQ = 1.0; // ~1 block/tick^2 of movement

    private final HighwayConditionsConfig cfg;
    private final GeoCache geoCache;
    private final ExecutorService executor;
    private final Supplier<IngestClient> clientSupplier;

    private final AtomicBoolean polling = new AtomicBoolean(false);
    private volatile long lastPollMs = 0;
    private volatile List<IngestClient.Condition> cached = List.of();
    private volatile String nearestAheadLabel = null;

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

    public HazardHudElement(HighwayConditionsConfig cfg, GeoCache geoCache, ExecutorService executor,
                            Supplier<IngestClient> clientSupplier) {
        this.cfg = cfg;
        this.geoCache = geoCache;
        this.executor = executor;
        this.clientSupplier = clientSupplier;
    }

    /** Call from END_CLIENT_TICK every tick -- computation happens here (consistent tick-rate
     *  cadence), {@link #render} only ever reads the already-computed label. */
    public void tick(MinecraftClient mc) {
        HighwayConditionsConfig.Hud h = cfg.hud;
        if (!h.enabled || mc.player == null || mc.world == null) {
            nearestAheadLabel = null;
            return;
        }
        Geo g = geoCache.get();
        if (g == null) {
            nearestAheadLabel = null;
            return;
        }

        double x = mc.player.getX(), z = mc.player.getZ();
        double dx = Double.isNaN(lastX) ? 0.0 : x - lastX;
        double dz = Double.isNaN(lastZ) ? 0.0 : z - lastZ;
        lastX = x;
        lastZ = z;

        Geo.Snap snap = g.nearestAllowed(x, z);
        if (snap == null) {
            nearestAheadLabel = null;
            return;  // off-road: nothing to show, no point polling for this position either
        }

        maybePoll(h);
        nearestAheadLabel = computeNearestAhead(snap.road, x, z, dx, dz);
    }

    private void maybePoll(HighwayConditionsConfig.Hud h) {
        long now = System.currentTimeMillis();
        long pollMs = (long) Math.max(h.pollSeconds, MIN_POLL_SECONDS) * 1000L;
        if (now - lastPollMs < pollMs) {
            return;
        }
        IngestClient client = clientSupplier.get();
        if (client == null) {
            return;
        }
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        lastPollMs = now;
        String server = cfg.reporter.server;
        executor.execute(() -> {
            try {
                cached = client.fetchConditions(server);
            } catch (Exception ignored) {
                // keep the previous cache; the next scheduled poll simply tries again
            } finally {
                polling.set(false);
            }
        });
    }

    /** Ahead/behind via dot product of (condition - player) against the player's own recent
     *  movement delta -- generalizes correctly to curving ring/diamond roads, unlike a naive
     *  "bigger seg index = ahead" assumption. While stationary (no reliable direction yet), the
     *  nearest hazard on this road is shown regardless of side, rather than showing nothing. */
    private String computeNearestAhead(int road, double x, double z, double dx, double dz) {
        List<IngestClient.Condition> conditions = cached;
        if (conditions.isEmpty()) {
            return null;
        }
        boolean moving = dx * dx + dz * dz > MOVING_EPSILON_SQ * 0.01;
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (IngestClient.Condition c : conditions) {
            if (c.road == null || c.road != road || !c.published || "CLEAR".equals(c.cond)) {
                continue;
            }
            if (c.x == null || c.z == null) {
                continue;  // server's road/seg lookup missed -- a stale geometry edge case
            }
            double cx = c.x - x, cz = c.z - z;
            if (moving && (cx * dx + cz * dz) <= 0) {
                continue;  // behind or perpendicular to travel direction
            }
            double dist = Math.hypot(cx, cz);
            if (dist < bestDist) {
                bestDist = dist;
                best = c.cond + " ~" + Math.round(dist) + " blocks ahead";
            }
        }
        return best;
    }

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        String label = nearestAheadLabel;
        if (label == null) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        context.drawTextWithShadow(mc.textRenderer, "⚠ " + label, 6, 6, TEXT_COLOR);
    }
}
