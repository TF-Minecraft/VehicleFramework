package net.tfminecraft.vehicleframework.database;

/**
 * Where a saved train car was on its track, and where it stood in the world. Uuids are
 * lower case; parent is null for the head of a consist. Track edits can re-id a spline or
 * shift its arc lengths, so the world position finds the car again.
 */
public record TrackedCar(String uuid, String splineId, double s, String parent,
		String world, double x, double y, double z) {
}
