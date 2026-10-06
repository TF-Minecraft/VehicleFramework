package net.tfminecraft.vehicleframework.vehicles.handlers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
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
import net.tfminecraft.vehicleframework.tracks.TrainRoute;
import net.tfminecraft.vehicleframework.tracks.TrainRoute.Position;
import net.tfminecraft.vehicleframework.tracks.TrainBlockCollision;
import net.tfminecraft.vehicleframework.tracks.TrainSpaceHighlight;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Bogies;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Connector;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.Deck;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.DeckBody;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;

public class TrainHandler {
	private final boolean locomotive;
	private final LocomotiveOverdrive overdrive = new LocomotiveOverdrive();
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
	// The nose direction on this spline, independent of forward/reverse movement.
	private int orientation = 1;
	private final Map<UUID, Boolean> junctionRoutes = new LinkedHashMap<>();
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
	// Blocks across the wheels. With it, the move animations turn at the train's speed.
	private double wheelDiameter;
	private final List<String> wheelBones = new ArrayList<>();
	// Two-bogie carriages rest on the rail under each bogie instead of their centre.
	private Bogies bogies;
	// A roof or other surface players can walk on, and the boxes that make it solid.
	private Deck deck;
	private DeckBody deckBody;
	
	public TrainHandler(ConfigurationSection config) {
		locomotive = config.getBoolean("locomotive", false);
		wheelDiameter = Math.max(0, config.getDouble("wheel-diameter", 0));
		wheelBones.addAll(config.getStringList("wheel-bones"));
		if (config.contains("bogies")) {
			bogies = new Bogies(config.getStringList("bogies"));
		}
		if (config.contains("walkable")) {
			deck = Deck.fromConfig(config.getConfigurationSection("walkable"));
			if (deck == null) {
				VFLogger.log("Ignoring walkable in " + config.getCurrentPath()
						+ ": it needs x and z as [from, to] and a top height");
			}
		}
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
		locomotive = another.locomotive;
		wheelDiameter = another.wheelDiameter;
		wheelBones.addAll(another.wheelBones);
		this.v = v;
		if (another.bogies != null) {
			bogies = new Bogies(v, another.bogies);
		}
		deck = another.deck;
		if (deck != null) {
			deckBody = new DeckBody(v, deck);
		}
		if(another.isAttachable()) {
			front = new Connector(v, another.getFront());
		}
		if(another.canHaveAttached()) {
			back = new Connector(v, another.getBack());
		}
		travelSign = another.travelSign;
		orientation = another.orientation;
		junctionRoutes.putAll(another.junctionRoutes);
		armedJunctionId = another.armedJunctionId;
		armedSide = another.armedSide;
		routeJunctionId = another.routeJunctionId;
		takeBranch = another.takeBranch;
		fuelCars.addAll(another.fuelCars);
		if (another.installedTape != null) {
			installedTape = ThrottleTape.fromJson(another.installedTape.toJson());
		}
	}
	
	public boolean isLocomotive() {
		return locomotive;
	}

	public LocomotiveOverdrive getOverdrive() {
		return overdrive;
	}

	/** The solid boxes of this car's walkable deck, or null if it has none. */
	public DeckBody getDeckBody() {
		return deckBody;
	}

	public void removeDeck() {
		if (deckBody != null) {
			deckBody.remove();
		}
	}

	public void updateModel(ActiveModel m) {
		if (bogies != null) {
			bogies.updateModel(m);
		}
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
			p.sendMessage("§cThat vehicle is not a train");
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
		ActiveVehicle loco = locoOf(target);
		if (loco == v) {
			p.sendMessage("§cA train cannot be attached to itself or one of its own cars");
			return false;
		}
		target.getTrainHandler().setChild(v);
		v.setParent(target);
		p.sendMessage("§aConnected car to train");
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
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
			// A carriage can load before the locomotive. Recover its saved choices
			// before the first placement crosses a turnout to reconstruct the chain.
			ActiveVehicle loco = locoOf(this.v);
			if (loco != null) {
				TrainHandler root = loco.getTrainHandler();
				for (ActiveVehicle car = v; car != null; car = car.getTrainHandler().child) {
					car.getTrainHandler().junctionRoutes.forEach(root.junctionRoutes::putIfAbsent);
				}
				root.syncRoute();
			}
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
		orientation = consist.getOrientation();
		junctionRoutes.clear();
		consist.getJunctions().forEach((id, choice) -> {
			try { junctionRoutes.put(UUID.fromString(id), choice); }
			catch (IllegalArgumentException ignored) {
				// A malformed route id must not prevent loading the remaining consist.
			}
		});
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
		syncRoute();
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
		Map<String, Boolean> routes = new LinkedHashMap<>();
		junctionRoutes.forEach((id, choice) -> routes.put(id.toString(), choice));
		return new ConsistData(parentId, childId, spline, arc, splineId == null ? null : travelSign,
				junction, diverge, orientation, routes);
	}

	public void holdJunction(TrackJunction.Side side) {
		if (side == null || v == null || v.hasParent() || boundSpline() == null) {
			return;
		}
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		Map<UUID, Boolean> used = new LinkedHashMap<>();
		List<CarPlacement> cars = planCars(used);
		retainRoutes(cars, used);
		int direction = controlDirection();
		Position lead = leadingPosition(cars, direction);
		TrackSpline spline = registry.get(lead.splineId()).orElse(null);
		TrackJunction next = null;
		double nearest = Double.POSITIVE_INFINITY;
		for (TrackJunction junction : registry.junctionsOn(lead.splineId())) {
			if (junction.branchSplineId == null || direction * lead.orientation() != junction.facingSign) {
				continue;
			}
			double ahead = TrackJunctionTravel.ahead(lead.s(), junction.s, direction * lead.orientation(),
					spline.isLoop(), spline.length());
			if (ahead >= -1e-9 && ahead < nearest) {
				next = junction;
				nearest = ahead;
			}
		}
		if (next == null || nearest > Cache.trackJunctionArmDistance) {
			long now = System.currentTimeMillis();
			if (now - lastNoFrogChatMs >= 1500) {
				lastNoFrogChatMs = now;
				tellCaptain(next == null ? "Junction: no facing turnout ahead of the leading car"
						: "Junction: press A/D within " + (int) Cache.trackJunctionArmDistance
						+ " of the leading wheels reaching the turnout");
			}
			return;
		}
		boolean diverge = next.side == side;
		if ((junctionRoutes.containsKey(next.id) || registry.junctionOccupied(next.id)) && next.thrown != diverge) {
			if (System.currentTimeMillis() - lastNoFrogChatMs >= 1500) {
				lastNoFrogChatMs = System.currentTimeMillis();
				tellCaptain("Junction: points locked until the whole train clears");
			}
			return;
		}
		boolean first = !next.id.equals(armedJunctionId);
		boolean changed = registry.setThrown(next.id, diverge);
		armedJunctionId = next.id;
		armedSide = side;
		if (first || changed) {
			tellArm(registry.getJunction(next.id).orElse(next), nearest, diverge ? "diverge" : "through");
		}
	}

	// Actual velocity wins while braking/coasting; throttle only chooses direction at rest.
	private int controlDirection() {
		double speed = v.getAccessPanel() == null ? 0 : v.getAccessPanel().getSpeed();
		if (!TrackSplineMotion.stopped(speed)) {
			return speed < 0 ? -1 : 1;
		}
		int throttle = v.getThrottle() == null ? 0 : v.getThrottle().getCurrent();
		return throttle == 0 ? travelSign * orientation : throttle < 0 ? -1 : 1;
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

	public int getOrientation() {
		return orientation;
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
		recordingTape.tryAppend(s, travelSign, throttle, splineId.toString(), null, orientation);
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
		String junction = onOrigin ? null : registry.junctionByBranch(splineId)
				.map(branch -> branch.id.toString()).orElse(null);
		ThrottleTape.AppendResult result = recordingTape.tryAppend(
				s, travelSign, throttle, spline.getId().toString(), junction, orientation);
		int hold = recordingTape.getSamples().get(recordingTape.getSamples().size() - 1).holdTicks;
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
		int target = installedTape.targetWithDwell(s, travelSign, tapeDwell, splineId, orientation);
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
		orientation = 1;
		junctionRoutes.clear();
		clearArm();
		routeJunctionId = null;
		takeBranch = false;
		ActiveVehicle car = v;
		while (car != null && car.getTrainHandler().hasChild()) {
			car = car.getTrainHandler().getChild();
			car.getTrainHandler().splineId = null;
			car.getTrainHandler().s = 0;
			car.getTrainHandler().travelSign = 1;
			car.getTrainHandler().orientation = 1;
			car.getTrainHandler().junctionRoutes.clear();
		}
	}

	public boolean bind(TrackSpline spline) {
		if (spline == null || v == null || v.getEntity() == null) {
			return false;
		}
		Location loc = v.getEntity().getLocation();
		splineId = spline.getId();
		s = spline.nearestS(loc.getX(), loc.getY(), loc.getZ());
		orientation = 1;
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
		animateMove(dir, 0);
	}

	/**
	 * Plays the move animation on every car. With a speed in blocks per tick, cars with a
	 * wheel diameter turn their wheels to match it; the forward and backward animations
	 * must each contain one mirrored wheel turn.
	 */
	public void animateMove(Direction dir, double speed) {
		if (wheelDiameter > 0) {
			v.getAnimationHandler().animateWheels(dir,
					dir == Direction.STILL ? 0 : wheelTurnsPerSecond(speed, wheelDiameter));
		} else {
			v.getMoveControls().animateMove(dir);
		}
		if (hasChild()) {
			child.getTrainHandler().animateMove(dir, speed);
		}
	}

	static double wheelTurnsPerSecond(double blocksPerTick, double wheelDiameter) {
		return Math.abs(blocksPerTick) * 20 / (Math.PI * wheelDiameter);
	}
	
	public void splineTick() {
		if (v.hasParent()) {
			return;
		}
		double speed = v.getAccessPanel() == null ? 0 : v.getAccessPanel().getSpeed();
		if (TrackSplineMotion.stopped(speed)) {
			animateMove(Direction.STILL);
			if (keepBound()) {
				placeLoadedCars();
			} else {
				still();
			}
			return;
		}
		// Follow how the train is moving, not the throttle: slowing down with the throttle
		// below zero still rolls forward, and coasting at zero can roll backwards.
		animateMove(speed < 0 ? Direction.BACKWARD : Direction.FORWARD, speed);
		if (!tryBindOrKeep()) {
			still();
			return;
		}
		travelSign = (speed < 0 ? -1 : 1) * orientation;
		splineStep(speed);
	}

	public void placeLoadedCars() {
		PersistenceLog.placeCars(v);
		if (checkLoadedPosition && !v.hasParent()) {
			checkLoadedPosition = false;
			followTrackUnderEntity();
		}
		Map<UUID, Boolean> used = new LinkedHashMap<>();
		List<CarPlacement> placements = planCars(used);
		retainRoutes(placements, used);
		applyPlacements(placements);
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
		Float facing = savedModelYaw();
		if (current != null) {
			TrackPose saved = current.sampleAt(s);
			if (onTrack(saved, at) && (facing == null || facesAlong(facing, TrainRoute.facing(saved, orientation)))) {
				return;
			}
		}
		TrackMatch match = nearestTrack(registry.inWorld(v.getEntity().getWorld().getName()), at, facing);
		if (match == null) {
			PersistenceLog.append("RETRACK_LOAD none " + PersistenceLog.vehicle(v));
			unbind();
			return;
		}
		moveTo(registry, match);
	}

	private record CarPlacement(ActiveVehicle vehicle, TrackSpline spline, double s, int sign, int orientation,
			double missingSpacing, TrackPose pose, TrackPose[] bogieRails, double[] missingRail) {
		Position position() { return new Position(spline.getId(), s, orientation); }
	}

	private Position position() { return new Position(splineId, s, orientation); }

	private TrainRoute route() { return new TrainRoute(VehicleFramework.getTrackRegistry(), junctionRoutes); }

	// Model offsets remain in the body frame even when the body faces -s.
	private double[] supportOffsets() {
		List<Double> offsets = new ArrayList<>();
		if (onBogies()) {
			for (double offset : bogies.offsets()) { offsets.add(offset); }
		}
		for (String name : wheelBones) {
			try {
				ActiveModel model = v.getModel();
				offsets.add((double) model.getBone(name).orElseThrow().getBlueprintBone()
						.getRotatedGlobalPosition().z() * model.getScale().z());
			} catch (RuntimeException notLoaded) { }
		}
		return offsets.isEmpty() ? new double[] {0} : offsets.stream().mapToDouble(Double::doubleValue).toArray();
	}

	private TrackPose[] bogieRails(TrainRoute route, Position at) {
		if (!onBogies()) { return null; }
		double[] offsets = bogies.offsets();
		TrackPose[] rails = new TrackPose[offsets.length];
		for (int i = 0; i < offsets.length; i++) {
			rails[i] = route.rail(at, offsets[i]);
			if (rails[i] == null) { return null; }
		}
		return rails;
	}

	private double[] missingWheelRail(TrainRoute route, Position at, Map<UUID, Boolean> used) {
		double[] missing = new double[2];
		for (double offset : supportOffsets()) {
			TrainRoute.Walk walked = route.walk(at, offset);
			if (used != null) { used.putAll(walked.junctions()); }
			int end = offset < 0 ? 0 : 1;
			missing[end] = Math.max(missing[end], walked.missing());
		}
		return missing;
	}

	private boolean onBogies() { return bogies != null && bogies.isReady(); }

	private void applyPlacements(List<CarPlacement> placements) {
		for (CarPlacement placement : placements) {
			TrainHandler train = placement.vehicle.getTrainHandler();
			train.splineId = placement.spline.getId();
			train.s = placement.s;
			train.travelSign = placement.sign;
			train.orientation = placement.orientation;
			if (train != this) {
				train.junctionRoutes.clear();
				train.junctionRoutes.putAll(junctionRoutes);
				train.syncRoute();
			}
			applyPose(placement.vehicle, placement.pose());
			if (placement.bogieRails() != null) {
				train.bogies.follow(placement.bogieRails(), placement.pose());
			}
		}
	}

	private List<CarPlacement> planCars() { return planCars(null); }

	private List<CarPlacement> planCars(Map<UUID, Boolean> used) {
		List<CarPlacement> placements = new ArrayList<>();
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null || boundSpline() == null) { return placements; }
		TrainRoute route = route();
		Position at = position();
		ActiveVehicle car = v;
		CarPlacement parent = null;
		double missing = 0;
		int bodyTravel = travelSign * orientation;
		while (car != null) {
			TrainHandler train = car.getTrainHandler();
			TrackSpline spline = registry.get(at.splineId()).orElse(null);
			if (spline == null) { return List.of(); }
			TrackPose[] rails = train.bogieRails(route, at);
			TrackPose pose = rails == null ? TrainRoute.facing(spline.sampleAt(at.s()), at.orientation())
					: train.bogies.bodyPose(rails);
			if (parent != null && rails == null && parent.vehicle.getTrainHandler().canHaveAttached()
					&& train.isAttachable() && missing <= 1e-9) {
				try {
					pose = train.getFront().coupledPose(pose,
							parent.vehicle.getTrainHandler().getBack().positionAt(parent.pose()));
				} catch (RuntimeException notLoaded) { }
			}
			CarPlacement placement = new CarPlacement(car, spline, at.s(), bodyTravel * at.orientation(),
					at.orientation(), missing, pose, rails, train.missingWheelRail(route, at, used));
			placements.add(placement);
			parent = placement;
			car = train.child;
			if (car != null) {
				TrainRoute.Walk walk = route.walk(at, -spacing(train, car.getTrainHandler()));
				if (used != null) { used.putAll(walk.junctions()); }
				at = walk.position();
				missing = walk.missing();
			}
		}
		return placements;
	}

	private Position leadingPosition(List<CarPlacement> cars, int direction) {
		if (cars.isEmpty()) { return null; }
		CarPlacement car = direction > 0 ? cars.get(0) : cars.get(cars.size() - 1);
		double offset = 0;
		for (double wheel : car.vehicle.getTrainHandler().supportOffsets()) {
			offset = direction > 0 ? Math.max(offset, wheel) : Math.min(offset, wheel);
		}
		return route().walk(car.position(), offset).position();
	}

	/** Retain each occupied switch, including adjacent switches spanned by one consist. */
	private void retainRoutes(List<CarPlacement> cars, Map<UUID, Boolean> used) {
		TrackRegistry registry = VehicleFramework.getTrackRegistry();
		if (registry == null) { return; }
		Map<UUID, Boolean> occupied = new LinkedHashMap<>();
		// Missing cars may still straddle points. Keep their snapshot until the
		// links resolve or are explicitly removed, then trim it normally again.
		if ((pendingParent != null && !v.hasParent()) || cars.stream().anyMatch(car -> {
			TrainHandler train = car.vehicle.getTrainHandler();
			return train.pendingChild != null && train.child == null;
		})) {
			occupied.putAll(junctionRoutes);
		}
		used.forEach(occupied::putIfAbsent);
		for (CarPlacement car : cars) {
			TrainHandler train = car.vehicle.getTrainHandler();
			double low = 0;
			double high = 0;
			for (double offset : train.supportOffsets()) {
				low = Math.min(low, offset * car.orientation);
				high = Math.max(high, offset * car.orientation);
			}
			for (TrackJunction junction : registry.junctionsOn(car.spline.getId())) {
				double padding = junctionRoutes.containsKey(junction.id) ? reach(train) : 0;
				boolean ahead = TrackJunctionTravel.inArmWindow(car.s, junction.s, 1,
						car.spline.isLoop(), car.spline.length(), Math.max(high, padding) + 1e-9);
				boolean behind = TrackJunctionTravel.inArmWindow(car.s, junction.s, -1,
						car.spline.isLoop(), car.spline.length(), Math.max(-low, padding) + 1e-9);
				if (ahead || behind) {
					occupied.putIfAbsent(junction.id, junctionRoutes.getOrDefault(junction.id, false));
				}
			}
			TrackJunction branch = registry.junctionByBranch(car.spline.getId()).orElse(null);
			double padding = branch != null && junctionRoutes.containsKey(branch.id) ? reach(train) : 0;
			if (branch != null && car.s - Math.max(-low, padding) <= branch.turnoutEndS + 1e-9) {
				occupied.put(branch.id, true);
			}
		}
		junctionRoutes.clear();
		junctionRoutes.putAll(occupied);
		syncRoute();
	}

	private void syncRoute() {
		routeJunctionId = junctionRoutes.isEmpty() ? null : junctionRoutes.keySet().iterator().next();
		takeBranch = routeJunctionId != null && junctionRoutes.get(routeJunctionId);
	}

	public boolean holdsJunction(UUID id) {
		return v != null && !v.hasParent() && boundSpline() != null && junctionRoutes.containsKey(id);
	}

	public static boolean junctionOccupied(UUID id) {
		VehicleManager vehicles = VehicleFramework.getVehicleManager();
		if (vehicles != null) {
			for (ActiveVehicle vehicle : vehicles.get().values()) {
				if (vehicle.isTrain() && vehicle.getTrainHandler().holdsJunction(id)) { return true; }
			}
		}
		return false;
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
	 * preferring the current track. With {@code facing}, the saved body orientation
	 * must still match the rail; reversing an unloaded train's track is rejected.
	 */
	private TrackMatch nearestTrack(Collection<TrackSpline> candidates, TrackPose was, Float facing) {
		TrackMatch best = null;
		double bestD = Double.POSITIVE_INFINITY;
		for (TrackSpline candidate : candidates) {
			double candidateS = candidate.nearestS(was.x, was.y, was.z);
			TrackPose at = candidate.sampleAt(candidateS);
			if (!onTrack(at, was) || (facing != null && !facesAlong(facing, TrainRoute.facing(at, orientation)))) {
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
	 * body's heading relative to the entity, so undo that. Null without a rotator.
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
			junctionRoutes.clear();
		}
		PersistenceLog.append("RETRACK " + PersistenceLog.vehicle(v)
				+ " from=" + splineId + "@" + s + " to=" + target + "@" + match.s());
		splineId = target;
		s = match.s();
	}

	private boolean routeTouches(TrackRegistry registry, UUID trackId) {
		for (UUID id : junctionRoutes.keySet()) {
			TrackJunction route = registry.getJunction(id).orElse(null);
			if (route != null && (trackId.equals(route.stemSplineId) || trackId.equals(route.branchSplineId))) {
				return true;
			}
		}
		return false;
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

	private static final long STOPPED_SHOW_MS = 2000;
	private long stoppedShownMs;

	private record StepState(UUID splineId, double s, int travelSign, int orientation,
			Map<UUID, Boolean> routes, UUID armedJunctionId, TrackJunction.Side armedSide) { }

	private StepState stepState() {
		return new StepState(splineId, s, travelSign, orientation, new LinkedHashMap<>(junctionRoutes),
				armedJunctionId, armedSide);
	}

	private void restoreStep(StepState state) {
		splineId = state.splineId;
		s = state.s;
		travelSign = state.travelSign;
		orientation = state.orientation;
		junctionRoutes.clear();
		junctionRoutes.putAll(state.routes);
		syncRoute();
		armedJunctionId = state.armedJunctionId;
		armedSide = state.armedSide;
	}

	private void splineStep(double distance) {
		if (boundSpline() == null) { unbind(); still(); return; }
		Map<UUID, Boolean> used = new LinkedHashMap<>();
		List<CarPlacement> accepted = planCars(used);
		retainRoutes(accepted, used);
		List<Runnable> afterMove = new ArrayList<>();
		Map<Long, List<BoundingBox>> shapes = new HashMap<>();
		int steps = Math.max(1, (int) Math.ceil(Math.abs(distance) / 0.25));
		double step = distance / steps;
		int direction = distance < 0 ? -1 : 1;
		double moved = 0;
		boolean blocked = false;
		boolean trackEnd = false;
		for (int i = 0; i < steps; i++) {
			StepState before = stepState();
			Position lead = leadingPosition(accepted, direction);
			TrainRoute.Walk leading = route().walk(lead, step, true, false);
			leading.junctions().forEach(junctionRoutes::putIfAbsent);
			TrainRoute.Walk advance = route().walk(position(), step, false, true);
			advance.junctions().forEach(junctionRoutes::putIfAbsent);
			splineId = advance.position().splineId();
			s = advance.position().s();
			orientation = advance.position().orientation();
			travelSign = direction * orientation;
			syncRoute();
			used.clear();
			List<CarPlacement> next = planCars(used);
			if (!clearStep(accepted, next, shapes)) {
				restoreStep(before);
				blocked = true;
				trackEnd = compressesConsist(accepted, next) || losesWheelSupport(accepted, next);
				break;
			}
			for (Map.Entry<UUID, Boolean> entry : junctionRoutes.entrySet()) {
				if (!before.routes.containsKey(entry.getKey())) {
					UUID id = entry.getKey();
					boolean diverge = entry.getValue();
					afterMove.add(() -> RecorderLog.junction(v, diverge, id, "leading-wheels"));
					if (id.equals(armedJunctionId)) { clearArm(); }
				}
			}
			trackEnd = reachesTrackEnd(accepted, next) || advance.missing() > 1e-9 || advance.broken();
			accepted = next;
			retainRoutes(accepted, used);
			moved += Math.max(0, Math.abs(step) - advance.missing());
			if (trackEnd) { blocked = true; break; }
		}
		if (moved > 0) {
			applyPlacements(accepted);
			maybeClack(moved);
			afterMove.forEach(Runnable::run);
		}
		if (blocked) {
			if (trackEnd) {
				if (v.getThrottle() != null) { v.getThrottle().setThrottle(0); }
				if (v.getAccessPanel() != null) { v.getAccessPanel().setSpeed(0); }
			}
			animateMove(Direction.STILL);
			for (CarPlacement car : accepted) { car.vehicle.getEntity().setVelocity(new Vector(0, 0, 0)); }
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

	private boolean clearStep(List<CarPlacement> previous, List<CarPlacement> next,
			Map<Long, List<BoundingBox>> shapes) {
		if (next.isEmpty() || previous.size() != next.size() || compressesConsist(previous, next)
				|| losesWheelSupport(previous, next)) {
			return false;
		}
		for (int i = 0; i < next.size(); i++) {
			CarPlacement from = previous.get(i);
			CarPlacement to = next.get(i);
			if (TrainBlockCollision.blocked(to.vehicle.getEntity(), from.spline, from.s,
					to.spline, to.s, reach(to.vehicle.getTrainHandler()), shapes)) {
				showWhatStopped(from, to, shapes);
				return false;
			}
		}
		return true;
	}

	// Outlines the blocks that stopped the train for the players riding the locomotive.
	private void showWhatStopped(CarPlacement from, CarPlacement to, Map<Long, List<BoundingBox>> shapes) {
		long now = System.currentTimeMillis();
		if (now - stoppedShownMs < STOPPED_SHOW_MS || v.getSeatHandler() == null) {
			return;
		}
		List<Player> riders = new ArrayList<>();
		for (Entity passenger : v.getSeatHandler().getPassengers()) {
			if (passenger instanceof Player player) {
				riders.add(player);
			}
		}
		if (riders.isEmpty()) {
			return;
		}
		stoppedShownMs = now;
		List<TrainBlockCollision.Obstruction> found = TrainBlockCollision.blockers(to.vehicle.getEntity(),
				from.spline, from.s, to.spline, to.s, reach(to.vehicle.getTrainHandler()), shapes);
		if (found.isEmpty()) {
			return;
		}
		TrainBlockCollision.Obstruction first = found.get(0);
		String message = "Blocked at " + first.x() + ", " + first.y() + ", " + first.z()
				+ (found.size() > 1 ? " and " + (found.size() - 1) + " more" : "");
		for (Player rider : riders) {
			TrainSpaceHighlight.show(rider, found);
			rider.sendActionBar(Component.text(message, NamedTextColor.RED));
		}
	}

	private boolean losesWheelSupport(List<CarPlacement> previous, List<CarPlacement> next) {
		for (int i = 0; i < Math.min(previous.size(), next.size()); i++) {
			// A loaded or newly attached car may already overhang. Let it recover,
			// including while clamped cars ahead of it regain their coupling gaps.
			for (int end = 0; end < 2; end++) {
				if (next.get(i).missingRail[end] > previous.get(i).missingRail[end] + 1e-9) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean compressesConsist(List<CarPlacement> previous, List<CarPlacement> next) {
		double before = previous.stream().mapToDouble(CarPlacement::missingSpacing).sum();
		double after = next.stream().mapToDouble(CarPlacement::missingSpacing).sum();
		// Cars attached at a track boundary may already lack space. Allow them
		// to pull away and recover their gaps, but never compress them further.
		return after > 1e-9 && after >= before - 1e-9;
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
		DeckBody deckAt = vehicle.getTrainHandler().getDeckBody();
		if (deckAt != null) {
			deckAt.place(new Deck.Frame(next.getX(), next.getY(), next.getZ(), pose.yaw, pose.pitch));
		}
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
			return connector.getTrackReach();
		} catch (Exception ignored) {
			// Connector blueprints are unavailable until the model loads.
			return 0;
		}
	}
}
