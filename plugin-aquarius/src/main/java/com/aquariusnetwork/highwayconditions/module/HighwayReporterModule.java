package com.aquariusnetwork.highwayconditions.module;

import com.aquarius.Globals;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.mc.block.Block;
import com.aquarius.mc.block.BlockRegistry;
import com.aquarius.module.api.Module;
import com.aquarius.util.timer.Timer;
import com.aquarius.util.timer.Timers;
import com.github.rfresh2.EventConsumer;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.HighwayConditionsPlugin;
import com.aquariusnetwork.highwayconditions.net.Geo;
import com.aquariusnetwork.highwayconditions.net.IngestClient;
import com.aquariusnetwork.highwayconditions.net.Report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aquarius.Globals.CACHE;
import static com.github.rfresh2.EventConsumer.of;

/**
 * Polls the bot's own state each tick and, when on a highway, enqueues a 1-D condition report.
 *
 * <p>The gate order mirrors {@code server/reference_client.py}: nether dimension → radius cap
 * → on-road within tolerance → strict y120. The perpendicular offset is confined to
 * {@link Geo#nearestAllowed} and never becomes part of a report. Off-road ⇒ nothing is built.
 *
 * <p>Obstruction detection (PROTOCOL.md §5.1) is layered on top of the same gate: an
 * {@link ObstructionWatcher} tracks per-tick stall state (mode-agnostic, relative to the bot's
 * own recent peak speed) and, once a real ≥3s stall is confirmed, a one-shot cross-section scan
 * classifies it as FULL/PARTIAL. This is deliberately how signs/item frames are filtered — see
 * the watchdog's own javadoc — rather than a block-type blacklist.
 */
public class HighwayReporterModule extends Module {

    /** Floor for {@code cfg.flushIntervalTicks}/{@code cfg.resendSeconds} so a fat-fingered
     *  config edit (e.g. 0) can't turn this into a self-inflicted flood against the ingest
     *  service — this network's whole premise depends on contributors being well-behaved. */
    private static final int MIN_FLUSH_INTERVAL_TICKS = 20;   // 1s @ 20 tick/s
    private static final int MIN_RESEND_SECONDS = 10;

    private final Timer flushTimer = Timers.tickTimer();
    private final Timer geoTimer = Timers.tickTimer();
    private final ConcurrentLinkedQueue<Map<String, Object>> batch = new ConcurrentLinkedQueue<>();
    // ConcurrentHashMap: reads happen on the tick thread (enqueue-time isThrottled check),
    // writes happen on Globals.EXECUTOR (markSent, only after a confirmed-successful flush).
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final ObstructionWatcher obstructionWatcher = new ObstructionWatcher();

    private volatile Geo geo;
    private volatile IngestClient client;
    private String clientUrl;    // last url/token the client was built from -- tick-thread only
    private String clientToken;

    @Override
    public boolean enabledSetting() {
        return HighwayConditionsPlugin.PLUGIN_CONFIG.reporter.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::handleBotTick));
    }

    @Override
    public void onEnable() {
        HighwayConditionsConfig.Reporter cfg = HighwayConditionsPlugin.PLUGIN_CONFIG.reporter;
        this.geo = null;
        this.batch.clear();
        this.lastSent.clear();
        this.obstructionWatcher.reset();
        refreshClient(cfg);
        info("highway-conditions reporter enabled -> " + cfg.ingestUrl + " server=" + cfg.server);
    }

    @Override
    public void onDisable() {
        flush(HighwayConditionsPlugin.PLUGIN_CONFIG.reporter);
        closeClient();
        info("highway-conditions reporter disabled");
    }

    /** (Re)builds the ingest client if the config's URL/token changed since the last build --
     *  a live config edit (e.g. via the control panel, without a full off/on toggle) must not
     *  keep talking to a stale endpoint with a stale credential. Cheap no-op when unchanged. */
    private void refreshClient(HighwayConditionsConfig.Reporter cfg) {
        if (client != null && Objects.equals(clientUrl, cfg.ingestUrl) && Objects.equals(clientToken, cfg.token)) {
            return;
        }
        closeClient();
        this.client = new IngestClient(cfg.ingestUrl, cfg.token);
        this.clientUrl = cfg.ingestUrl;
        this.clientToken = cfg.token;
    }

    /** Dispatched onto Globals.EXECUTOR (never the tick thread) since close() can briefly wait
     *  on in-flight requests. */
    private void closeClient() {
        IngestClient old = this.client;
        if (old != null) {
            Globals.EXECUTOR.execute(old::close);
        }
    }

    private void handleBotTick(ClientBotTick event) {
        HighwayConditionsConfig.Reporter cfg = HighwayConditionsPlugin.PLUGIN_CONFIG.reporter;
        refreshClient(cfg);

        Geo g = this.geo;
        if (g == null) {
            // Bootstrap: fetch the authoritative geometry (single source of truth) off-thread.
            if (geoTimer.tick(200) && fetching.compareAndSet(false, true)) {
                Globals.EXECUTOR.execute(this::fetchGeometry);
            }
            return;
        }

        // Flush on schedule regardless of whether this tick produces a report.
        if (flushTimer.tick(Math.max(cfg.flushIntervalTicks, MIN_FLUSH_INTERVAL_TICKS))) {
            flush(cfg);
        }

        var pc = CACHE.getPlayerCache();
        if (pc == null) {
            return;
        }
        var dim = CACHE.getChunkCache().getCurrentDimension();
        if (dim == null || !String.valueOf(dim.name()).toLowerCase().contains("nether")) {
            obstructionWatcher.reset();
            return;
        }
        double x = pc.getX(), y = pc.getY(), z = pc.getZ();
        if (Math.max(Math.abs(x), Math.abs(z)) > cfg.maxReportRadius) {
            obstructionWatcher.reset();
            return;  // per-contributor privacy cap
        }
        if (Math.round(y) != g.roadY) {
            obstructionWatcher.reset();
            return;  // strict y120 (off-y observations are a /moderation concern, not auto-report)
        }
        Geo.Snap snap = g.nearestAllowed(x, z);
        if (snap == null) {
            obstructionWatcher.reset();
            return;  // OFF-ROAD: no report object is ever built; the offset stays in nearestAllowed
        }

        long now = System.currentTimeMillis() / 1000L;
        long ts = now / 30 * 30;

        ObstructionWatcher.Trigger trig = obstructionWatcher.tick(x, z);
        if (trig != null) {
            enqueueObstructionIfPhysical(g, cfg, snap, x, z, trig, ts);
        }

        String cond = sampleCondition(x, y, z, cfg, g);
        if (cond.equals("CLEAR") && !cfg.reportClear) {
            return;
        }
        Map<String, Object> rep = Report.basic(cfg.server, g.map, snap.road, snap.seg, snap.along, cond, ts);
        if (isThrottled(Report.key(rep), now, Math.max(cfg.resendSeconds, MIN_RESEND_SECONDS))) {
            return;
        }
        batch.add(rep);
        if (batch.size() >= 128) {
            flush(cfg);
        }
    }

    // --- condition sampling (v1: robust subset; LAVA/COBWEB identity-checked against
    // BlockRegistry, same pattern World.isWater() uses -- version-stable, no name-string
    // matching. Not yet exercised against a live server; goldfarm is still the place to
    // confirm these trigger on real griefed sections, not just in theory.) ---
    private String sampleCondition(double x, double y, double z,
                                   HighwayConditionsConfig.Reporter cfg, Geo g) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        // HOLE: the two blocks beneath the road plane are air -> a gap you'd fall through.
        if (isAir(bx, g.roadY - 1, bz) && isAir(bx, g.roadY - 2, bz)) {
            return "HOLE";
        }
        // LAVA: the road floor itself, or the space a player stands in, is lava -- either a
        // burned-through/void-breach section (floor) or a puddle poured onto an intact floor
        // (foot level). Either way the road is impassable on foot here.
        Block floor = blockAt(bx, g.roadY - 1, bz);
        Block foot = blockAt(bx, g.roadY, bz);
        if (floor == BlockRegistry.LAVA || foot == BlockRegistry.LAVA) {
            return "LAVA";
        }
        // COBWEB: placed at foot or body height to trap a walking/Baritone/elytra-bounce bot.
        Block body = blockAt(bx, g.roadY + 1, bz);
        if (foot == BlockRegistry.COBWEB || body == BlockRegistry.COBWEB) {
            return "COBWEB";
        }
        // PRESENCE (opt-in, count-only, no identity).
        if (cfg.reportPresence && nearbyPlayers(x, z, cfg.presenceRadius) > 0) {
            return "PRESENCE";
        }
        return "CLEAR";
    }

    /** The block at a world position, or null if the chunk isn't loaded/cached yet. */
    private Block blockAt(int bx, int by, int bz) {
        try {
            var ch = CACHE.getChunkCache().get(bx >> 4, bz >> 4);
            if (ch == null) {
                return null;
            }
            int id = ch.getBlockStateId(bx & 15, by, bz & 15);
            return Globals.BLOCK_DATA.getBlockDataFromBlockStateId(id);
        } catch (Exception ex) {
            return null;
        }
    }

    // --- obstruction classification (only runs once ObstructionWatcher confirms a real stall) ---
    private void enqueueObstructionIfPhysical(Geo g, HighwayConditionsConfig.Reporter cfg, Geo.Snap snap,
                                              double x, double z, ObstructionWatcher.Trigger trig, long ts) {
        Geo.Road road = g.roadByIndex(snap.road);
        if (road == null || road.segments == null || snap.seg >= road.segments.length) {
            return;
        }
        int[] seg = road.segments[snap.seg];
        double dx = seg[2] - seg[0], dz = seg[3] - seg[1];
        double len = Math.hypot(dx, dz);
        if (len == 0) {
            return;
        }
        double perpX = -(dz / len), perpZ = dx / len;
        int width = road.roadWidth(6);
        int half = Math.max(1, width / 2);

        List<Integer> blocked = new ArrayList<>();
        for (int w = -half; w <= half; w++) {
            double lx = x + perpX * w, lz = z + perpZ * w;
            if (laneBlocked(lx, lz, g.roadY)) {
                blocked.add(w);
            }
        }
        if (blocked.isEmpty()) {
            return;  // nothing physically there -> the stall had some other cause; don't report
        }

        boolean full = blocked.size() >= (2 * half + 1);
        Map<String, Object> rep;
        if (full) {
            rep = Report.obstruction(cfg.server, g.map, snap.road, snap.seg, snap.along,
                true, 0, 0, trig.sev, ts);
        } else {
            int lo = Collections.min(blocked), hi = Collections.max(blocked);
            rep = Report.obstruction(cfg.server, g.map, snap.road, snap.seg, snap.along,
                false, lo, hi, trig.sev, ts);
        }
        batch.add(rep);
        info("highway-conditions: " + (full ? "FULL" : "PARTIAL")
            + " obstruction detected (stall sev=" + trig.sev + ")");
    }

    /** A lane counts as blocked if it has a solid non-water block in its clear column, or an
     *  item-frame entity sitting in it. Deliberately no sign/item-frame exclusion here — by
     *  the time this runs, ObstructionWatcher already confirmed a real ≥3s stall, so whatever
     *  is physically present is relevant regardless of type (see class javadoc). */
    private boolean laneBlocked(double x, double z, int roadY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int dy = 1; dy <= 3; dy++) {
            if (isSolidNonWater(bx, roadY + dy, bz)) {
                return true;
            }
        }
        return nearbyItemFrame(x, z, roadY);
    }

    private boolean isSolidNonWater(int bx, int by, int bz) {
        try {
            var ch = CACHE.getChunkCache().get(bx >> 4, bz >> 4);
            if (ch == null) {
                return false;  // unknown chunk -> can't confirm; don't claim blocked
            }
            int id = ch.getBlockStateId(bx & 15, by, bz & 15);
            if (id == 0) {
                return false;  // air
            }
            var block = Globals.BLOCK_DATA.getBlockDataFromBlockStateId(id);
            String name = block != null ? block.name() : "";
            return !name.contains("water");
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean nearbyItemFrame(double x, double z, int roadY) {
        try {
            for (var e : CACHE.getEntityCache().getEntities().values()) {
                var t = e.getEntityType();
                if (t != EntityType.ITEM_FRAME && t != EntityType.GLOW_ITEM_FRAME) {
                    continue;
                }
                double dx = e.getX() - x, dz = e.getZ() - z, dy = e.getY() - roadY;
                if (dx * dx + dz * dz <= 1.0 && dy >= 0 && dy <= 4) {
                    return true;
                }
            }
        } catch (Exception ex) {
            // no entities / cache not ready
        }
        return false;
    }

    private boolean isAir(int bx, int by, int bz) {
        try {
            var ch = CACHE.getChunkCache().get(bx >> 4, bz >> 4);
            if (ch == null) {
                return false;  // unknown chunk -> never claim a hole
            }
            return ch.getBlockStateId(bx & 15, by, bz & 15) == 0;  // air is block-state 0
        } catch (Exception ex) {
            return false;
        }
    }

    private int nearbyPlayers(double x, double z, int r) {
        int count = 0;
        try {
            int self = CACHE.getPlayerCache().getEntityId();
            for (var e : CACHE.getEntityCache().getPlayers().values()) {
                if (e.getEntityId() == self) {
                    continue;
                }
                double dx = e.getX() - x, dz = e.getZ() - z;
                if (dx * dx + dz * dz <= (double) r * r) {
                    count++;
                }
            }
        } catch (Exception ex) {
            // no players / cache not ready
        }
        return count;
    }

    // --- throttle: don't (re-)enqueue the same (road,seg,along,cond) within resendSeconds.
    // Read-only -- deliberately does NOT commit lastSent itself. Committing here (as an
    // earlier version did) meant a report that failed to actually SEND (network blip, ingest
    // service briefly down) still burned its resend window: on a highway a bot typically
    // passes a given along-bucket once per direction, so a single transient failure could mean
    // a real hazard never gets reported at all. lastSent is only written by markSent, and only
    // once the send is confirmed (HTTP 200). ---
    private boolean isThrottled(String key, long now, int resendSeconds) {
        Long t = lastSent.get(key);
        return t != null && now - t < resendSeconds;
    }

    /** Commits the resend-throttle for a batch that was just confirmed delivered. Runs on
     *  Globals.EXECUTOR (see {@link #flush}), hence lastSent must be a concurrent map. */
    private void markSent(List<Map<String, Object>> sent, long now, int resendSeconds) {
        for (Map<String, Object> r : sent) {
            lastSent.put(Report.key(r), now);
        }
        if (lastSent.size() > 4096) {
            lastSent.entrySet().removeIf(en -> now - en.getValue() > resendSeconds);
        }
    }

    private void flush(HighwayConditionsConfig.Reporter cfg) {
        if (batch.isEmpty()) {
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> r;
        while ((r = batch.poll()) != null) {
            out.add(r);
        }
        if (out.isEmpty()) {
            return;
        }
        IngestClient c = this.client;
        if (c == null) {
            return;
        }
        int resendSeconds = Math.max(cfg.resendSeconds, MIN_RESEND_SECONDS);
        Globals.EXECUTOR.execute(() -> {
            try {
                int code = c.postReports(out);
                if (code == 200) {
                    markSent(out, System.currentTimeMillis() / 1000L, resendSeconds);
                } else {
                    warn("highway-conditions ingest returned HTTP " + code);
                }
            } catch (Exception ex) {
                debug("highway-conditions post failed: " + ex.getMessage());
            }
        });
    }

    private void fetchGeometry() {
        try {
            HighwayConditionsConfig.Reporter cfg = HighwayConditionsPlugin.PLUGIN_CONFIG.reporter;
            IngestClient c = this.client;
            if (c == null) {
                return;
            }
            Geo g = c.fetchGeometry(cfg.server);
            if (g != null && g.roads != null && g.map != null) {
                this.geo = g;
                info("highway-conditions: geometry loaded (" + g.roads.size()
                    + " roads, map " + g.map + ")");
            }
        } catch (Exception ex) {
            debug("highway-conditions geometry fetch failed: " + ex.getMessage());
        } finally {
            fetching.set(false);
        }
    }
}
