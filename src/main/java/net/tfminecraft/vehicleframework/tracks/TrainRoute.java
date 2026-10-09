package net.tfminecraft.vehicleframework.tracks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Walks the rails in a train's body frame, preserving orientation at each turnout. */
public final class TrainRoute {

	public record Position(UUID splineId, double s, int orientation) {
		public Position {
			orientation = orientation < 0 ? -1 : 1;
		}
	}

	public record Walk(Position position, double missing, boolean broken, Map<UUID, Boolean> junctions) {
	}

	/**
	 * One stretch of a walk on one spline: {@code length} blocks from {@code s}, towards
	 * higher s when {@code direction} is 1, starting {@code start} blocks into the walk.
	 */
	public record Piece(UUID splineId, double s, int direction, double length, double start,
			boolean loop, double splineLength) {
	}

	private final TrackRegistry registry;
	private final Map<UUID, Boolean> choices;

	public TrainRoute(TrackRegistry registry, Map<UUID, Boolean> choices) {
		this.registry = registry;
		this.choices = choices;
	}

	/**
	 * Positive distance is towards the front coupler, negative towards the back.
	 * Only the leading wheels select new switches; all other walks use frozen choices.
	 */
	public Walk walk(Position start, double distance, boolean select, boolean stopAtBreak) {
		return walk(start, distance, select, stopAtBreak, null);
	}

	/** The pieces of rail a walk covers, in order, ending early at a track end or break. */
	public List<Piece> trace(Position start, double distance, boolean select, boolean stopAtBreak) {
		List<Piece> pieces = new ArrayList<>();
		walk(start, distance, select, stopAtBreak, pieces);
		return pieces;
	}

	private Walk walk(Position start, double distance, boolean select, boolean stopAtBreak, List<Piece> pieces) {
		Position at = start;
		double left = Math.abs(distance);
		int bodyDirection = distance < 0 ? -1 : 1;
		Map<UUID, Boolean> crossed = new LinkedHashMap<>();
		Set<UUID> atPoint = new HashSet<>();
		// Bounds malformed/zero-length connections, while allowing loops and long consists.
		for (int events = 0; left > 1e-9 && events < 1024; events++) {
			TrackSpline spline = registry.get(at.splineId()).orElse(null);
			if (spline == null || spline.length() < 1e-9) {
				return new Walk(at, left, false, crossed);
			}
			int direction = bodyDirection * at.orientation();
			double end = spline.isLoop() ? Double.POSITIVE_INFINITY
					: direction > 0 ? spline.length() - at.s() : at.s();
			TrackJunction next = null;
			boolean fromBranch = false;
			double ahead = Math.max(0, end);
			if (!spline.isLoop()) {
				// Running off the end where the branch meets a frog crosses onto that stem.
				next = registry.branchJunctionAt(at.splineId(), direction > 0).orElse(null);
				fromBranch = next != null;
			}
			for (TrackJunction junction : registry.junctionsOn(at.splineId())) {
				if (junction.branchSplineId == null || registry.get(junction.branchSplineId).isEmpty()) {
					continue;
				}
				double d = TrackJunctionTravel.ahead(at.s(), junction.s, direction,
						spline.isLoop(), spline.length());
				if (d < -1e-9 || (d <= 1e-9 && atPoint.contains(junction.id))) {
					continue;
				}
				if (d <= ahead + 1e-9) {
					next = junction;
					fromBranch = false;
					ahead = Math.max(0, d);
				}
			}
			double step = Math.min(left, ahead);
			double nextS = TrackJunction.wrapS(at.s() + direction * step, spline.length(), spline.isLoop());
			double travelled = Math.abs(distance) - left;
			if (stopAtBreak) {
				TrackAdvance advanced = spline.advance(at.s(), direction * step);
				if (advanced.stoppedAtBreak) {
					double moved = TrackJunctionTravel.ahead(at.s(), advanced.s, direction,
							spline.isLoop(), spline.length());
					record(pieces, spline, at.s(), direction, moved, travelled);
					return new Walk(new Position(at.splineId(), advanced.s, at.orientation()),
							Math.max(0, left - moved), true, crossed);
				}
				nextS = advanced.s;
			}
			record(pieces, spline, at.s(), direction, step, travelled);
			at = new Position(at.splineId(), nextS, at.orientation());
			left = Math.max(0, left - step);
			if (step > 1e-9) {
				atPoint.clear();
			}
			if (ahead > step + 1e-9) {
				break;
			}
			if (next == null) {
				return new Walk(at, left, false, crossed);
			}
			if (fromBranch) {
				crossed.put(next.id, true);
				at = new Position(next.stemSplineId, next.s, at.orientation() * next.crossingSign());
				atPoint.add(next.id);
			} else {
				boolean diverge = direction == next.facingSign
						&& choices.getOrDefault(next.id, select && next.thrown);
				crossed.put(next.id, diverge);
				atPoint.add(next.id);
				if (diverge) {
					double frogS = next.branchFrogS(registry.get(next.branchSplineId).orElseThrow().length());
					at = new Position(next.branchSplineId, frogS, at.orientation() * next.crossingSign());
				}
			}
		}
		return new Walk(at, left, false, crossed);
	}

	public Walk walk(Position start, double distance) {
		return walk(start, distance, false, false);
	}

	private static void record(List<Piece> pieces, TrackSpline spline, double s, int direction,
			double length, double start) {
		if (pieces != null && length > 1e-9) {
			pieces.add(new Piece(spline.getId(), s, direction, length, start, spline.isLoop(), spline.length()));
		}
	}

	/** Sample a body-facing rail pose, extrapolating only the unsupported part at a real end. */
	public TrackPose rail(Position start, double offset) {
		Walk walk = walk(start, offset);
		Position at = walk.position();
		TrackSpline spline = registry.get(at.splineId()).orElse(null);
		if (spline == null) {
			return null;
		}
		double beyond = (offset < 0 ? -1 : 1) * at.orientation() * walk.missing();
		return facing(net.tfminecraft.vehicleframework.vehicles.handlers.train.Bogies.rail(spline, at.s() + beyond),
				at.orientation());
	}

	public static TrackPose facing(TrackPose pose, int orientation) {
		return orientation < 0
				? new TrackPose(pose.x, pose.y, pose.z, pose.yaw + 180, -pose.pitch) : pose;
	}
}
