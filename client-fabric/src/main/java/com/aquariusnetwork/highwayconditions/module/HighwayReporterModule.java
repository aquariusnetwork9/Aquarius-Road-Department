package com.aquariusnetwork.highwayconditions.module;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.net.Geo;
import com.aquariusnetwork.highwayconditions.net.GeoCache;
import com.aquariusnetwork.highwayconditions.net.IngestClient;
import com.aquariusnetwork.highwayconditions.net.Report;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Polls the client's own player state each tick and, when on a highway, enqueues a 1-D condition
 * report. Fabric-native port of AquariusProxy's {@code HighwayReporterModule} in
 * {@code plugin-aquarius} -- the gate order and reporting logic mirror it 1:1 (see that class and
 * PROTOCOL.md SS5), only the AquariusProxy bot-state accessors changed to Fabric's own
 * {@link MinecraftClient} APIs.
 *
 * <p>Obstruction detection (PROTOCOL.md SS5.1) is layered on top of the same gate: an
 * {@link ObstructionWatcher} tracks per-tick stall state and, once a real >=3s stall is
 * confirmed, a one-shot cross-section scan classifies it as FULL/PARTIAL -- ported unchanged,
 * see that class's own javadoc for why signs/item frames don't need a blacklist.
 */
public class HighwayReporterModule {

    /** Floor for {@code cfg.flushIntervalTicks}/{@code cfg.resendSeconds} so a fat-fingered
     *  config edit (e.g. 0) can't turn this into a self-inflicted flood against the ingest
     *  service -- this network's whole premise depends on contributors being well-behaved. */
    private static final int MIN_FLUSH_INTERVAL_TICKS = 20;   // 1s @ 20 tick/s
    private static final int MIN_RESEND_SECONDS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger("ard");

    private final HighwayConditionsConfig cfg;
    private final GeoCache geoCache;
    private final ExecutorService executor;

    private int flushTickCounter = 0;
    private final ConcurrentLinkedQueue<Map<String, Object>> batch = new ConcurrentLinkedQueue<>();
    // ConcurrentHashMap: reads happen on the tick thread (enqueue-time isThrottled check),
    // writes happen on the shared executor (markSent, only after a confirmed-successful flush).
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final ObstructionWatcher obstructionWatcher = new ObstructionWatcher();

    private volatile IngestClient client;
    private String clientUrl;    // last url/token the client was built from -- tick-thread only
    private String clientToken;

    public HighwayReporterModule(HighwayConditionsConfig cfg, GeoCache geoCache, ExecutorService executor) {
        this.cfg = cfg;
        this.geoCache = geoCache;
        this.executor = executor;
        refreshClient();
    }

    /** The client this module currently reports through -- shared read-only by the HUD element,
     *  which never needs its own ingest client (geometry/conditions reads reuse this one). */
    public IngestClient currentClient() {
        return client;
    }

    /** (Re)builds the ingest client if the config's URL/token changed since the last build -- a
     *  live edit of {@code ard.json} (or an /ard token/reporting command) must not keep talking
     *  to a stale endpoint with a stale credential. Cheap no-op when unchanged. */
    private void refreshClient() {
        HighwayConditionsConfig.Reporter r = cfg.reporter;
        if (client != null && Objects.equals(clientUrl, r.ingestUrl) && Objects.equals(clientToken, r.token)) {
            return;
        }
        IngestClient old = client;
        if (old != null) {
            executor.execute(old::close);
        }
        client = new IngestClient(r.ingestUrl, r.token);
        clientUrl = r.ingestUrl;
        clientToken = r.token;
    }

    /** Call from END_CLIENT_TICK every tick. Does nothing at all when reporting is disabled --
     *  geometry is fetched independently by the shared {@link GeoCache} (the HUD needs it too,
     *  regardless of this module's enabled state). */
    public void tick(MinecraftClient mc) {
        refreshClient();

        HighwayConditionsConfig.Reporter r = cfg.reporter;
        if (!r.enabled) {
            obstructionWatcher.reset();
            return;
        }

        if (flushTickCounter++ >= Math.max(r.flushIntervalTicks, MIN_FLUSH_INTERVAL_TICKS)) {
            flushTickCounter = 0;
            flush();
        }

        Geo g = geoCache.get();
        if (g == null) {
            obstructionWatcher.reset();
            return;  // still bootstrapping; the shared GeoCache is fetching off-thread
        }
        if (mc.player == null || mc.world == null) {
            obstructionWatcher.reset();
            return;
        }
        if (mc.world.getRegistryKey() != World.NETHER) {
            obstructionWatcher.reset();
            return;
        }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        if (Math.max(Math.abs(x), Math.abs(z)) > r.maxReportRadius) {
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
            enqueueObstructionIfPhysical(mc, g, r, snap, x, z, trig, ts);
        }

        String cond = sampleCondition(mc, x, y, z, r, g);
        if (cond.equals("CLEAR") && !r.reportClear) {
            return;
        }
        Map<String, Object> rep = Report.basic(r.server, g.map, snap.road, snap.seg, snap.along, cond, ts);
        if (isThrottled(Report.key(rep), now, Math.max(r.resendSeconds, MIN_RESEND_SECONDS))) {
            return;
        }
        batch.add(rep);
        if (batch.size() >= 128) {
            flush();
        }
    }

    /** Flushes any queued reports immediately -- called from the tick loop on schedule, and from
     *  the /ard reporting off command (mirrors the proxy plugin's onDisable behavior: don't lose
     *  a batch just because the user toggled off right after it was built). */
    public void flushNow() {
        flush();
    }

    // --- condition sampling (v1: robust subset; LAVA/COBWEB identity-checked against
    // Blocks.LAVA/COBWEB via BlockState#isOf -- version-stable, no name-string matching. Mirrors
    // plugin-aquarius's own BlockRegistry-identity approach.) ---
    private String sampleCondition(MinecraftClient mc, double x, double y, double z,
                                   HighwayConditionsConfig.Reporter r, Geo g) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        // HOLE: the two blocks beneath the road plane are air -> a gap you'd fall through.
        if (isAir(mc, bx, g.roadY - 1, bz) && isAir(mc, bx, g.roadY - 2, bz)) {
            return "HOLE";
        }
        // LAVA: the road floor itself, or the space a player stands in, is lava -- either a
        // burned-through/void-breach section (floor) or a puddle poured onto an intact floor
        // (foot level). Either way the road is impassable on foot here.
        BlockState floor = blockAt(mc, bx, g.roadY - 1, bz);
        BlockState foot = blockAt(mc, bx, g.roadY, bz);
        if (isLava(floor) || isLava(foot)) {
            return "LAVA";
        }
        // COBWEB: placed at foot or body height to trap a walking/bouncing player.
        BlockState body = blockAt(mc, bx, g.roadY + 1, bz);
        if (isCobweb(foot) || isCobweb(body)) {
            return "COBWEB";
        }
        // PRESENCE (opt-in, count-only, no identity).
        if (r.reportPresence && nearbyPlayers(mc, x, z, r.presenceRadius) > 0) {
            return "PRESENCE";
        }
        return "CLEAR";
    }

    private static boolean isLava(BlockState state) {
        return state != null && state.isOf(Blocks.LAVA);
    }

    private static boolean isCobweb(BlockState state) {
        return state != null && state.isOf(Blocks.COBWEB);
    }

    /** The block state at a world position, or null if the chunk isn't loaded. Conservative by
     *  construction: an unknown chunk never claims a condition, same posture as the proxy's
     *  chunk-cache-may-be-null handling. */
    private BlockState blockAt(MinecraftClient mc, int bx, int by, int bz) {
        if (mc.world == null || !mc.world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        return mc.world.getBlockState(new BlockPos(bx, by, bz));
    }

    private boolean isAir(MinecraftClient mc, int bx, int by, int bz) {
        BlockState state = blockAt(mc, bx, by, bz);
        return state != null && state.isAir();  // unknown chunk -> never claim a hole
    }

    // --- obstruction classification (only runs once ObstructionWatcher confirms a real stall) ---
    private void enqueueObstructionIfPhysical(MinecraftClient mc, Geo g, HighwayConditionsConfig.Reporter r,
                                              Geo.Snap snap, double x, double z,
                                              ObstructionWatcher.Trigger trig, long ts) {
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
            if (laneBlocked(mc, lx, lz, g.roadY)) {
                blocked.add(w);
            }
        }
        if (blocked.isEmpty()) {
            return;  // nothing physically there -> the stall had some other cause; don't report
        }

        boolean full = blocked.size() >= (2 * half + 1);
        Map<String, Object> rep;
        if (full) {
            rep = Report.obstruction(r.server, g.map, snap.road, snap.seg, snap.along,
                true, 0, 0, trig.sev, ts);
        } else {
            int lo = Collections.min(blocked), hi = Collections.max(blocked);
            rep = Report.obstruction(r.server, g.map, snap.road, snap.seg, snap.along,
                false, lo, hi, trig.sev, ts);
        }
        batch.add(rep);
    }

    /** A lane counts as blocked if it has a solid non-water block in its clear column, or an
     *  item-frame entity sitting in it. Deliberately no sign/item-frame exclusion here -- by the
     *  time this runs, ObstructionWatcher already confirmed a real >=3s stall, so whatever is
     *  physically present is relevant regardless of type (see that class's javadoc). */
    private boolean laneBlocked(MinecraftClient mc, double x, double z, int roadY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int dy = 1; dy <= 3; dy++) {
            BlockState state = blockAt(mc, bx, roadY + dy, bz);
            if (state != null && !state.isAir() && !state.isOf(Blocks.WATER)) {
                return true;
            }
        }
        return nearbyItemFrame(mc, x, z, roadY);
    }

    /** {@link net.minecraft.entity.decoration.GlowItemFrameEntity} extends {@link ItemFrameEntity}
     *  in vanilla, so scanning for {@code ItemFrameEntity} alone already covers both -- no
     *  separate glow-item-frame check needed (unlike the proxy's entity-type-based iteration). */
    private boolean nearbyItemFrame(MinecraftClient mc, double x, double z, int roadY) {
        if (mc.world == null) {
            return false;
        }
        Box box = new Box(x - 2, roadY - 1, z - 2, x + 2, roadY + 5, z + 2);
        List<ItemFrameEntity> frames = mc.world.getEntitiesByClass(ItemFrameEntity.class, box, e -> true);
        for (ItemFrameEntity e : frames) {
            double dx = e.getX() - x, dz = e.getZ() - z, dy = e.getY() - roadY;
            if (dx * dx + dz * dz <= 1.0 && dy >= 0 && dy <= 4) {
                return true;
            }
        }
        return false;
    }

    private int nearbyPlayers(MinecraftClient mc, double x, double z, int radius) {
        if (mc.world == null || mc.player == null) {
            return 0;
        }
        Box box = new Box(x - radius, mc.player.getY() - 10, z - radius,
                          x + radius, mc.player.getY() + 10, z + radius);
        List<PlayerEntity> nearby = mc.world.getEntitiesByClass(PlayerEntity.class, box,
            p -> p != mc.player);
        int count = 0;
        for (PlayerEntity p : nearby) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= (double) radius * radius) {
                count++;
            }
        }
        return count;
    }

    // --- throttle: don't (re-)enqueue the same (road,seg,along,cond) within resendSeconds.
    // Read-only -- deliberately does NOT commit lastSent itself. Committing here would mean a
    // report that failed to actually SEND (network blip, ingest service briefly down) still
    // burns its resend window: on a highway a player typically passes a given along-bucket once
    // per direction, so a single transient failure could mean a real hazard never gets reported
    // at all. lastSent is only written by markSent, and only once the send is confirmed (200). ---
    private boolean isThrottled(String key, long now, int resendSeconds) {
        Long t = lastSent.get(key);
        return t != null && now - t < resendSeconds;
    }

    private void markSent(List<Map<String, Object>> sent, long now, int resendSeconds) {
        for (Map<String, Object> r : sent) {
            lastSent.put(Report.key(r), now);
        }
        if (lastSent.size() > 4096) {
            lastSent.entrySet().removeIf(en -> now - en.getValue() > resendSeconds);
        }
    }

    private void flush() {
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
        int resendSeconds = Math.max(cfg.reporter.resendSeconds, MIN_RESEND_SECONDS);
        executor.execute(() -> {
            try {
                int code = c.postReports(out);
                if (code == 200) {
                    markSent(out, System.currentTimeMillis() / 1000L, resendSeconds);
                } else {
                    LOGGER.debug("Highway Conditions: ingest returned HTTP {}", code);
                }
            } catch (Exception ex) {
                LOGGER.debug("Highway Conditions: report POST failed: {}", ex.toString());
                // transient network failure -- lastSent is untouched, so the next tick's pass
                // over this segment (or the next scheduled flush) will simply try again.
            }
        });
    }
}
