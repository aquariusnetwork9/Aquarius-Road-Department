package com.aquariusnetwork.highwayconditions.module;

import com.aquariusnetwork.highwayconditions.api.LocalHazard;
import com.aquariusnetwork.highwayconditions.api.LocalHazardEvents;
import com.aquariusnetwork.highwayconditions.net.Geo;
import com.aquariusnetwork.highwayconditions.net.GeoCache;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

/**
 * Always-on local obstruction alert -- runs regardless of {@code cfg.reporter.enabled} (network
 * reporting is opt-in; this isn't, since nothing here ever leaves the client). Reuses the same
 * {@link ObstructionWatcher}/{@link LaneScan} detection {@code HighwayReporterModule} feeds the
 * network with, but on its own independent {@link ObstructionWatcher} instance, so toggling
 * report submission never resets this one's stall state -- and fires it immediately as a
 * {@link LocalHazardEvents} event, with no trust tier, no publish latency, no network dependency
 * at all. This is the always-on backbone behind both the HUD's local-alert line and the public
 * API third-party mods hook into.
 *
 * <p>Deliberately looser gate than the network path: no per-contributor radius cap and no strict
 * y120 check (those exist for network-privacy/schema reasons that don't apply to an in-process
 * event about your own player) -- mirrors {@code HazardHudElement}'s own looser "just on-road"
 * gate rather than {@code HighwayReporterModule}'s stricter one.
 */
public final class LocalHazardModule {

    private final GeoCache geoCache;
    private final ObstructionWatcher watcher = new ObstructionWatcher();

    private volatile LocalHazard current;

    public LocalHazardModule(GeoCache geoCache) {
        this.geoCache = geoCache;
    }

    /** The most recently detected, still-active local hazard, or {@code null}. Safe to poll from
     *  the render thread (e.g. {@code HazardHudElement}) -- this field is only ever written from
     *  {@link #tick}, itself only ever called from END_CLIENT_TICK, so reader and writer share a
     *  thread; {@code volatile} is only for visibility, not for concurrency control. */
    public LocalHazard current() {
        return current;
    }

    /** Call from END_CLIENT_TICK every tick, unconditionally. */
    public void tick(MinecraftClient mc) {
        Geo g = geoCache.get();
        if (g == null || mc.player == null || mc.world == null
            || mc.world.getRegistryKey() != World.NETHER) {
            leaveGate();
            return;
        }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        Geo.Snap snap = g.nearestAllowed(x, z);
        if (snap == null) {
            leaveGate();  // OFF-ROAD
            return;
        }

        ObstructionWatcher.Trigger trig = watcher.tick(x, z);
        if (trig != null) {
            fireIfPhysical(mc, g, snap, x, y, z, trig);
        } else if (!watcher.isActive() && current != null) {
            clearHazard();  // stall resolved on its own (still on-road -- don't reset peak-speed)
        }
    }

    private void fireIfPhysical(MinecraftClient mc, Geo g, Geo.Snap snap, double x, double y, double z,
                                ObstructionWatcher.Trigger trig) {
        Geo.Road road = g.roadByIndex(snap.road);
        LaneScan.Frame frame = LaneScan.frame(road, snap.seg);
        if (frame == null) {
            return;
        }
        LaneScan.Classification c = LaneScan.classify(mc, frame, x, z, g.roadY);
        if (c == null) {
            return;  // nothing physically there -- the stall had some other cause
        }
        LocalHazard hazard = new LocalHazard(
            c.full, c.full ? null : c.laneMin, c.full ? null : c.laneMax,
            trig.sev, x, y, z, frame.headingX, frame.headingZ, frame.perpX, frame.perpZ,
            snap.road, snap.seg, snap.along, System.currentTimeMillis());
        current = hazard;
        LocalHazardEvents.DETECTED.invoker().onLocalHazardDetected(hazard);
    }

    /** Leaving the road/nether/gate entirely -- unlike a stall simply resolving in place, this
     *  also resets the watcher's peak-speed tracking (matches {@code HighwayReporterModule}'s own
     *  reset-on-every-early-return posture). */
    private void leaveGate() {
        watcher.reset();
        clearHazard();
    }

    private void clearHazard() {
        if (current != null) {
            current = null;
            LocalHazardEvents.CLEARED.invoker().onLocalHazardCleared();
        }
    }
}
