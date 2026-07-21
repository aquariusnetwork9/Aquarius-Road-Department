package com.aquariusnetwork.highwayconditions;

/**
 * Plugin configuration (plugins/config/highway-conditions.json). Public mutable fields.
 *
 * <p>Reporting is OFF by default: like ProxyBridge's PositionReporter, a contributor must
 * opt in. Even when on, the plugin can only ever emit an on-highway 1-D coordinate — see
 * the network's PROTOCOL.md.
 */
public class HighwayConditionsConfig {

    public final Reporter reporter = new Reporter();

    public static class Reporter {
        /** Master switch. Default OFF (opt-in). */
        public boolean enabled = false;

        /** Ingest service base URL (the highway-conditions server). */
        public String ingestUrl = "http://127.0.0.1:8788";

        /** Fleet (Tier A) token. Sent as the Authorization header. Kept out of logs. */
        public String token = "";

        /** Server id this bot is on; must match a server the ingest knows. */
        public String server = "2b2t.org";

        /** Per-contributor privacy cap: never report when max(|x|,|z|) exceeds this. */
        public int maxReportRadius = 500_000;

        /** Camper/player-presence reporting. Opt-in, default OFF (count only, no identity). */
        public boolean reportPresence = false;

        /** Also report CLEAR (road-traversed) samples, not just hazards. */
        public boolean reportClear = true;

        /** How often to flush the batched reports, in ticks (~20/s). Floored at 20 (1s)
         *  regardless of what's configured here — protects the ingest service from a
         *  fat-fingered 0/negative value turning this into a per-tick flood. */
        public int flushIntervalTicks = 100;

        /** Don't resend the same (road,seg,along,cond) within this many seconds. Floored at
         *  10s regardless of what's configured here, same reasoning as flushIntervalTicks. */
        public int resendSeconds = 120;

        /** Radius (blocks) to count nearby players for PRESENCE. */
        public int presenceRadius = 24;
    }
}
