package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import net.tfminecraft.vehicleframework.cache.Cache;

/**
 * Block clearance for a short, proposed movement, before any car is teleported.
 *
 * <p>A car needs the space above its rails, {@link Cache#trainClearanceWidth} wide and
 * {@link Cache#trainClearanceHeight} high from the rail, along its length out to its
 * couplers. The space follows the track through bends and grades, so it matches what
 * laying track keeps clear rather than the model's square hitbox.
 */
public final class TrainBlockCollision {
    // Short slices follow bends and grades closely without testing every sample.
    private static final double SLICE = 0.5;
    // Touching a block face is not a collision.
    private static final double EPS = 1e-6;

    private TrainBlockCollision() {
    }

    /**
     * Whether a car moving from one track position to another would run into a block.
     * Blocks already inside the car's space at the start are ignored, so a car can
     * still move off something that ended up inside it.
     */
    public static boolean blocked(Entity entity, TrackSpline fromSpline, double fromS,
            TrackSpline toSpline, double toS, double reach) {
        if (entity == null || entity.getWorld() == null || toSpline == null) {
            return false;
        }
        World world = entity.getWorld();
        Map<Long, List<BoundingBox>> shapes = new HashMap<>();
        Set<Long> inside = fromSpline == null
                ? Set.of()
                : touched(world, slices(fromSpline, fromS, reach), shapes, Set.of(), false);
        // Steps are much shorter than a car, so the car's space at the end of a step
        // also covers the blocks it passed, even on fast ticks.
        return !touched(world, slices(toSpline, toS, reach), shapes, inside, true).isEmpty();
    }

    private record Slice(double x, double z, double fx, double fz, double halfLength,
            double minY, double maxY) {
    }

    private static List<Slice> slices(TrackSpline spline, double s, double reach) {
        List<Slice> slices = new ArrayList<>();
        double length = Math.max(SLICE, 2 * reach);
        int count = (int) Math.ceil(length / SLICE);
        double step = length / count;
        for (int i = 0; i < count; i++) {
            TrackPose pose = spline.sampleAt(s - length / 2 + (i + 0.5) * step);
            double yaw = Math.toRadians(pose.yaw);
            slices.add(new Slice(pose.x, pose.z, -Math.sin(yaw), Math.cos(yaw), step / 2,
                    pose.y + Cache.trackVehicleYOffset, pose.y + Cache.trainClearanceHeight));
        }
        return slices;
    }

    private static Set<Long> touched(World world, List<Slice> slices, Map<Long, List<BoundingBox>> shapes,
            Set<Long> ignore, boolean firstOnly) {
        Set<Long> hits = new HashSet<>();
        double halfWidth = Cache.trainClearanceWidth / 2;
        for (Slice slice : slices) {
            if (slice.maxY - slice.minY <= EPS) {
                continue;
            }
            double ex = slice.halfLength * Math.abs(slice.fx) + halfWidth * Math.abs(slice.fz);
            double ez = slice.halfLength * Math.abs(slice.fz) + halfWidth * Math.abs(slice.fx);
            // Fences and walls can extend above the block containing them.
            for (int x = (int) Math.floor(slice.x - ex); x < Math.ceil(slice.x + ex); x++) {
                for (int y = (int) Math.floor(slice.minY) - 1; y < Math.ceil(slice.maxY); y++) {
                    for (int z = (int) Math.floor(slice.z - ez); z < Math.ceil(slice.z + ez); z++) {
                        long key = key(x, y, z);
                        if (ignore.contains(key) || hits.contains(key)) {
                            continue;
                        }
                        for (BoundingBox solid : shapes.computeIfAbsent(key, k -> solids(world, k))) {
                            if (overlaps(slice, halfWidth, solid)) {
                                hits.add(key);
                                if (firstOnly) {
                                    return hits;
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    private static List<BoundingBox> solids(World world, long key) {
        int x = (int) (key >> 38);
        int y = (int) (key << 52 >> 52);
        int z = (int) (key << 26 >> 38);
        Block block = world.getBlockAt(x, y, z);
        if (block.isPassable()) {
            return List.of();
        }
        List<BoundingBox> solids = new ArrayList<>();
        for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
            solids.add(local.clone().shift(x, y, z));
        }
        return solids;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    // Separating axes: the world X and Z axes, then the slice's own two axes.
    private static boolean overlaps(Slice slice, double halfWidth, BoundingBox box) {
        if (box.getMinY() >= slice.maxY - EPS || box.getMaxY() <= slice.minY + EPS) {
            return false;
        }
        double hx = box.getWidthX() / 2;
        double hz = box.getWidthZ() / 2;
        double dx = box.getCenterX() - slice.x;
        double dz = box.getCenterZ() - slice.z;
        double fx = slice.fx;
        double fz = slice.fz;
        double ex = slice.halfLength * Math.abs(fx) + halfWidth * Math.abs(fz);
        double ez = slice.halfLength * Math.abs(fz) + halfWidth * Math.abs(fx);
        if (Math.abs(dx) >= ex + hx - EPS || Math.abs(dz) >= ez + hz - EPS) {
            return false;
        }
        double along = Math.abs(dx * fx + dz * fz);
        double alongReach = slice.halfLength + hx * Math.abs(fx) + hz * Math.abs(fz);
        double across = Math.abs(dx * fz - dz * fx);
        double acrossReach = halfWidth + hx * Math.abs(fz) + hz * Math.abs(fx);
        return along < alongReach - EPS && across < acrossReach - EPS;
    }
}
