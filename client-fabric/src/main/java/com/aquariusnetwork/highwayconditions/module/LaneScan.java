package com.aquariusnetwork.highwayconditions.module;

import com.aquariusnetwork.highwayconditions.net.Geo;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cross-section lane-blockage scan (PROTOCOL.md §5.1), shared by every consumer of a confirmed
 * {@link ObstructionWatcher} stall trigger. Extracted out of {@code HighwayReporterModule} so the
 * network-report path and the always-on local alert ({@code LocalHazardModule}) classify a stall
 * identically instead of maintaining two copies of the same scan.
 */
final class LaneScan {

    private LaneScan() {}

    /** The road-relative frame at a given segment: unit vectors along ({@code headingX/Z}) and
     *  across ({@code perpX/Z}) the road, plus the lane half-width used for the scan. */
    static final class Frame {
        final double headingX, headingZ, perpX, perpZ;
        final int half;

        private Frame(double headingX, double headingZ, double perpX, double perpZ, int half) {
            this.headingX = headingX;
            this.headingZ = headingZ;
            this.perpX = perpX;
            this.perpZ = perpZ;
            this.half = half;
        }
    }

    /** Builds the frame for {@code road}'s segment {@code seg}, or {@code null} if the segment is
     *  missing or degenerate (zero length) -- callers should skip the scan entirely in that case. */
    static Frame frame(Geo.Road road, int seg) {
        if (road == null || road.segments == null || seg < 0 || seg >= road.segments.length) {
            return null;
        }
        int[] s = road.segments[seg];
        double dx = s[2] - s[0], dz = s[3] - s[1];
        double len = Math.hypot(dx, dz);
        if (len == 0) {
            return null;
        }
        double headingX = dx / len, headingZ = dz / len;
        double perpX = -headingZ, perpZ = headingX;
        int half = Math.max(1, road.roadWidth(6) / 2);
        return new Frame(headingX, headingZ, perpX, perpZ, half);
    }

    /** FULL vs PARTIAL classification of a confirmed stall at {@code (x,z)}, or {@code null} if
     *  the scan found nothing physical (the stall had some other cause -- caller shouldn't treat
     *  it as an obstruction at all). */
    static Classification classify(MinecraftClient mc, Frame f, double x, double z, int roadY) {
        List<Integer> blocked = new ArrayList<>();
        for (int w = -f.half; w <= f.half; w++) {
            double lx = x + f.perpX * w, lz = z + f.perpZ * w;
            if (laneBlocked(mc, lx, lz, roadY)) {
                blocked.add(w);
            }
        }
        if (blocked.isEmpty()) {
            return null;
        }
        boolean full = blocked.size() >= (2 * f.half + 1);
        if (full) {
            return new Classification(true, 0, 0);
        }
        return new Classification(false, Collections.min(blocked), Collections.max(blocked));
    }

    static final class Classification {
        final boolean full;
        final int laneMin, laneMax;

        private Classification(boolean full, int laneMin, int laneMax) {
            this.full = full;
            this.laneMin = laneMin;
            this.laneMax = laneMax;
        }
    }

    /** A lane counts as blocked if it has a solid non-water block in its clear column, or an
     *  item-frame entity sitting in it. Deliberately no sign/item-frame exclusion here -- by the
     *  time this runs, {@link ObstructionWatcher} already confirmed a real >=3s stall, so whatever
     *  is physically present is relevant regardless of type (see that class's own javadoc). */
    private static boolean laneBlocked(MinecraftClient mc, double x, double z, int roadY) {
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
     *  in vanilla, so scanning for {@code ItemFrameEntity} alone already covers both. */
    private static boolean nearbyItemFrame(MinecraftClient mc, double x, double z, int roadY) {
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

    private static BlockState blockAt(MinecraftClient mc, int bx, int by, int bz) {
        if (mc.world == null || !mc.world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        return mc.world.getBlockState(new BlockPos(bx, by, bz));
    }
}
