package com.aquariusnetwork.highwayconditions.module;

/**
 * Mode-agnostic "is the road obstructed here" stall watchdog (PROTOCOL.md §5.1).
 *
 * <p>This is a fresh, independent implementation — {@code ElytraPilot}'s own bounce-stall
 * timer is private and module-scoped, so it can't be hooked from here. The pattern mirrors it
 * (per-tick XZ position delta vs. a speed threshold) but generalizes the threshold: rather
 * than a fixed blocks/sec constant (which is flight-tuned), the trigger is relative to the
 * bot's own recently-observed peak speed, so one detector works whether the bot is walking,
 * Baritone-driving, or elytra-bouncing without per-mode constants.
 *
 * <p>This IS the sign/item-frame filter: decorative signs and item frames have negligible
 * collision and essentially never sustain a real stall, so they're excluded by construction —
 * no block/entity-type blacklist is needed. If something ever DOES cause a genuine 3s+ stall,
 * whatever is physically there is worth reporting regardless of what it is (see the
 * classification scan in {@link HighwayReporterModule}).
 */
final class ObstructionWatcher {

    private static final double PEAK_DECAY = 0.995;     // ~7s half-life
    private static final double MIN_ARM_BPS = 3.0;       // peak must exceed this to arm at all
    private static final double STALL_FRACTION = 0.20;   // speed must drop below 20% of peak
    static final int STALL_TICKS = 60;                   // 3.0s @ 20 tick/s -> sev 1
    static final int ESCALATE_TICKS_1 = 120;              // 6.0s -> sev 2
    static final int ESCALATE_TICKS_2 = 300;              // 15.0s -> sev 3 (matches ElytraPilot's
                                                           // own PIN_ABORT_TICKS precedent)

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private double peakBps = 0.0;
    private int stallTicks = 0;
    private int lastReportedSev = 0;

    static final class Trigger {
        final int sev;
        Trigger(int sev) { this.sev = sev; }
    }

    /**
     * Call every tick while the base on-road gate holds. Returns a {@link Trigger} the first
     * time each new severity tier is crossed (edge-triggered — never re-fires the same tier),
     * else {@code null}.
     */
    Trigger tick(double x, double z) {
        if (Double.isNaN(lastX)) {
            lastX = x;
            lastZ = z;
            return null;
        }
        double bps = Math.hypot(x - lastX, z - lastZ) * 20.0;
        lastX = x;
        lastZ = z;
        peakBps = Math.max(peakBps * PEAK_DECAY, bps);

        boolean armed = peakBps >= MIN_ARM_BPS;
        boolean stalled = armed && bps < STALL_FRACTION * peakBps;
        if (!stalled) {
            stallTicks = 0;
            lastReportedSev = 0;
            return null;
        }
        stallTicks++;
        int sev = stallTicks >= ESCALATE_TICKS_2 ? 3
            : stallTicks >= ESCALATE_TICKS_1 ? 2
            : stallTicks >= STALL_TICKS ? 1 : 0;
        if (sev > lastReportedSev) {
            lastReportedSev = sev;
            return new Trigger(sev);
        }
        return null;
    }

    /** Call when leaving the road/gate so stale peak-speed/stall state doesn't carry over. */
    void reset() {
        lastX = Double.NaN;
        lastZ = Double.NaN;
        peakBps = 0.0;
        stallTicks = 0;
        lastReportedSev = 0;
    }
}
