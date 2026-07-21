package com.aquariusnetwork.highwayconditions.net;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the wire report (PROTOCOL.md section 5) as a plain {@code Map} rather than a typed
 * POJO. The ONLY spatial fields are {@code road}, {@code seg}, {@code along} — there is
 * deliberately no x/z/offset field, so an off-highway coordinate cannot be represented here.
 *
 * <p>A map (rather than a class with nullable fields) sidesteps any ambiguity in how Gson
 * serializes nulls: {@code laneMin}/{@code laneMax} must be present ONLY for
 * {@code OBSTRUCTION_PARTIAL} (the strict server schema rejects them everywhere else), and a
 * key that's simply never put on the map can't accidentally round-trip as a JSON {@code null}.
 */
public final class Report {
    private Report() {}

    public static Map<String, Object> basic(String server, String map, int road, int seg,
                                             int along, String cond, long ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("server", server);
        m.put("map", map);
        m.put("road", road);
        m.put("seg", seg);
        m.put("along", along);
        m.put("cond", cond);
        m.put("ts", ts);
        return m;
    }

    /** cond is OBSTRUCTION_FULL (laneMin/laneMax omitted) or OBSTRUCTION_PARTIAL (required). */
    public static Map<String, Object> obstruction(String server, String map, int road, int seg,
                                                   int along, boolean full, int laneMin,
                                                   int laneMax, int sev, long ts) {
        Map<String, Object> m = basic(server, map, road, seg, along,
            full ? "OBSTRUCTION_FULL" : "OBSTRUCTION_PARTIAL", ts);
        if (!full) {
            m.put("laneMin", laneMin);
            m.put("laneMax", laneMax);
        }
        m.put("sev", sev);
        return m;
    }

    /** Stable de-dup key (excludes ts) to throttle resending the same observation. */
    public static String key(Map<String, Object> report) {
        return report.get("road") + "|" + report.get("seg") + "|" + report.get("along")
            + "|" + report.get("cond");
    }
}
