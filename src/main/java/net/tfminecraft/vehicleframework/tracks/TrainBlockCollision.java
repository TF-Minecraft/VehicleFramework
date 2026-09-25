package net.tfminecraft.vehicleframework.tracks;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import net.tfminecraft.vehicleframework.cache.Cache;

/** Block clearance for a short, proposed movement, before any car is teleported. */
public final class TrainBlockCollision {
    private TrainBlockCollision() {
    }

    public static boolean blocked(Entity entity, TrackPose from, TrackPose to) {
        if (entity == null || entity.getWorld() == null) {
            return false;
        }
        Location origin = entity.getLocation();
        BoundingBox body = entity.getBoundingBox();
        BoundingBox start = body.clone().shift(from.x - origin.getX(),
                from.y + Cache.trackVehicleYOffset - origin.getY(), from.z - origin.getZ());
        BoundingBox end = body.clone().shift(to.x - origin.getX(),
                to.y + Cache.trackVehicleYOffset - origin.getY(), to.z - origin.getZ());
        // Sweep the body, rather than just testing the destination. Movement is
        // subdivided along the spline so this also follows bends and loop seams.
        BoundingBox swept = start.union(end);
        World world = entity.getWorld();
        for (int x = (int) Math.floor(swept.getMinX()); x < Math.ceil(swept.getMaxX()); x++) {
            // Fences and walls can extend above the block containing them.
            for (int y = (int) Math.floor(swept.getMinY()) - 1; y < Math.ceil(swept.getMaxY()); y++) {
                for (int z = (int) Math.floor(swept.getMinZ()); z < Math.ceil(swept.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isPassable()) {
                        continue;
                    }
                    Location base = block.getLocation();
                    for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox solid = local.clone().shift(base.getX(), base.getY(), base.getZ());
                        if (swept.overlaps(solid)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
