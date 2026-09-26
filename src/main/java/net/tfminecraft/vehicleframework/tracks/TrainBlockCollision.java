package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
     * Blocks already inside the car's space at the start are ignored where the car is
     * not moving into new space, so it can still move off something that ended up
     * inside it but cannot push further into it.
     */
    public static boolean blocked(Entity entity, TrackSpline fromSpline, double fromS,
            TrackSpline toSpline, double toS, double reach) {
        return blocked(entity, fromSpline, fromS, toSpline, toS, reach, new HashMap<>());
    }

    /** As above, reusing block shapes already looked up during the same movement tick. */
    public static boolean blocked(Entity entity, TrackSpline fromSpline, double fromS,
            TrackSpline toSpline, double toS, double reach, Map<Long, List<BoundingBox>> shapes) {
        if (entity == null || entity.getWorld() == null || toSpline == null) {
            return false;
        }
        World world = entity.getWorld();
        Set<Long> inside = fromSpline == null
                ? Set.of()
                : touched(world, slices(fromSpline, fromS, reach, 0), shapes, Set.of(), false);
        // Only compare positions along one track. After a junction change, the whole
        // car counts as moving within its old space.
        double moved = 0;
        if (fromSpline != null && fromSpline.getId().equals(toSpline.getId())) {
            moved = toS - fromS;
            if (toSpline.isLoop()) {
                double length = toSpline.length();
                moved = moved - length * Math.rint(moved / length);
            }
        }
        // Steps are much shorter than a car, so the car's space at the end of a step
        // also covers the blocks it passed, even on fast ticks.
        return !touched(world, slices(toSpline, toS, reach, moved), shapes, inside, true).isEmpty();
    }

    private record Slice(double x, double z, double fx, double fz, double halfLength,
            double minY, double maxY, boolean leading) {
    }

    private static List<Slice> slices(TrackSpline spline, double s, double reach, double moved) {
        List<Slice> slices = new ArrayList<>();
        double length = Math.max(SLICE, 2 * reach);
        int count = (int) Math.ceil(length / SLICE);
        double step = length / count;
        for (int i = 0; i < count; i++) {
            double along = -length / 2 + (i + 0.5) * step;
            // The part of the car that has moved into space it did not cover before.
            boolean leading = moved > 0 ? along + step / 2 > length / 2 - moved
                    : moved < 0 && along - step / 2 < -length / 2 - moved;
            slices.add(slice(spline, s + along, step / 2, leading));
        }
        return slices;
    }

    private static Slice slice(TrackSpline spline, double at, double halfLength, boolean leading) {
        double end = spline.length();
        double clamped = spline.isLoop() ? at : Math.max(0, Math.min(end, at));
        TrackPose pose = spline.sampleAt(clamped);
        double yaw = Math.toRadians(pose.yaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        // Couplers can reach past the end of the track. Carry on straight there.
        double past = at - clamped;
        double x = pose.x + fx * past;
        double z = pose.z + fz * past;
        double y = pose.y - Math.tan(Math.toRadians(pose.pitch)) * past;
        return new Slice(x, z, fx, fz, halfLength,
                y + Cache.trackVehicleYOffset, y + Cache.trainClearanceHeight, leading);
    }

    /** A block inside the space trains need, and how far along the track it is. */
    public record Obstruction(int x, int y, int z, double s) {
    }

    /** Blocks inside the space trains need between two points on a track, in track order. */
    public static List<Obstruction> obstructions(World world, TrackSpline spline, double fromS, double toS) {
        return scan(world, spline, fromS, toS, false).obstructions();
    }

    /**
     * Obstructions along a track, skipping unloaded chunks rather than loading them.
     * {@code skipped} is how much of the track was not checked.
     */
    public record Scan(List<Obstruction> obstructions, double skipped) {
    }

    public static Scan scanLoaded(World world, TrackSpline spline) {
        return scan(world, spline, 0, spline.length(), true);
    }

    private static Scan scan(World world, TrackSpline spline, double fromS, double toS, boolean loadedOnly) {
        List<Obstruction> found = new ArrayList<>();
        if (world == null || spline == null || toS <= fromS) {
            return new Scan(found, 0);
        }
        Map<Long, List<BoundingBox>> shapes = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        int count = (int) Math.ceil((toS - fromS) / SLICE);
        double step = (toS - fromS) / count;
        double skipped = 0;
        for (int i = 0; i < count; i++) {
            double at = fromS + (i + 0.5) * step;
            Slice slice = slice(spline, at, step / 2, false);
            if (loadedOnly && !world.isChunkLoaded((int) Math.floor(slice.x) >> 4, (int) Math.floor(slice.z) >> 4)) {
                skipped += step;
                continue;
            }
            for (long key : touched(world, List.of(slice), shapes, seen, false, loadedOnly)) {
                seen.add(key);
                found.add(new Obstruction(keyX(key), keyY(key), keyZ(key), at));
            }
        }
        return new Scan(found, skipped);
    }

    private static Set<Long> touched(World world, List<Slice> slices, Map<Long, List<BoundingBox>> shapes,
            Set<Long> ignore, boolean firstOnly) {
        return touched(world, slices, shapes, ignore, firstOnly, false);
    }

    private static Set<Long> touched(World world, List<Slice> slices, Map<Long, List<BoundingBox>> shapes,
            Set<Long> ignore, boolean firstOnly, boolean loadedOnly) {
        Set<Long> hits = new LinkedHashSet<>();
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
                        if ((!slice.leading && ignore.contains(key)) || hits.contains(key)) {
                            continue;
                        }
                        if (loadedOnly && !world.isChunkLoaded(x >> 4, z >> 4)) {
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
        int x = keyX(key);
        int y = keyY(key);
        int z = keyZ(key);
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

    private static int keyX(long key) {
        return (int) (key >> 38);
    }

    private static int keyY(long key) {
        return (int) (key << 52 >> 52);
    }

    private static int keyZ(long key) {
        return (int) (key << 26 >> 38);
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
