package com.aquariusnetwork.highwayconditions.api;

/**
 * A single, live, locally-detected obstruction on the road the player is currently on
 * (PROTOCOL.md §5.1's FULL/PARTIAL split) — the same classification the network reporter would
 * turn into an {@code OBSTRUCTION_FULL}/{@code OBSTRUCTION_PARTIAL} report, but delivered
 * immediately, in-process, with no network round-trip and no trust-tier gate.
 *
 * <p><b>Unlike the wire protocol, this record freely carries real {@code x}/{@code y}/{@code z}
 * and direction vectors.</b> PROTOCOL.md's "no off-highway coordinate" guarantee is about what
 * this mod ever transmits over the network; this event never leaves the JVM and is always about
 * the local player's own, already-known position, so there's nothing to protect by hiding it —
 * withholding it would only make the API useless to a consumer that wants to actually do
 * something about the hazard (e.g. compute a detour).
 *
 * @param fullyBlocked  true for OBSTRUCTION_FULL (the whole road width), false for PARTIAL
 * @param laneMin       blocked-span low end, lane-offset blocks from the player's own position
 *                      along {@code perpX}/{@code perpZ}; {@code null} when {@code fullyBlocked}
 * @param laneMax       blocked-span high end; {@code null} when {@code fullyBlocked}
 * @param severity      1-3, matching the stall-duration tiers in {@code ObstructionWatcher}
 *                      (3s / 6s / 15s sustained)
 * @param x             player X at detection time
 * @param y             player Y at detection time
 * @param z             player Z at detection time
 * @param headingX      unit vector X-component along the road's direction of travel
 * @param headingZ      unit vector Z-component along the road's direction of travel
 * @param perpX         unit vector X-component across the road (the lane-offset axis)
 * @param perpZ         unit vector Z-component across the road (the lane-offset axis)
 * @param road          ARD's internal road index (see {@code net.Geo.Road#i}) — meaningful only
 *                      within this client session; not a stable cross-session identifier
 * @param seg           segment index within that road's polyline
 * @param along         quantized distance along the segment (same bucketing as PROTOCOL.md §1)
 * @param atMs          {@code System.currentTimeMillis()} when this was detected
 */
public record LocalHazard(
    boolean fullyBlocked,
    Integer laneMin,
    Integer laneMax,
    int severity,
    double x,
    double y,
    double z,
    double headingX,
    double headingZ,
    double perpX,
    double perpZ,
    int road,
    int seg,
    int along,
    long atMs
) {
}
