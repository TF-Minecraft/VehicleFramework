package net.tfminecraft.vehicleframework.vehicles.component;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.database.PersistenceLog;
import net.tfminecraft.vehicleframework.data.ParticleData;
import net.tfminecraft.vehicleframework.effects.CustomEffect;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.CustomAction;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.loaders.FuelLoader;
import net.tfminecraft.vehicleframework.util.ParticleLoader;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class GearedEngineCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>(), later = new ArrayList<>();
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private MockedStatic<FuelLoader> fuels;
    private VehicleFramework previousPlugin;
    private final World world = mock(World.class);

    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin;
        VehicleFramework.plugin = mock(VehicleFramework.class);
        MockedStatic<Bukkit> bukkit = keep(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> task(call.getArgument(1), timers));
        when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                .thenAnswer(call -> task(call.getArgument(1), later));
        fuels = keep(mockStatic(FuelLoader.class));
        keep(mockStatic(PersistenceLog.class));
    }

    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin;
    }

    @Test void templateAndCopiesPreserveGearDefinitionsButHaveIndependentThrottle() {
        YamlConfiguration config = config(true);
        config.set("start-gear", 1);
        GearedEngine template = new GearedEngine(config);
        Fixture f = fixture(template);
        assertTrue(f.engine.requiresStart());
        assertFalse(f.engine.isStarted());
        assertEquals(1, f.engine.getDefaultGear());
        assertEquals(1, f.engine.getCurrentGear());
        assertEquals(2.5, template.getTurnRate());
        assertEquals(3, f.engine.getGears().size());
        assertEquals("gear1", f.engine.getGear().getName());
        f.engine.getGear().getThrottle().setThrottle(25);
        assertEquals(0, template.getGear().getThrottle().getCurrent());
        assertEquals(.5, f.engine.getSpeed(), 1e-9);
        assertSame(template.getParticles(), f.engine.getParticles());
        assertSame(template.getParticleBones(), f.engine.getParticleBones());
        f.engine.setCurrentGear(-1);
        f.engine.setCurrentGear(3);
        assertEquals(1, f.engine.getCurrentGear());
        f.engine.setStarted(true);
        assertTrue(f.engine.isStarted());
    }

    @Test void liveTurnRateCanScaleWithForwardOrReverseThrottle() {
        Fixture f = fixture(false);
        when(f.behaviour.turnScale()).thenReturn(true);
        f.throttle().setThrottle(-20);
        assertEquals(-.5, f.engine.getTurnRate());
        when(f.behaviour.turnScale()).thenReturn(false);
        assertEquals(2.5, f.engine.getTurnRate());
    }

    @Test void acceleratorChangesByGearIncrementAndHonoursHardLimits() {
        Fixture f = fixture(false);
        f.engine.throttle(false);
        assertEquals(5, f.throttle().getCurrent());
        f.engine.throttle(true);
        assertEquals(0, f.throttle().getCurrent());
        f.throttle().setThrottle(-100);
        f.engine.throttle(true);
        assertEquals(-100, f.throttle().getCurrent());
        f.engine.setCurrentGear(2);
        f.throttle().setThrottle(120);
        f.engine.throttle(false);
        assertEquals(120, f.throttle().getCurrent());
        verify(world, times(2)).playSound(any(Location.class), eq("accelerate"), eq(1f), eq(1f));
        later.forEach(Runnable::run);
        f.engine.throttle(true);
        assertEquals(115, f.throttle().getCurrent());
    }

    @Test void shiftingRampsDownAndIgnoresInputUntilTheShiftCompletes() {
        Fixture f = fixture(false);
        f.throttle().setThrottle(120);
        f.engine.throttle(false);
        assertTrue(f.engine.isShifting());
        assertEquals(1, timers.size());
        f.engine.throttle(true);
        assertEquals(120, f.throttle().getCurrent());
        Runnable shift = timers.get(0);
        for (int i = 0; i < 10; i++) shift.run();
        assertEquals(110, f.throttle().getCurrent());
        assertEquals(0, f.engine.getCurrentGear());
        shift.run();
        assertEquals(1, f.engine.getCurrentGear());
        assertFalse(f.engine.isShifting());
        f.throttle().setThrottle(-100);
        f.engine.throttle(true);
        for (int i = 0; i < 11; i++) timers.get(1).run();
        assertEquals(0, f.engine.getCurrentGear());
        assertFalse(f.engine.isShifting());
    }

    @ParameterizedTest @ValueSource(ints = {80, -80})
    void damagedEnginePublishesOnlyItsCurrentSafeSpeed(int demand) {
        Fixture f = fixture(false);
        when(f.behaviour.turnScale()).thenReturn(true);
        f.engine.getHealthData().setDamage(60);
        f.throttle().setThrottle(demand);
        f.engine.tick(List.of());
        assertEquals(Integer.signum(demand) * 40, f.throttle().getCurrent());
        verify(f.panel).setSpeed(Integer.signum(demand) * .4);
        verify(f.panel).setTurnRate(Integer.signum(demand));
        verify(f.panel).setReverse(demand < 0);
    }

    @Test void tapePlaybackStartsAndChangesThrottleOneStepWithoutNormalizingOrRecording() {
        Fixture f = fixture(true);
        when(f.train.playbackThrottle(f.engine.getFuelTank())).thenReturn(40);
        f.engine.tick(List.of());
        assertTrue(f.engine.isStarted());
        assertEquals(1, f.throttle().getCurrent());
        verify(f.train, never()).maybeRecordSample(anyInt());
        verify(f.vehicle).animate(Animation.ENGINE_ACTIVE);
        verify(f.controls).input(null, Input.MOVE);
        when(f.train.playbackThrottle(f.engine.getFuelTank())).thenReturn(-20);
        f.engine.tick(List.of());
        assertEquals(0, f.throttle().getCurrent());
    }

    @Test void rootTrainRecordsThrottleAndDrainsFuelFromItsTender() {
        Fixture f = fixture(false);
        f.throttle().setThrottle(30);
        f.engine.tick(List.of());
        verify(f.train).maybeRecordSample(30);
        f.engine.getFuelTank().setFuel(10);
        f.engine.slowTick(List.of());
        verify(f.train).drainFromChild(f.engine.getFuelTank());
        assertEquals(8, f.engine.getFuelTank().getCurrent());
        when(f.vehicle.hasParent()).thenReturn(true);
        f.engine.slowTick(List.of());
        verify(f.train, times(1)).drainFromChild(any());
    }

    @Test void stoppedEngineDoesNotBurnFuelAndIdleEngineMakesNoSound() {
        Fixture f = fixture(true);
        f.engine.getFuelTank().setFuel(10);
        f.engine.slowTick(List.of());
        assertEquals(10, f.engine.getFuelTank().getCurrent());
        verifyNoInteractions(world);
    }

    @ParameterizedTest @ValueSource(ints = {-100, 50, 120})
    void enginePitchFollowsThrottleWithinAudibleLimits(int throttle) {
        Fixture f = fixture(false);
        Player listener = mock(Player.class);
        f.throttle().setThrottle(throttle);
        f.engine.playSound(List.of(listener));
        verify(listener).playSound(any(Location.class), eq("engine"), eq(1f),
                eq(Math.max(.5f, Math.min(1.6f, .5f + throttle / 100f))));
    }

    @Test void healthyEngineStartsImmediatelyAndPlayerCooldownPreventsDuplicateStart() {
        Fixture f = fixture(true);
        Player driver = mock(Player.class);
        f.engine.setCurrentGear(2);
        f.engine.start(driver);
        assertTrue(f.engine.isStarted());
        assertEquals(0, f.engine.getCurrentGear());
        assertEquals(1, f.throttle().getCurrent());
        f.engine.start(driver);
        assertEquals(1, f.throttle().getCurrent());
        verify(driver).sendMessage("§e[Motor] §astarted!");
        f.engine.stop();
        assertFalse(f.engine.isStarted());
        assertEquals(0, f.throttle().getCurrent());
        verify(f.vehicle).stopAnimation(Animation.ENGINE_ACTIVE);
    }

    @Test void customStartEffectKeepsEngineOffUntilFinishedAndIgnoresOtherStarts() {
        Fixture f = fixture(true);
        CustomEffect effect = mock(CustomEffect.class);
        when(f.vehicle.hasEffect(CustomAction.ENGINE_START)).thenReturn(true);
        when(f.vehicle.playEffect(CustomAction.ENGINE_START)).thenReturn(effect);
        Player driver = mock(Player.class);
        f.engine.start(driver);
        f.engine.start(mock(Player.class));
        assertEquals(1, timers.size());
        timers.get(0).run();
        assertFalse(f.engine.isStarted());
        when(effect.isFinished()).thenReturn(true);
        timers.get(0).run();
        assertTrue(f.engine.isStarted());
        assertEquals(1, f.throttle().getCurrent());
    }

    @Test void engineNeedingFuelCannotStartAnEmptyTank() {
        fuelRequired();
        Fixture f = fixture(true);
        Player driver = mock(Player.class);
        f.engine.start(driver);
        assertFalse(f.engine.isStarted());
        verify(driver).sendMessage("§e[Motor] §cNo fuel!");
        assertTrue(timers.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void damagedStartFailureResolvesWithOrWithoutItsCustomEffect(boolean customEffect) {
        Fixture f = fixture(true);
        GearedEngine engine = spy(f.engine);
        engine.getHealthData().setDamage(60);
        doReturn(80.).when(engine).rollStartChance();
        Player driver = mock(Player.class);
        CustomEffect effect = mock(CustomEffect.class);
        when(f.vehicle.hasEffect(CustomAction.ENGINE_START_FAIL)).thenReturn(customEffect);
        when(f.vehicle.playEffect(CustomAction.ENGINE_START_FAIL)).thenReturn(effect);
        engine.start(driver);
        assertFalse(engine.isStarted());
        if (customEffect) {
            timers.get(0).run();
            verify(driver, never()).sendMessage(contains("failed"));
            when(effect.isFinished()).thenReturn(true);
            timers.get(0).run();
        }
        verify(driver).sendMessage("§e[Motor] §cfailed!");
        assertEquals(0, engine.getGear().getThrottle().getCurrent());
        doReturn(0.).when(engine).rollStartChance();
        engine.start(mock(Player.class));
        assertTrue(engine.isStarted(), "A resolved failed effect must allow a later start");
    }

    @ParameterizedTest @ValueSource(ints = {-1, 3})
    void defaultGearMustReferToAnExistingGear(int invalid) {
        YamlConfiguration config = config(true); config.set("start-gear", invalid);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new GearedEngine(config));
        assertTrue(error.getMessage().contains("start-gear"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configurationRequiresAtLeastOneGear(boolean emptySection) {
        YamlConfiguration config = config(true); config.set("gears", null);
        if (emptySection) config.createSection("gears");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new GearedEngine(config));
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("gear"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void emptyTankStopsMotionEvenIfTheEngineWasAlreadyOff(boolean started) {
        fuelRequired();
        Fixture f = fixture(true);
        f.engine.setStarted(started);
        f.throttle().setThrottle(30);
        f.engine.tick(List.of());
        assertFalse(f.engine.isStarted());
        assertEquals(0, f.throttle().getCurrent());
        verify(f.panel).setSpeed(0);
    }

    @Test void anAutomaticEngineAlsoStopsProducingPowerWhenItsFuelRunsOut() {
        fuelRequired();
        Fixture f = fixture(false);
        f.throttle().setThrottle(30);
        f.engine.tick(List.of());
        assertEquals(0, f.throttle().getCurrent());
        verify(f.panel).setSpeed(0);
    }

    @Test void emptyTankDoesNotRestartForRecordedThrottle() {
        fuelRequired();
        Fixture f = fixture(true);
        when(f.train.playbackThrottle(any())).thenReturn(50);
        f.engine.tick(List.of());
        assertFalse(f.engine.isStarted());
        assertEquals(0, f.throttle().getCurrent());
        f.engine.getFuelTank().setFuel(1);
        f.engine.tick(List.of());
        assertTrue(f.engine.isStarted());
    }

    @Test void destroyedEngineStopsInsteadOfRemainingStartedAtZeroThrottle() {
        Fixture f = fixture(true);
        f.engine.setStarted(true);
        f.engine.getHealthData().setDamage(100);
        f.throttle().setThrottle(40);
        f.engine.tick(List.of());
        assertFalse(f.engine.isStarted());
        assertEquals(0, f.throttle().getCurrent());
        verify(f.panel).setSpeed(0);
        verify(f.vehicle).stopAnimation(Animation.ENGINE_ACTIVE);
    }

    @Test void stopLeavesAnAlwaysRunningEngineAvailable() {
        Fixture f = fixture(false);
        f.throttle().setThrottle(25);
        f.engine.stop();
        assertTrue(f.engine.isStarted());
        assertEquals(25, f.throttle().getCurrent());
    }

    @Test void unattendedEngineNormalizesThenStopsWhenItReturnsToIdle() {
        Fixture f = fixture(true);
        when(f.train.isRecording()).thenReturn(false);
        when(f.seats.hasPassengers()).thenReturn(false);
        f.engine.setStarted(true);
        f.throttle().setThrottle(2);
        f.engine.tick(List.of());
        assertEquals(1, f.throttle().getCurrent());
        assertEquals(1, later.size());
        f.engine.tick(List.of());
        assertEquals(0, f.throttle().getCurrent());
        assertFalse(f.engine.isStarted());
        later.get(0).run();
    }

    @Test void delayedIdleCheckDoesNotStopAnEngineThatHasAccelerated() {
        Fixture f = fixture(true);
        when(f.vehicle.isTrain()).thenReturn(false);
        f.engine.setStarted(true);
        f.throttle().setThrottle(20);
        f.engine.tick(List.of());
        f.engine.tick(List.of());
        assertEquals(1, later.size());
        later.get(0).run();
        assertTrue(f.engine.isStarted());
        f.engine.tick(List.of());
        assertEquals(2, later.size());
    }

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void unattendedNeutralGearsReturnTowardTheDefaultGear(int selected) {
        YamlConfiguration config = config(true);
        config.set("start-gear", 1);
        config.set("gears." + selected + ".min", 0);
        config.set("gears." + selected + ".max", 0);
        Fixture f = fixture(new GearedEngine(config));
        when(f.train.isRecording()).thenReturn(false);
        when(f.seats.hasPassengers()).thenReturn(false);
        f.engine.setCurrentGear(selected);
        f.engine.tick(List.of());
        assertTrue(f.engine.isShifting());
        f.engine.tick(List.of());
        assertEquals(1, timers.size());
        for (int i = 0; i < 11; i++) timers.get(0).run();
        assertEquals(1, f.engine.getCurrentGear());
        assertFalse(f.engine.isShifting());
    }

    @Test void flyingUnattendedVehicleKeepsItsThrottle() {
        Fixture f = fixture(true);
        when(f.vehicle.isTrain()).thenReturn(false);
        when(f.seats.hasPassengers()).thenReturn(false);
        when(f.states.getCurrentState().getType()).thenReturn(State.FLYING);
        f.throttle().setThrottle(-30);
        f.engine.tick(List.of());
        assertEquals(-30, f.throttle().getCurrent());
    }

    @Test void activeEngineEmitsParticlesFromModelBonesAndSupportsModelReload() {
        ParticleData particle = mock(ParticleData.class);
        MockedStatic<ParticleLoader> particles = keep(mockStatic(ParticleLoader.class));
        particles.when(() -> ParticleLoader.getParticlesFromConfig(any())).thenReturn(List.of(particle));
        YamlConfiguration config = config(false);
        config.createSection("particles.exhaust");
        config.set("particle-bones", List.of("base.align"));
        Fixture f = fixture(new GearedEngine(config));
        f.throttle().setThrottle(20);
        f.engine.tick(List.of());
        verify(particle).spawnParticle(new Location(world, 0, 65, 0), new Vector(0, 1, 0));
        ActiveModel replacement = model();
        f.engine.updateModel(replacement);
        f.engine.tick(List.of());
        verify(particle, times(2)).spawnParticle(any(), eq(new Vector(0, 1, 0)));
    }

    private Fixture fixture(boolean requireStart) { return fixture(new GearedEngine(config(requireStart))); }
    private Fixture fixture(GearedEngine template) {
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        Entity entity = mock(Entity.class);
        BehaviourHandler behaviour = mock(BehaviourHandler.class);
        SeatHandler seats = mock(SeatHandler.class);
        StateHandler states = mock(StateHandler.class, RETURNS_DEEP_STUBS);
        TrainHandler train = mock(TrainHandler.class);
        AccessPanel panel = mock(AccessPanel.class);
        VehicleMovementController controls = mock(VehicleMovementController.class);
        when(vehicle.getEntity()).thenReturn(entity);
        when(entity.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(entity.getVelocity()).thenReturn(new Vector());
        when(vehicle.getBehaviourHandler()).thenReturn(behaviour);
        when(vehicle.getSeatHandler()).thenReturn(seats);
        when(seats.hasPassengers()).thenReturn(true);
        when(vehicle.getStateHandler()).thenReturn(states);
        when(states.getCurrentState().getType()).thenReturn(State.GROUND);
        when(vehicle.getAccessPanel()).thenReturn(panel);
        when(vehicle.getMoveControls()).thenReturn(controls);
        when(vehicle.isTrain()).thenReturn(true);
        when(vehicle.getTrainHandler()).thenReturn(train);
        when(train.isRecording()).thenReturn(true);
        when(train.playbackThrottle(any())).thenReturn(null);
        GearedEngine engine = new GearedEngine(vehicle, template, entity, model(), null);
        return new Fixture(vehicle, engine, behaviour, seats, states, train, panel, controls);
    }

    private ActiveModel model() {
        ActiveModel model = mock(ActiveModel.class);
        ModelBone base = mock(ModelBone.class), align = mock(ModelBone.class);
        when(base.getBoneId()).thenReturn("base");
        when(align.getBoneId()).thenReturn("align");
        when(base.getLocation()).thenAnswer(call -> new Location(world, 0, 65, 0));
        when(align.getLocation()).thenAnswer(call -> new Location(world, 0, 66, 0));
        when(model.getBone("base")).thenReturn(Optional.of(base));
        when(model.getBone("align")).thenReturn(Optional.of(align));
        return model;
    }

    private void fuelRequired() {
        Fuel fuel = mock(Fuel.class);
        fuels.when(() -> FuelLoader.getByString("none")).thenReturn(fuel);
    }

    private YamlConfiguration config(boolean requireStart) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("alias", "Motor");
        config.set("health", 100);
        config.set("requires-start", requireStart);
        config.set("turn-rate", 2.5);
        for (int i = 0; i < 3; i++) {
            String gear = "gears." + i + ".";
            config.set(gear + "name", "gear" + i);
            config.set(gear + "min", -100);
            config.set(gear + "max", 120);
            config.set(gear + "acceleration", 5);
            config.set(gear + "speed", i + 1);
            config.set(gear + "engine-sound.sound", "engine");
            config.set(gear + "engine-sound.pitched", true);
            config.set(gear + "accelerate-sound.sound", "accelerate");
        }
        return config;
    }
    private BukkitTask task(Runnable runnable, List<Runnable> target) {
        target.add(runnable);
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(later.size() + timers.size());
        return task;
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private record Fixture(ActiveVehicle vehicle, GearedEngine engine, BehaviourHandler behaviour,
            SeatHandler seats, StateHandler states, TrainHandler train, AccessPanel panel,
            VehicleMovementController controls) {
        Throttle throttle() { return engine.getGear().getThrottle(); }
    }
}
