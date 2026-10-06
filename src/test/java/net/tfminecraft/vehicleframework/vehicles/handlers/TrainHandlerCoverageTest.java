package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.tracks.*;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.AnimationHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.*;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class TrainHandlerCoverageTest {
    @TempDir Path directory;
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private MockedStatic<VehicleFramework> framework;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ThrottleTapeItems> tapes;
    private MockedStatic<TrackTools> tools;
    private MockedStatic<TrackFx> effects;
    private TrackRegistry registry;
    private TrackStore store;
    private final World world = mock(World.class);
    private double oldArm, oldFx, oldOffset, oldSnap;

    @BeforeAll static void initializeSounds() {
        net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
    }


    @BeforeEach void setup() {
        oldArm = Cache.trackJunctionArmDistance; Cache.trackJunctionArmDistance = 10;
        oldFx = Cache.trackFxSoundInterval; Cache.trackFxSoundInterval = .5;
        oldOffset = Cache.trackVehicleYOffset; Cache.trackVehicleYOffset = 0;
        oldSnap = Cache.trackSnapDistance; Cache.trackSnapDistance = 2;
        registry = new TrackRegistry(directory.toFile());
        store = new TrackStore(directory.toFile());
        framework = keep(mockStatic(VehicleFramework.class));
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
        bukkit = keep(mockStatic(Bukkit.class));
        keep(mockStatic(PersistenceLog.class));
        keep(mockStatic(RecorderLog.class));
        keep(mockStatic(VFLogger.class));
        effects = keep(mockStatic(TrackFx.class));
        tapes = keep(mockStatic(ThrottleTapeItems.class));
        tools = keep(mockStatic(TrackTools.class));
        when(world.getName()).thenReturn("world");
        org.bukkit.block.Block air = mock(org.bukkit.block.Block.class);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        Cache.trackJunctionArmDistance = oldArm; Cache.trackFxSoundInterval = oldFx;
        Cache.trackVehicleYOffset = oldOffset; Cache.trackSnapDistance = oldSnap;
    }

    @Test void copiesKeepConfigurationRoutesAndAnIndependentInstalledTape() {
        YamlConfiguration config = config(true);
        config.set("locomotive", true);
        config.set("wheel-diameter", 2);
        config.set("bogies", List.of("front", "back"));
        config.set("fuel-cars", List.of("coal", "", " "));
        config.set("walkable.x", List.of(-1, 1));
        config.set("walkable.z", List.of(-2, 2));
        config.set("walkable.top", 2);
        TrainHandler template = new TrainHandler(config);
        UUID track = UUID.randomUUID(), junction = UUID.randomUUID();
        template.applyConsist(new ConsistData(null, null, track.toString(), 3., -1,
                junction.toString(), true, -1, Map.of()));
        ThrottleTape tape = new ThrottleTape(track.toString(), List.of(new ThrottleTape.Sample(3, -1, -20)));
        template.setInstalledTape(tape);
        MockedConstruction<DeckBody> decks = keep(mockConstruction(DeckBody.class));
        MockedConstruction<Bogies> bogies = keep(mockConstruction(Bogies.class));
        Car car = car(template);
        assertTrue(car.train.isLocomotive());
        assertNotNull(car.train.getOverdrive());
        assertTrue(car.train.isAttachable());
        assertTrue(car.train.canHaveAttached());
        assertEquals(-1, car.train.getTravelSign());
        assertEquals(-1, car.train.getOrientation());
        car.train.setSplineId(track);
        assertEquals(Map.of(junction.toString(), true), car.train.toConsistData().getJunctions());
        assertNotSame(tape, car.train.getInstalledTape());
        assertEquals(tape.toJson(), car.train.getInstalledTape().toJson());
        assertSame(decks.constructed().get(0), car.train.getDeckBody());
        car.train.updateModel(car.model);
        verify(bogies.constructed().get(0)).updateModel(car.model);
        car.train.removeDeck();
        verify(decks.constructed().get(0)).remove();
        Car coal = car(false); when(coal.vehicle.getId()).thenReturn("COAL");
        assertTrue(car.train.acceptsFuelCar(coal.vehicle));
    }

    @Test void malformedWalkableSectionDoesNotCreateADeck() {
        YamlConfiguration config = config(false); config.set("walkable.top", 2);
        Car car = car(new TrainHandler(config));
        assertNull(car.train.getDeckBody());
        car.train.removeDeck(); car.train.updateModel(car.model);
        assertFalse(car.train.isLocomotive());
    }

    @Test void attachingAnUnboundCarJoinsConnectorsAndParentLinks() {
        Car engine = car(true), trailer = car(true);
        Player driver = mock(Player.class);
        engine.location[0].setZ(20);
        assertTrue(trailer.train.attach(driver, engine.vehicle));
        assertSame(trailer.vehicle, engine.train.getChild());
        assertSame(engine.vehicle, trailer.vehicle.getParent());
        assertEquals(16, trailer.location[0].getZ(), 1e-9);
        verify(driver).sendMessage("§aConnected car to train");
        engine.train.clear();
    }

    @Test void attachingToABoundConsistPlacesTheNewCarOnItsTrack() {
        TrackSpline track = track(false);
        Car engine = car(true), trailer = car(true);
        bindAt(engine, track, 50);
        assertTrue(trailer.train.attach(mock(Player.class), engine.vehicle));
        assertEquals(track.getId(), trailer.train.getSplineId());
        assertEquals(46, trailer.train.getS(), 1e-9);
        assertEquals(46, trailer.location[0].getZ(), 1e-9);
    }

    @Test void aLocomotiveCannotBeCoupledBehindItsOwnDescendant() {
        Car root = car(true), tail = car(true);
        // Observe the mutation boundary without allowing the broken implementation
        // to create a cycle and hang its subsequent traversal.
        TrainHandler tailHandler = spy(tail.train);
        doNothing().when(tailHandler).setChild(any());
        when(tail.vehicle.getTrainHandler()).thenReturn(tailHandler);
        when(tail.behaviour.getTrainHandler()).thenReturn(tailHandler);
        root.train.setChild(tail.vehicle); tail.vehicle.setParent(root.vehicle);
        doNothing().when(root.vehicle).setParent(any());
        boolean attached = root.train.attach(mock(Player.class), tail.vehicle);
        assertAll(() -> assertFalse(attached),
                () -> verify(tailHandler, never()).setChild(root.vehicle),
                () -> verify(root.vehicle, never()).setParent(tail.vehicle));
    }

    @Test void aCarCannotBeItsOwnParent() {
        Car car = car(true);
        doNothing().when(car.vehicle).setParent(any());
        assertFalse(car.train.attach(mock(Player.class), car.vehicle));
        verify(car.vehicle, never()).setParent(car.vehicle);
        assertFalse(car.train.hasChild());
    }

    @Test void aWholeConsistCanStillCoupleBehindAnUnrelatedLocomotive() {
        Car leader = car(true), train = car(true), tail = car(true);
        train.train.setChild(tail.vehicle); tail.vehicle.setParent(train.vehicle);
        bindAt(leader, track(false), 50);
        assertTrue(train.train.attach(mock(Player.class), leader.vehicle));
        assertSame(train.vehicle, leader.train.getChild());
        assertSame(tail.vehicle, train.train.getChild());
        assertEquals(46, train.train.getS(), 1e-9);
        assertEquals(42, tail.train.getS(), 1e-9);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void invalidCouplingIsRejectedBeforeChangingEitherTrain(int reason) {
        Car target = car(reason != 3), trailer = car(reason != 0);
        if (reason == 1) when(trailer.vehicle.hasParent()).thenReturn(true);
        if (reason == 2) when(target.behaviour.isTrain()).thenReturn(false);
        if (reason == 4) target.train.setChild(car(true).vehicle);
        if (reason == 5) target.train.setPendingChild("unloaded-car");
        ActiveVehicle previous = target.train.getChild();
        assertFalse(trailer.train.attach(mock(Player.class), target.vehicle));
        assertSame(previous, target.train.getChild());
        verify(trailer.vehicle, never()).setParent(any());
    }

    @Test void pendingLinksNormalizeAndActualLinksTakePrecedenceWhenSaving() {
        Car root = car(false), child = car(false), parent = car(false);
        root.train.setPendingParent(" "); root.train.setPendingChild(null);
        assertNull(root.train.getPendingParent()); assertNull(root.train.getPendingChild());
        root.train.setPendingParent("waiting-parent"); root.train.setPendingChild("waiting-child");
        assertEquals("waiting-parent", root.train.toConsistData().getParent());
        assertEquals("waiting-child", root.train.toConsistData().getChild());
        root.train.setChild(root.vehicle);
        assertFalse(root.train.hasChild());
        root.train.setChild(child.vehicle); root.vehicle.setParent(parent.vehicle);
        assertNull(root.train.getPendingChild());
        assertEquals(child.vehicle.getUUID(), root.train.toConsistData().getChild());
        assertEquals(parent.vehicle.getUUID(), root.train.toConsistData().getParent());
        root.train.setChild(null); assertFalse(root.train.hasChild());
    }

    @Test void corruptPersistedIdsDoNotPreventOtherConsistDataLoading() {
        Car car = car(false); UUID valid = UUID.randomUUID();
        car.train.applyConsist(null);
        car.train.applyConsist(new ConsistData("parent", "child", "invalid", null, -1,
                "invalid-junction", true, -1, Map.of(valid.toString(), false)));
        assertNull(car.train.getSplineId()); assertEquals(0, car.train.getS());
        assertEquals("parent", car.train.getPendingParent());
        assertEquals("child", car.train.getPendingChild());
        car.train.applyConsist(ConsistData.unbound());
        assertTrue(car.train.toConsistData().isUnbound());
    }

    @Test void onlyAnActualCircuitCanStartRecording() {
        Car car = car(false);
        assertFalse(car.train.canRecordCircuit()); car.train.startRecording(null);
        assertFalse(car.train.isRecording());
        bindAt(car, track(false), 20); car.train.startRecording(null);
        assertFalse(car.train.canRecordCircuit()); assertFalse(car.train.isRecording());
        bindAt(car, track(true), 20); car.train.startRecording(null);
        assertTrue(car.train.canRecordCircuit()); assertTrue(car.train.isRecording());
        assertEquals(1, car.train.recordingSampleCount());
        assertNull(car.train.playbackThrottle(null));
    }

    @Test void manualRecordingCapturesThrottleAndWritesTheRecorderItem() {
        Car car = car(false); TrackSpline track = track(true);
        bindAt(car, track, 10); car.throttle.setThrottle(20);
        car.train.startRecording(null); car.train.maybeRecordSample(20);
        assertEquals(1, car.train.recordingSampleCount());
        car.train.setS(15); car.train.maybeRecordSample(50);
        assertEquals(2, car.train.recordingSampleCount());
        ItemStack hand = mock(ItemStack.class);
        car.train.stopRecording(hand);
        tapes.verify(() -> ThrottleTapeItems.write(eq(hand), argThat(tape ->
                tape.getSamples().size() == 2 && tape.getSamples().get(0).holdTicks == 2
                && tape.getSamples().get(1).throttle == 50)));
        assertFalse(car.train.isRecording()); assertEquals(0, car.train.recordingSampleCount());
        car.train.stopRecording(null); car.train.maybeRecordSample(99);
    }

    @Test void completingALapInstallsTheTapeWhenNoRecorderHolderIsOnline() {
        Car car = car(false); TrackSpline circuit = track(true);
        bindAt(car, circuit, 0); car.train.startRecording(null);
        for (int i = 1; i <= 10; i++) {
            car.train.setS(circuit.length() * i / 10 % circuit.length());
            car.train.maybeRecordSample(20);
        }
        assertFalse(car.train.isRecording()); assertTrue(car.train.hasInstalledTape());
        assertTrue(car.train.getInstalledTape().getSamples().size() >= 9);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void leavingCircuitSavesRecordingToHeldRecorderOrInstallsIt(boolean recorderHeld) {
        Car car = car(false); bindAt(car, track(true), 5);
        Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack hand = mock(ItemStack.class); UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(hand);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        tools.when(() -> TrackTools.isRecorder(hand)).thenReturn(recorderHeld);
        car.train.startRecording(player); car.train.unbind(); car.train.maybeRecordSample(20);
        assertFalse(car.train.isRecording());
        verify(player).sendMessage("§eRecording stopped: locomotive left the circuit");
        if (recorderHeld) tapes.verify(() -> ThrottleTapeItems.write(eq(hand), any()));
        else assertTrue(car.train.hasInstalledTape());
    }

    @Test void fullRecordingStopsAtItsDocumentedCapacity() {
        Car car = car(false); bindAt(car, track(true), 5); car.train.startRecording(null);
        for (int i = 1; i <= ThrottleTape.MAX_SAMPLES; i++) car.train.maybeRecordSample(i % 2);
        assertFalse(car.train.isRecording());
        assertEquals(ThrottleTape.MAX_SAMPLES, car.train.getInstalledTape().getSamples().size());
    }

    @Test void recordingMayVisitItsCircuitsBranchButNotAnUnrelatedTrack() {
        TrackSpline circuit = track(true), branch = line(30, false);
        TrackJunction junction = junction(circuit, branch, 10, 1);
        Car car = car(false); bindAt(car, circuit, 5); car.train.startRecording(null);
        car.train.setSplineId(branch.getId()); car.train.setS(2); car.train.maybeRecordSample(40);
        assertTrue(car.train.isRecording()); assertEquals(2, car.train.recordingSampleCount());
        ItemStack hand = mock(ItemStack.class); car.train.stopRecording(hand);
        tapes.verify(() -> ThrottleTapeItems.write(eq(hand), argThat(t ->
                t.getSamples().get(1).junctionId.equals(junction.id.toString()))));
        bindAt(car, circuit, 5); car.train.startRecording(null);
        car.train.setSplineId(line(50, false).getId()); car.train.maybeRecordSample(40);
        assertFalse(car.train.isRecording());
    }

    @Test void tapePlaybackRequiresTrackFuelAndNoHumanCaptain() {
        Car car = car(false); TrackSpline circuit = track(true);
        assertNull(car.train.playbackThrottle(null));
        bindAt(car, circuit, 5); assertNull(car.train.playbackThrottle(null));
        ThrottleTape tape = new ThrottleTape(circuit.getId().toString(), List.of(new ThrottleTape.Sample(5, 1, 30)));
        car.train.setInstalledTape(tape); assertEquals(30, car.train.playbackThrottle(null));
        FuelTank tank = tank(0); assertNull(car.train.playbackThrottle(tank));
        tank.setFuel(1); assertEquals(30, car.train.playbackThrottle(tank));
        when(car.seats.hasCaptain()).thenReturn(true); assertNull(car.train.playbackThrottle(tank));
        car.train.setInstalledTape(new ThrottleTape(circuit.getId().toString()));
        assertFalse(car.train.hasInstalledTape()); car.train.setInstalledTape(null);
        assertNull(car.train.getInstalledTape());
    }

    @Test void switchingTapeCancelsRecordingAndResetsPlayback() {
        Car car = car(false); TrackSpline circuit = track(true);
        bindAt(car, circuit, 5); car.train.startRecording(null);
        car.train.setInstalledTape(new ThrottleTape(circuit.getId().toString(), List.of(new ThrottleTape.Sample(5, 1, 10))));
        assertFalse(car.train.isRecording()); assertEquals(10, car.train.playbackThrottle(null));
    }

    @Test void tenderFuelConsumesOnlyTheFirstMatchingItemAndFillsTheLocomotive() {
        YamlConfiguration config = config(false); config.set("fuel-cars", List.of("coal"));
        Car root = car(new TrainHandler(config)), tender = car(false);
        when(tender.vehicle.getId()).thenReturn("coal"); when(tender.vehicle.hasContainers()).thenReturn(true);
        Container empty = mock(Container.class), full = mock(Container.class), spare = mock(Container.class);
        ContainerHandler containers = mock(ContainerHandler.class);
        HashMap<String, Container> ordered = new LinkedHashMap<>();
        ordered.put("empty", empty); ordered.put("full", full); ordered.put("spare", spare);
        when(containers.getContainers()).thenReturn(ordered); when(tender.vehicle.getContainerHandler()).thenReturn(containers);
        ItemStack coal = mock(ItemStack.class); when(full.takeOneMatching("coal-item")).thenReturn(coal);
        root.train.setChild(tender.vehicle); FuelTank tank = tank(97);
        root.train.drainFromChild(tank);
        assertEquals(100, tank.getCurrent()); verify(empty).takeOneMatching("coal-item");
        verify(full).takeOneMatching("coal-item"); verifyNoInteractions(spare);
        root.train.drainFromChild(tank); verify(full, times(1)).takeOneMatching(any());
        tank.setFuel(0); when(full.takeOneMatching("coal-item")).thenReturn(null);
        root.train.drainFromChild(tank);
        assertEquals(0, tank.getCurrent()); verify(spare).takeOneMatching("coal-item");
        assertFalse(root.train.acceptsFuelCar(null));
    }

    @Test void fuelTransferGuardsRejectIneligibleCarsAndTanks() {
        Car car = car(false), child = car(false); FuelTank tank = tank(0);
        car.train.drainFromChild(tank); car.train.setChild(child.vehicle);
        car.train.drainFromChild(null); car.train.drainFromChild(new FuelTank(0, 100, 1, List.of(), null));
        car.train.drainFromChild(tank); assertEquals(0, tank.getCurrent());
        when(car.vehicle.hasParent()).thenReturn(true); car.train.drainFromChild(tank);
        assertFalse(TrainHandler.childIdAllowed(null, "coal"));
        assertFalse(TrainHandler.childIdAllowed(Arrays.asList(null, "coal"), " "));
        assertTrue(TrainHandler.childIdAllowed(Arrays.asList(null, "COAL"), "coal"));
        assertTrue(TrainHandler.shouldDrain(List.of("coal"), "coal", true, true));
    }

    @Test void bindingInfersReverseFacingAndUnbindingRestoresConsistGravity() {
        Car root = car(true), child = car(true); TrackSpline track = track(false);
        root.train.setChild(child.vehicle); root.location[0].setZ(50); root.location[0].setYaw(180);
        assertTrue(root.train.bind(track)); assertEquals(-1, root.train.getTravelSign());
        assertEquals(track.getId(), child.train.getSplineId());
        root.train.unbind(); assertFalse(root.train.isBound()); assertFalse(child.train.isBound());
        assertEquals(1, root.train.getTravelSign()); assertEquals(0, child.train.getS());
        verify(root.entity).setGravity(true); verify(child.entity).setGravity(true);
        assertFalse(root.train.bind(null)); assertFalse(new TrainHandler(config(false)).bind(track));
    }

    @Test void movingUnboundTrainSnapsToNearbyRailAndMakesProgress() {
        Car car = car(false); TrackSpline track = track(false);
        car.location[0].setZ(20); car.panel.setSpeed(1);
        car.train.splineTick(); assertEquals(track.getId(), car.train.getSplineId());
        assertEquals(21, car.train.getS(), 1e-9);
        effects.verify(() -> TrackFx.clack(eq(world), any()));
        effects.verify(() -> TrackFx.crumbs(eq(world), any()), atLeastOnce());
        car.train.setSplineId(UUID.randomUUID()); car.location[0].setX(50);
        car.train.splineTick(); assertFalse(car.train.isBound());
    }

    @Test void parentedCarsAndTrainsWithoutWorldOrRegistryDoNotMoveIndependently() {
        Car car = car(false); car.panel.setSpeed(1);
        when(car.vehicle.hasParent()).thenReturn(true); car.train.splineTick();
        verify(car.entity, never()).teleport(any(Location.class));
        when(car.vehicle.hasParent()).thenReturn(false); when(car.entity.getWorld()).thenReturn(null);
        car.train.splineTick(); assertFalse(car.train.isBound());
        when(car.entity.getWorld()).thenReturn(world); framework.when(VehicleFramework::getTrackRegistry).thenReturn(null);
        car.train.splineTick(); assertFalse(car.train.isBound());
    }

    @Test void engineCanArmNearbySwitchAndReportsDistantOrMissingSwitches() {
        Car car = car(false); TrackSpline stem = track(false), branch = line(10, false);
        TrackJunction switchAt = junction(stem, branch, 50, 1);
        bindAt(car, stem, 45); Player captain = mock(Player.class); when(car.seats.captainPlayer()).thenReturn(captain);
        car.train.holdJunction(TrackJunction.Side.RIGHT);
        assertTrue(registry.getJunction(switchAt.id).orElseThrow().thrown);
        verify(captain).sendMessage("§eSwitch: diverge (diverge), 5 ahead");
        car.train.holdJunction(TrackJunction.Side.RIGHT);
        verify(captain, times(1)).sendMessage(anyString());
        Car distant = car(false); bindAt(distant, stem, 10); Player driver = mock(Player.class);
        when(distant.seats.captainPlayer()).thenReturn(driver);
        distant.train.holdJunction(TrackJunction.Side.LEFT);
        verify(driver).sendMessage(contains("press A/D within 10"));
        distant.train.holdJunction(TrackJunction.Side.LEFT); verify(driver, times(1)).sendMessage(anyString());
        Car behind = car(false); bindAt(behind, stem, 60); when(behind.seats.captainPlayer()).thenReturn(driver);
        behind.train.holdJunction(TrackJunction.Side.LEFT);
        verify(driver).sendMessage(contains("no facing turnout"));
    }

    @Test void occupiedSwitchCannotBeThrownAgainstTheOtherTrain() {
        TrackSpline stem = track(false); TrackJunction switchAt = junction(stem, line(10, false), 50, 1);
        registry.onJunctionOccupied(id -> true);
        Car car = car(false); bindAt(car, stem, 45); Player captain = mock(Player.class);
        when(car.seats.captainPlayer()).thenReturn(captain);
        car.train.holdJunction(TrackJunction.Side.RIGHT);
        assertFalse(registry.getJunction(switchAt.id).orElseThrow().thrown);
        verify(captain).sendMessage(contains("points locked"));
        car.train.holdJunction(TrackJunction.Side.RIGHT); verify(captain, times(1)).sendMessage(anyString());
    }

    @Test void occupiedTrackQueriesIncludeLoadedCarsAndRejectMalformedSavedRows() {
        assertFalse(TrainHandler.anyTrainOn(null)); assertFalse(TrainHandler.anyTrainOn(UUID.randomUUID()));
        UUID id = UUID.randomUUID(); Car car = car(false); car.train.setSplineId(id);
        VehicleManager vehicles = mock(VehicleManager.class);
        when(vehicles.get()).thenReturn(new HashMap<>(Map.of(car.entity, car.vehicle)));
        framework.when(VehicleFramework::getVehicleManager).thenReturn(vehicles);
        assertTrue(TrainHandler.anyTrainOn(id));
        when(car.vehicle.isTrain()).thenReturn(false);
        VehicleRepository repository = mock(VehicleRepository.class);
        framework.when(VehicleFramework::getVehicleRepository).thenReturn(repository);
        List<VehicleSnapshot> unreadable = List.of(snapshot(null), snapshot("{}"),
                snapshot("broken " + id), snapshot("[\"" + id + "\"]"), snapshot("{\"note\":\"" + id + "\"}"));
        when(repository.listAllLive()).thenReturn(unreadable);
        assertFalse(TrainHandler.anyTrainOn(id));
        VehicleSnapshot occupied = snapshot("{\"splineId\":\"" + id + "\"}");
        when(repository.listAllLive()).thenReturn(List.of(occupied));
        assertTrue(TrainHandler.anyTrainOn(id));
    }

    @Test void globalRetrackAndJunctionOccupancyUseOnlyTrains() {
        TrackSpline old = track(false), replacement = line(0, false); Car car = car(false);
        bindAt(car, old, 20); TrainHandler.retrackTrains(old, List.of(replacement));
        assertEquals(old.getId(), car.train.getSplineId()); assertFalse(TrainHandler.junctionOccupied(UUID.randomUUID()));
        VehicleManager vehicles = mock(VehicleManager.class);
        when(vehicles.get()).thenReturn(new HashMap<>(Map.of(car.entity, car.vehicle)));
        framework.when(VehicleFramework::getVehicleManager).thenReturn(vehicles);
        TrainHandler.retrackTrains(old, List.of(replacement));
        assertEquals(replacement.getId(), car.train.getSplineId());
        UUID junction = UUID.randomUUID();
        car.train.applyConsist(new ConsistData(null, null, replacement.getId().toString(), 20., 1,
                junction.toString(), true));
        assertTrue(TrainHandler.junctionOccupied(junction));
        assertFalse(TrainHandler.junctionOccupied(UUID.randomUUID()));
    }

    @Test void bogiesFollowTheirOwnRailsAndADeckFollowsTheBodyPose() {
        YamlConfiguration config = config(false);
        config.set("bogies", List.of("front", "back"));
        config.set("walkable.x", List.of(-1, 1)); config.set("walkable.z", List.of(-2, 2));
        config.set("walkable.top", 2);
        MockedConstruction<BoneRotator> rotators = keep(mockConstruction(BoneRotator.class));
        MockedConstruction<DeckBody> decks = keep(mockConstruction(DeckBody.class));
        Car car = car(new TrainHandler(config)); TrackSpline track = track(false);
        bindAt(car, track, 50); car.panel.setSpeed(.5); car.train.splineTick();
        assertEquals(50.5, car.train.getS(), 1e-9);
        assertEquals(50.5, car.location[0].getZ(), 1e-9);
        assertEquals(2, rotators.constructed().size());
        for (BoneRotator rotator : rotators.constructed()) {
            verify(rotator, atLeastOnce()).rotateToTarget(anyFloat(), anyFloat(), eq(0f), eq(1f), eq(true), eq(true), eq(false));
        }
        verify(decks.constructed().get(0), atLeastOnce()).place(argThat(frame -> frame.z() == 50.5));
        car.train.updateModel(car.model);
        for (BoneRotator rotator : rotators.constructed()) verify(rotator).updateModel(car.model);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1, 2})
    void blockedTrainsExplainObstaclesToRidersWithoutSpamming(int blockCount) {
        Car car = car(false); bindAt(car, track(false), 50);
        Player rider = mock(Player.class); Entity cargo = mock(Entity.class);
        when(car.seats.getPassengers()).thenReturn(blockCount < 0 ? List.of(cargo) : List.of(cargo, rider));
        MockedStatic<TrainBlockCollision> collision = keep(mockStatic(TrainBlockCollision.class));
        MockedStatic<TrainSpaceHighlight> highlight = keep(mockStatic(TrainSpaceHighlight.class));
        collision.when(() -> TrainBlockCollision.blocked(any(), any(), anyDouble(), any(), anyDouble(), anyDouble(), anyMap()))
                .thenReturn(true);
        List<TrainBlockCollision.Obstruction> blocks = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) blocks.add(new TrainBlockCollision.Obstruction(i, 64, 52, 51));
        collision.when(() -> TrainBlockCollision.blockers(any(), any(), anyDouble(), any(), anyDouble(), anyDouble(), anyMap()))
                .thenReturn(blocks);
        car.panel.setSpeed(1); car.train.splineTick(); car.train.splineTick();
        assertEquals(50, car.train.getS(), 1e-9);
        if (blockCount <= 0) verifyNoInteractions(rider);
        else {
            String message = "Blocked at 0, 64, 52" + (blockCount == 1 ? "" : " and 1 more");
            verify(rider).sendActionBar(net.kyori.adventure.text.Component.text(message, net.kyori.adventure.text.format.NamedTextColor.RED));
            highlight.verify(() -> TrainSpaceHighlight.show(rider, blocks));
        }
    }

    @Test void positionAndOccupancyGuardsHandleUnboundAndUnloadedCars() {
        TrainHandler template = new TrainHandler(config(false));
        template.holdJunction(TrackJunction.Side.LEFT);
        assertFalse(template.occupies(List.of()));
        Car car = car(false); car.train.holdJunction(null); car.train.holdJunction(TrackJunction.Side.RIGHT);
        assertFalse(car.train.occupies(null));
        TrackSpline loop = track(true); bindAt(car, loop, 1);
        assertFalse(car.train.occupies(List.of(new TrackRegistry.Span(UUID.randomUUID(), 1, 1))));
        assertTrue(car.train.occupies(List.of(new TrackRegistry.Span(loop.getId(), loop.length() - .5, 1))));
        when(car.vehicle.hasParent()).thenReturn(true); car.train.holdJunction(TrackJunction.Side.LEFT);
        assertFalse(car.train.occupies(List.of(new TrackRegistry.Span(loop.getId(), 1, 1))));
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(null);
        assertFalse(car.train.canRecordCircuit());
        assertFalse(TrainHandler.shouldDrain(List.of("coal"), "coal", false, true));
    }

    @Test void verticallyFacingEntityUsesForwardRailDirectionWhenBinding() {
        Car car = car(false); car.location[0].setPitch(90);
        assertTrue(car.train.bind(track(false))); assertEquals(1, car.train.getTravelSign());
    }

    @Test void attachingAfterAnExistingCarFindsTheRootLocomotive() {
        Car root = car(true), first = car(true), second = car(true);
        bindAt(root, track(false), 50);
        assertTrue(first.train.attach(mock(Player.class), root.vehicle));
        assertTrue(second.train.attach(mock(Player.class), first.vehicle));
        assertEquals(42, second.train.getS(), 1e-9);
        assertSame(second.vehicle, first.train.getChild());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void junctionApproachUsesVelocityThenThrottleAndIgnoresUnbuiltOrOpposingSwitches(int movement) {
        TrackSpline stem = track(false), branch = line(10, false);
        TrackJunction switchAt = junction(stem, branch, movement < 0 ? 40 : 50, movement < 0 ? -1 : 1);
        store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 44, 1, TrackJunction.Side.LEFT, null));
        store.saveJunction("world", new TrackJunction(UUID.randomUUID(), stem.getId(), 46, -switchAt.facingSign,
                TrackJunction.Side.LEFT, branch.getId())); registry.loadFromDisk();
        Car car = car(false); bindAt(car, stem, 45);
        if (movement == 0) car.throttle.setThrottle(20); else car.panel.setSpeed(movement);
        car.train.holdJunction(TrackJunction.Side.RIGHT);
        assertTrue(registry.getJunction(switchAt.id).orElseThrow().thrown);
    }

    @Test void retrackingToBranchKeepsAnExistingJunctionChoice() {
        TrackSpline stem = track(false), branch = line(0, false);
        TrackJunction junction = junction(stem, branch, 50, 1);
        Car car = car(false); bindAt(car, stem, 50);
        car.train.applyConsist(new ConsistData(null, null, stem.getId().toString(), 50., 1,
                junction.id.toString(), true));
        car.train.retrack(stem, List.of(branch));
        assertEquals(branch.getId(), car.train.getSplineId());
        assertEquals(junction.id.toString(), car.train.toConsistData().getJunctionId());
        assertTrue(car.train.toConsistData().isDiverge());
    }

    @Test void loadingWithoutABodyRotatorPreservesTheSavedPhysicalLocation() {
        Car car = car(false); TrackSpline track = track(false); bindAt(car, track, 40);
        car.train.applyConsist(new ConsistData(null, null, track.getId().toString(), 40.));
        car.train.placeLoadedCars();
        assertEquals(track.getId(), car.train.getSplineId());
        assertEquals(40, car.location[0].getZ(), 1e-9);
    }

    @Test void retrackingClearsRoutesWhoseJunctionWasRemoved() {
        Car car = car(false); TrackSpline old = track(false), rebuilt = line(0, false);
        bindAt(car, old, 40);
        car.train.applyConsist(new ConsistData(null, null, old.getId().toString(), 40., 1,
                UUID.randomUUID().toString(), true));
        car.train.retrack(old, List.of(rebuilt));
        assertEquals(rebuilt.getId(), car.train.getSplineId());
        assertTrue(car.train.toConsistData().getJunctions().isEmpty());
    }

    @Test void placementCanUpdateLinkedCarMetadataBeforeItsEntityIsAvailable() {
        Car root = car(false), loading = car(false);
        when(loading.vehicle.getEntity()).thenReturn(null);
        root.train.setChild(loading.vehicle);
        TrackSpline track = track(false);
        bindAt(root, track, 40);
        assertEquals(track.getId(), loading.train.getSplineId());
        assertEquals(40, loading.train.getS(), 1e-9);
        verify(loading.entity, never()).teleport(any(Location.class));
        root.train.unbind();
        assertFalse(loading.train.isBound());
    }

    private Car car(boolean couplers) { return car(new TrainHandler(config(couplers))); }
    private Car car(TrainHandler template) {
        ActiveVehicle vehicle = mock(ActiveVehicle.class); Entity entity = mock(Entity.class);
        ActiveModel model = mock(ActiveModel.class); when(model.getScale()).thenReturn(new Vector3f(1));
        Location[] location = {new Location(world, 0, 64, 0)};
        when(entity.getLocation()).thenAnswer(call -> location[0].clone()); when(entity.getWorld()).thenReturn(world);
        when(entity.getBoundingBox()).thenAnswer(call -> new BoundingBox(location[0].getX() - .5,
                location[0].getY(), location[0].getZ() - .5, location[0].getX() + .5,
                location[0].getY() + 2, location[0].getZ() + .5));
        when(entity.teleport(any(Location.class))).thenAnswer(call -> { location[0] = call.<Location>getArgument(0).clone(); return true; });
        when(vehicle.getEntity()).thenReturn(entity); when(vehicle.getModel()).thenReturn(model);
        when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString()); when(vehicle.isTrain()).thenReturn(true);
        when(vehicle.getId()).thenReturn("car");
        BehaviourHandler behaviour = mock(BehaviourHandler.class); when(behaviour.isTrain()).thenReturn(true);
        when(vehicle.getBehaviourHandler()).thenReturn(behaviour);
        SeatHandler seats = mock(SeatHandler.class); when(vehicle.getSeatHandler()).thenReturn(seats);
        AccessPanel panel = new AccessPanel(); when(vehicle.getAccessPanel()).thenReturn(panel);
        Throttle throttle = new Throttle("Throttle", 100, -100, null); when(vehicle.getThrottle()).thenReturn(throttle);
        VehicleMovementController controls = mock(VehicleMovementController.class);
        when(vehicle.getMoveControls()).thenReturn(controls);
        AnimationHandler animation = mock(AnimationHandler.class); when(vehicle.getAnimationHandler()).thenReturn(animation);
        ActiveVehicle[] parent = {null}; when(vehicle.hasParent()).thenAnswer(call -> parent[0] != null);
        when(vehicle.getParent()).thenAnswer(call -> parent[0]);
        doAnswer(call -> { parent[0] = call.getArgument(0); return null; }).when(vehicle).setParent(any());
        bone(model, location, "front", 2); bone(model, location, "back", -2);
        TrainHandler train = new TrainHandler(vehicle, template);
        when(vehicle.getTrainHandler()).thenReturn(train); when(behaviour.getTrainHandler()).thenReturn(train);
        return new Car(vehicle, entity, model, behaviour, seats, panel, throttle, train, location);
    }
    private ModelBone bone(ActiveModel model, Location[] location, String name, float z) {
        ModelBone bone = mock(ModelBone.class); BlueprintBone blueprint = new BlueprintBone();
        blueprint.setRotatedGlobalPosition(new Vector3f(0, 0, z));
        when(bone.getBoneId()).thenReturn(name); when(bone.getBlueprintBone()).thenReturn(blueprint);
        when(bone.getLocation()).thenAnswer(call -> location[0].clone().add(0, 0, z));
        when(model.getBone(name)).thenReturn(Optional.of(bone)); return bone;
    }
    private YamlConfiguration config(boolean couplers) {
        YamlConfiguration config = new YamlConfiguration();
        if (couplers) { config.set("front-connector", "front"); config.set("back-connector", "back"); }
        return config;
    }
    private TrackSpline track(boolean loop) { return line(0, loop); }
    private TrackSpline line(double x, boolean loop) {
        TrackSpline track = TrackSpline.fromPoints(UUID.randomUUID(), "world", loop,
                List.of(new double[]{x, 64, 0}, new double[]{x, 64, 100}));
        store.save(track); registry.loadFromDisk(); return track;
    }
    private TrackJunction junction(TrackSpline stem, TrackSpline branch, double at, int facing) {
        TrackJunction junction = new TrackJunction(UUID.randomUUID(), stem.getId(), at, facing,
                TrackJunction.Side.RIGHT, branch.getId(), false, 3);
        store.saveJunction("world", junction); registry.loadFromDisk(); return junction;
    }
    private void bindAt(Car car, TrackSpline track, double at) {
        TrackPose pose = track.sampleAt(at);
        car.location[0] = new Location(world, pose.x, pose.y, pose.z, pose.yaw, pose.pitch);
        assertTrue(car.train.bind(track));
    }
    private FuelTank tank(double current) {
        Fuel fuel = mock(Fuel.class); when(fuel.getItem()).thenReturn("coal-item"); when(fuel.getAmount()).thenReturn(10);
        return new FuelTank(current, 100, 1, List.of(), fuel);
    }
    private VehicleSnapshot snapshot(String payload) {
        VehicleSnapshot row = mock(VehicleSnapshot.class); when(row.getPayloadJson()).thenReturn(payload); return row;
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private record Car(ActiveVehicle vehicle, Entity entity, ActiveModel model, BehaviourHandler behaviour,
            SeatHandler seats, AccessPanel panel, Throttle throttle, TrainHandler train, Location[] location) {}
}
