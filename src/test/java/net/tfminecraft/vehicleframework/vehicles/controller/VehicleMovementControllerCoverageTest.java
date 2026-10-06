package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4d;
import org.joml.Quaternionf;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.bones.VectorBone;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.managers.InventoryManager;
import net.tfminecraft.vehicleframework.tracks.TrackJunction;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.TowHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.TerrainFollowConfig;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.state.VehicleState;

class VehicleMovementControllerCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
    private final LivingEntity entity = mock(LivingEntity.class);
    private final World world = mock(World.class);
    private final Block ground = mock(Block.class);
    private final Player player = mock(Player.class);
    private final VehicleState state = mock(VehicleState.class);
    private final BoneRotator rotator = mock(BoneRotator.class, RETURNS_DEEP_STUBS);
    private final VectorBone vector = mock(VectorBone.class);
    private final Seat seat = mock(Seat.class);
    private MockedConstruction<InventoryManager> inventories;
    private MockedConstruction<FloatController> floats;
    private MockedStatic<TerrainFollowEngine> terrain;
    private VehicleMovementController controller;
    private Vector velocity = new Vector(.4, .3, .2);
    private Location location;

    @BeforeEach
    void setup() {
        inventories = keep(mockConstruction(InventoryManager.class));
        floats = keep(mockConstruction(FloatController.class, (mock, context) ->
                when(mock.calculateFloat(any(), any())).thenAnswer(call -> ((Vector) call.getArgument(1)).clone().setY(.1))));
        terrain = keep(mockStatic(TerrainFollowEngine.class));
        location = new Location(world, 8, 64, 8);
        when(vehicle.getEntity()).thenReturn(entity);
        when(vehicle.getCurrentState()).thenReturn(state);
        when(vehicle.getStateHandler().getCurrentState()).thenReturn(state);
        when(vehicle.getBehaviourHandler().getRotator()).thenReturn(rotator);
        when(vehicle.getBehaviourHandler().getVector()).thenReturn(vector);
        when(vehicle.getSeat(player)).thenReturn(seat);
        when(seat.getType()).thenReturn(SeatType.CAPTAIN);
        when(vehicle.getLocation()).thenAnswer(call -> location.clone());
        when(vehicle.getSpawnTime()).thenReturn(System.currentTimeMillis() - 20_000);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.4);
        when(vehicle.getAccessPanel().getTurnRate()).thenReturn(2d);
        when(vehicle.getThrottle().getCurrent()).thenReturn(25);
        when(state.getType()).thenReturn(State.GROUND);
        when(state.getTerrainFollow()).thenReturn(TerrainFollowConfig.disabled());
        when(entity.isValid()).thenReturn(true);
        when(entity.getVelocity()).thenAnswer(call -> velocity.clone());
        doAnswer(call -> { velocity = ((Vector) call.getArgument(0)).clone(); return null; }).when(entity).setVelocity(any(Vector.class));
        when(entity.getLocation()).thenAnswer(call -> location.clone());
        when(entity.getWorld()).thenReturn(world);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(7.5, 64, 7.5, 8.5, 66, 8.5));
        when(entity.teleport(any(Location.class))).thenAnswer(call -> { location = ((Location) call.getArgument(0)).clone(); return true; });
        when(world.getBlockAt(any(Location.class))).thenReturn(ground);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(ground);
        when(ground.isPassable()).thenReturn(true);
        when(vector.getVector()).thenAnswer(call -> new Vector(1, 0, 0));
        when(rotator.getAnimator().getRotation()).thenReturn(new Quaternionf());
        when(rotator.getAngles()).thenReturn(new AxisAngle4d());
        when(rotator.getDriveYaw()).thenReturn(35f);
        controller = new VehicleMovementController(vehicle, state);
        clearInvocations(vehicle, entity, rotator, state);
    }

    @AfterEach
    void teardown() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
    }

    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private <T extends VehicleComponent> T component(Component type, Class<T> clazz) {
        T component = mock(clazz, RETURNS_DEEP_STUBS);
        when(vehicle.hasComponent(type)).thenReturn(true);
        when(vehicle.getComponent(type)).thenReturn(component);
        return component;
    }
    private TerrainFollowConfig follow() {
        TerrainFollowConfig config = new TerrainFollowConfig(true, 1, .25, 3, 1, .08, .98, List.of());
        when(state.getTerrainFollow()).thenReturn(config);
        return config;
    }
    private void terrainResult(TerrainFollowEngine.Result result) {
        terrain.when(() -> TerrainFollowEngine.step(eq(vehicle), any(VectorBone.class), any(Direction.class),
                any(BaseController.class), any(TerrainFollowConfig.class))).thenReturn(result);
    }

    @Test
    void exposesControllersAndRefreshesModelVectorsAndRotator() {
        assertInstanceOf(BaseController.class, controller.getBaseController());
        assertInstanceOf(LiftController.class, controller.getLiftController());
        assertSame(floats.constructed().getFirst(), controller.getFloatController());
        assertInstanceOf(ThrottleController.class, controller.getThrottleController());
        assertInstanceOf(RotateController.class, controller.getRotateController());
        BoneRotator replacement = mock(BoneRotator.class);
        VectorBone replacementVector = mock(VectorBone.class);
        when(replacementVector.getVector()).thenReturn(new Vector(0, 0, 1));
        when(vehicle.getBehaviourHandler().getRotator()).thenReturn(replacement);
        when(vehicle.getBehaviourHandler().getVector()).thenReturn(replacementVector);
        controller.update(vehicle);
        Wings wings = component(Component.WINGS, Wings.class);
        when(wings.getTurnRate()).thenReturn(3f);
        controller.input(player, Input.PITCH_UP);
        verify(replacement).rotateSmoothed(-3, 0, 0);
        component(Component.ENGINE, Engine.class);
        controller.input(player, Input.FORWARD);
        assertEquals(new Vector(0, 0, .4), velocity);
    }

    @Test
    void throttleInputsChangeOrdinaryEngineAndStartItBeforeChangingThrottle() {
        Engine engine = component(Component.ENGINE, Engine.class);
        controller.input(player, Input.THROTTLE_UP);
        controller.input(player, Input.THROTTLE_DOWN);
        verify(engine.getThrottle()).change(1);
        verify(engine.getThrottle()).change(-1);
        when(engine.requiresStart()).thenReturn(true);
        controller.input(player, Input.THROTTLE_UP);
        verify(engine).start(player);
        verify(engine.getThrottle(), times(1)).change(1);
        when(engine.isStarted()).thenReturn(true);
        controller.input(player, Input.THROTTLE_UP);
        verify(engine.getThrottle(), times(2)).change(1);
        when(vehicle.usesFuel()).thenReturn(true);
        when(vehicle.hasFuel()).thenReturn(false);
        controller.input(player, Input.THROTTLE_DOWN);
        verify(engine.getThrottle(), times(1)).change(-1);
        when(vehicle.hasFuel()).thenReturn(true);
        controller.input(player, Input.THROTTLE_DOWN);
        verify(engine.getThrottle(), times(2)).change(-1);
    }

    @Test
    void gearedThrottleStartsEngineThenDelegatesDirection() {
        GearedEngine engine = component(Component.GEARED_ENGINE, GearedEngine.class);
        when(engine.requiresStart()).thenReturn(true);
        controller.input(player, Input.THROTTLE_DOWN);
        verify(engine).start(player);
        verify(engine, never()).throttle(anyBoolean());
        when(engine.isStarted()).thenReturn(true);
        controller.input(player, Input.THROTTLE_UP);
        controller.input(player, Input.THROTTLE_DOWN);
        verify(engine).throttle(false);
        verify(engine).throttle(true);
    }

    @Test
    void captainTurnsWorldYawAndLocalYawInBothDirections() {
        controller.input(player, Input.TURN_LEFT);
        controller.input(player, Input.TURN_RIGHT);
        controller.input(player, Input.TURN_LEFT_LOCAL);
        controller.input(player, Input.TURN_RIGHT_LOCAL);
        verify(rotator).setRotation(45, 0, 0, true, false, false);
        verify(rotator).setRotation(25, 0, 0, true, false, false);
        verify(rotator).rotateSmoothed(0, 2, 0);
        verify(rotator).rotateSmoothed(0, -2, 0);
        verify(vehicle, times(2)).animate(Animation.LEFT);
        verify(vehicle, times(2)).animate(Animation.RIGHT);
        when(vehicle.getAccessPanel().getTurnRate()).thenReturn(0d);
        controller.input(player, Input.TURN_LEFT);
        controller.input(player, Input.TURN_RIGHT_LOCAL);
        verify(rotator, times(1)).rotateSmoothed(0, -2, 0);
    }

    @Test
    void pitchAndRollUseConfiguredWingRateOrZeroWithoutWings() {
        controller.input(player, Input.PITCH_UP);
        verify(rotator).rotateSmoothed(-0.0, 0, 0);
        Wings wings = component(Component.WINGS, Wings.class);
        when(wings.getTurnRate()).thenReturn(4f);
        controller.input(player, Input.PITCH_UP);
        controller.input(player, Input.PITCH_DOWN);
        controller.input(player, Input.ROLL_LEFT);
        controller.input(player, Input.ROLL_RIGHT);
        verify(rotator).rotateSmoothed(-4, 0, 0);
        verify(rotator).rotateSmoothed(4, 0, 0);
        verify(rotator).rotateSmoothed(0, 0, -4);
        verify(rotator).rotateSmoothed(0, 0, 4);
    }

    @Test
    void junctionControlsRequireLocomotiveCaptainAndPreserveRequestedSide() {
        controller.input(null, Input.JUNCTION_LEFT);
        controller.input(player, Input.JUNCTION_LEFT);
        when(vehicle.isTrain()).thenReturn(true);
        controller.input(player, Input.JUNCTION_LEFT);
        when(vehicle.getSeatHandler().isCaptain(player)).thenReturn(true);
        when(vehicle.hasParent()).thenReturn(true);
        controller.input(player, Input.JUNCTION_LEFT);
        verify(vehicle.getTrainHandler(), never()).holdJunction(any());
        when(vehicle.hasParent()).thenReturn(false);
        controller.input(player, Input.JUNCTION_LEFT);
        controller.input(player, Input.JUNCTION_RIGHT);
        verify(vehicle.getTrainHandler()).holdJunction(TrackJunction.Side.LEFT);
        verify(vehicle.getTrainHandler()).holdJunction(TrackJunction.Side.RIGHT);
        when(vehicle.getSeatHandler()).thenReturn(null);
        controller.input(player, Input.JUNCTION_RIGHT);
        verify(vehicle.getTrainHandler(), times(1)).holdJunction(TrackJunction.Side.RIGHT);
    }

    @Test
    void seatSelectionOpensInventoryAndReportsProviderErrors() {
        InventoryManager inventory = inventories.constructed().getFirst();
        controller.input(player, Input.SEAT_SELECTION);
        verify(inventory).seatSelection(null, player, vehicle, true);
        IllegalStateException error = new IllegalStateException("inventory unavailable");
        doThrow(error).when(inventory).seatSelection(null, player, vehicle, true);
        controller.input(player, Input.SEAT_SELECTION);
        verify(player).sendMessage("An error occurred while opening inventory: inventory unavailable");
    }

    @Test
    void lightsAreToggledAndOnlyCaptainCanHonk() {
        controller.input(player, Input.LIGHTS);
        controller.input(player, Input.HORN);
        verify(vehicle).toggleLights(player);
        verify(vehicle).honk(player);
        when(seat.getType()).thenReturn(SeatType.PASSENGER);
        controller.input(player, Input.HORN);
        verify(vehicle, times(1)).honk(player);
    }

    @ParameterizedTest
    @EnumSource(value = Input.class, names = {"FORWARD", "BACKWARD", "UP", "DOWN", "THROTTLE_UP", "THROTTLE_DOWN",
            "TURN_LEFT", "TURN_RIGHT", "TURN_LEFT_LOCAL", "TURN_RIGHT_LOCAL", "PITCH_UP", "PITCH_DOWN", "ROLL_LEFT", "ROLL_RIGHT"})
    void passengerCannotDriveOrRotate(Input input) {
        when(seat.getType()).thenReturn(SeatType.PASSENGER);
        component(Component.ENGINE, Engine.class);
        component(Component.BALLOON, Balloon.class);
        component(Component.WINGS, Wings.class);
        controller.input(player, input);
        verify(entity, never()).setVelocity(any());
        verify(rotator, never()).rotateSmoothed(anyFloat(), anyFloat(), anyFloat());
        verify(rotator, never()).setRotation(anyFloat(), anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = Input.class, names = {"NONE", "WEAPON_UP", "WEAPON_DOWN", "WEAPON_LEFT", "WEAPON_RIGHT", "WEAPON_RELOAD", "WEAPON_SHOOT", "WEAPON_RELOAD_AND_SHOOT", "WEAPON_SWITCH"})
    void unrelatedInputsLeaveMovementUntouched(Input input) {
        controller.input(player, input);
        verifyNoInteractions(entity, rotator);
    }

    @ParameterizedTest
    @EnumSource(value = Input.class, names = {"FORWARD", "BACKWARD", "MOVE"})
    void breakStateStopsHorizontalMovementWithoutCancellingGravity(Input input) {
        when(state.isBreakState()).thenReturn(true);
        controller.input(player, input);
        assertEquals(new Vector(0, .3, 0), velocity);
        terrain.verifyNoInteractions();
        verify(vehicle, never()).animate(any());
    }

    @Test
    void stationaryOrParentedVehiclesDoNotApplyDriveMovement() {
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(0d);
        controller.input(player, Input.FORWARD);
        controller.input(player, Input.BACKWARD);
        when(vehicle.hasParent()).thenReturn(true);
        controller.input(player, Input.MOVE);
        verify(entity, never()).setVelocity(any());
        terrain.verifyNoInteractions();
    }

    @Test
    void forwardAndBackwardHarnessInputsUseRealBasePhysicsAndAnimations() {
        Harness harness = component(Component.HARNESS, Harness.class);
        when(harness.hasMounts()).thenReturn(true);
        when(harness.getSpeed()).thenReturn(2d);
        controller.input(player, Input.FORWARD);
        assertEquals(new Vector(2, -.49, 0), velocity);
        verify(vehicle).animate(Animation.FORWARD);
        controller.input(player, Input.BACKWARD);
        assertEquals(new Vector(-.6, -.49, 0), velocity);
        verify(vehicle).animate(Animation.BACKWARD);
    }

    @Test
    void defaultStateAndMissingTerrainConfigUseSafeOrdinaryMovement() {
        when(state.isDefault()).thenReturn(true);
        controller.input(player, Input.FORWARD);
        verify(entity, never()).setVelocity(any());
        when(state.isDefault()).thenReturn(false);
        when(state.getTerrainFollow()).thenReturn(null);
        controller.input(player, Input.BACKWARD);
        assertEquals(new Vector(.4, -.49, .2), velocity);
        terrain.verifyNoInteractions();
    }

    @Test
    void floatingStateStopsVerticalDriveWhenVehicleCannotFloat() {
        when(state.getType()).thenReturn(State.FLOATING);
        controller.input(player, Input.FORWARD);
        assertEquals(new Vector(.4, 0, .2), velocity);
        when(vehicle.shouldFloat()).thenReturn(true);
        velocity = new Vector(.4, .3, .2);
        controller.input(player, Input.MOVE);
        assertEquals(new Vector(.4, .1, .2), velocity);
        verify(controller.getFloatController()).calculateFloat(eq(vehicle), any());
        verify(rotator).rotateSmoothed(0, 0, 0);
    }

    @Test
    void groundPlaneWithLowThrottleCannotPitchThroughSolidGround() {
        component(Component.WINGS, Wings.class);
        component(Component.ENGINE, Engine.class);
        when(vector.getVector()).thenReturn(new Vector(1, 1, 0));
        when(vehicle.getThrottle().getCurrent()).thenReturn(10);
        when(ground.isPassable()).thenReturn(false);
        controller.input(player, Input.FORWARD);
        assertEquals(0, velocity.getY());
        assertTrue(velocity.getX() > 0);
        when(ground.isPassable()).thenReturn(true);
        controller.input(player, Input.FORWARD);
        assertTrue(velocity.getY() > 0);
    }

    @Test
    void wingsWithoutEngineKeepVerticalMovementAndLowThrottleFlightKeepsGliding() {
        component(Component.WINGS, Wings.class);
        controller.input(player, Input.FORWARD);
        assertEquals(.3, velocity.getY());
        component(Component.ENGINE, Engine.class);
        when(state.getType()).thenReturn(State.FLYING);
        when(vehicle.getThrottle().getCurrent()).thenReturn(0);
        controller.input(player, Input.FORWARD);
        assertEquals(.65, velocity.getX(), 1e-9);
        assertEquals(0, velocity.getY());
    }

    @Test
    void ordinaryMoveAppliesBalloonLiftAndResetsBodyTilt() {
        Balloon balloon = component(Component.BALLOON, Balloon.class);
        when(balloon.getLift()).thenReturn(.25);
        when(balloon.getDelta()).thenReturn(.12);
        controller.input(player, Input.MOVE);
        assertEquals(.12, velocity.getY());
        verify(rotator).rotateSmoothed(0, 0, 0);
    }

    @Test
    void matureTrainUsesSplineTickWhileFreshTrainUsesSpawnVelocity() {
        when(vehicle.isTrain()).thenReturn(true);
        follow();
        controller.input(player, Input.MOVE);
        verify(vehicle.getTrainHandler()).splineTick();
        verify(entity, never()).setVelocity(any());
        verify(rotator, never()).rotateSmoothed(anyFloat(), anyFloat(), anyFloat());
        terrain.verifyNoInteractions();
        when(vehicle.getSpawnTime()).thenReturn(System.currentTimeMillis());
        controller.input(player, Input.FORWARD);
        verify(entity).setVelocity(any());
        verify(vehicle.getTrainHandler(), times(1)).splineTick();
        verify(vehicle, never()).animate(Animation.FORWARD);
    }

    @Test
    void terrainFollowingTeleportsSyncsHarnessTiltsAndPropagatesToTow() {
        TerrainFollowConfig config = follow();
        Harness harness = component(Component.HARNESS, Harness.class);
        when(harness.hasMounts()).thenReturn(true);
        TowHandler tow = vehicle.getTowHandler();
        when(vehicle.hasTowHandler()).thenReturn(true);
        when(tow.getTowPoint().isOccupied()).thenReturn(true);
        Location dest = new Location(world, 9, 64.5, 8);
        Vector moved = new Vector(.5, 0, 0);
        terrainResult(new TerrainFollowEngine.Result(dest, moved, new TerrainFollowMath.Tilt(6, -8)));
        controller.input(player, Input.FORWARD);
        terrain.verify(() -> TerrainFollowEngine.step(vehicle, vector, Direction.FORWARD, controller.getBaseController(), config));
        assertEquals(dest, location);
        assertEquals(moved, velocity);
        verify(harness).syncMountedEntities();
        verify(rotator).rotateToTarget(35, 6, -8, .25f, true, true, true);
        verify(tow).animate(Direction.FORWARD);
        verify(tow.getTowPoint().getEntity()).setVelocity(moved);
        verify(vehicle).animate(Animation.FORWARD);
    }

    @Test
    void backwardAndAutomaticTerrainMovesPreserveDirectionAndAcceptNoTilt() {
        follow();
        terrainResult(new TerrainFollowEngine.Result(location.clone(), new Vector(), null));
        controller.input(player, Input.BACKWARD);
        terrain.verify(() -> TerrainFollowEngine.step(eq(vehicle), eq(vector), eq(Direction.BACKWARD), any(), any()));
        when(vehicle.getAccessPanel().isReverse()).thenReturn(true);
        controller.input(player, Input.MOVE);
        terrain.verify(() -> TerrainFollowEngine.step(eq(vehicle), eq(vector), eq(Direction.BACKWARD), any(), any()), times(2));
        verify(rotator, never()).rotateToTarget(anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void invalidTerrainEntityProducesNoFallbackVelocity() {
        follow();
        controller.input(player, Input.FORWARD);
        verify(entity, never()).teleport(any(Location.class));
        verify(entity, never()).setVelocity(any());
        verify(vehicle, never()).animate(any());
    }

    @Test
    void balloonClimbAndDescentPreserveHorizontalVelocityAndSetDelta() {
        Balloon balloon = component(Component.BALLOON, Balloon.class);
        when(balloon.getLift()).thenReturn(.4);
        when(balloon.getBaseLift()).thenReturn(.2);
        controller.input(player, Input.UP);
        assertEquals(new Vector(.4, .4, .2), velocity);
        verify(balloon).setDelta(.4);
        controller.input(player, Input.DOWN);
        assertEquals(new Vector(.4, -.2, .2), velocity);
        verify(balloon).setDelta(-.2);
        velocity.setY(-.3);
        controller.input(player, Input.DOWN);
        assertEquals(-.3, velocity.getY());
        verify(balloon, times(1)).setDelta(-.2);
    }

    @Test
    void balloonControlsIgnoreMissingOrInsufficientLift() {
        controller.input(player, Input.UP);
        controller.input(player, Input.DOWN);
        Balloon balloon = component(Component.BALLOON, Balloon.class);
        when(balloon.getLift()).thenReturn(-.1);
        when(balloon.getBaseLift()).thenReturn(-.1);
        controller.input(player, Input.UP);
        controller.input(player, Input.DOWN);
        verify(entity, never()).setVelocity(any());
        verify(balloon, never()).setDelta(anyDouble());
    }

    @Test
    void idleAnimationStopsBothDirectionsAndUpdatesOccupiedTowPoint() {
        when(vehicle.hasTowHandler()).thenReturn(true);
        TowHandler tow = vehicle.getTowHandler();
        when(tow.getTowPoint().isOccupied()).thenReturn(true);
        velocity = new Vector(.01, 0, .01);
        controller.setAnimation();
        verify(vehicle).stopAnimation(Animation.FORWARD);
        verify(vehicle).stopAnimation(Animation.BACKWARD);
        verify(tow).animate(Direction.STILL);
        verify(tow.getTowPoint().getEntity()).setVelocity(velocity);
        when(tow.getTowPoint().isOccupied()).thenReturn(false);
        controller.setAnimation();
        verify(tow, times(1)).animate(Direction.STILL);
    }

    @Test
    void idleAnimationLeavesAutomaticParentedAndMovingVehiclesAlone() {
        when(vehicle.shouldAutoMove()).thenReturn(true);
        controller.setAnimation();
        when(vehicle.shouldAutoMove()).thenReturn(false);
        when(vehicle.hasParent()).thenReturn(true);
        controller.setAnimation();
        when(vehicle.hasParent()).thenReturn(false);
        controller.setAnimation();
        verify(vehicle, never()).stopAnimation(any());
    }

    @Test
    void explicitAnimationSupportsForwardBackwardStillAndNeutralMovement() {
        controller.animateMove(Direction.FORWARD);
        controller.animateMove(Direction.BACKWARD);
        controller.animateMove(Direction.STILL);
        controller.animateMove(Direction.MOVING);
        verify(vehicle).animate(Animation.FORWARD);
        verify(vehicle).animate(Animation.BACKWARD);
        verify(vehicle).stopAnimation(Animation.FORWARD);
        verify(vehicle).stopAnimation(Animation.BACKWARD);
    }

    @Test
    void realLiftPhysicsHandlesHealthyAndDamagedBalloon() {
        Balloon balloon = component(Component.BALLOON, Balloon.class);
        when(balloon.getLift()).thenReturn(.2);
        assertEquals(0, controller.getLiftController().calculateLift(rotator, vehicle, new Vector()).getY());
        when(balloon.getLift()).thenReturn(-.2);
        when(balloon.getDelta()).thenReturn(.4);
        assertEquals(-.2, controller.getLiftController().calculateLift(rotator, vehicle, new Vector()).getY());
    }

    @Test
    void realLiftPhysicsAppliesWingLiftAndThrottleGravityWithCap() {
        Wings wings = component(Component.WINGS, Wings.class);
        Engine engine = component(Component.ENGINE, Engine.class);
        when(wings.getLift()).thenReturn(100d);
        when(engine.getThrottle().getCurrent()).thenReturn(30);
        Vector lifted = controller.getLiftController().calculateLift(rotator, vehicle, new Vector(1, 0, 0));
        assertEquals(.03, lifted.getY(), 1e-9);
        when(state.getType()).thenReturn(State.FLYING);
        when(engine.getThrottle().getCurrent()).thenReturn(0);
        Vector stalled = controller.getLiftController().calculateLift(rotator, vehicle, new Vector(1, 0, 0));
        assertEquals(-.95, stalled.getY(), 1e-9);
        when(engine.getThrottle().getCurrent()).thenReturn(50);
        when(wings.getLift()).thenReturn(0d);
        assertEquals(-.49, controller.getLiftController().calculateLift(rotator, vehicle, new Vector(1, 0, 0)).getY(), 1e-9);
        when(state.getType()).thenReturn(State.GROUND);
        when(engine.getThrottle().getCurrent()).thenReturn(0);
        assertEquals(new Vector(1, .2, 0), controller.getLiftController().calculateLift(rotator, vehicle, new Vector(1, .2, 0)));
    }

    @Test
    void flyingChassisCollisionDestroysVehicleOnlyWhenMovingIntoSolidBlock() {
        component(Component.WINGS, Wings.class);
        when(vehicle.isDestroyed()).thenReturn(true);
        controller.getLiftController().checkHitWall(vehicle);
        when(vehicle.isDestroyed()).thenReturn(false);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.1);
        controller.getLiftController().checkHitWall(vehicle);
        when(vehicle.getAccessPanel().getSpeed()).thenReturn(.4);
        controller.getLiftController().checkHitWall(vehicle);
        verify(vehicle, never()).kill(any());
        when(ground.isPassable()).thenReturn(false);
        controller.getLiftController().checkHitWall(vehicle);
        verify(vehicle).kill(VehicleDeath.EXPLODE);
    }
}
