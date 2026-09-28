package net.tfminecraft.vehicleframework.tracks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public final class TrackCommands {
	private static final Map<UUID, Long> lastToolMs = new ConcurrentHashMap<>();
	private static final LayRetryCooldown retry = new LayRetryCooldown();

	private TrackCommands() {
	}

	public static boolean handle(CommandSender sender, String[] args) {
		if (!Permissions.isAdmin(sender)) {
			sender.sendMessage("§cNo permission.");
			return true;
		}
		if (args.length >= 2 && args[1].equalsIgnoreCase("resync")) {
			return resync(sender);
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("§cOnly players can use track commands.");
			return true;
		}
		if (args.length < 2) {
			player.sendMessage("§e/vf track start|end|list|info|particles|clearance|dump|delete|bind|unbind|resync");
			return true;
		}
		String sub = args[1];
		if (sub.equalsIgnoreCase("start")) {
			markStart(player, player.getLocation());
			return true;
		}
		if (sub.equalsIgnoreCase("end")) {
			markEnd(player, player.getLocation());
			return true;
		}
		if (sub.equalsIgnoreCase("list")) {
			List<TrackSpline> list = registry().inWorld(player.getWorld().getName());
			if (list.isEmpty()) {
				player.sendMessage("§7No tracks in this world.");
				return true;
			}
			player.sendMessage("§bTracks:");
			for (TrackSpline spline : list) {
				player.sendMessage("§e" + spline.getId() + " §7samples=" + spline.getSamples().size()
						+ " length=" + String.format("%.1f", spline.length()));
			}
			return true;
		}
		if (sub.equalsIgnoreCase("info")) {
			Optional<TrackSpline> spline = resolveSpline(player, args);
			if (spline.isEmpty()) {
				player.sendMessage("§cNo track nearby (8 blocks) or unknown id.");
				return true;
			}
			TrackSpline s = spline.get();
			player.sendMessage("§bTrack " + s.getId());
			player.sendMessage("§7samples=" + s.getSamples().size() + " length=" + String.format("%.1f", s.length())
					+ " loop=" + s.isLoop());
			return true;
		}
		if (sub.equalsIgnoreCase("particles")) {
			Optional<TrackSpline> spline = resolveSpline(player, args);
			if (spline.isEmpty()) {
				player.sendMessage("§cNo track nearby (8 blocks) or unknown id.");
				return true;
			}
			for (TrackSample sample : spline.get().getSamples()) {
				Location loc = new Location(player.getWorld(), sample.x, sample.y + 0.2, sample.z);
				player.spawnParticle(Particle.END_ROD, loc, 1, 0, 0, 0, 0);
			}
			player.sendMessage("§aShowed " + spline.get().getSamples().size() + " samples.");
			return true;
		}
		if (sub.equalsIgnoreCase("clearance")) {
			return clearance(player, args);
		}
		if (sub.equalsIgnoreCase("dump")) {
			return dump(player);
		}
		if (sub.equalsIgnoreCase("delete")) {
			return delete(player, args);
		}
		if (sub.equalsIgnoreCase("bind")) {
			return bindTrain(player, args);
		}
		if (sub.equalsIgnoreCase("unbind")) {
			return unbindTrain(player);
		}
		player.sendMessage("§e/vf track start|end|list|info|particles|clearance|dump|delete|bind|unbind|resync");
		return true;
	}

	private static boolean resync(CommandSender sender) {
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays == null) {
			sender.sendMessage("§cTrack displays are not running.");
			return true;
		}
		displays.startRailResync(sender);
		return true;
	}

	private static boolean clearance(Player player, String[] args) {
		Optional<TrackSpline> spline = resolveSpline(player, args);
		if (spline.isEmpty()) {
			player.sendMessage("§cNo track nearby (8 blocks) or unknown id.");
			return true;
		}
		if (!player.getWorld().getName().equals(spline.get().getWorld())) {
			player.sendMessage("§cThat track is in " + spline.get().getWorld() + ". Run this from that world.");
			return true;
		}
		TrainBlockCollision.Scan scan = TrainBlockCollision.scanLoaded(player.getWorld(), spline.get());
		List<TrainBlockCollision.Obstruction> found = scan.obstructions();
		String space = TrackClearance.format(Cache.trainClearanceWidth) + " wide by "
				+ TrackClearance.format(Cache.trainClearanceHeight) + " high";
		if (found.isEmpty()) {
			player.sendMessage("§aNothing in the way of trains (" + space + " above the rail).");
		} else {
			int shown = TrainSpaceHighlight.show(player, found);
			player.sendMessage("§c" + found.size() + " blocks in the way of trains (" + space
					+ " above the rail). Outlined the nearest " + shown + " for a minute.");
			List<List<TrainBlockCollision.Obstruction>> stretches = stretches(found);
			stretches.sort((a, b) -> Integer.compare(b.size(), a.size()));
			for (List<TrainBlockCollision.Obstruction> stretch : stretches.subList(0, Math.min(5, stretches.size()))) {
				TrainBlockCollision.Obstruction first = stretch.get(0);
				TrainBlockCollision.Obstruction last = stretch.get(stretch.size() - 1);
				player.sendMessage(String.format("§7 %d blocks from %d, %d, %d (%.0f to %.0f along the track)",
						stretch.size(), first.x(), first.y(), first.z(), first.s(), last.s()));
			}
			if (stretches.size() > 5) {
				player.sendMessage("§7 and " + (stretches.size() - 5) + " smaller stretches.");
			}
		}
		if (scan.skipped() > 0) {
			player.sendMessage(String.format("§7Did not check %.0f blocks of track in unloaded chunks.", scan.skipped()));
		}
		return true;
	}

	// Obstructions within a few blocks of each other along the track, as one stretch.
	private static List<List<TrainBlockCollision.Obstruction>> stretches(List<TrainBlockCollision.Obstruction> found) {
		List<List<TrainBlockCollision.Obstruction>> stretches = new java.util.ArrayList<>();
		List<TrainBlockCollision.Obstruction> current = null;
		for (TrainBlockCollision.Obstruction o : found) {
			if (current == null || o.s() - current.get(current.size() - 1).s() > 3) {
				current = new java.util.ArrayList<>();
				stretches.add(current);
			}
			current.add(o);
		}
		return stretches;
	}

	private static boolean waitingAfterRefusal(Player player) {
		long left = retry.remainingMs(player.getUniqueId(), System.currentTimeMillis());
		if (left <= 0) {
			return false;
		}
		player.sendActionBar(Component.text(
				"Wait " + (long) Math.ceil(left / 1000.0) + "s before laying again", NamedTextColor.RED));
		return true;
	}

	private static void showRefused(Player player, TrackLayException e) {
		retry.start(player.getUniqueId(), System.currentTimeMillis(), Cache.trackLayRetryMs);
		if (!e.inTrainSpace.isEmpty()) {
			TrainSpaceHighlight.show(player, e.inTrainSpace);
		} else if (e.hasBlock()) {
			TrainSpaceHighlight.show(player, List.of(
					new TrainBlockCollision.Obstruction(e.blockX, e.blockY, e.blockZ, 0)));
		}
	}

	private static boolean dump(Player player) {
		if (!Cache.debugLogging) {
			player.sendMessage("§cEnable debug-logging in trains.yml first.");
			return true;
		}
		registry().dumpToLog();
		player.sendMessage("§aWrote spline dump to logs/track.log (DUMP / SPLINE / PT / JUNCTION).");
		return true;
	}

	private static boolean bindTrain(Player player, String[] args) {
		ActiveVehicle train = findTrain(player);
		if (train == null) {
			player.sendMessage("§cNo train (sit in one or stand close).");
			return true;
		}
		ActiveVehicle loco = locoOf(train);
		Optional<TrackSpline> spline = resolveSpline(player, args);
		if (spline.isEmpty()) {
			Location loc = loco.getEntity().getLocation();
			spline = registry().nearest(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), 8);
		}
		if (spline.isEmpty()) {
			player.sendMessage("§cNo track nearby or unknown id.");
			return true;
		}
		if (loco.getTrainHandler().bind(spline.get())) {
			player.sendMessage("§aBound to track " + spline.get().getId());
		} else {
			player.sendMessage("§cCould not bind to track.");
		}
		return true;
	}

	private static boolean unbindTrain(Player player) {
		ActiveVehicle train = findTrain(player);
		if (train == null) {
			player.sendMessage("§cNo train (sit in one or stand close).");
			return true;
		}
		locoOf(train).getTrainHandler().unbind();
		player.sendMessage("§aUnbound from track.");
		return true;
	}

	private static ActiveVehicle findTrain(Player player) {
		ActiveVehicle riding = VehicleFramework.getVehicleManager().getByPassenger(player);
		if (riding != null && riding.isTrain()) {
			return riding;
		}
		ActiveVehicle best = null;
		double bestD = 8;
		Location loc = player.getLocation();
		for (ActiveVehicle vehicle : VehicleFramework.getVehicleManager().get().values()) {
			if (!vehicle.isTrain() || vehicle.getEntity() == null) {
				continue;
			}
			if (!vehicle.getEntity().getWorld().equals(loc.getWorld())) {
				continue;
			}
			double dist = vehicle.getEntity().getLocation().distance(loc);
			if (dist <= bestD) {
				bestD = dist;
				best = vehicle;
			}
		}
		return best;
	}

	private static ActiveVehicle locoOf(ActiveVehicle car) {
		ActiveVehicle loco = car;
		while (loco != null && loco.hasParent()) {
			loco = loco.getParent();
		}
		return loco;
	}

	public static boolean markStart(Player player, Location at) {
		TrackJunctionSession.clear(player);
		Location snapped = snapClick(player, at);
		if (snapped == null) {
			return false;
		}
		lastToolMs.put(player.getUniqueId(), System.currentTimeMillis());
		TrackAnchorSession.setStart(player, snapped);
		player.sendMessage("§aStart location set.");
		TrackLog.start(player.getName(), snapped.getX(), snapped.getY(), snapped.getZ());
		return true;
	}

	public static void markEnd(Player player, Location at) {
		markEnd(player, at, null);
	}

	public static void markEnd(Player player, Location at, Block hitBlock) {
		if (waitingAfterRefusal(player)) {
			return;
		}
		TrackJunctionSession.Pending pending = TrackJunctionSession.get(player);
		if (pending != null) {
			Location snapped = snapClick(player, at);
			if (snapped == null) {
				return;
			}
			playHit(hitBlock, snapped);
			finishBranch(player, pending, snapped);
			return;
		}
		Location start = TrackAnchorSession.getStart(player);
		if (start == null) {
			player.sendMessage("§cSet a start location first (left click).");
			return;
		}
		Location snapped = snapClick(player, at);
		if (snapped == null) {
			return;
		}
		playHit(hitBlock, snapped);
		finish(player, start, snapped);
	}

	private static void playHit(Block hitBlock, Location snapped) {
		if (hitBlock != null) {
			TrackFx.hit(hitBlock);
			return;
		}
		TrackFx.hitNear(snapped);
	}

	public static void startJunction(Player player, Location at) {
		if (waitingAfterRefusal(player)) {
			return;
		}
		if (at == null || at.getWorld() == null) {
			player.sendMessage("§cClick existing track to start a junction.");
			return;
		}
		lastToolMs.put(player.getUniqueId(), System.currentTimeMillis());
		TrackSpline stem = registry().nearest(
				at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), Cache.trackSnapDistance)
				.orElse(null);
		if (stem == null) {
			player.sendMessage("§cClick existing track to start a junction.");
			return;
		}
		registry().dropIncompleteJunctions();
		double s = stem.nearestS(at.getX(), at.getY(), at.getZ());
		try {
			registry().ensureFrogClear(stem, s, null);
			TrackPose pose = stem.sampleAt(s);
			int facing = TrackJunction.facingSign(at.getYaw(), pose.yaw);
			TrackAnchorSession.clear(player);
			TrackJunctionSession.set(player, new TrackJunctionSession.Pending(stem.getId(), s, facing));
			player.sendMessage("§aJunction started. Right-click with the track layer to lay the branch.");
			TrackLog.junctionStart(player.getName(), stem.getId(), s);
		} catch (TrackLayException e) {
			player.sendMessage("§c" + e.getMessage());
			showRefused(player, e);
			TrackLog.layFail(e.getMessage(), e);
		}
	}

	private static void finishBranch(Player player, TrackJunctionSession.Pending pending, Location at) {
		if (at.getWorld() == null) {
			return;
		}
		if (!TrackPieces.canAffordFirst(player)) {
			player.sendMessage("§cYou need track in your inventory to lay rail.");
			return;
		}
		try {
			TrackSpline branch = registry().layBranch(
					pending.stemId,
					pending.s,
					pending.facingSign,
					at.getWorld().getName(),
					at.getWorld(),
					at.getX(), at.getY(), at.getZ());
			TrackJunctionSession.clear(player);
			TrackLayResult result = TrackLayResult.of(
					TrackLayResult.Kind.NEW, branch, branch.xyz(), 0);
			Presented presented = presentLay(player, result);
			announceLay(player, result, presented, true);
		} catch (TrackLayException e) {
			player.sendMessage("§c" + e.getMessage());
			showRefused(player, e);
		}
	}

	private static Location snapClick(Player player, Location at) {
		if (at == null || at.getWorld() == null) {
			return at;
		}
		double y = TrackSupport.snapY(at.getWorld(), at.getX(), at.getY(), at.getZ());
		Double sit = TrackSupport.firstSitY(at.getWorld(), at.getX(), at.getY(), at.getZ());
		if (sit == null) {
			player.sendMessage("§cClick solid ground, not grass or plants.");
			return null;
		}
		Location snapped = at.clone();
		snapped.setY(y);
		return snapped;
	}

	public static void finish(Player player, Location a, Location b) {
		if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
			player.sendMessage("§cStart and end must be in the same world.");
			TrackLog.append("LAY_FAIL player=" + player.getName() + " different worlds");
			return;
		}
		if (!TrackPieces.canAffordFirst(player)) {
			player.sendMessage("§cYou need track in your inventory to lay rail.");
			return;
		}
		try {
			TrackLayResult result = registry().lay(
					a.getWorld().getName(),
					a.getWorld(),
					a.getX(), a.getY(), a.getZ(),
					b.getX(), b.getY(), b.getZ());
			TrackAnchorSession.clear(player);
			announceLay(player, result, presentLay(player, result), false);
		} catch (TrackLayException e) {
			player.sendMessage("§c" + e.getMessage());
			showRefused(player, e);
		}
	}

	public static void digAt(Player player, Location loc) {
		if (loc.getWorld() == null) {
			return;
		}
		Optional<TrackRegistry.DigTarget> target = registry().digTarget(
				loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
		if (target.isEmpty()) {
			applyDig(player, DigResult.none(), loc);
			return;
		}
		if (trainOn(target.get())) {
			lastToolMs.put(player.getUniqueId(), System.currentTimeMillis());
			player.sendMessage("§cA train is on this track. Move it before removing the rail.");
			return;
		}
		TrackSpline before = target.get().spline();
		applyDig(player, registry().digAt(before, target.get().index(), loc.getWorld()), loc, before);
	}

	private static boolean trainOn(TrackRegistry.DigTarget target) {
		VehicleManager vehicles = VehicleFramework.getVehicleManager();
		if (vehicles == null) {
			return false;
		}
		for (ActiveVehicle vehicle : vehicles.get().values()) {
			if (vehicle.isTrain() && !vehicle.hasParent()
					&& vehicle.getTrainHandler().occupies(target.spans())) {
				return true;
			}
		}
		return false;
	}

	private static boolean delete(Player player, String[] args) {
		if (args.length < 3) {
			player.sendMessage("§cUsage: /vf track delete <uuid>");
			return true;
		}
		try {
			deleteFull(player, UUID.fromString(args[2]));
		} catch (IllegalArgumentException e) {
			player.sendMessage("§cInvalid uuid.");
		}
		return true;
	}

	private static void deleteFull(Player player, UUID id) {
		TrackBuildAnimator.cancel(id);
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.despawnSpline(id);
		}
		if (registry().delete(id)) {
			player.sendMessage("§aDeleted track " + id);
			TrackLog.delete(player.getName(), id, true);
		} else {
			player.sendMessage("§cUnknown track.");
			TrackLog.delete(player.getName(), id, false);
		}
	}

	public static boolean skipDuplicateToolUse(Player player) {
		Long prev = lastToolMs.get(player.getUniqueId());
		return prev != null && System.currentTimeMillis() - prev < 100;
	}

	public static void applyDig(Player player, DigResult result, Location at) {
		applyDig(player, result, at, null);
	}

	/** {@code before} is the track as it was, so only the displays that changed are replaced. */
	public static void applyDig(Player player, DigResult result, Location at, TrackSpline before) {
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (result.kind == DigResult.Kind.NONE) {
			TrackLog.dig(player.getName(), result);
			return;
		}
		lastToolMs.put(player.getUniqueId(), System.currentTimeMillis());
		TrackLog.dig(player.getName(), result);
		if (result.removedJunctionTurnout) {
			if (result.kept != null) {
				player.sendMessage("§eRemoved junction turnout; branch track kept beyond the frog.");
			} else {
				player.sendMessage("§eRemoved junction turnout.");
			}
		}
		if (at != null && at.getWorld() != null) {
			TrackFx.place(at.getWorld(), new TrackPose(at.getX(), at.getY(), at.getZ(), 0, 0));
			if (Cache.trackBuildSwing) {
				player.swingMainHand();
			}
		}
		// Mid-build, the world shows only part of the stroke, so the old pieces are not known.
		if (result.kept != null && TrackBuildAnimator.isBuilding(result.kept.getId())) {
			before = null;
		}
		if (result.deletedId != null) {
			TrackBuildAnimator.cancel(result.deletedId);
		}
		if (result.kept != null) {
			TrackBuildAnimator.cancel(result.kept.getId());
		}
		if (result.tail != null) {
			TrackBuildAnimator.cancel(result.tail.getId());
		}
		if (result.kind == DigResult.Kind.DELETED) {
			if (displays != null) {
				displays.despawnSpline(result.deletedId);
			}
			return;
		}
		if (result.kind == DigResult.Kind.UPDATED) {
			if (displays != null && result.deletedId != null
					&& !result.deletedId.equals(result.kept.getId())) {
				displays.despawnSpline(result.deletedId);
			}
			if (displays != null) {
				displays.refreshSpline(before, result.kept.getId());
			}
			return;
		}
		if (displays != null) {
			displays.despawnSpline(result.kept.getId());
			if (result.tail != null) {
				displays.despawnSpline(result.tail.getId());
			}
			displays.spawnSpline(result.kept);
			if (result.tail != null) {
				displays.spawnSpline(result.tail);
			}
		}
	}

	private static Presented presentLay(Player player, TrackLayResult result) {
		if (result == null || result.spline() == null) {
			return Presented.none();
		}
		int cost = TrackPieces.cost(result.stroke);
		if (result.sequential() && TrackBuildAnimator.sequential(player)) {
			boolean started = TrackBuildAnimator.start(
					player, result.spline(), result.keepPoints(), result.stroke);
			int paid = TrackPieces.pays(player) ? (started ? 1 : 0) : cost;
			return new Presented(remaining(result), paid, cost, true);
		}
		int paid = TrackPieces.consumeUpTo(player, cost);
		if (result.kind != TrackLayResult.Kind.CONNECT
				&& TrackPieces.pays(player)
				&& paid < cost) {
			boolean append = result.kind == TrackLayResult.Kind.APPEND;
			registry().persistPoints(
					result.spline().getId(),
					TrackPieces.persistPoints(result.keepPoints(), result.stroke, append, paid));
		}
		TrackDisplayManager displays = VehicleFramework.getTrackDisplayManager();
		if (displays != null) {
			displays.refreshSpline(result.before, result.spline().getId());
		}
		burstPlace(player, result);
		return new Presented(remaining(result), paid, cost, false);
	}

	private static Optional<TrackSpline> remaining(TrackLayResult result) {
		if (result == null || result.spline() == null) {
			return Optional.empty();
		}
		return registry().get(result.spline().getId())
				.filter(spline -> spline.getSamples().size() >= 2);
	}

	private static void announceLay(
			Player player,
			TrackLayResult result,
			Presented presented,
			boolean branch) {
		if (player == null || result == null || presented == null) {
			return;
		}
		Optional<TrackSpline> now = presented.spline;
		if (now.isPresent()) {
			TrackSpline spline = now.get();
			if (TrackLayResult.shouldAnnounce(result.kind, result.previousCount, spline.getSamples().size())) {
				String length = String.format("%.1f", spline.length());
				if (result.kind == TrackLayResult.Kind.CONNECT) {
					player.sendMessage("§aConnected two tracks. Length " + length + ".");
				} else if (branch) {
					player.sendMessage("§aLaid branch. Length " + length + ".");
				} else {
					player.sendMessage("§aLaid track from start to end. Length " + length + ".");
				}
			}
		}
		if (presented.sequential) {
			return;
		}
		if (!TrackPieces.pays(player) || presented.paid >= presented.cost) {
			return;
		}
		if (now.isEmpty()
				|| !TrackLayResult.shouldAnnounce(
						result.kind, result.previousCount, now.get().getSamples().size())) {
			player.sendMessage("§cYou need track in your inventory to lay rail.");
			return;
		}
		player.sendMessage("§cNot enough track to finish.");
	}

	private static final class Presented {
		private final Optional<TrackSpline> spline;
		private final int paid;
		private final int cost;
		private final boolean sequential;

		private Presented(Optional<TrackSpline> spline, int paid, int cost, boolean sequential) {
			this.spline = spline == null ? Optional.empty() : spline;
			this.paid = paid;
			this.cost = cost;
			this.sequential = sequential;
		}

		private static Presented none() {
			return new Presented(Optional.empty(), 0, 0, false);
		}
	}

	private static void burstPlace(Player player, TrackLayResult result) {
		if (player == null || player.getWorld() == null || result == null) {
			return;
		}
		double[] p;
		float yaw = 0;
		if (result.stroke != null && result.stroke.size() >= 1) {
			p = result.stroke.get(result.stroke.size() - 1);
			if (result.stroke.size() >= 2) {
				double[] a = result.stroke.get(result.stroke.size() - 2);
				yaw = (float) Math.toDegrees(Math.atan2(-(p[0] - a[0]), p[2] - a[2]));
			}
		} else {
			TrackSample last = result.spline().last();
			p = new double[] {last.x, last.y, last.z};
			yaw = last.yaw;
		}
		TrackFx.place(player.getWorld(), new TrackPose(p[0], p[1], p[2], yaw, 0));
	}

	private static Optional<TrackSpline> resolveSpline(Player player, String[] args) {
		if (args.length >= 3) {
			try {
				return registry().get(UUID.fromString(args[2]));
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}
		}
		Location loc = player.getLocation();
		return registry().nearest(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), 8);
	}

	private static TrackRegistry registry() {
		return VehicleFramework.getTrackRegistry();
	}
}
