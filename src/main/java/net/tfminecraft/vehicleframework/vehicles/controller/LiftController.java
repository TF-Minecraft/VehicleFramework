package net.tfminecraft.vehicleframework.vehicles.controller;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Balloon;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.state.VehicleState;

public class LiftController {

	/** How close a solid block may come to the hitbox's sides before a flying vehicle counts as hitting it. */
	static final double CONTACT_MARGIN = 0.5;
	/** Blocks lower than this above the hitbox floor are what the vehicle skims or lands on, not walls. */
	static final double FLOOR_CLEARANCE = 0.5;

	/** Healthy balloon: climb/descend delta, or hover at 0. Damaged balloon sinks with health lift. */
	public static double balloonVerticalY(double healthLift, double delta) {
		if (healthLift < 0) {
			return healthLift;
		}
		if (delta != 0) {
			return delta;
		}
		return 0;
	}

	public Vector calculateLift(BoneRotator rotator, ActiveVehicle v, Vector velocity) {
		if(v.hasComponent(Component.BALLOON)) {
			Balloon balloon = (Balloon) v.getComponent(Component.BALLOON);
			velocity.setY(balloonVerticalY(balloon.getLift(), balloon.getDelta()));
			return velocity;
		}
	    if (v.hasComponent(Component.WINGS) && v.hasComponent(Component.ENGINE)) {
	        Wings wings = (Wings) v.getComponent(Component.WINGS);
	        Engine engine = (Engine) v.getComponent(Component.ENGINE);
	        
	        if (v.getStateHandler().getCurrentState().getType().equals(State.FLYING) || engine.getThrottle().getCurrent() != 0) {
				
	            AxisAngle4d angles = rotator.getAngles(); // Pitch (x), Yaw (y), Roll (z)
	            double y = velocity.getY();
	            y -= 0.49;
				double throttle = engine.getThrottle().getCurrent();

				// Only apply interpolation if throttle is 30 or below
				if (throttle <= 30) {

					// Clamp throttle between 0 and 30
					double clampedThrottle = Math.max(0, Math.min(30, throttle));

					// Convert to 0 → 1 range
					double factor = clampedThrottle / 30.0;

					// Interpolate between -0.98 (at 0 throttle) and 0 (at 30 throttle)
					double extraGravity = -0.98 * (1 - factor);

					y += extraGravity;
				}
	            velocity.setY(y); // Update vertical velocity

	            // Calculate lift based on velocity magnitude instead of engine speed
	            double velocityMagnitude = velocity.length(); // Get speed
	            double lift = wings.getLift() * velocityMagnitude * 0.1; // Scale lift with speed

	            // Apply an upper bound to lift
	            if (lift > 0.52) lift = 0.52;

	            // Calculate lift vector relative to plane's orientation
	            Vector liftVector = calculateLiftVector(angles, lift);

	            // Apply the lift vector to velocity
	            velocity.add(liftVector);
	        }
	    }
	    return velocity;
	}
    
    private Vector calculateLiftVector(AxisAngle4d axisAngle, double lift) {
        // Convert AxisAngle to Quaternion
        Quaterniond quaternion = new Quaterniond(axisAngle);

        // "Up" vector in local space before rotation (e.g., (0, 1, 0))
        Vector3d localUp = new Vector3d(0, 1, 0);

        // Rotate the "up" vector using the quaternion
        Vector3d rotatedUp = localUp.rotate(quaternion);

        // Use the Y-component of the rotated vector to calculate lift
        double liftY = Math.abs(rotatedUp.y * lift);

        // Scale the lift vector by the lift force
        return new Vector(0, liftY, 0);
    }
    
    public void checkHitWall(ActiveVehicle vehicle) {
    	if(vehicle.isDestroyed()) return;
    	if(!(vehicle.hasComponent(Component.WINGS) || vehicle.hasComponent(Component.BALLOON))) return;
		if(vehicle.getAccessPanel().getSpeed() < 0.3) return;
		// On the ground the hitbox's own block collision halts the vehicle; only a flying one wrecks.
		VehicleState current = vehicle.getStateHandler().getCurrentState();
		if (current != null && current.getType() == State.GROUND) return;
		World world = vehicle.getEntity().getWorld();
		if (touchesSolid(contactZone(vehicle.getEntity().getBoundingBox()), (x, y, z) -> solidBoxes(world, x, y, z))) {
			vehicle.kill(VehicleDeath.EXPLODE);
		}
    }

	/** The hitbox widened by {@link #CONTACT_MARGIN} on each side, from {@link #FLOOR_CLEARANCE} above its floor to its top. */
	static BoundingBox contactZone(BoundingBox hitbox) {
		double floor = Math.min(FLOOR_CLEARANCE, hitbox.getHeight() / 2);
		return new BoundingBox(
				hitbox.getMinX() - CONTACT_MARGIN, hitbox.getMinY() + floor, hitbox.getMinZ() - CONTACT_MARGIN,
				hitbox.getMaxX() + CONTACT_MARGIN, hitbox.getMaxY(), hitbox.getMaxZ() + CONTACT_MARGIN);
	}

	/** World-space collision boxes of the block at a position; empty when it is passable or unloaded. */
	interface SolidLookup {
		List<BoundingBox> at(int x, int y, int z);
	}

	/** True when a solid block's real collision shape reaches inside the zone (touching its edge is not enough). */
	static boolean touchesSolid(BoundingBox zone, SolidLookup solids) {
		for (int x = (int) Math.floor(zone.getMinX()); x <= (int) Math.floor(zone.getMaxX()); x++) {
			for (int y = (int) Math.floor(zone.getMinY()); y <= (int) Math.floor(zone.getMaxY()); y++) {
				for (int z = (int) Math.floor(zone.getMinZ()); z <= (int) Math.floor(zone.getMaxZ()); z++) {
					for (BoundingBox box : solids.at(x, y, z)) {
						if (box.overlaps(zone)) return true;
					}
				}
			}
		}
		return false;
	}

	static List<BoundingBox> solidBoxes(World world, int x, int y, int z) {
		// Never load a chunk just to look for walls.
		if (!world.isChunkLoaded(x >> 4, z >> 4)) return List.of();
		Block block = world.getBlockAt(x, y, z);
		if (block.isPassable()) return List.of();
		List<BoundingBox> boxes = new ArrayList<>();
		for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
			boxes.add(local.clone().shift(x, y, z));
		}
		return boxes;
	}
}
