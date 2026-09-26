package net.tfminecraft.vehicleframework.tracks;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.bukkit.World;

import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.VehicleFramework;

public final class TrackRegistry {
	private static final double PRUNE_NESTED_MAX_LENGTH = 16.0;
	private static final double REHOME_REACH = 2.5;
	private static final double DROP_MARGIN = 0.5;

	private final TrackStore store;
	private final Map<UUID, TrackSpline> splines = new ConcurrentHashMap<>();
	private final Map<UUID, TrackJunction> junctions = new ConcurrentHashMap<>();
	private BiConsumer<TrackSpline, List<TrackSpline>> rebuilt = (old, next) -> {
	};
	private Predicate<UUID> occupied = id -> false;

	public TrackRegistry(File dataFolder) {
		this.store = new TrackStore(dataFolder);
	}

	/**
	 * Called when a spline's geometry is rebuilt, with the old spline and the
	 * splines that now carry its track. Arc lengths are not preserved, so
	 * anything bound to the old spline must re-find its position.
	 */
	public void onRebuilt(BiConsumer<TrackSpline, List<TrackSpline>> listener) {
		rebuilt = listener == null ? (old, next) -> {
		} : listener;
	}

	/** Tells the registry which splines have a train bound to them. */
	public void occupiedBy(Predicate<UUID> test) {
		occupied = test == null ? id -> false : test;
	}

	public void loadFromDisk() {
		splines.clear();
		junctions.clear();
		for (TrackSpline spline : store.loadAll()) {
			TrackSpline promoted = spline.promotedLoop(Cache.trackJoinDistance);
			splines.put(promoted.getId(), promoted);
			if (promoted.isLoop() && !spline.isLoop()) {
				store.save(promoted);
			}
		}
		for (TrackStore.LoadedJunction loaded : store.loadAllJunctions()) {
			TrackJunction junction = loaded.junction;
			TrackSpline stem = splines.get(junction.stemSplineId);
			if (stem == null || junction.branchSplineId == null) {
				String why = stem == null ? "no-stem" : "no-branch";
				TrackLog.junctionDrop(junction.id, junction.stemSplineId, why);
				store.deleteJunction(loaded.world, junction.id);
				continue;
			}
			TrackJunction placed = junction.withS(
					TrackJunction.wrapS(junction.s, stem.length(), stem.isLoop()));
			junctions.put(placed.id, placed);
		}
		pruneNestedShortTracks();
		dumpToLog();
	}

	public TrackSpline createBetween(
			String world,
			double ax, double ay, double az,
			double bx, double by, double bz) {
		try {
			return lay(world, null, ax, ay, az, bx, by, bz).spline();
		} catch (TrackLayException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	public TrackLayResult lay(
			String world,
			double ax, double ay, double az,
			double bx, double by, double bz) throws TrackLayException {
		return lay(world, null, ax, ay, az, bx, by, bz);
	}

	public TrackLayResult lay(
			String world,
			World bukkitWorld,
			double ax, double ay, double az,
			double bx, double by, double bz) throws TrackLayException {
		Optional<TrackEnd> atA = findEnd(world, ax, ay, az);
		Optional<TrackEnd> atB = findEnd(world, bx, by, bz);
		TrackLog.layAttempt(world, ax, ay, az, bx, by, bz, atA.isPresent(), atB.isPresent());
		try {
			if (atA.isPresent() && atB.isPresent()
					&& atA.get().spline.getId().equals(atB.get().spline.getId())
					&& atA.get().prepend != atB.get().prepend) {
				StrokeLay laid = closeLoop(atA.get(), atB.get(), bukkitWorld);
				TrackLog.layOk(laid.spline, "loop");
				return finishLay(TrackLayResult.of(TrackLayResult.Kind.CONNECT, laid.spline, laid.stroke, 0));
			}
			if (atA.isPresent() && atB.isPresent()
					&& !atA.get().spline.getId().equals(atB.get().spline.getId())) {
				StrokeLay laid = connect(atA.get(), atB.get(), bukkitWorld);
				TrackLog.layOk(laid.spline, "connect");
				return finishLay(TrackLayResult.of(TrackLayResult.Kind.CONNECT, laid.spline, laid.stroke, 0));
			}
			if (atA.isPresent()) {
				StrokeLay laid = extend(atA.get(), bx, by, bz, bukkitWorld);
				TrackLayResult.Kind kind = atA.get().prepend
						? TrackLayResult.Kind.PREPEND
						: TrackLayResult.Kind.APPEND;
				TrackLog.layOk(laid.spline, kind.name().toLowerCase());
				return finishLay(TrackLayResult.of(kind, laid.spline, laid.stroke, laid.previousCount));
			}
			if (atB.isPresent()) {
				StrokeLay laid = extend(atB.get(), ax, ay, az, bukkitWorld);
				TrackLayResult.Kind kind = atB.get().prepend
						? TrackLayResult.Kind.PREPEND
						: TrackLayResult.Kind.APPEND;
				TrackLog.layOk(laid.spline, kind.name().toLowerCase());
				return finishLay(TrackLayResult.of(kind, laid.spline, laid.stroke, laid.previousCount));
			}
			List<double[]> points = TrackCurve.between(
					ax, ay, az, bx, by, bz,
					Cache.trackMinLayDistance,
					Cache.trackDesiredGradeDegrees, Cache.trackMaxGradeDegrees, TrackGenerate.STEP);
			TrackClearance.check(bukkitWorld, points, this, Set.of());
			TrackSpline spline = TrackSpline.fromPoints(UUID.randomUUID(), world, false, points);
			splines.put(spline.getId(), spline);
			store.save(spline);
			TrackLog.layOk(spline, "new");
			return finishLay(TrackLayResult.of(TrackLayResult.Kind.NEW, spline, points, 0));
		} catch (TrackLayException e) {
			TrackLog.layFail(e.getMessage(), e);
			throw e;
		}
	}

	public Optional<TrackEnd> findEnd(String world, double x, double y, double z) {
		TrackEnd best = null;
		double bestD = Cache.trackJoinDistance;
		for (TrackSpline spline : inWorld(world)) {
			TrackSample first = spline.first();
			TrackSample last = spline.last();
			double dFirst = dist(first, x, y, z);
			double dLast = dist(last, x, y, z);
			if (dLast <= bestD) {
				bestD = dLast;
				best = new TrackEnd(spline, false);
			}
			if (dFirst <= bestD) {
				bestD = dFirst;
				best = new TrackEnd(spline, true);
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * The sample a dig at this point would remove, and every stretch of track
	 * the dig may take with it, including turnouts it would drop.
	 */
	public record DigTarget(TrackSpline spline, int index, List<Span> spans) {
	}

	/** Track from {@code centreS - halfSpan} to {@code centreS + halfSpan} on {@code trackId}. */
	public record Span(UUID trackId, double centreS, double halfSpan) {
	}

	public Optional<DigTarget> digTarget(String world, double x, double y, double z) {
		TrackSpline spline = null;
		int index = -1;
		double best = Math.max(Cache.trackJoinDistance, 2.0);
		for (TrackSpline candidate : inWorld(world)) {
			List<TrackSample> samples = candidate.getSamples();
			for (int i = 0; i < samples.size(); i++) {
				double d = dist(samples.get(i), x, y, z);
				if (d <= best) {
					best = d;
					spline = candidate;
					index = i;
				}
			}
		}
		if (spline == null) {
			return Optional.empty();
		}
		List<TrackSample> samples = spline.getSamples();
		double digS = samples.get(index).s;
		Optional<TrackJunction> turnout = turnoutDug(spline, digS);
		if (turnout.isPresent()) {
			// Digging a turnout removes the branch all the way back to the stem.
			return Optional.of(new DigTarget(spline, index, List.of(turnoutSpan(turnout.get(), spline))));
		}
		double before = index > 0 ? digS - samples.get(index - 1).s : 0;
		double after = index < samples.size() - 1 ? samples.get(index + 1).s - digS : 0;
		if (spline.isLoop() && (index == 0 || index == samples.size() - 1)) {
			double seam = spline.length() - samples.get(samples.size() - 1).s;
			before = index == 0 ? seam : before;
			after = index == 0 ? after : seam;
		}
		List<Span> spans = new ArrayList<>();
		spans.add(new Span(spline.getId(), digS, Math.max(before, after)));
		// A junction whose frog ends up on a piece too short for it loses its
		// turnout when rehomed (see rehomeJunctions), so that turnout goes too.
		for (TrackJunction junction : junctionsOn(spline.getId())) {
			TrackSpline branch = junction.branchSplineId == null ? null : splines.get(junction.branchSplineId);
			// Resettling can change piece length slightly, so err towards protecting the turnout.
			if (branch != null
					&& frogPieceLength(spline, index, junction.s) < Cache.trackMinLayDistance + DROP_MARGIN) {
				spans.add(turnoutSpan(junction, branch));
			}
		}
		return Optional.of(new DigTarget(spline, index, spans));
	}

	private static Span turnoutSpan(TrackJunction junction, TrackSpline branch) {
		double cutoff = turnoutCutoff(junction, branch);
		return new Span(branch.getId(), cutoff / 2, cutoff / 2 + TrackGenerate.STEP);
	}

	/**
	 * Length of the piece a frog at {@code frogS} is rehomed onto after digging
	 * {@code index}, following digAt and rehomeJunctions. Infinite when the
	 * turnout survives: the whole track is deleted (only the junction record
	 * goes), or no piece is near enough and just the junction is deleted.
	 */
	private static double frogPieceLength(TrackSpline spline, int index, double frogS) {
		List<TrackSample> samples = spline.getSamples();
		int n = samples.size();
		if (n <= 2) {
			return Double.POSITIVE_INFINITY;
		}
		List<double[]> pieces = new ArrayList<>();
		if (index == 0 || index == n - 1) {
			int first = index == 0 ? 1 : 0;
			int last = index == 0 ? n - 1 : n - 2;
			pieces.add(new double[] {samples.get(first).s, samples.get(last).s});
		} else {
			// A piece needs two samples; a one-sample stub is dropped.
			if (index >= 2) {
				pieces.add(new double[] {0, samples.get(index - 1).s});
			}
			if (index <= n - 3) {
				pieces.add(new double[] {samples.get(index + 1).s, samples.get(n - 1).s});
			}
		}
		double length = Double.POSITIVE_INFINITY;
		for (double[] piece : pieces) {
			// rehomeJunctions matches within 2.5 blocks, so a frog in the gap
			// can land on the end of either piece.
			if (frogS >= piece[0] - REHOME_REACH && frogS <= piece[1] + REHOME_REACH) {
				length = Math.min(length, piece[1] - piece[0]);
			}
		}
		return length;
	}

	public DigResult digAt(TrackSpline spline, int index) {
		return digAt(spline, index, null);
	}

	public DigResult digAt(TrackSpline spline, int index, World bukkitWorld) {
		List<double[]> xyz = spline.xyz();
		if (index < 0 || index >= xyz.size()) {
			return DigResult.none();
		}
		UUID id = spline.getId();
		Optional<TrackJunction> turnout = turnoutDug(spline, spline.getSamples().get(index).s);
		if (turnout.isPresent()) {
			return finishDig(removeJunctionTurnout(turnout.get(), bukkitWorld));
		}
		List<TrackJunction> saved = List.copyOf(junctionsOn(id));
		if (xyz.size() <= 2) {
			delete(id);
			return finishDig(DigResult.deleted(id));
		}
		if (index == 0 || index == xyz.size() - 1) {
			xyz.remove(index);
			boolean floorFirst = index == 0;
			boolean floorLast = index != 0;
			TrackResettle.resettle(bukkitWorld, xyz, floorFirst, floorLast);
			TrackSpline next = TrackSpline.fromPoints(id, spline.getWorld(), false, xyz);
			replace(next);
			rehomeJunctions(saved, spline, false, next);
			return finishDig(DigResult.updated(next));
		}
		List<double[]> head = new ArrayList<>(xyz.subList(0, index));
		List<double[]> tail = new ArrayList<>(xyz.subList(index + 1, xyz.size()));
		removeSplineRecord(id);
		TrackSpline start = null;
		TrackSpline rest = null;
		if (head.size() >= 2) {
			TrackResettle.resettle(bukkitWorld, head, false, true);
			start = TrackSpline.fromPoints(id, spline.getWorld(), false, head);
			splines.put(start.getId(), start);
			store.save(start);
		}
		if (tail.size() >= 2) {
			TrackResettle.resettle(bukkitWorld, tail, true, false);
			rest = TrackSpline.fromPoints(UUID.randomUUID(), spline.getWorld(), false, tail);
			splines.put(rest.getId(), rest);
			store.save(rest);
		}
		if (start == null && rest == null) {
			dropJunctionsForSpline(id, spline.getWorld());
			return finishDig(DigResult.deleted(id));
		}
		List<TrackSpline> pieces = new ArrayList<>();
		if (start != null) {
			pieces.add(start);
		}
		if (rest != null) {
			pieces.add(rest);
		}
		rehomeJunctions(saved, spline, false, start, rest);
		rebuilt.accept(spline, pieces);
		if (start != null && rest != null) {
			return finishDig(DigResult.split(start, rest));
		}
		if (start != null) {
			return finishDig(DigResult.updated(start));
		}
		return finishDig(DigResult.updated(id, rest));
	}

	private StrokeLay extend(TrackEnd end, double x, double y, double z, World bukkitWorld)
			throws TrackLayException {
		TrackSpline spline = end.spline;
		int previousCount = spline.getSamples().size();
		List<TrackJunction> saved = List.copyOf(junctionsOn(spline.getId()));
		TrackSample origin = end.prepend ? spline.first() : spline.last();
		float yaw = origin.yaw;
		if (end.prepend) {
			yaw = yaw + 180f;
		}
		List<double[]> extra = TrackCurve.lay(
				origin.x, origin.y, origin.z, yaw, x, y, z,
				Cache.trackMinLayDistance, Cache.trackMaxTurnDegrees,
				Cache.trackDesiredGradeDegrees, Cache.trackMaxGradeDegrees, TrackGenerate.STEP);
		TrackClearance.check(bukkitWorld, extra, this, Set.of(spline.getId()));
		List<double[]> merged = new ArrayList<>();
		if (end.prepend) {
			for (int i = extra.size() - 1; i >= 1; i--) {
				merged.add(extra.get(i));
			}
			merged.addAll(spline.xyz());
		} else {
			merged.addAll(spline.xyz());
			for (int i = 1; i < extra.size(); i++) {
				merged.add(extra.get(i));
			}
		}
		TrackSpline next = TrackSpline.fromPoints(
				spline.getId(),
				spline.getWorld(),
				TrackSpline.shouldLoop(merged, Cache.trackJoinDistance),
				merged);
		TrackSpline stored = replace(next);
		rehomeJunctions(saved, spline, false, stored);
		return new StrokeLay(stored, extra, previousCount);
	}

	private StrokeLay closeLoop(TrackEnd from, TrackEnd to, World bukkitWorld) throws TrackLayException {
		TrackSpline spline = from.spline;
		TrackSample originA = from.prepend ? spline.first() : spline.last();
		float yaw = originA.yaw;
		if (from.prepend) {
			yaw = yaw + 180f;
		}
		TrackSample originB = to.prepend ? spline.first() : spline.last();
		List<double[]> extra = TrackCurve.lay(
				originA.x, originA.y, originA.z, yaw, originB.x, originB.y, originB.z,
				Cache.trackMinLayDistance, Cache.trackMaxTurnDegrees,
				Cache.trackDesiredGradeDegrees, Cache.trackMaxGradeDegrees, TrackGenerate.STEP);
		TrackClearance.check(bukkitWorld, extra, this, Set.of(spline.getId()));
		// Close the loop in the track's own direction. Reversing it would turn
		// round any train on it and leave its junctions facing the wrong way.
		List<double[]> merged = spline.xyz();
		int last = extra.size() - 1;
		if (from.prepend) {
			for (int i = last - 1; i >= 1; i--) {
				merged.add(extra.get(i));
			}
		} else {
			for (int i = 1; i < last; i++) {
				merged.add(extra.get(i));
			}
		}
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.despawnSpline(spline.getId());
		}
		TrackSpline next = TrackSpline.fromPoints(spline.getId(), spline.getWorld(), true, merged);
		return new StrokeLay(replace(next), extra, 0);
	}

	private StrokeLay connect(TrackEnd from, TrackEnd to, World bukkitWorld) throws TrackLayException {
		// Laid from `from` to `to`, joining from a start reverses `from` and
		// joining to an end reverses `to`. Building the joined track the other
		// way round flips both. Trains face +s, so a track with a train on it
		// must keep its direction; a kept branch must still start at its frog.
		boolean fromReversedAsLaid = from.prepend;
		boolean toReversedAsLaid = !to.prepend;
		boolean fromOccupied = occupied.test(from.spline.getId());
		boolean toOccupied = occupied.test(to.spline.getId());
		boolean asLaidOk = !(fromReversedAsLaid && fromOccupied) && !(toReversedAsLaid && toOccupied);
		boolean flippedOk = !(!fromReversedAsLaid && fromOccupied) && !(!toReversedAsLaid && toOccupied);
		if (!asLaidOk && !flippedOk) {
			throw new TrackLayException("A train is on this track. Move it before joining here.");
		}
		// The dropped track's junction refs are cleared anyway, so only the kept track's branch matters.
		int fromBranchCost = junctionByBranch(from.spline.getId()).isPresent() ? 5 : 1;
		int asLaidCost = (fromReversedAsLaid ? fromBranchCost : 0) + (toReversedAsLaid ? 1 : 0);
		int flippedCost = (fromReversedAsLaid ? 0 : fromBranchCost) + (toReversedAsLaid ? 0 : 1);
		boolean flip = !asLaidOk || (flippedOk && flippedCost < asLaidCost);
		boolean keepReversed = flip != fromReversedAsLaid;
		boolean dropReversed = flip != toReversedAsLaid;
		TrackSample originA = from.prepend ? from.spline.first() : from.spline.last();
		float yaw = originA.yaw;
		if (from.prepend) {
			yaw = yaw + 180f;
		}
		TrackSample originB = to.prepend ? to.spline.first() : to.spline.last();
		List<double[]> extra = TrackCurve.lay(
				originA.x, originA.y, originA.z, yaw, originB.x, originB.y, originB.z,
				Cache.trackMinLayDistance, Cache.trackMaxTurnDegrees,
				Cache.trackDesiredGradeDegrees, Cache.trackMaxGradeDegrees, TrackGenerate.STEP);
		TrackClearance.check(
				bukkitWorld, extra, this, Set.of(from.spline.getId(), to.spline.getId()));
		List<double[]> fromPart = points(from.spline, keepReversed);
		List<double[]> toPart = points(to.spline, dropReversed);
		List<double[]> stroke = new ArrayList<>(extra);
		if (flip) {
			Collections.reverse(stroke);
		}
		List<double[]> merged = new ArrayList<>(flip ? toPart : fromPart);
		merged.addAll(stroke.subList(1, stroke.size()));
		List<double[]> tail = flip ? fromPart : toPart;
		merged.addAll(tail.subList(1, tail.size()));
		UUID drop = to.spline.getId();
		UUID keep = from.spline.getId();
		List<TrackJunction> keepSaved = List.copyOf(junctionsOn(keep));
		List<TrackJunction> dropSaved = List.copyOf(junctionsOn(drop));
		TrackSpline oldKeep = from.spline;
		TrackSpline oldDrop = to.spline;
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.despawnSpline(drop);
			displays.despawnSpline(keep);
		}
		removeSplineRecord(drop);
		clearBranchRefs(drop);
		TrackSpline next = TrackSpline.fromPoints(
				keep,
				from.spline.getWorld(),
				TrackSpline.shouldLoop(merged, Cache.trackJoinDistance),
				merged);
		TrackSpline stored = replaceQuietly(next);
		rehomeJunctions(keepSaved, oldKeep, keepReversed, stored);
		rehomeJunctions(dropSaved, oldDrop, dropReversed, stored);
		// Retrack only once junctions sit on the joined spline, so train routes survive.
		rebuilt.accept(oldKeep, List.of(stored));
		rebuilt.accept(oldDrop, List.of(stored));
		return new StrokeLay(stored, extra, 0);
	}

	private record StrokeLay(TrackSpline spline, List<double[]> stroke, int previousCount) {
	}

	private static List<double[]> points(TrackSpline spline, boolean reversed) {
		List<double[]> xyz = spline.xyz();
		if (reversed) {
			Collections.reverse(xyz);
		}
		return xyz;
	}

	private static double dist(TrackSample sample, double x, double y, double z) {
		double dx = sample.x - x;
		double dy = sample.y - y;
		double dz = sample.z - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public Optional<TrackSpline> get(UUID id) {
		return Optional.ofNullable(splines.get(id));
	}

	public TrackSpline replace(TrackSpline spline) {
		TrackSpline previous = splines.get(spline.getId());
		TrackSpline next = replaceQuietly(spline);
		if (previous != null && previous != next) {
			rebuilt.accept(previous, List.of(next));
		}
		return next;
	}

	private TrackSpline replaceQuietly(TrackSpline spline) {
		TrackSpline next = spline.promotedLoop(Cache.trackJoinDistance);
		next.invalidateVisuals();
		splines.put(next.getId(), next);
		store.save(next);
		return next;
	}

	public void persistPoints(UUID id, List<double[]> points) {
		TrackSpline current = splines.get(id);
		if (current == null) {
			return;
		}
		if (points == null || points.size() < 2) {
			TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
			if (displays != null) {
				displays.despawnSpline(id);
			}
			delete(id);
			return;
		}
		replace(TrackSpline.fromPoints(
				id,
				current.getWorld(),
				TrackSpline.shouldLoop(points, Cache.trackJoinDistance),
				points));
	}

	public TrackSpline layBranch(
			UUID stemId,
			double s,
			int facingSign,
			String world,
			World bukkitWorld,
			double x, double y, double z) throws TrackLayException {
		try {
			TrackSpline stem = splines.get(stemId);
			if (stem == null) {
				throw new TrackLayException("Junction stem track is missing");
			}
			double frogS = TrackJunction.wrapS(s, stem.length(), stem.isLoop());
			ensureFrogClear(stem, frogS, null);
			TrackPose pose = stem.sampleAt(frogS);
			float yaw = pose.yaw;
			if (facingSign < 0) {
				yaw = yaw + 180f;
			}
			int facing = facingSign < 0 ? -1 : 1;
			List<double[]> extra = branchCurve(pose, yaw, world, bukkitWorld, x, y, z);
			TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), world, false, extra);
			return commitBranch(stem, frogS, facing, extra, yaw, branch);
		} catch (TrackLayException e) {
			TrackLog.layFail(e.getMessage(), e);
			throw e;
		}
	}

	public TrackSpline layBranch(
			UUID junctionId,
			String world,
			World bukkitWorld,
			double x, double y, double z) throws TrackLayException {
		try {
			TrackJunction junction = junctions.get(junctionId);
			if (junction == null) {
				throw new TrackLayException("Junction is missing");
			}
			if (junction.branchSplineId != null) {
				throw new TrackLayException("Junction already has a branch");
			}
			TrackSpline stem = splines.get(junction.stemSplineId);
			if (stem == null) {
				throw new TrackLayException("Junction stem track is missing");
			}
			ensureFrogClear(stem, junction.s, junction.id);
			TrackPose pose = stem.sampleAt(junction.s);
			float yaw = pose.yaw;
			if (junction.facingSign < 0) {
				yaw = yaw + 180f;
			}
			List<double[]> extra = branchCurve(pose, yaw, world, bukkitWorld, x, y, z);
			TrackSpline branch = TrackSpline.fromPoints(UUID.randomUUID(), world, false, extra);
			splines.put(branch.getId(), branch);
			store.save(branch);
			try {
				TrackJunction.Side side = branchSide(yaw, extra);
				putJunction(junction.withSide(side).withBranch(branch.getId()).withTurnoutEndS(branch.length()));
			} catch (TrackLayException e) {
				removeSplineRecord(branch.getId());
				throw e;
			}
			TrackLog.junctionBranch(branch, stem.getId(), junction.id);
			dumpToLog();
			return finishLayBranch(branch);
		} catch (TrackLayException e) {
			TrackLog.layFail(e.getMessage(), e);
			throw e;
		}
	}

	private TrackSpline finishLayBranch(TrackSpline branch) {
		pruneNestedShortTracks();
		return branch;
	}

	private TrackSpline commitBranch(
			TrackSpline stem,
			double frogS,
			int facing,
			List<double[]> extra,
			float yaw,
			TrackSpline branch) throws TrackLayException {
		splines.put(branch.getId(), branch);
		store.save(branch);
		try {
			TrackJunction placed = putJunction(new TrackJunction(
					UUID.randomUUID(),
					stem.getId(),
					frogS,
					facing,
					branchSide(yaw, extra),
					branch.getId(),
					false,
					branch.length()));
			TrackLog.junctionBranch(branch, stem.getId(), placed.id);
			dumpToLog();
			return finishLayBranch(branch);
		} catch (TrackLayException e) {
			removeSplineRecord(branch.getId());
			throw e;
		}
	}

	private List<double[]> branchCurve(
			TrackPose pose,
			float yaw,
			String world,
			World bukkitWorld,
			double x, double y, double z) throws TrackLayException {
		double chord = Math.sqrt(
				(x - pose.x) * (x - pose.x)
						+ (y - pose.y) * (y - pose.y)
						+ (z - pose.z) * (z - pose.z));
		if (chord > Cache.trackMaxJunctionLength) {
			throw new TrackLayException("Junction branch can be at most "
					+ (int) Math.round(Cache.trackMaxJunctionLength) + " blocks long.");
		}
		List<double[]> extra = TrackCurve.lay(
				pose.x, pose.y, pose.z, yaw, x, y, z,
				Cache.trackMinLayDistance, Cache.trackMaxTurnDegrees,
				Cache.trackDesiredGradeDegrees, Cache.trackMaxGradeDegrees, TrackGenerate.STEP);
		if (polylineLength(extra) > Cache.trackMaxJunctionLength + 1e-6) {
			throw new TrackLayException("Junction branch can be at most "
					+ (int) Math.round(Cache.trackMaxJunctionLength) + " blocks long.");
		}
		Set<UUID> ignoreTracks = new HashSet<>();
		for (TrackSpline spline : splines.values()) {
			ignoreTracks.add(spline.getId());
		}
		if (bukkitWorld != null) {
			TrackClearance.check(bukkitWorld, extra, this, ignoreTracks);
		} else {
			TrackClearance.checkOverlap(world, extra, this, ignoreTracks, null);
		}
		return extra;
	}

	private static TrackJunction.Side branchSide(float yaw, List<double[]> extra) {
		if (extra.size() >= 2) {
			double[] a = extra.get(0);
			double[] b = extra.get(1);
			return TrackJunction.sideFrom(yaw, b[0] - a[0], b[2] - a[2]);
		}
		return TrackJunction.Side.RIGHT;
	}

	public boolean delete(UUID id) {
		if (!deleteWithoutPrune(id)) {
			return false;
		}
		pruneNestedShortTracks();
		return true;
	}

	private boolean deleteWithoutPrune(UUID id) {
		TrackBuildAnimator.cancel(id);
		TrackSpline spline = splines.get(id);
		if (spline == null) {
			return false;
		}
		dropJunctionsForSpline(id, spline.getWorld());
		removeSplineRecord(id);
		return true;
	}

	private void removeSplineRecord(UUID id) {
		TrackSpline spline = splines.remove(id);
		if (spline != null) {
			store.delete(spline);
		}
	}

	private void rehomeJunctions(
			List<TrackJunction> saved,
			TrackSpline from,
			boolean flipFacing,
			TrackSpline... onto) {
		if (saved == null || saved.isEmpty() || from == null) {
			return;
		}
		for (TrackJunction junction : saved) {
			TrackPose pose = from.sampleAt(junction.s);
			TrackSpline best = null;
			double bestD = REHOME_REACH;
			double bestS = 0;
			for (TrackSpline target : onto) {
				if (target == null) {
					continue;
				}
				double s = target.nearestS(pose.x, pose.y, pose.z);
				TrackPose at = target.sampleAt(s);
				double horiz = Math.hypot(at.x - pose.x, at.z - pose.z);
				if (horiz <= bestD) {
					bestD = horiz;
					best = target;
					bestS = s;
				}
			}
			if (best == null) {
				deleteJunction(junction.id);
				continue;
			}
			if (!best.isLoop() && best.length() < Cache.trackMinLayDistance - 1e-9) {
				dropJunctionAndBranch(junction);
				continue;
			}
			int facing = flipFacing ? -junction.facingSign : junction.facingSign;
			try {
				putJunction(junction.withStem(best.getId(), bestS).withFacing(facing));
			} catch (TrackLayException e) {
				dropJunctionAndBranch(junction);
			}
		}
	}

	public TrackJunction putJunction(TrackJunction junction) throws TrackLayException {
		if (junction == null) {
			throw new TrackLayException("Junction is missing");
		}
		TrackSpline stem = splines.get(junction.stemSplineId);
		if (stem == null) {
			throw new TrackLayException("Junction stem track is missing");
		}
		ensureStemLongEnoughForJunction(stem);
		double s = TrackJunction.wrapS(junction.s, stem.length(), stem.isLoop());
		TrackJunction placed = junction.withS(s);
		TrackJunction existing = junctions.get(placed.id);
		if (existing != null
				&& existing.branchSplineId != null
				&& placed.branchSplineId != null
				&& !existing.branchSplineId.equals(placed.branchSplineId)) {
			throw new TrackLayException("Junction already has a branch");
		}
		double min = Cache.trackMinJunctionSpacing;
		for (TrackJunction other : junctions.values()) {
			if (other.id.equals(placed.id)) {
				continue;
			}
			if (!other.stemSplineId.equals(placed.stemSplineId)) {
				continue;
			}
			if (other.branchSplineId == null) {
				continue;
			}
			if (TrackJunction.arcDistance(placed.s, other.s, stem.length(), stem.isLoop()) < min) {
				throw new TrackLayException("Junctions must be at least "
						+ (int) Math.round(min) + " blocks apart along the track");
			}
		}
		junctions.put(placed.id, placed);
		store.saveJunction(stem.getWorld(), placed);
		refreshSwitchDisplay(placed);
		return placed;
	}

	public boolean setThrown(UUID id, boolean thrown) {
		TrackJunction junction = junctions.get(id);
		if (junction == null) {
			return false;
		}
		if (junction.thrown == thrown) {
			return false;
		}
		TrackJunction next = junction.withThrown(thrown);
		junctions.put(next.id, next);
		TrackSpline stem = splines.get(next.stemSplineId);
		if (stem != null) {
			store.saveJunction(stem.getWorld(), next);
		}
		return true;
	}

	private static void despawnSwitchDisplay(UUID junctionId) {
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.despawnSwitch(junctionId);
		}
	}

	private static void refreshSwitchDisplay(TrackJunction junction) {
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.refreshSwitch(junction);
		}
	}

	public void dropIncompleteJunctions() {
		List<TrackJunction> drop = new ArrayList<>();
		for (TrackJunction junction : junctions.values()) {
			if (junction.branchSplineId == null) {
				drop.add(junction);
			}
		}
		for (TrackJunction junction : drop) {
			TrackLog.junctionDrop(junction.id, junction.stemSplineId, "incomplete");
			deleteJunction(junction.id);
		}
	}

	public void ensureFrogClear(TrackSpline stem, double s, UUID ignoreId) throws TrackLayException {
		if (stem == null) {
			throw new TrackLayException("Junction stem track is missing");
		}
		ensureStemLongEnoughForJunction(stem);
		double frogS = TrackJunction.wrapS(s, stem.length(), stem.isLoop());
		double min = Cache.trackMinJunctionSpacing;
		for (TrackJunction other : junctions.values()) {
			if (ignoreId != null && other.id.equals(ignoreId)) {
				continue;
			}
			if (!other.stemSplineId.equals(stem.getId())) {
				continue;
			}
			if (other.branchSplineId == null) {
				continue;
			}
			double dist = TrackJunction.arcDistance(frogS, other.s, stem.length(), stem.isLoop());
			if (dist <= 1.0) {
				throw new TrackLayException("Junction already has a branch");
			}
			if (dist < min) {
				throw new TrackLayException("Junctions must be at least "
						+ (int) Math.round(min) + " blocks apart along the track");
			}
		}
	}

	public Optional<TrackJunction> getJunction(UUID id) {
		return Optional.ofNullable(junctions.get(id));
	}

	public List<TrackJunction> junctionsOn(UUID stemId) {
		List<TrackJunction> out = new ArrayList<>();
		for (TrackJunction junction : junctions.values()) {
			if (junction.stemSplineId.equals(stemId)) {
				out.add(junction);
			}
		}
		return out;
	}

	public Optional<TrackJunction> junctionByBranch(UUID branchId) {
		if (branchId == null) {
			return Optional.empty();
		}
		for (TrackJunction junction : junctions.values()) {
			if (branchId.equals(junction.branchSplineId)) {
				return Optional.of(junction);
			}
		}
		return Optional.empty();
	}

	public TrackJunction attachBranch(UUID junctionId, UUID branchId) throws TrackLayException {
		if (junctionId == null || branchId == null) {
			throw new TrackLayException("Junction branch is missing");
		}
		TrackJunction existing = junctions.get(junctionId);
		if (existing == null) {
			throw new TrackLayException("Junction is missing");
		}
		if (existing.branchSplineId != null && !existing.branchSplineId.equals(branchId)) {
			throw new TrackLayException("Junction already has a branch");
		}
		return putJunction(existing.withBranch(branchId));
	}

	public boolean deleteJunction(UUID id) {
		TrackJunction junction = junctions.remove(id);
		if (junction == null) {
			return false;
		}
		TrackSpline stem = splines.get(junction.stemSplineId);
		String world = stem != null ? stem.getWorld() : "unknown";
		store.deleteJunction(world, id);
		despawnSwitchDisplay(id);
		return true;
	}

	private void dropJunctionsForSpline(UUID splineId, String world) {
		List<UUID> drop = new ArrayList<>();
		for (TrackJunction junction : junctions.values()) {
			if (junction.stemSplineId.equals(splineId) || branchIdEquals(junction, splineId)) {
				drop.add(junction.id);
			}
		}
		for (UUID junctionId : drop) {
			junctions.remove(junctionId);
			store.deleteJunction(world, junctionId);
			despawnSwitchDisplay(junctionId);
		}
	}

	private void clearBranchRefs(UUID splineId) {
		List<UUID> drop = new ArrayList<>();
		for (TrackJunction junction : junctions.values()) {
			if (branchIdEquals(junction, splineId)) {
				drop.add(junction.id);
			}
		}
		for (UUID junctionId : drop) {
			TrackJunction junction = junctions.remove(junctionId);
			if (junction == null) {
				continue;
			}
			TrackSpline stem = splines.get(junction.stemSplineId);
			String world = stem != null ? stem.getWorld() : "unknown";
			store.deleteJunction(world, junctionId);
			despawnSwitchDisplay(junctionId);
		}
	}

	private static boolean branchIdEquals(TrackJunction junction, UUID splineId) {
		return junction.branchSplineId != null && junction.branchSplineId.equals(splineId);
	}

	private static double polylineLength(List<double[]> points) {
		if (points == null || points.size() < 2) {
			return 0;
		}
		double len = 0;
		for (int i = 1; i < points.size(); i++) {
			double[] a = points.get(i - 1);
			double[] b = points.get(i);
			len += Math.sqrt(
					(b[0] - a[0]) * (b[0] - a[0])
							+ (b[1] - a[1]) * (b[1] - a[1])
							+ (b[2] - a[2]) * (b[2] - a[2]));
		}
		return len;
	}

	public Collection<TrackSpline> all() {
		return List.copyOf(splines.values());
	}

	public void invalidateAllVisuals() {
		for (TrackSpline spline : splines.values()) {
			spline.invalidateVisuals();
		}
	}

	public List<TrackSpline> inWorld(String world) {
		List<TrackSpline> out = new ArrayList<>();
		for (TrackSpline spline : splines.values()) {
			if (spline.getWorld().equals(world)) {
				out.add(spline);
			}
		}
		return out;
	}

	public void dumpToLog() {
		TrackLog.append("DUMP splines=" + splines.size() + " junctions=" + junctions.size());
		for (TrackSpline spline : splines.values()) {
			TrackSample first = spline.first();
			TrackSample last = spline.last();
			TrackLog.spline(
					spline.getId(),
					spline.getWorld(),
					spline.isLoop(),
					spline.length(),
					spline.getSamples().size(),
					first.x, first.y, first.z,
					last.x, last.y, last.z);
			double len = spline.length();
			double step = 32;
			for (double s = 0; s < len - 1e-6; s += step) {
				TrackPose p = spline.sampleAt(s);
				TrackLog.pt(spline.getId(), s, p.x, p.y, p.z);
			}
			TrackPose end = spline.sampleAt(len);
			TrackLog.pt(spline.getId(), len, end.x, end.y, end.z);
		}
		for (TrackJunction junction : junctions.values()) {
			TrackSpline stem = splines.get(junction.stemSplineId);
			TrackPose frog = stem == null ? null : stem.sampleAt(junction.s);
			TrackSpline branch = junction.branchSplineId == null ? null : splines.get(junction.branchSplineId);
			TrackSample branchLast = branch == null ? null : branch.last();
			TrackLog.junction(
					junction.id,
					junction.stemSplineId,
					junction.s,
					frog == null ? 0 : frog.x,
					frog == null ? 0 : frog.y,
					frog == null ? 0 : frog.z,
					frog != null,
					junction.side.name(),
					junction.facingSign,
					junction.branchSplineId,
					branch == null ? 0 : branch.length(),
					branchLast == null ? 0 : branchLast.x,
					branchLast == null ? 0 : branchLast.z,
					branch != null,
					junction.thrown);
		}
	}

	public Optional<TrackSpline> nearest(String world, double x, double y, double z, double maxHoriz) {
		TrackSpline best = null;
		double bestD = maxHoriz;
		for (TrackSpline spline : inWorld(world)) {
			double s = spline.nearestS(x, y, z);
			TrackPose pose = spline.sampleAt(s);
			double dx = pose.x - x;
			double dz = pose.z - z;
			double horiz = Math.hypot(dx, dz);
			if (horiz <= bestD) {
				bestD = horiz;
				best = spline;
			}
		}
		return Optional.ofNullable(best);
	}

	private TrackLayResult finishLay(TrackLayResult result) {
		pruneNestedShortTracks();
		return result;
	}

	private Optional<TrackJunction> turnoutDug(TrackSpline spline, double digS) {
		return junctionByBranch(spline.getId())
				.filter(junction -> junction.turnoutEndS > 0 && digS <= junction.turnoutEndS + 1e-9);
	}

	private DigResult finishDig(DigResult result) {
		if (result.kind != DigResult.Kind.NONE) {
			pruneNestedShortTracks();
		}
		return result;
	}

	private void ensureStemLongEnoughForJunction(TrackSpline stem) throws TrackLayException {
		if (!stem.isLoop() && stem.length() < Cache.trackMinLayDistance - 1e-9) {
			throw new TrackLayException("Track is too short for a junction (need "
					+ (int) Math.round(Cache.trackMinLayDistance) + ").");
		}
	}

	private void dropJunctionAndBranch(TrackJunction junction) {
		removeJunctionTurnout(junction, null);
		pruneNestedShortTracks();
	}

	private DigResult removeJunctionTurnout(TrackJunction junction, World bukkitWorld) {
		if (junction == null) {
			return DigResult.none();
		}
		UUID branchId = junction.branchSplineId;
		deleteJunction(junction.id);
		if (branchId == null) {
			return DigResult.none();
		}
		TrackSpline branch = splines.get(branchId);
		if (branch == null) {
			return DigResult.none();
		}
		double cutoff = turnoutCutoff(junction, branch);
		List<TrackSample> samples = branch.getSamples();
		int keepFrom = samples.size();
		for (int i = 0; i < samples.size(); i++) {
			if (samples.get(i).s > cutoff + 1e-9) {
				keepFrom = i;
				break;
			}
		}
		List<double[]> xyz = branch.xyz();
		if (keepFrom >= xyz.size() - 1) {
			removeSplineRecord(branchId);
			return DigResult.deletedJunctionTurnout(branchId);
		}
		List<double[]> tail = new ArrayList<>(xyz.subList(keepFrom, xyz.size()));
		TrackResettle.resettle(bukkitWorld, tail, true, false);
		TrackSpline next = replace(TrackSpline.fromPoints(branchId, branch.getWorld(), false, tail));
		return DigResult.removedJunctionTurnout(next);
	}

	private static double turnoutCutoff(TrackJunction junction, TrackSpline branch) {
		return junction.turnoutEndS > 0 ? junction.turnoutEndS : branch.length();
	}

	public void pruneNestedShortTracks() {
		boolean changed = true;
		while (changed) {
			changed = false;
			List<UUID> toDelete = new ArrayList<>();
			for (TrackSpline candidate : splines.values()) {
				if (candidate.isLoop() || candidate.length() > PRUNE_NESTED_MAX_LENGTH + 1e-9) {
					continue;
				}
				if (isFullyNestedInLongerTrack(candidate)) {
					toDelete.add(candidate.getId());
				}
			}
			for (UUID id : toDelete) {
				if (deleteWithoutPrune(id)) {
					changed = true;
				}
			}
		}
	}

	private boolean isFullyNestedInLongerTrack(TrackSpline candidate) {
		if (candidate.getSamples().size() < 2) {
			return false;
		}
		for (TrackSpline host : inWorld(candidate.getWorld())) {
			if (host.getId().equals(candidate.getId())) {
				continue;
			}
			if (host.length() <= candidate.length() + 1e-9) {
				continue;
			}
			if (allSamplesOverlap(host, candidate)) {
				return true;
			}
		}
		return false;
	}

	private static boolean allSamplesOverlap(TrackSpline host, TrackSpline candidate) {
		for (TrackSample sample : candidate.getSamples()) {
			if (!anyHostSampleOverlaps(host, sample)) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyHostSampleOverlaps(TrackSpline host, TrackSample sample) {
		for (TrackSample hostSample : host.getSamples()) {
			double dx = hostSample.x - sample.x;
			double dz = hostSample.z - sample.z;
			if (Math.hypot(dx, dz) > TrackClearance.OVERLAP_HORIZ) {
				continue;
			}
			if (Math.abs(hostSample.y - sample.y) > TrackClearance.OVERLAP_VERT) {
				continue;
			}
			return true;
		}
		return false;
	}
}
