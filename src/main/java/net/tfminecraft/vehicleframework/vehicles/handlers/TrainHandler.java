package net.tfminecraft.vehicleframework.vehicles.handlers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.ticxo.modelengine.api.model.ActiveModel;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.bones.ConvertedAngle;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.PersistenceLog;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.tracks.ThrottleTape;
import net.tfminecraft.vehicleframework.tracks.ThrottleTapeItems;
import net.tfminecraft.vehicleframework.tracks.TrackAdvance;
import net.tfminecraft.vehicleframework.tracks.TrainBlockCollision;
import net.tfminecraft.vehicleframework.tracks.TrackClearance;
import net.tfminecraft.vehicleframework.tracks.TrackFx;
import net.tfminecraft.vehicleframework.tracks.TrackJunction;
import net.tfminecraft.vehicleframework.tracks.TrackJunctionTravel;
import net.tfminecraft.vehicleframework.tracks.TrackSplineMotion;
import net.tfminecraft.vehicleframework.tracks.TrackTools;
import net.tfminecraft.vehicleframework.tracks.TrackConsistMath;
import net.tfminecraft.vehicleframework.tracks.TrackLap;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackRegistry;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;
import net.tfminecraft.vehicleframework.tracks.RecorderLog;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Connector;

public class TrainHandler {
	protected ActiveVehicle v;
	protected ActiveVehicle child;
	
	private Connector front;
	private Connector back;
	private String pendingParent;
	private String pendingChild;
	private UUID splineId;
	private double s;
	// Track may have been edited while this train was unloaded.
	private boolean checkLoadedPosition;
	private int travelSign = 1;
	private UUID armedJunctionId;
	private TrackJunction.Side armedSide;
	private long lastNoFrogChatMs;
	private UUID routeJunctionId;
	private boolean takeBranch;
	private final List<String> fuelCars = new ArrayList<>();
	private ThrottleTape installedTape;
	private ThrottleTape recordingTape;
	private UUID recordingPlayer;
	private boolean recording;
	private double recordPrevS;
	private UUID recordPrevSpline;
	private double recordTraveled;
	private double recordLength;
	private double fxTraveled;
	private final ThrottleTape.DwellState tapeDwell = new ThrottleTape.DwellState();
	
	public TrainHandler(ConfigurationSection config) {
		if(config.contains("front-connector")) {
			front = new Connector(config.getString("front-connector"));
		}
		if(config.contains("back-connector")) {
			back = new Connector(config.getString("back-connector"));
		}
		if (config.contains("fuel-cars")) {
			for (String id : config.getStringList("fuel-cars")) {
				if (id != null && !id.isBlank()) {
					fuelCars.add(id);
				}
			}
		}
	}
	
	public TrainHandler(ActiveVehicle v, TrainHandler another) {
		this.v = v;
		if(another.isAttachable()) {
			front = new Connector(v, another.getFront());
		}
		if(another.canHaveAttached()) {
			back = new Connector(v, another.getBack());
		}
		travelSign = another.travelSign;
		armedJunctionId = another.armedJunctionId;
		armedSide = another.armedSide;
		routeJunctionId = another.routeJunctionId;
		takeBranch = another.takeBranch;
		fuelCars.addAll(another.fuelCars);
		if (another.installedTape != null) {
			installedTape = ThrottleTape.fromJson(another.installedTape.toJson());
		}
	}
	
	public void updateModel(ActiveModel m) {
		if(isAttachable()) {
			front.updateModel(m);
		}
		if(canHaveAttached()) {
			back.updateModel(m);
		}
	}
	
	public boolean attach(Player p, ActiveVehicle target) {
		if(!isAttachable()) {
			p.sendMessage("§cThis train car cannot be attached to anything");
			return false;
		}
		if(v.hasParent()) {
			p.sendMessage("§cThis vehicle is already attached to something!");
			return false;
		}
		if(!target.getBehaviourHandler().isTrain()) {
			p.sendMessage("§cTarget vehicle is not a train type");
			return false;
		}
		TrainHandler handler = target.getBehaviourHandler().getTrainHandler();
		if(!handler.canHaveAttached()) {
			p.sendMessage("§cTarget train cannot have any cars attached");
			return false;
		}
		if(target.getTrainHandler().hasChild() || target.getTrainHandler().getPendingChild() != null) {
			p.sendMessage("§cTarget train already has a car attached");
			return false;
		}
		target.getTrainHandler().setChild(v);
		v.setParent(target);
		p.sendMessage("§aConnected car to train");
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
		ActiveVehicle loco = locoOf(target);
		if (loco.getTrainHandler().isBound()) {
			loco.getTrainHandler().placeLoadedCars();
		} else {
			Location loc = getOffsetPosition(target);
			v.getEntity().teleport(loc);
		}
		return true;
	}
	
	public boolean hasChild() {
		return child != null;
	}
	public ActiveVehicle getChild() {
		return child;
	}
	public void setChild(ActiveVehicle v) {
		if (v != null && this.v != null && this.v.equals(v)) {
			return;
		}
		child = v;
		if (v != null) {
			pendingChild = null;
		}
	}

	public String getPendingParent() {
		return pendingParent;
	}

	public void setPendingParent(String uuid) {
		pendingParent = blankToNull(uuid);
	}

	public String getPendingChild() {
		return pendingChild;
	}

	public void setPendingChild(String uuid) {
		pendingChild = blankToNull(uuid);
	}

	public UUID getSplineId() {
		return splineId;
	}

	public void setSplineId(UUID splineId) {
		this.splineId = splineId;
	}

	public double getS() {
		return s;
	}

	public void setS(double s) {
		this.s = s;
	}

	public void applyConsist(ConsistData consist) {
		if (consist == null) {
			return;
		}
		pendingParent = consist.getParent();
		pendingChild = consist.getChild();
		if (consist.getSplineId() != null) {
			try {
				splineId = UUID.fromString(consist.getSplineId());
			} catch (IllegalArgumentException e) {
				splineId = null;
			}
		} else {
			splineId = null;
		}
		s = consist.getS() == null ? 0 : consist.getS();
		checkLoadedPosition = splineId != null;
		travelSign = consist.getTravelSign();
		routeJunctionId = null;
		takeBranch = consist.isDiverge();
		if (consist.getJunctionId() != null) {
			try {
				routeJunctionId = UUID.fromString(consist.getJunctionId());
			} catch (IllegalArgumentException e) {
				routeJunctionId = null;
				takeBranch = false;
			}
		}
		PersistenceLog.append("APPLY_CONSIST " + PersistenceLog.vehicle(v));
	}

	public ConsistData toConsistData() {
		String childId = hasChild() ? child.getUUID() : pendingChild;
		String parentId = v != null && v.hasParent() ? v.getParent().getUUID() : pendingParent;
		String spline = splineId == null ? null : splineId.toString();
		Double arc = splineId == null ? null : s;
		boolean loco = pendingParent == null && (v == null || !v.hasParent());
		String junction = loco && routeJunctionId != null ? routeJunctionId.toString() : null;
		Boolean diverge = junction == null ? null : takeBranch;
		return new ConsistData(parentId, childId, spline, arc, splineId == null ? null : travelSign, junction, diverge);
	}

	public void holdJunction(TrackJunction.Side side) {
		if (side == null || v == null || v.hasParent()) {
			return;
		}
		if (v.getAccessPanel() != null && v.getAccessPanel().getSpeed() < 0) {
			clearArm();
			tellCaptain("Junction arm cleared: reverse");
			RecorderLog.arm(v, "clear-reverse", side, null, 0);
			return;
		}
		TrackSpline spline = boundSpline();
		if (spline == null) {
			tellCaptain("Junction: not on a track");
			return;
		}
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return;
		}
		if (armedJunctionId != null) {
			TrackJunction armed = registry.getJunction(armedJunctionId).orElse(null);
			if (armed != null && armed.stemSplineId.equals(splineId)
					&& TrackJunctionTravel.facing(travelSign, armed.facingSign)) {
				double ahead = TrackJunctionTravel.ahead(
						s, armed.s, travelSign, spline.isLoop(), spline.length());
				boolean stillBefore = ahead >= -1e-9
						&& ahead <= Cache.trackJunctionArmDistance + 1e-9
						&& (!spline.isLoop() || ahead <= spline.length() * 0.5 + 1e-9);
				if (stillBefore) {
					throwSwitch(registry, armed, side, ahead);
					return;
				}
			}
			clearArm();
		}
		TrackJunction next = nextFrogInWindow(registry, spline);
		if (next == null) {
			TrackJunction far = nextFrogAhead(registry, spline, Double.POSITIVE_INFINITY);
			long now = System.currentTimeMillis();
			if (now - lastNoFrogChatMs >= 1500) {
				lastNoFrogChatMs = now;
				if (far == null) {
					tellCaptain("Junction: no turnout ahead on this track");
					RecorderLog.arm(v, "no-frog", side, null, 0);
				} else {
					double ahead = TrackJunctionTravel.ahead(
							s, far.s, travelSign, spline.isLoop(), spline.length());
					tellCaptain("Junction: press A/D within "
							+ (int) Cache.trackJunctionArmDistance
							+ " of the frog. Next is "
							+ far.side.name().toLowerCase()
							+ " at s="
							+ String.format(java.util.Locale.US, "%.0f", far.s)
							+ " ("
							+ String.format(java.util.Locale.US, "%.0f", ahead)
							+ " ahead)");
					RecorderLog.arm(v, "too-far", side, far, ahead);
				}
			}
			return;
		}
		double ahead = TrackJunctionTravel.ahead(
				s, next.s, travelSign, spline.isLoop(), spline.length());
		throwSwitch(registry, next, side, ahead);
	}

	private void throwSwitch(TrackRegistry registry, TrackJunction frog, TrackJunction.Side side, double ahead) {
		boolean first = armedJunctionId == null || !armedJunctionId.equals(frog.id);
		boolean diverge = frog.side == side;
		boolean changed = registry.setThrown(frog.id, diverge);
		armedJunctionId = frog.id;
		armedSide = side;
		if (!first && !changed) {
			return;
		}
		TrackJunction live = registry.getJunction(frog.id).orElse(frog);
		tellArm(live, ahead, diverge ? "diverge" : "through");
	}

	private void tellArm(TrackJunction frog, double ahead, String status) {
		RecorderLog.arm(v, status, armedSide, frog, ahead);
		tellCaptain("Switch: "
				+ (frog.thrown ? "diverge" : "through")
				+ " ("
				+ status
				+ "), "
				+ String.format(java.util.Locale.US, "%.0f", ahead)
				+ " ahead");
	}

	private void tellCaptain(String message) {
		if (v == null || v.getSeatHandler() == null || message == null) {
			return;
		}
		Player captain = v.getSeatHandler().captainPlayer();
		if (captain != null) {
			captain.sendMessage("§e" + message);
		}
	}

	private TrackJunction nextFrogInWindow(TrackRegistry registry, TrackSpline spline) {
		return nextFrogAhead(registry, spline, Cache.trackJunctionArmDistance);
	}

	private TrackJunction nextFrogAhead(TrackRegistry registry, TrackSpline spline, double maxAhead) {
		TrackJunction best = null;
		double bestAhead = Double.POSITIVE_INFINITY;
		for (TrackJunction junction : registry.junctionsOn(splineId)) {
			if (junction.branchSplineId == null) {
				continue;
			}
			if (!TrackJunctionTravel.facing(travelSign, junction.facingSign)) {
				continue;
			}
			double ahead = TrackJunctionTravel.ahead(
					s, junction.s, travelSign, spline.isLoop(), spline.length());
			if (ahead < -1e-9 || ahead > maxAhead) {
				continue;
			}
			if (ahead < bestAhead) {
				bestAhead = ahead;
				best = junction;
			}
		}
		return best;
	}

	private void clearArm() {
		armedJunctionId = null;
		armedSide = null;
	}

	private static String blankToNull(String uuid) {
		if (uuid == null || uuid.isBlank()) {
			return null;
		}
		return uuid;
	}
	
	public int getTravelSign() {
		return travelSign;
	}

	public boolean hasInstalledTape() {
		return installedTape != null && !installedTape.isEmpty();
	}

	public ThrottleTape getInstalledTape() {
		return installedTape;
	}

	public void setInstalledTape(ThrottleTape tape) {
		installedTape = tape;
		tapeDwell.left = 0;
		tapeDwell.atS = null;
		clearRecording();
		RecorderLog.append("TAPE_INSTALL samples=" + (tape == null || tape.isEmpty() ? 0 : tape.getSamples().size())
				+ " " + RecorderLog.train(v));
	}

	public boolean isRecording() {
		return recording;
	}

	public int recordingSampleCount() {
		return recordingTape == null ? 0 : recordingTape.getSamples().size();
	}

	public boolean canRecordCircuit() {
		TrackSpline spline = boundSpline();
		return spline != null && spline.isLoop();
	}

	public void startRecording(Player player) {
		TrackSpline spline = boundSpline();
		if (spline == null || !spline.isLoop()) {
			RecorderLog.append("RECORD_START_FAIL not-circuit " + RecorderLog.train(v));
			return;
		}
		recording = true;
		recordingTape = new ThrottleTape(splineId.toString());
		recordingPlayer = player == null ? null : player.getUniqueId();
		recordPrevS = s;
		recordPrevSpline = splineId;
		recordTraveled = 0;
		recordLength = spline.length();
		int throttle = v != null && v.getThrottle() != null ? v.getThrottle().getCurrent() : 0;
		recordingTape.tryAppend(s, travelSign, throttle, splineId.toString(), null);
		RecorderLog.append("RECORD_START player=" + (player == null ? "none" : player.getName())
				+ " length=" + recordLength + " " + RecorderLog.train(v));
	}

	public void stopRecording(ItemStack hand) {
		RecorderLog.append("RECORD_STOP samples=" + recordingSampleCount() + " traveled=" + recordTraveled
				+ " " + RecorderLog.train(v));
		if (recordingTape != null && hand != null) {
			ThrottleTapeItems.write(hand, recordingTape);
		}
		clearRecording();
	}

	public void maybeRecordSample(int throttle) {
		if (!recording || recordingTape == null) {
			return;
		}
		TrackSpline spline = boundSpline();
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (spline == null || registry == null || !onRecordedCircuit(registry, spline)) {
			finishRecordingCappedOrUnbound("Recording stopped: locomotive left the circuit");
			return;
		}
		boolean onOrigin = recordingTape.matchesSpline(spline.getId());
		if (onOrigin && recordPrevSpline != null && recordPrevSpline.equals(spline.getId())) {
			recordTraveled += TrackLap.wrapDelta(recordPrevS, s, recordLength);
		}
		recordPrevS = s;
		recordPrevSpline = spline.getId();
		String junction = null;
		if (!onOrigin && routeJunctionId != null) {
			junction = routeJunctionId.toString();
		}
		ThrottleTape.AppendResult result = recordingTape.tryAppend(
				s, travelSign, throttle, spline.getId().toString(), junction);
		int hold = recordingTape.getSamples().isEmpty()
				? 0
				: recordingTape.getSamples().get(recordingTape.getSamples().size() - 1).holdTicks;
		RecorderLog.sample(v, result, throttle, hold);
		if (result == ThrottleTape.AppendResult.CAPPED) {
			finishRecordingCappedOrUnbound("Recording stopped: tape is full");
			return;
		}
		if (TrackLap.complete(recordTraveled, recordLength)) {
			finishRecordingCappedOrUnbound("Recording complete");
		}
	}

	private boolean onRecordedCircuit(TrackRegistry registry, TrackSpline spline) {
		if (recordingTape == null || spline == null) {
			return false;
		}
		if (recordingTape.matchesSpline(spline.getId())) {
			return true;
		}
		TrackJunction branch = registry.junctionByBranch(spline.getId()).orElse(null);
		if (branch == null) {
			return false;
		}
		return recordingTape.matchesSpline(branch.stemSplineId);
	}

	private void clearRecording() {
		recording = false;
		recordingTape = null;
		recordingPlayer = null;
		recordPrevS = 0;
		recordPrevSpline = null;
		recordTraveled = 0;
		recordLength = 0;
	}

	private void finishRecordingCappedOrUnbound(String message) {
		RecorderLog.append("RECORD_FINISH " + message + " samples=" + recordingSampleCount()
				+ " traveled=" + recordTraveled + " " + RecorderLog.train(v));
		Player player = recordingPlayer == null ? null : Bukkit.getPlayer(recordingPlayer);
		if (player != null && player.isOnline()) {
			ItemStack hand = player.getInventory().getItemInMainHand();
			if (TrackTools.isRecorder(hand) && recordingTape != null) {
				ThrottleTapeItems.write(hand, recordingTape);
			} else if (recordingTape != null && !recordingTape.isEmpty()) {
				installedTape = recordingTape;
			}
			player.sendMessage("§e" + message);
		} else if (recordingTape != null && !recordingTape.isEmpty()) {
			installedTape = recordingTape;
		}
		clearRecording();
	}

	public Integer playbackThrottle(FuelTank tank) {
		if (recording || !isBound() || !hasInstalledTape()) {
			return null;
		}
		if (tank != null && tank.useFuel() && tank.getCurrent() <= 0) {
			RecorderLog.playback(v, "no-fuel", null, tapeDwell);
			return null;
		}
		if (v != null && v.getSeatHandler() != null && v.getSeatHandler().hasCaptain()) {
			RecorderLog.playback(v, "captain", null, tapeDwell);
			return null;
		}
		int target = installedTape.targetWithDwell(s, travelSign, tapeDwell, splineId);
		RecorderLog.playback(v, "ok", target, tapeDwell);
		return target;
	}

	public boolean isBound() {
		return splineId != null;
	}

	public void unbind() {
		PersistenceLog.append("UNBIND " + PersistenceLog.vehicle(v));
		setConsistGravity(true);
		splineId = null;
		s = 0;
		travelSign = 1;
		clearArm();
		routeJunctionId = null;
		takeBranch = false;
		ActiveVehicle car = v;
		while (car != null && car.getTrainHandler().hasChild()) {
			car = car.getTrainHandler().getChild();
			car.getTrainHandler().splineId = null;
			car.getTrainHandler().s = 0;
			car.getTrainHandler().travelSign = 1;
		}
	}

	public boolean bind(TrackSpline spline) {
		if (spline == null || v == null || v.getEntity() == null) {
			return false;
		}
		Location loc = v.getEntity().getLocation();
		splineId = spline.getId();
		s = spline.nearestS(loc.getX(), loc.getY(), loc.getZ());
		travelSign = facingSign(loc, spline.sampleAt(s));
		applyPose(v, spline.sampleAt(s));
		placeLoadedCars();
		PersistenceLog.append("BIND " + PersistenceLog.vehicle(v));
		return true;
	}

	public boolean isAttachable() {
		return front != null;
	}
	
	public Connector getFront() {
		return front;
	}
	
	public boolean canHaveAttached() {
		return back != null;
	}
	
	public Connector getBack() {
		return back;
	}

	public boolean acceptsFuelCar(ActiveVehicle car) {
		return car != null && childIdAllowed(fuelCars, car.getId());
	}

	static boolean childIdAllowed(List<String> fuelCars, String childId) {
		if (fuelCars == null || fuelCars.isEmpty() || childId == null || childId.isBlank()) {
			return false;
		}
		for (String id : fuelCars) {
			if (id != null && id.equalsIgnoreCase(childId)) {
				return true;
			}
		}
		return false;
	}

	public void drainFromChild(FuelTank tank) {
		if (v == null || v.hasParent() || !hasChild() || tank == null || !tank.useFuel() || !tank.hasInput()) {
			return;
		}
		if (tank.getCurrent() >= tank.getCapacity()) {
			return;
		}
		if (!acceptsFuelCar(child) || !child.hasContainers()) {
			return;
		}
		String path = tank.getInput().getItem();
		int amount = tank.getInput().getAmount();
		for (Container c : child.getContainerHandler().getContainers().values()) {
			if (c.takeOneMatching(path) == null) {
				continue;
			}
			tank.addFuel(amount);
			return;
		}
	}

	static boolean shouldDrain(List<String> fuelCars, String childId, boolean tankHasSpace, boolean matchingItemTaken) {
		return childIdAllowed(fuelCars, childId) && tankHasSpace && matchingItemTaken;
	}
	
	public void clear() {
		if(hasChild()) child.getTrainHandler().clear();
	}
	
	public Location getOffsetPosition(ActiveVehicle parent) {
		Location loc = parent.getEntity().getLocation().clone();
		loc.add(parent.getTrainHandler().getBack().getOffset());
		loc.add(v.getTrainHandler().getFront().getOffset().multiply(-1));
		return loc;
	}

	public void animateMove(Direction dir) {
		v.getMoveControls().animateMove(dir);
    	if(hasChild()) child.getTrainHandler().animateMove(dir);
	}
	
	public void splineTick() {
		if (v.hasParent()) {
			return;
		}
		double speed = v.getAccessPanel() == null ? 0 : v.getAccessPanel().getSpeed();
		if (speed < 0) {
			clearArm();
		}
		if (TrackSplineMotion.stopped(speed)) {
			animateMove(Direction.STILL);
			if (keepBound()) {
				placeLoadedCars();
			} else {
				still();
			}
			return;
		}
		boolean reverse = v.getAccessPanel() != null && v.getAccessPanel().isReverse();
		animateMove(reverse ? Direction.BACKWARD : Direction.FORWARD);
		if (!tryBindOrKeep()) {
			still();
			return;
		}
		travelSign = speed < 0 ? -1 : 1;
		splineStep(speed);
	}

	public void placeLoadedCars() {
		PersistenceLog.placeCars(v);
		if (checkLoadedPosition && !v.hasParent()) {
			checkLoadedPosition = false;
			followTrackUnderEntity();
		}
		applyPlacements(planCars());
	}

	/**
	 * Saved {@code (spline, s)} is only valid if the track was not edited while
	 * the train was unloaded. The entity respawns where it was saved, so check
	 * that point and re-find the track under it if they disagree.
	 */
	private void followTrackUnderEntity() {
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null || splineId == null || v == null || v.getEntity() == null
				|| v.getEntity().getWorld() == null) {
			return;
		}
		Location loc = v.getEntity().getLocation();
		TrackPose at = new TrackPose(loc.getX(), loc.getY() - Cache.trackVehicleYOffset, loc.getZ(), 0, 0);
		TrackSpline current = boundSpline();
		if (current != null && onTrack(current.sampleAt(s), at)) {
			return;
		}
		TrackMatch match = nearestTrack(registry.inWorld(v.getEntity().getWorld().getName()), at, savedModelYaw());
		if (match == null) {
			PersistenceLog.append("RETRACK_LOAD none " + PersistenceLog.vehicle(v));
			unbind();
			return;
		}
		moveTo(registry, match);
	}

	private record CarPlacement(ActiveVehicle vehicle, TrackSpline spline, double s, int sign, double missingSpacing) {
		TrackPose pose() {
			return spline.sampleAt(s);
		}
	}

	private void applyPlacements(List<CarPlacement> placements) {
		for (CarPlacement placement : placements) {
			TrainHandler train = placement.vehicle.getTrainHandler();
			train.splineId = placement.spline.getId();
			train.s = placement.s;
			train.travelSign = placement.sign;
			applyPose(placement.vehicle, placement.pose());
		}
	}

	private List<CarPlacement> planCars() {
		List<CarPlacement> placements = new ArrayList<>();
		TrackSpline spline = boundSpline();
		if (spline == null) {
			return placements;
		}
		placements.add(new CarPlacement(v, spline, s, travelSign, 0));
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return placements;
		}
		TrackJunction route = routeJunction();
		UUID stemId = route == null ? null : route.stemSplineId;
		UUID branchId = route == null ? null : route.branchSplineId;
		double junctionS = route == null ? 0 : route.s;
		int facingSign = route == null ? 1 : route.facingSign;
		TrackSpline stem = stemId == null ? null : registry.get(stemId).orElse(null);
		TrackSpline branch = branchId == null ? null : registry.get(branchId).orElse(null);
		double stemLength = stem == null ? spline.length() : stem.length();
		boolean stemLoop = stem != null ? stem.isLoop() : spline.isLoop();
		double branchLength = branch == null ? 0 : branch.length();
		UUID parentSpline = splineId;
		double parentS = s;
		// Models face the +s tangent even in reverse. Couplers stay on that
		// physical side; using travelSign here swaps the cars across the loco.
		int parentPlacementSign = 1;
		ActiveVehicle parentCar = v;
		ActiveVehicle car = child;
		while (car != null) {
			TrainHandler parentTrain = parentCar.getTrainHandler();
			TrainHandler carTrain = car.getTrainHandler();
			double gap = spacing(parentTrain, carTrain);
			TrackJunctionTravel.Pose pose = TrackJunctionTravel.rewind(
					parentSpline,
					parentS,
					parentPlacementSign,
					gap,
					takeBranch && route != null,
					stemId == null ? splineId : stemId,
					branchId,
					junctionS,
					facingSign,
					stemLength,
					stemLoop,
					branchLength);
			int carTravelSign;
			int carPlacementSign = 1;
			if (branchId != null && branchId.equals(pose.splineId)) {
				carTravelSign = 1;
			} else {
				carTravelSign = travelSign;
				if (takeBranch && route != null && splineId != null && splineId.equals(branchId)) {
					carTravelSign = facingSign;
					carPlacementSign = facingSign;
				}
			}
			TrackSpline carSpline = pose.splineId == null ? null : registry.get(pose.splineId).orElse(null);
			if (carSpline != null) {
				placements.add(new CarPlacement(car, carSpline, pose.s, carTravelSign, pose.missingSpacing));
			} else {
				return List.of();
			}
			parentSpline = pose.splineId;
			parentS = pose.s;
			parentCar = car;
			parentPlacementSign = carPlacementSign;
			car = carTrain.child;
		}
		return placements;
	}

	private boolean keepBound() {
		if (splineId == null) {
			return false;
		}
		if (boundSpline() == null) {
			unbind();
			return false;
		}
		return true;
	}

	/** Moves every train on {@code old} onto the track that replaced it. */
	public static void retrackTrains(TrackSpline old, List<TrackSpline> rebuilt) {
		VehicleManager vehicles = VehicleFramework.getVehicleManager();
		if (vehicles == null) {
			return;
		}
		for (ActiveVehicle vehicle : vehicles.get().values()) {
			if (vehicle.isTrain()) {
				vehicle.getTrainHandler().retrack(old, rebuilt);
			}
		}
	}

	/**
	 * Whether any train car is bound to the given track, loaded or not. A
	 * long track can reach chunks with trains parked in them. This reads every
	 * saved vehicle, so keep it to rare edits such as joins.
	 */
	public static boolean anyTrainOn(UUID splineId) {
		if (splineId == null) {
			return false;
		}
		VehicleManager vehicles = VehicleFramework.getVehicleManager();
		if (vehicles != null) {
			for (ActiveVehicle vehicle : vehicles.get().values()) {
				if (vehicle.isTrain() && splineId.equals(vehicle.getTrainHandler().getSplineId())) {
					return true;
				}
			}
		}
		VehicleRepository repository = VehicleFramework.getVehicleRepository();
		if (repository == null) {
			return false;
		}
		String id = splineId.toString();
		JSONParser parser = new JSONParser();
		for (VehicleSnapshot snapshot : repository.listAllLive()) {
			String payload = snapshot.getPayloadJson();
			if (payload == null || !payload.contains(id)) {
				continue;
			}
			try {
				if (parser.parse(payload) instanceof JSONObject json
						&& id.equals(ConsistData.fromJson(json).getSplineId())) {
					return true;
				}
			} catch (Exception ignored) {
				// An unreadable row cannot be loaded either, so it holds no train.
			}
		}
		return false;
	}

	/**
	 * Keeps this car where it physically was after its track is rebuilt.
	 * Digging splits or trims a spline, which re-ids the far piece and shifts
	 * arc lengths; the stale {@code s} would otherwise teleport the train.
	 * If none of the rebuilt splines passes under the car it is left alone,
	 * and unbinds on its next tick if its spline is gone.
	 */
	public void retrack(TrackSpline old, List<TrackSpline> rebuilt) {
		if (splineId == null || old == null || rebuilt == null || !splineId.equals(old.getId())) {
			return;
		}
		TrackMatch match = nearestTrack(rebuilt, old.sampleAt(s), null);
		if (match != null) {
			moveTo(VehicleFramework.getTrackRegistry(), match);
		}
	}

	private record TrackMatch(TrackSpline spline, double s) {
	}

	/**
	 * The closest point on these tracks that counts as the same place,
	 * preferring the current track. With {@code facing}, only track whose +s
	 * runs the way the model faces qualifies; the consist cannot face -s.
	 */
	private TrackMatch nearestTrack(Collection<TrackSpline> candidates, TrackPose was, Float facing) {
		TrackMatch best = null;
		double bestD = Double.POSITIVE_INFINITY;
		for (TrackSpline candidate : candidates) {
			double candidateS = candidate.nearestS(was.x, was.y, was.z);
			TrackPose at = candidate.sampleAt(candidateS);
			if (!onTrack(at, was) || (facing != null && !facesAlong(facing, at))) {
				continue;
			}
			double d = Math.pow(at.x - was.x, 2) + Math.pow(at.y - was.y, 2) + Math.pow(at.z - was.z, 2);
			boolean tie = Math.abs(d - bestD) <= 1e-9;
			if (d < bestD - 1e-9 || (tie && candidate.getId().equals(splineId))) {
				best = new TrackMatch(candidate, candidateS);
				bestD = d;
			}
		}
		return best;
	}

	/**
	 * World yaw the model faced when saved. applyPose turns the bone to the
	 * track's +s heading relative to the entity, so undo that. Null without a rotator.
	 */
	private Float savedModelYaw() {
		if (v == null || v.getEntity() == null || v.getBehaviourHandler() == null) {
			return null;
		}
		BoneRotator rotator = v.getBehaviourHandler().getRotator();
		if (rotator == null || rotator.getAnimator() == null || rotator.getAnimator().getRotation() == null) {
			return null;
		}
		float boneYaw = new ConvertedAngle(new Quaternionf(rotator.getAnimator().getRotation())).getYaw();
		return ConvertedAngle.wrapDegrees(v.getEntity().getLocation().getYaw() - boneYaw);
	}

	// Loose enough for curves and turnouts, tight enough to reject crossings and reversed track.
	static boolean facesAlong(float modelYaw, TrackPose pose) {
		float trackYaw = TrackSplineMotion.worldHeading(null, pose, 1).getYaw();
		return Math.abs(ConvertedAngle.wrapDegrees(trackYaw - modelYaw)) <= 60f;
	}

	private static boolean onTrack(TrackPose at, TrackPose was) {
		return Math.hypot(at.x - was.x, at.z - was.z) <= TrackClearance.OVERLAP_HORIZ
				&& Math.abs(at.y - was.y) <= TrackClearance.OVERLAP_VERT;
	}

	private void moveTo(TrackRegistry registry, TrackMatch match) {
		UUID target = match.spline().getId();
		if (!target.equals(splineId) && registry != null && !routeTouches(registry, target)) {
			routeJunctionId = null;
			takeBranch = false;
		}
		PersistenceLog.append("RETRACK " + PersistenceLog.vehicle(v)
				+ " from=" + splineId + "@" + s + " to=" + target + "@" + match.s());
		splineId = target;
		s = match.s();
	}

	private boolean routeTouches(TrackRegistry registry, UUID trackId) {
		if (routeJunctionId == null) {
			return false;
		}
		TrackJunction route = registry.getJunction(routeJunctionId).orElse(null);
		return route != null
				&& (trackId.equals(route.stemSplineId) || trackId.equals(route.branchSplineId));
	}

	/**
	 * Whether any car of this consist sits on any of these spans, counting
	 * each car out to its couplers.
	 */
	public boolean occupies(List<TrackRegistry.Span> spans) {
		if (spans == null || spans.isEmpty() || v == null || v.hasParent() || boundSpline() == null) {
			return false;
		}
		for (CarPlacement car : planCars()) {
			double reach = reach(car.vehicle.getTrainHandler());
			for (TrackRegistry.Span span : spans) {
				if (!car.spline.getId().equals(span.trackId())) {
					continue;
				}
				double d = Math.abs(car.s - span.centreS());
				if (car.spline.isLoop()) {
					d = Math.min(d, car.spline.length() - d);
				}
				if (d <= reach + span.halfSpan()) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean tryBindOrKeep() {
		if (keepBound()) {
			return true;
		}
		if (v == null || v.getEntity() == null || v.getEntity().getWorld() == null) {
			return false;
		}
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return false;
		}
		Location loc = v.getEntity().getLocation();
		return registry.nearest(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), Cache.trackSnapDistance)
				.map(this::bind)
				.orElse(false);
	}

	private record StepState(UUID splineId, double s, int travelSign, UUID routeJunctionId,
			boolean takeBranch, UUID armedJunctionId, TrackJunction.Side armedSide) {
	}

	private StepState stepState() {
		return new StepState(splineId, s, travelSign, routeJunctionId, takeBranch, armedJunctionId, armedSide);
	}

	private void restoreStep(StepState state) {
		splineId = state.splineId;
		s = state.s;
		travelSign = state.travelSign;
		routeJunctionId = state.routeJunctionId;
		takeBranch = state.takeBranch;
		armedJunctionId = state.armedJunctionId;
		armedSide = state.armedSide;
	}

	private void splineStep(double ds) {
		if (boundSpline() == null) {
			unbind();
			still();
			return;
		}
		// Plan short steps for every car. Commit only the last clear plan, so a
		// blocked carriage cannot leave the locomotive moving independently.
		List<CarPlacement> accepted = planCars();
		List<Runnable> afterMove = new ArrayList<>();
		int steps = Math.max(1, (int) Math.ceil(Math.abs(ds) / 0.25));
		double step = ds / steps;
		double moved = 0;
		boolean blocked = false;
		boolean trackEnd = false;
		for (int i = 0; i < steps; i++) {
			StepState before = stepState();
			TrackSpline spline = boundSpline();
			TrackAdvance advance = spline.advance(s, step);
			TrackRegistry registry = VehicleFramework.getTrackRegistry();
			List<Runnable> junctionEvents = new ArrayList<>();
			if (!applyJunctionStep(registry, spline, s, advance.s, step, junctionEvents)) {
				s = advance.s;
			}
			List<CarPlacement> next = planCars();
			if (!clearStep(accepted, next)) {
				restoreStep(before);
				blocked = true;
				trackEnd = compressesConsist(accepted, next);
				break;
			}
			trackEnd = reachesTrackEnd(accepted, next);
			accepted = next;
			afterMove.addAll(junctionEvents);
			if (splineId.equals(before.splineId) && Math.abs(s - before.s) < 1e-9) {
				blocked = true;
				trackEnd = !spline.isLoop();
				break;
			}
			moved += Math.abs(step);
			if (trackEnd || advance.stoppedAtBreak) {
				blocked = true;
				trackEnd = true;
				break;
			}
		}
		if (moved > 0) {
			applyPlacements(accepted);
			maybeClack(moved);
			afterMove.forEach(Runnable::run);
		}
		if (blocked) {
			if (trackEnd) {
				if (v.getThrottle() != null) {
					v.getThrottle().setThrottle(0);
				}
				if (v.getAccessPanel() != null) {
					v.getAccessPanel().setSpeed(0);
				}
			}
			animateMove(Direction.STILL);
			for (CarPlacement car : accepted) {
				car.vehicle.getEntity().setVelocity(new Vector(0, 0, 0));
			}
		}
	}

	private boolean reachesTrackEnd(List<CarPlacement> previous, List<CarPlacement> next) {
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		for (int i = 0; i < next.size(); i++) {
			CarPlacement from = previous.get(i);
			CarPlacement to = next.get(i);
			if (to.spline.isLoop() || !from.spline.getId().equals(to.spline.getId())) {
				continue;
			}
			if (to.s > from.s && to.s >= to.spline.length() - 1e-9) {
				return true;
			}
			// A branch's start joins the stem; it is not the end of the route.
			if (to.s < from.s && to.s <= 1e-9
					&& registry.junctionByBranch(to.spline.getId()).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean clearStep(List<CarPlacement> previous, List<CarPlacement> next) {
		if (next.isEmpty() || previous.size() != next.size() || compressesConsist(previous, next)) {
			return false;
		}
		for (int i = 0; i < next.size(); i++) {
			CarPlacement from = previous.get(i);
			CarPlacement to = next.get(i);
			if (TrainBlockCollision.blocked(to.vehicle.getEntity(), from.pose(), to.pose())) {
				return false;
			}
		}
		return true;
	}

	private boolean compressesConsist(List<CarPlacement> previous, List<CarPlacement> next) {
		double before = previous.stream().mapToDouble(CarPlacement::missingSpacing).sum();
		double after = next.stream().mapToDouble(CarPlacement::missingSpacing).sum();
		// Cars attached at a track boundary may already lack space. Allow them
		// to pull away and recover their gaps, but never compress them further.
		return after > 1e-9 && after >= before - 1e-9;
	}

	private boolean applyJunctionStep(
			TrackRegistry registry,
			TrackSpline spline,
			double from,
			double to,
			double ds,
			List<Runnable> afterMove) {
		TrackJunction asBranch = registry.junctionByBranch(splineId).orElse(null);
		if (asBranch != null && !spline.isLoop() && ds < 0 && to <= 1e-6) {
			TrackSpline stem = registry.get(asBranch.stemSplineId).orElse(null);
			if (stem == null) {
				return false;
			}
			splineId = stem.getId();
			s = asBranch.s;
			travelSign = -asBranch.facingSign;
			routeJunctionId = asBranch.id;
			takeBranch = true;
			return true;
		}
		if (asBranch != null) {
			return false;
		}
		for (TrackJunction junction : registry.junctionsOn(splineId)) {
			if (junction.branchSplineId == null) {
				continue;
			}
			if (!TrackJunctionTravel.crosses(from, to, junction.s, travelSign, spline.isLoop(), spline.length())) {
				continue;
			}
			if (!TrackJunctionTravel.facing(travelSign, junction.facingSign)) {
				continue;
			}
			TrackJunction live = registry.getJunction(junction.id).orElse(junction);
			boolean diverge = live.thrown;
			String reason = diverge ? "switch-diverge" : "switch-through";
			if (armedJunctionId != null && armedJunctionId.equals(junction.id)) {
				clearArm();
			}
			routeJunctionId = junction.id;
			takeBranch = diverge;
			String detail = reason
					+ " thrown="
				+ live.thrown
				+ " frog=" + live.side.name()
					+ " facing=" + junction.facingSign
					+ " travel=" + travelSign;
			afterMove.add(() -> {
				if (RecorderLog.throttle("junc:" + v.getUUID() + ":" + junction.id, 2000)) {
					RecorderLog.junction(v, diverge, junction.id, detail);
					if (diverge) {
						tellCaptain("Junction: diverge (" + reason.replace('-', ' ') + ")");
					} else {
						tellCaptain("Junction: through (" + reason.replace('-', ' ') + ")");
					}
				}
			});
			if (!diverge) {
				continue;
			}
			TrackSpline branch = registry.get(junction.branchSplineId).orElse(null);
			if (branch == null) {
				takeBranch = false;
				continue;
			}
			splineId = branch.getId();
			s = 0;
			travelSign = 1;
			return true;
		}
		return false;
	}

	private TrackJunction routeJunction() {
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return null;
		}
		if (routeJunctionId != null) {
			TrackJunction stored = registry.getJunction(routeJunctionId).orElse(null);
			if (stored != null) {
				return stored;
			}
		}
		if (splineId == null) {
			return null;
		}
		return registry.junctionByBranch(splineId).orElse(null);
	}

	private void maybeClack(double ds) {
		fxTraveled += Math.abs(ds);
		if (fxTraveled < Cache.trackFxSoundInterval) {
			return;
		}
		fxTraveled = 0;
		TrackSpline spline = boundSpline();
		if (spline == null || v == null || v.getEntity() == null || v.getEntity().getWorld() == null) {
			return;
		}
		TrackFx.clack(v.getEntity().getWorld(), spline.sampleAt(s));
	}

	private TrackSpline boundSpline() {
		if (splineId == null) {
			return null;
		}
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) {
			return null;
		}
		return registry.get(splineId).orElse(null);
	}

	private void applyPose(ActiveVehicle vehicle, TrackPose pose) {
		if (vehicle == null || vehicle.getEntity() == null || pose == null) {
			return;
		}
		Location loc = vehicle.getEntity().getLocation();
		Location next = loc.clone();
		next.setX(pose.x);
		next.setY(pose.y + Cache.trackVehicleYOffset);
		next.setZ(pose.z);
		Vector move = new Vector(
				next.getX() - loc.getX(),
				next.getY() - loc.getY(),
				next.getZ() - loc.getZ());
		Vector xz = move.clone();
		xz.setY(0);
		boolean idle = xz.lengthSquared() <= TrackSplineMotion.MOVE_EPS_SQ;
		ConvertedAngle world = TrackSplineMotion.worldHeading(move, pose, 1);
		Entity entity = vehicle.getEntity();
		entity.setGravity(false);
		entity.teleport(next);
		entity.setVelocity(new Vector(0, 0, 0));
		if (vehicle.getBehaviourHandler() != null) {
			BoneRotator rotator = vehicle.getBehaviourHandler().getRotator();
			if (rotator != null) {
				float boneYaw = TrackSplineMotion.boneYaw(world.getYaw(), loc.getYaw());
				rotator.rotateToTarget(
						boneYaw,
						TrackSplineMotion.bonePitch(world.getPitch()),
						0f,
						1f,
						true,
						true,
						false);
			}
		}
		RecorderLog.pose(vehicle, loc, next, pose, idle ? null : world);
		PersistenceLog.applyPose(vehicle, "spline", loc, next);
		if (!idle && vehicle.getEntity() != null && vehicle.getEntity().getWorld() != null) {
			TrackFx.crumbs(vehicle.getEntity().getWorld(), pose);
		}
	}

	private static int facingSign(Location loc, TrackPose pose) {
		if (loc == null || pose == null) {
			return 1;
		}
		Vector look = loc.getDirection().clone();
		look.setY(0);
		if (look.lengthSquared() < 1e-8) {
			return 1;
		}
		look.normalize();
		double yawRad = Math.toRadians(pose.yaw);
		Vector tangent = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
		return look.dot(tangent) < -1e-4 ? -1 : 1;
	}

	private void setConsistGravity(boolean gravity) {
		ActiveVehicle car = v;
		while (car != null) {
			if (car.getEntity() != null) {
				car.getEntity().setGravity(gravity);
			}
			if (!car.getTrainHandler().hasChild()) {
				break;
			}
			car = car.getTrainHandler().getChild();
		}
	}

	private void still() {
		if (v != null && v.getEntity() != null) {
			v.getEntity().setVelocity(new Vector(0, 0, 0));
		}
	}

	private static ActiveVehicle locoOf(ActiveVehicle car) {
		ActiveVehicle loco = car;
		while (loco != null && loco.hasParent()) {
			loco = loco.getParent();
		}
		return loco;
	}

	private static double spacing(TrainHandler parent, TrainHandler child) {
		double back = parent != null && parent.canHaveAttached() ? offsetLength(parent.getBack()) : 0;
		double front = child != null && child.isAttachable() ? offsetLength(child.getFront()) : 0;
		return TrackConsistMath.connectorSpacing(back, front);
	}

	// How far along the track a car extends from its centre to its couplers.
	private static double reach(TrainHandler car) {
		double front = car.isAttachable() ? offsetLength(car.getFront()) : 0;
		double back = car.canHaveAttached() ? offsetLength(car.getBack()) : 0;
		return Math.max(1.0, Math.max(front, back));
	}

	private static double offsetLength(Connector connector) {
		try {
			return connector.getOffset().length();
		} catch (Exception ignored) {
			// Offsets come from the live model and are unavailable until it loads.
			return 0;
		}
	}
}
