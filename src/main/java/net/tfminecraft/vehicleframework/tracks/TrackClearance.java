package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.Block;

import net.tfminecraft.vehicleframework.cache.Cache;

public final class TrackClearance {
	public static final double OVERLAP_HORIZ = 1.0;
	public static final double OVERLAP_VERT = 1.5;
	public static final double FROG_S = 4.0;
	/** Blocks kept clear from the rail block upwards, when laying and afterwards. */
	public static final int HEADROOM_BLOCKS = 3;

	public static final class FrogIgnore {
		public final UUID splineId;
		public final double s;

		public FrogIgnore(UUID splineId, double s) {
			this.splineId = splineId;
			this.s = s;
		}
	}

	private TrackClearance() {
	}

	public static void check(
			World world,
			List<double[]> points,
			TrackRegistry registry,
			Set<UUID> ignoreSplineIds) throws TrackLayException {
		check(world, points, registry, ignoreSplineIds, null);
	}

	public static void check(
			World world,
			List<double[]> points,
			TrackRegistry registry,
			Set<UUID> ignoreSplineIds,
			FrogIgnore frog) throws TrackLayException {
		if (world == null || points == null || points.size() < 2) {
			return;
		}
		Set<UUID> ignore = ignoreSplineIds == null ? Set.of() : ignoreSplineIds;
		try {
			liftOneBlockSteps(world, points);
		} catch (TrackLayException e) {
			throw withTrainSpace(world, points, e);
		}
		// Before the rail check, so a refusal lists every block in the way of trains
		// rather than only the first one found.
		checkTrainSpace(world, points);
		for (int i = 0; i < points.size(); i++) {
			double[] p = points.get(i);
			double[] dir = step(points, i);
			double len = Math.hypot(dir[0], dir[1]);
			double rx = 1;
			double rz = 0;
			if (len > 1e-6) {
				double fx = dir[0] / len;
				double fz = dir[1] / len;
				rx = fz;
				rz = -fx;
			}
			int y0 = (int) Math.floor(p[1]);
			for (int side = -1; side <= 1; side++) {
				int x = (int) Math.floor(p[0] + rx * side);
				int z = (int) Math.floor(p[2] + rz * side);
				for (int h = 0; h < HEADROOM_BLOCKS; h++) {
					int y = y0 + h;
					Block block = world.getBlockAt(x, y, z);
					if (TrackSupport.blocksRail(block, p[1])) {
						String name = block.getType().name().toLowerCase(Locale.US);
						throw withTrainSpace(world, points, new TrackLayException(
								"Cannot lay track: " + name + " in the way at " + x + ", " + y + ", " + z + ".",
								x, y, z));
					}
				}
			}
		}
		if (registry == null) {
			return;
		}
		checkOverlap(world.getName(), points, registry, ignore, frog);
	}

	/** Refuses track that trains could not run along, using the same space as moving trains. */
	static void checkTrainSpace(World world, List<double[]> points) throws TrackLayException {
		TrackSpline stroke = TrackSpline.fromPoints(UUID.randomUUID(), world.getName(), false, points);
		List<TrainBlockCollision.Obstruction> inWay =
				TrainBlockCollision.obstructions(world, stroke, 0, stroke.length());
		if (inWay.isEmpty()) {
			return;
		}
		TrainBlockCollision.Obstruction first = inWay.get(0);
		String name = world.getBlockAt(first.x(), first.y(), first.z()).getType().name().toLowerCase(Locale.US);
		String more = inWay.size() == 1 ? "" : " and " + (inWay.size() - 1) + " more";
		throw new TrackLayException(
				"Cannot lay track: trains need " + format(Cache.trainClearanceWidth) + " wide by "
						+ format(Cache.trainClearanceHeight) + " high above the rail. " + name + " at "
						+ first.x() + ", " + first.y() + ", " + first.z() + more + " in the way.",
				inWay);
	}

	/**
	 * A refusal naming one block, such as a step too high to climb, also lists every
	 * block in the way of trains along the stroke, so all of them can be shown.
	 */
	static TrackLayException withTrainSpace(World world, List<double[]> points, TrackLayException refused) {
		if (!refused.hasBlock()) {
			return refused;
		}
		TrackSpline stroke = TrackSpline.fromPoints(UUID.randomUUID(), world.getName(), false, points);
		List<TrainBlockCollision.Obstruction> inWay = new ArrayList<>();
		inWay.add(new TrainBlockCollision.Obstruction(refused.blockX, refused.blockY, refused.blockZ, 0));
		for (TrainBlockCollision.Obstruction o : TrainBlockCollision.obstructions(world, stroke, 0, stroke.length())) {
			if (o.x() != refused.blockX || o.y() != refused.blockY || o.z() != refused.blockZ) {
				inWay.add(o);
			}
		}
		if (inWay.size() == 1) {
			return refused;
		}
		String message = refused.getMessage();
		if (message.endsWith(".")) {
			message = message.substring(0, message.length() - 1);
		}
		return new TrackLayException(message + ", and " + (inWay.size() - 1)
				+ " more blocks in the way of trains.", inWay);
	}

	static String format(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	static void checkOverlap(
			String worldName,
			List<double[]> points,
			TrackRegistry registry,
			Set<UUID> ignoreSplineIds,
			FrogIgnore frog) throws TrackLayException {
		if (worldName == null || points == null || registry == null) {
			return;
		}
		Set<UUID> ignore = ignoreSplineIds == null ? Set.of() : ignoreSplineIds;
		for (double[] p : points) {
			for (TrackSpline spline : registry.inWorld(worldName)) {
				if (ignore.contains(spline.getId())) {
					continue;
				}
				for (TrackSample sample : spline.getSamples()) {
					if (inFrog(spline, sample, frog)) {
						continue;
					}
					double dx = sample.x - p[0];
					double dz = sample.z - p[2];
					if (Math.hypot(dx, dz) > OVERLAP_HORIZ) {
						continue;
					}
					if (Math.abs(sample.y - p[1]) > OVERLAP_VERT) {
						continue;
					}
					int x = (int) Math.floor(p[0]);
					int y = (int) Math.floor(p[1]);
					int z = (int) Math.floor(p[2]);
					throw new TrackLayException(
							"Cannot lay track: another track in the way at " + x + ", " + y + ", " + z + ".",
							x, y, z);
				}
			}
		}
	}

	private static boolean inFrog(TrackSpline spline, TrackSample sample, FrogIgnore frog) {
		if (frog == null || frog.splineId == null || !frog.splineId.equals(spline.getId())) {
			return false;
		}
		return TrackJunction.arcDistance(sample.s, frog.s, spline.length(), spline.isLoop()) <= FROG_S;
	}

	static void liftOneBlockSteps(World world, List<double[]> points) throws TrackLayException {
		if (world == null || points == null || points.size() < 2) {
			return;
		}
		int n = points.size();
		double[] minY = new double[n];
		boolean any = false;
		for (int i = 0; i < n; i++) {
			double[] p = points.get(i);
			minY[i] = p[1];
			double[] dir = step(points, i);
			double len = Math.hypot(dir[0], dir[1]);
			double rx = len > 1e-6 ? dir[1] / len : 1;
			double rz = len > 1e-6 ? -dir[0] / len : 0;
			int y0 = (int) Math.floor(p[1]);
			boolean stepUp = false;
			Double liftTo = null;
			for (int side = -1; side <= 1; side++) {
				int x = (int) Math.floor(p[0] + rx * side);
				int z = (int) Math.floor(p[2] + rz * side);
				Block at = world.getBlockAt(x, y0, z);
				Double sit = TrackSupport.sitY(at);
				if (sit == null || sit <= p[1] + 1e-4) {
					continue;
				}
				Block above = world.getBlockAt(x, y0 + 1, z);
				if (TrackSupport.sitY(above) != null && TrackSupport.sitY(above) > sit + 1e-4) {
					String name = at.getType().name().toLowerCase(Locale.US);
					throw new TrackLayException(
							"Cannot lay track: " + name + " in the way at " + x + ", " + y0 + ", " + z + ".",
							x, y0, z);
				}
				stepUp = true;
				if (liftTo == null || sit > liftTo) {
					liftTo = sit;
				}
			}
			if (stepUp && liftTo != null) {
				minY[i] = liftTo;
				any = true;
			}
		}
		if (!any) {
			return;
		}
		TrackLog.append("STEP_LIFT applying support steps, maxGrade="
				+ String.format(Locale.US, "%.1f", Cache.trackMaxGradeDegrees));
		TrackGrade.applyRequiredHeights(points, minY, Cache.trackMaxGradeDegrees);
	}

	private static double[] step(List<double[]> points, int i) {
		if (i + 1 < points.size()) {
			double[] a = points.get(i);
			double[] b = points.get(i + 1);
			return new double[] {b[0] - a[0], b[2] - a[2]};
		}
		double[] a = points.get(i - 1);
		double[] b = points.get(i);
		return new double[] {b[0] - a[0], b[2] - a[2]};
	}
}
