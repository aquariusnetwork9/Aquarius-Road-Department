package com.aquariusnetwork.highwayconditions.net;

import java.util.List;

/**
 * Authoritative geometry fetched from the ingest service's {@code GET /geometry/<server>}.
 *
 * <p>Fetching (rather than bundling) the road table makes the client and server share ONE
 * source of truth: the {@link #map} hash the server expects and the exact roads it will
 * re-derive against. The snapping / quantization here mirrors the reference implementation
 * ({@code protocol/geometry.py}) 1:1 so a report's {@code (road, seg, along)} means the same
 * thing on both ends. No {@code (x, z)} ever leaves this object in a report.
 */
public final class Geo {

    public String map;
    public int bucket;
    public int roadY;
    public int nearSpawnRadius;
    public double tolerance;
    public List<Road> roads;

    public static final class Road {
        public int i;
        public String category;
        public String surface;
        public String dim;      // e.g. "6x4" (width x height); null for grid roads
        public Integer radius;
        public int[][] segments;

        /** Parsed road width in blocks (first number of {@link #dim}), or {@code fallback}
         *  when absent/unparseable. Mirrors {@code geometry.road_width} in the Python server
         *  so both sides agree on lane bounds for obstruction classification. */
        public int roadWidth(int fallback) {
            if (dim == null || !dim.contains("x")) return fallback;
            try {
                return Integer.parseInt(dim.substring(0, dim.indexOf('x')));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** {@code roads[idx]} looked up by its declared {@code i}, tolerating reordering. */
    public Road roadByIndex(int idx) {
        if (roads == null || idx < 0) return null;
        if (idx < roads.size() && roads.get(idx).i == idx) return roads.get(idx);
        for (Road r : roads) {
            if (r.i == idx) return r;
        }
        return null;
    }

    /** Result of snapping a position onto an allowed road. Carries only the 1-D coordinate. */
    public static final class Snap {
        public final int road;
        public final int seg;
        public final int along;
        public final double dist;   // perpendicular offset — used for the gate, never reported

        Snap(int road, int seg, int along, double dist) {
            this.road = road;
            this.seg = seg;
            this.along = along;
            this.dist = dist;
        }
    }

    private static double[] closestPoint(double px, double pz, int[] s) {
        double x1 = s[0], z1 = s[1], x2 = s[2], z2 = s[3];
        double dx = x2 - x1, dz = z2 - z1;
        double len2 = dx * dx + dz * dz;
        if (len2 == 0.0) return new double[] {x1, z1};
        double t = ((px - x1) * dx + (pz - z1) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new double[] {x1 + t * dx, z1 + t * dz};
    }

    private boolean regionAllows(String category, double x, double z) {
        if (Math.max(Math.abs(x), Math.abs(z)) <= nearSpawnRadius) return true;
        return "axis".equals(category);
    }

    /**
     * Snap (x,z) onto the nearest allowed, usable road within tolerance, quantizing to a
     * 1-D coordinate. Returns null if off-road or the nearest road is disallowed here.
     */
    public Snap nearestAllowed(double x, double z) {
        if (roads == null) return null;
        Snap best = null;
        for (Road r : roads) {
            if (r.segments == null || "planned".equals(r.surface)) continue;
            for (int si = 0; si < r.segments.length; si++) {
                int[] s = r.segments[si];
                double[] c = closestPoint(x, z, s);
                if (!regionAllows(r.category, c[0], c[1])) continue;
                double d = Math.hypot(x - c[0], z - c[1]);
                if (best == null || d < best.dist) {
                    double d0 = Math.hypot(c[0] - s[0], c[1] - s[1]);
                    int along = (int) Math.floor(d0 / bucket);
                    best = new Snap(r.i, si, along, d);
                }
            }
        }
        if (best == null || best.dist > tolerance) return null;
        return best;
    }
}
