package net.tfminecraft.vehicleframework.vehicles.controller;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.bones.VectorBone;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.interfaces.MovementInterface;
import net.tfminecraft.vehicleframework.managers.InventoryManager;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.tracks.TrackJunction;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Balloon;
import net.tfminecraft.vehicleframework.vehicles.component.Harness;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;
import net.tfminecraft.vehicleframework.vehicles.handlers.TowHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.TerrainFollowConfig;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.state.VehicleState;

public class VehicleMovementController implements MovementInterface{
	protected VehicleState state;
	
	protected ActiveVehicle v;
	protected BoneRotator rotator;
	protected VectorBone vector;
	protected VehicleManager vehicleManager;
	//protected WeaponManager weaponManager;
	protected InventoryManager inv = new InventoryManager();
	protected Entity e;
	
	//Methods distributed to subcontrollers to avoid one massive class
	protected BaseController baseController = new BaseController();
	protected LiftController liftController = new LiftController();
	protected FloatController floatController = new FloatController();
	protected ThrottleController throttleController = new ThrottleController();
	protected RotateController rotateController = new RotateController();
	
	
	
	public VehicleMovementController(ActiveVehicle v, VehicleState state) {
		rotator = v.getBehaviourHandler().getRotator();
		vector = v.getBehaviourHandler().getVector();
		this.state = state;
		this.v = v;
		this.e = v.getEntity();
		vehicleManager = v.getVehicleManager();
	}
	public void input(Player p, Input i) {
		switch(i) {
			case THROTTLE_UP:
				throttleUp(p);
				break;
			case THROTTLE_DOWN:
				throttleDown(p);
				break;
			case TURN_LEFT:
				turnLeft(p);
				break;
			case TURN_RIGHT:
				turnRight(p);
				break;
			case TURN_LEFT_LOCAL:
				turnLeftLocal(p);
				break;
			case TURN_RIGHT_LOCAL:
				turnRightLocal(p);
				break;
			case JUNCTION_LEFT:
				junctionHold(p, TrackJunction.Side.LEFT);
				break;
			case JUNCTION_RIGHT:
				junctionHold(p, TrackJunction.Side.RIGHT);
				break;
			case SEAT_SELECTION:
				seatSelection(p);
				break;
			case MOVE:
				move();
				break;
			case PITCH_UP:
				pitchUp(p);
				break;
			case PITCH_DOWN:
				pitchDown(p);
				break;
			case ROLL_LEFT:
				rollLeft(p);
				break;
			case ROLL_RIGHT:
				rollRight(p);
				break;
			case FORWARD:
				forward(p);
				break;
			case BACKWARD:
				backward(p);
				break;
			case LIGHTS:
				v.toggleLights(p);
				break;
			case HORN:
				if(v.getSeat(p).getType().equals(SeatType.CAPTAIN)) v.honk(p);
				break;
			case UP:
				up(p);
				break;
			case DOWN:
				down(p);
				break;
			default:
				break;
			
		}
	}
	
	public void update(ActiveVehicle v) {
		rotator = v.getBehaviourHandler().getRotator();
		vector = v.getBehaviourHandler().getVector();
	}

	private void throttleUp(Player p) {
		throttleController.throttle(v, p, false);
	}

	private void throttleDown(Player p) {
		throttleController.throttle(v, p, true);
	}
	
	private void turnLeft(Player p) {
		rotateController.turnLeft(rotator, v, p);
	}

	private void turnRight(Player p) {
		rotateController.turnRight(rotator, v, p);
	}
	
	private void turnLeftLocal(Player p) {
		rotateController.turnLeftLocal(rotator, v, p);
	}

	private void turnRightLocal(Player p) {
		rotateController.turnRightLocal(rotator, v, p);
	}

	private void junctionHold(Player p, TrackJunction.Side side) {
		if (p == null || !v.isTrain()) {
			return;
		}
		if (v.getSeatHandler() == null || !v.getSeatHandler().isCaptain(p)) {
			return;
		}
		if (v.hasParent()) {
			return;
		}
		v.getTrainHandler().holdJunction(side);
	}

	private void pitchUp(Player p) {
		rotateController.pitchUp(rotator, v, p, getPitchRollRate());
	}

	private void pitchDown(Player p) {
		rotateController.pitchDown(rotator, v, p, getPitchRollRate());
	}
	private void rollLeft(Player p) {
		rotateController.rollLeft(rotator, v, p, getPitchRollRate());
	}

	private void rollRight(Player p) {
		rotateController.rollRight(rotator, v, p, getPitchRollRate());
	}
	 
	private float getPitchRollRate() {
		float rate = 0;
		if(v.hasComponent(Component.WINGS)) {
			Wings wings = (Wings) v.getComponent(Component.WINGS);
			rate = wings.getTurnRate();
		}
		return rate;
	}

	private void seatSelection(Player p) {
        try {
            inv.seatSelection(null, p, v, true); // Trigger inventory action
        } catch (Exception ex) {
            p.sendMessage("An error occurred while opening inventory: " + ex.getMessage());
            ex.printStackTrace();
        }
		
	}
	
	private void forward(Player p) {
		if(!v.getSeat(p).getType().equals(SeatType.CAPTAIN)) return;
		if (state.isBreakState()) {
			zeroBreakVelocity();
			return;
		}
		if(baseController.getDirection(v).equals(Direction.STILL)) return;
		if (applyTerrainFollow(Direction.FORWARD)) {
			return;
		}
		Vector velocity = getSimpleMovements(Direction.FORWARD);
		apply(velocity, Direction.FORWARD);
	}
	private void backward(Player p) {
		if(!v.getSeat(p).getType().equals(SeatType.CAPTAIN)) return;
		if (state.isBreakState()) {
			zeroBreakVelocity();
			return;
		}
		if(baseController.getDirection(v).equals(Direction.STILL)) return;
		if (applyTerrainFollow(Direction.BACKWARD)) {
			return;
		}
		Vector velocity = getSimpleMovements(Direction.BACKWARD);
		apply(velocity, Direction.BACKWARD);
	}

	private void move() {
		if(v.hasParent()) return;
		if (state.isBreakState()) {
			zeroBreakVelocity();
			return;
		}
		if (applyTerrainFollow(baseController.getDirection(v))) {
			return;
		}
		liftController.checkHitWall(v);
		Vector velocity = getSimpleMovements(Direction.STILL);
		if(v.shouldFloat()) velocity = floatController.calculateFloat(v, velocity);
		if(v.hasComponent(Component.WINGS) || v.hasComponent(Component.BALLOON)) velocity= liftController.calculateLift(rotator, v, velocity);
		apply(velocity, baseController.getDirection(v));
		if (!v.isTrain()) {
			rotator.rotateSmoothed(0, 0, 0);
		}
	}

	private void up(Player p) {
		if(!v.getSeat(p).getType().equals(SeatType.CAPTAIN)) return;
		if (!v.hasComponent(Component.BALLOON)) return;
		Balloon balloon = (Balloon) v.getComponent(Component.BALLOON);
		double lift = balloon.getLift();
		if (lift < 0) return;
		Vector velocity = v.getEntity().getVelocity();
		velocity.setY(lift);
		v.getEntity().setVelocity(velocity);
		balloon.setDelta(lift); // Set delta instead of directly setting velocity
	}

	private void down(Player p) {
		if(!v.getSeat(p).getType().equals(SeatType.CAPTAIN)) return;
		if (!v.hasComponent(Component.BALLOON)) return;
		Balloon balloon = (Balloon) v.getComponent(Component.BALLOON);
		double lift = balloon.getBaseLift();
		if (lift < 0) return;
		Vector velocity = v.getEntity().getVelocity();
		if(velocity.getY() < -lift) return;
		velocity.setY(-lift);
		v.getEntity().setVelocity(velocity);
		balloon.setDelta(-lift); // Set delta to negative for downward force
	}
	
	private boolean applyTerrainFollow(Direction dir) {
		if (state.isBreakState()) {
			return false;
		}
		if (state.isDefault()) {
			return false;
		}
		TerrainFollowConfig follow = state.getTerrainFollow();
		if (follow == null || !follow.isEnabled()) {
			return false;
		}
		if (v.isTrain()) {
			return false;
		}
		TerrainFollowEngine.Result result = TerrainFollowEngine.step(v, vector, dir, baseController, follow);
		if (result == null) {
			return true;
		}
		e.teleport(result.location);
		e.setVelocity(result.velocity);
		if (v.hasComponent(Component.HARNESS)) {
			((Harness) v.getComponent(Component.HARNESS)).syncMountedEntities();
		}
		if (result.tilt != null) {
			rotator.rotateToTarget(
					rotator.getDriveYaw(),
					result.tilt.pitchDeg,
					result.tilt.rollDeg,
					0.25f,
					true,
					true,
					true);
		}
		propagate(result.velocity, dir);
		if (!v.isTrain()) {
			animateMove(dir);
		}
		return true;
	}

	private Vector getSimpleMovements(Direction dir) {
		Vector velocity = baseController.calculateMoveVector(v, vector, dir);
		velocity = baseController.climbVector(v, velocity);
		velocity = flatten(velocity);
		return velocity;
	}

	private Vector flatten(Vector velocity) {
		if(!v.hasComponent(Component.WINGS)) {
			if(v.getCurrentState().getType().equals(State.FLOATING) && !v.shouldFloat()) {
				velocity.setY(0);
			}
			return velocity;
		}
		if(!v.hasComponent(Component.ENGINE)) return velocity;
		if(v.getThrottle().getCurrent() < 20 && !v.getLocation().clone().add(0, -0.5, 0).getBlock().isPassable()) velocity.setY(0);
		return velocity;
	}
	
	public void setAnimation() {
		if(v.shouldAutoMove()) return;
		if(v.hasParent()) return;
		if(e.getVelocity().length() < 0.08) {
			animateMove(Direction.STILL);
			propagate(e.getVelocity(), Direction.STILL);
		}
	}
	
	private void apply(Vector velocity, Direction dir) {
		if(state.isDefault()) return;
		if (state.isBreakState()) return;
		if(System.currentTimeMillis()-5000 > v.getSpawnTime()) {
			if(v.isTrain()) {
				v.getTrainHandler().splineTick();
			} else {
				e.setVelocity(velocity);
			}
		} else {
			e.setVelocity(velocity);
		}
		propagate(velocity, dir);
		
		if(!v.isTrain()) animateMove(dir);
	}
	
	private void propagate(Vector velocity, Direction dir) {
		if(v.hasTowHandler()) {
			TowHandler h = v.getTowHandler();
			Seat s = h.getTowPoint();
			if(s.isOccupied()) {
				h.animate(dir);
				s.getEntity().setVelocity(velocity);
			}
		}
	}
	
	public void animateMove(Direction dir) {
		if(dir.equals(Direction.STILL)) {
			v.stopAnimation(Animation.FORWARD);
			v.stopAnimation(Animation.BACKWARD);
			return;
		}
		if(dir.equals(Direction.FORWARD)) {
			v.animate(Animation.FORWARD);
		}
		if(dir.equals(Direction.BACKWARD)) {
			v.animate(Animation.BACKWARD);
		}
	}
	
	public BaseController getBaseController() {
		return baseController;
	}
	public LiftController getLiftController() {
		return liftController;
	}
	public FloatController getFloatController() {
		return floatController;
	}
	public ThrottleController getThrottleController() {
		return throttleController;
	}
	public RotateController getRotateController() {
		return rotateController;
	}

	private void zeroBreakVelocity() {
		Vector velocity = e.getVelocity();
		velocity.setX(0);
		velocity.setZ(0);
		e.setVelocity(velocity);
	}
	
	

}
