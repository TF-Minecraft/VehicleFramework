package net.tfminecraft.vehicleframework.vehicles.component;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import com.google.gson.*;
import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.effects.CustomEffect;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.CustomAction;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.loaders.FuelLoader;
import net.tfminecraft.vehicleframework.util.ParticleLoader;
import net.tfminecraft.vehicleframework.util.SoundLoader;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.controller.VehicleMovementController;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.LocomotiveOverdrive;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

class VehicleComponentsCoverageTest {
    @BeforeAll static void bootstrapRegistries() {
        net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
    }
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>(), timers = new ArrayList<>();
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final World world = mock(World.class);
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<FuelLoader> fuels;
    private VehicleFramework previousPlugin;
    private final Map<ItemStack, String> itemPaths = new IdentityHashMap<>();

    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin;
        VehicleFramework.plugin = mock(VehicleFramework.class);
        bukkit = keep(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                .thenAnswer(call -> task(call.getArgument(1), later));
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> task(call.getArgument(1), timers));
        fuels = keep(mockStatic(FuelLoader.class));
        keep(mockStatic(PersistenceLog.class)); keep(mockStatic(VFLogger.class));
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin;
    }

    @ParameterizedTest @ValueSource(ints = {-80, 80})
    void damagedEnginePublishesHealthLimitedSpeedAndSteering(int demand) {
        EngineFixture f = engine(false);
        when(f.behaviour.turnScale()).thenReturn(true);
        f.engine.getHealthData().setDamage(60); f.throttle().setThrottle(demand);
        f.engine.tick(List.of());
        assertEquals(Integer.signum(demand) * 40, f.throttle().getCurrent());
        verify(f.panel).setSpeed(Integer.signum(demand) * .4);
        verify(f.panel).setTurnRate(Integer.signum(demand));
        verify(f.panel).setReverse(demand < 0);
    }

    @Test void destroyedEngineStopsBeforeItsIdleEarlyReturn() {
        EngineFixture f = engine(true); f.engine.setStarted(true);
        f.engine.getHealthData().setDamage(100); f.throttle().setThrottle(40);
        f.engine.tick(List.of());
        assertFalse(f.engine.isStarted()); assertEquals(0, f.throttle().getCurrent());
        verify(f.panel).setSpeed(0); verify(f.vehicle).stopAnimation(Animation.ENGINE_ACTIVE);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fuelDepletionStopsPowerForManualAndAutomaticEngines(boolean requiresStart) {
        fuelRequired(); EngineFixture f = engine(requiresStart); f.engine.setStarted(true);
        f.throttle().setThrottle(40); f.engine.tick(List.of());
        assertEquals(0, f.throttle().getCurrent()); verify(f.panel).setSpeed(0);
        if (requiresStart) assertFalse(f.engine.isStarted());
    }

    @Test void unnamedThrottleUsesItsDefaultDisplayName() {
        assertEquals("Throttle", new Throttle(null, 100, -100, null).getName());
    }

    @Test void pumpCannotDrainFloodingBelowZero() {
        YamlConfiguration config = config(false); config.set("power", 100);
        EngineFixture f = engine(false); when(f.behaviour.shouldFloat()).thenReturn(true);
        SinkableHull hull = new SinkableHull(f.vehicle, new SinkableHull(config), f.model,
                new IncompleteComponent(Component.HULL, 0, 0, 1));
        hull.setPump(new Pump(config)); hull.slowTick(List.of());
        assertEquals(0, hull.getSinkProgress()); assertFalse(hull.hasSinkProgress());
    }

    @Test void componentModelReloadMovesFireParticlesToTheReplacementBones() {
        YamlConfiguration config = config(false); config.set("vfx", List.of("base"));
        EngineFixture f = engine(false); Player viewer = mock(Player.class);
        Hull hull = new Hull(new Hull(config), f.vehicle, f.model, null);
        hull.startFire(); hull.getFire().setProgress(10);
        ActiveModel replacement = model(80); hull.updateModel(replacement); hull.tick(List.of(viewer));
        verify(viewer).spawnParticle(eq(Particle.FLAME), eq(new Location(world, 0, 80, 0)), eq(0),
                anyDouble(), anyDouble(), anyDouble(), eq(.2));
    }

    @Test void engineCopiesConfigurationButNotLiveThrottleAndScalesTurnRate() {
        YamlConfiguration config = config(true); Engine template = new Engine(config); EngineFixture f = engine(template);
        assertTrue(f.engine.requiresStart()); assertFalse(f.engine.isStarted());
        assertEquals(1, template.getBaseSpeed()); assertEquals(2.5, template.getBaseTurnRate());
        assertEquals(2.5, template.getTurnRate());
        f.throttle().setThrottle(25); assertEquals(0, template.getThrottle().getCurrent());
        assertEquals(.25, f.engine.getSpeed()); assertEquals(2.5, f.engine.getTurnRate());
        when(f.behaviour.turnScale()).thenReturn(true); assertEquals(.625, f.engine.getTurnRate());
        assertSame(template.getSounds(), f.engine.getSounds());
        assertSame(template.getParticles(), f.engine.getParticles());
        assertSame(template.getParticleBones(), f.engine.getParticleBones());
    }

    @Test void tapePlaybackStartsEngineAndControlsThrottleWithoutRecording() {
        EngineFixture f = engine(true); when(f.train.playbackThrottle(any())).thenReturn(30);
        f.engine.tick(List.of()); assertTrue(f.engine.isStarted()); assertEquals(1, f.throttle().getCurrent());
        verify(f.controls).input(null, Input.MOVE); verify(f.train, never()).maybeRecordSample(anyInt());
        when(f.train.playbackThrottle(any())).thenReturn(-30); f.engine.tick(List.of());
        assertEquals(0, f.throttle().getCurrent());
        fuelRequired(); EngineFixture empty = engine(true); when(empty.train.playbackThrottle(any())).thenReturn(30);
        empty.engine.tick(List.of()); assertFalse(empty.engine.isStarted());
    }

    @Test void recordingPreservesThrottleAndRootTrainCollectsTenderFuel() {
        EngineFixture f = engine(false); f.throttle().setThrottle(30); f.engine.tick(List.of());
        verify(f.train).maybeRecordSample(30);
        f.engine.getFuelTank().setFuel(20); f.engine.slowTick(List.of());
        assertEquals(18, f.engine.getFuelTank().getCurrent());
        verify(f.train).drainFromChild(f.engine.getFuelTank());
        when(f.vehicle.hasParent()).thenReturn(true); f.engine.slowTick(List.of());
        verify(f.train, times(1)).drainFromChild(any());
    }

    @Test void stoppedEngineConsumesNoFuelAndMakesNoSound() {
        EngineFixture f = engine(true); f.engine.getFuelTank().setFuel(10); f.engine.slowTick(List.of());
        assertEquals(10, f.engine.getFuelTank().getCurrent()); verifyNoInteractions(world);
    }

    @ParameterizedTest @ValueSource(ints = {-100, 50, 120})
    void engineSoundPitchIsClampedToItsAudibleRange(int throttle) {
        SoundData sound = mock(SoundData.class);
        MockedStatic<SoundLoader> sounds = keep(mockStatic(SoundLoader.class));
        sounds.when(() -> SoundLoader.getSoundsFromConfig(any())).thenReturn(List.of(sound));
        YamlConfiguration config = config(false); config.createSection("sounds.engine");
        EngineFixture f = engine(new Engine(config)); f.throttle().setThrottle(throttle);
        Player listener = mock(Player.class); f.engine.playSound(List.of(listener));
        verify(sound).playSound(eq(List.of(listener)), eq(new Location(world, 0, 64, 0)), eq(new Vector()),
                eq(Math.max(.5f, Math.min(1.6f, .5f + throttle / 100f))));
    }

    @Test void engineExhaustUsesVectorBonesAndUpdatesWhenTheModelChanges() {
        ParticleData particle = mock(ParticleData.class);
        MockedStatic<ParticleLoader> particles = keep(mockStatic(ParticleLoader.class));
        particles.when(() -> ParticleLoader.getParticlesFromConfig(any())).thenReturn(List.of(particle));
        YamlConfiguration config = config(false); config.createSection("particles.exhaust");
        config.set("particle-bones", List.of("base.align"));
        EngineFixture f = engine(new Engine(config)); f.throttle().setThrottle(30); f.engine.tick(List.of());
        verify(particle).spawnParticle(new Location(world, 0, 65, 0), new Vector(0, 1, 0));
        f.engine.updateModel(model(80)); f.engine.tick(List.of());
        verify(particle).spawnParticle(new Location(world, 0, 80, 0), new Vector(0, 1, 0));
    }

    @Test void unattendedGroundedEngineReturnsToIdleAndStops() {
        EngineFixture f = engine(true); when(f.train.isRecording()).thenReturn(false);
        when(f.seats.hasPassengers()).thenReturn(false); f.engine.setStarted(true); f.throttle().setThrottle(2);
        f.engine.tick(List.of()); assertEquals(1, f.throttle().getCurrent());
        f.engine.tick(List.of()); assertEquals(0, f.throttle().getCurrent()); assertFalse(f.engine.isStarted());
        assertEquals(1, later.size()); later.get(0).run();
    }

    @Test void delayedIdleCheckStopsOnlyAnEngineThatRemainsIdle() {
        EngineFixture f = engine(true); when(f.vehicle.isTrain()).thenReturn(false); f.engine.setStarted(true);
        f.throttle().setThrottle(20); f.engine.tick(List.of()); f.engine.tick(List.of());
        assertEquals(1, later.size()); later.get(0).run(); assertTrue(f.engine.isStarted());
        f.engine.tick(List.of()); assertEquals(2, later.size());
        f.throttle().setThrottle(0); later.get(1).run(); assertFalse(f.engine.isStarted());
    }

    @Test void flyingEngineRetainsThrottleWithoutPassengers() {
        EngineFixture f = engine(true); when(f.vehicle.isTrain()).thenReturn(false);
        when(f.seats.hasPassengers()).thenReturn(false); when(f.states.getCurrentState().getType()).thenReturn(State.FLYING);
        f.throttle().setThrottle(-20); f.engine.tick(List.of()); assertEquals(-20, f.throttle().getCurrent());
    }

    @Test void healthyEngineStartsOncePerCooldownAndStopsOnRequest() {
        EngineFixture f = engine(true); Player player = mock(Player.class);
        f.engine.start(player); f.engine.start(player);
        assertTrue(f.engine.isStarted()); assertEquals(1, f.throttle().getCurrent());
        verify(player).sendMessage("§e[Motor] §astarted!"); f.engine.stop();
        assertFalse(f.engine.isStarted()); assertEquals(0, f.throttle().getCurrent());
        EngineFixture automatic = engine(false); automatic.throttle().setThrottle(20); automatic.engine.stop();
        assertTrue(automatic.engine.isStarted()); assertEquals(20, automatic.throttle().getCurrent());
    }

    @Test void startEffectCompletesBeforeTheEngineProducesPower() {
        EngineFixture f = engine(true); CustomEffect effect = mock(CustomEffect.class);
        when(f.vehicle.hasEffect(CustomAction.ENGINE_START)).thenReturn(true);
        when(f.vehicle.playEffect(CustomAction.ENGINE_START)).thenReturn(effect);
        f.engine.start(mock(Player.class)); f.engine.start(mock(Player.class));
        assertEquals(1, timers.size()); timers.get(0).run(); assertFalse(f.engine.isStarted());
        when(effect.isFinished()).thenReturn(true); timers.get(0).run(); assertTrue(f.engine.isStarted());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void damagedEngineReportsFailedStartAndAllowsAnotherAttempt(boolean custom) {
        EngineFixture f = engine(true); Engine engine = spy(f.engine); engine.getHealthData().setDamage(60);
        doReturn(80.).when(engine).rollStartChance();
        CustomEffect effect = mock(CustomEffect.class); when(f.vehicle.hasEffect(CustomAction.ENGINE_START_FAIL)).thenReturn(custom);
        when(f.vehicle.playEffect(CustomAction.ENGINE_START_FAIL)).thenReturn(effect);
        Player player = mock(Player.class); engine.start(player); assertFalse(engine.isStarted());
        if (custom) {
            timers.get(0).run(); verify(player, never()).sendMessage(contains("failed"));
            when(effect.isFinished()).thenReturn(true); timers.get(0).run();
        }
        verify(player).sendMessage("§e[Motor] §cfailed!");
        doReturn(0.).when(engine).rollStartChance(); engine.start(mock(Player.class)); assertTrue(engine.isStarted());
    }

    @Test void fuelRequiredForStartingAndAnAlreadyOffEmptyEngineStaysIdle() {
        fuelRequired(); EngineFixture f = engine(true); Player player = mock(Player.class);
        f.engine.start(player); assertFalse(f.engine.isStarted()); verify(player).sendMessage("§e[Motor] §cNo fuel!");
        f.throttle().setThrottle(20); f.engine.tick(List.of()); assertEquals(0, f.throttle().getCurrent());
    }

    @Test void hullDamageUsesConfiguredArmorAndLargeHitsIgniteTheVehicle() {
        EngineFixture f = engine(false); YamlConfiguration config = config(false);
        config.set("damage-chance", 1); config.set("damage", List.of("FALL(0.5)"));
        Hull template = new Hull(config), hull = new Hull(template, f.vehicle, f.model, null);
        assertTrue(hull.isFatal()); assertEquals(Component.HULL, hull.getType());
        assertEquals(1, hull.getDamageChance()); assertSame(template.getDamageData(), hull.getDamageData());
        hull.damage("FALL", 10); assertEquals(5, hull.getHealthData().getDamage());
        when(f.vehicle.hasEffect(CustomAction.FIRE_START)).thenReturn(true);
        hull.damage("EXPLOSION", 10_000);
        assertEquals(100, hull.getHealthData().getDamage()); assertTrue(hull.isOnFire());
        verify(f.vehicle).playEffect(CustomAction.FIRE_START);
        hull.getFire().setProgress(100); hull.slowTick(List.of());
        assertEquals(100, hull.getFire().getProgress());
        hull.getFire().setProgress(0); hull.slowTick(List.of()); assertFalse(hull.isOnFire());
    }

    @Test void savedComponentRestoresDamageAndFireAndRepairsOnSchedule() {
        EngineFixture f = engine(false); YamlConfiguration config = config(false); config.set("repair-time", 1);
        Hull hull = new Hull(new Hull(config), f.vehicle, f.model,
                new IncompleteComponent(Component.HULL, 20, 30, 0));
        assertEquals(20, hull.getHealthData().getDamage()); assertEquals(30, hull.getFire().getProgress());
        hull.getHealthData().startRepair(); hull.tick(List.of());
        assertFalse(hull.getHealthData().isUnderRepair());
        assertTrue(hull.getHealthData().getDamage() >= 6 && hull.getHealthData().getDamage() <= 13);
        assertNotNull(hull.getVfx()); assertEquals("Motor", hull.getAlias());
    }

    @Test void fireEffectsBoundParticleDensityAndSurviveMissingSkinBones() {
        EngineFixture f = engine(false); YamlConfiguration config = config(false); config.set("vfx", List.of("missing", "base"));
        when(f.model.getBone("missing")).thenReturn(Optional.empty());
        Hull hull = new Hull(new Hull(config), f.vehicle, f.model, null); Player viewer = mock(Player.class);
        assertEquals(1, hull.getVfx().getModelBones().size());
        hull.startFire(); hull.getFire().setProgress(100); hull.tick(List.of(viewer));
        verify(viewer, times(3)).spawnParticle(eq(Particle.FLAME), eq(new Location(world, 0, 65, 0)), eq(0),
                anyDouble(), anyDouble(), anyDouble(), eq(.2));
        ActiveModel replacement = model(80); when(replacement.getBone("base")).thenReturn(Optional.empty());
        hull.updateModel(replacement); hull.getFire().setProgress(0); hull.tick(List.of(viewer));
        verify(viewer, times(4)).spawnParticle(eq(Particle.FLAME), eq(new Location(world, 0, 65, 0)), eq(0),
                anyDouble(), anyDouble(), anyDouble(), eq(.2));
        hull.slowTick(List.of()); assertFalse(hull.isOnFire());
    }

    @Test void fireReportsSeverityAndBoundsDamageGrowthAndExtinguishing() {
        EngineFixture f = engine(false); Hull hull = new Hull(new Hull(config(false)), f.vehicle, f.model, null);
        hull.startFire(); var fire = hull.getFire();
        assertTrue(fire.getProgress() >= 1 && fire.getProgress() <= 7);
        fire.setProgress(30); assertEquals("§eSmall Fire: §630%", fire.getFireString()); assertFalse(fire.engulfed());
        fire.setProgress(50); assertEquals("§cWidespread Fire!: §650%", fire.getFireString());
        fire.fight(); assertTrue(fire.getProgress() >= 47 && fire.getProgress() <= 50);
        fire.setProgress(80); assertEquals("§4Massive Fire!!: §680%", fire.getFireString());
        fire.setProgress(110); fire.tick(); assertEquals(100, fire.getProgress()); assertTrue(fire.engulfed());
        fire.setProgress(-1); fire.fight(); assertEquals(0, fire.getProgress()); assertFalse(fire.engulfed());
        hull.slowTick(List.of()); assertFalse(hull.isOnFire());
    }

    @Test void floodingGrowsWithHullDamageAndRecoversWithAndWithoutAPump() {
        EngineFixture f = engine(false); when(f.behaviour.shouldFloat()).thenReturn(true);
        YamlConfiguration config = config(false);
        SinkableHull hull = new SinkableHull(f.vehicle, new SinkableHull(config), f.model,
                new IncompleteComponent(Component.HULL, 21, 0, 0));
        assertFalse(hull.hasPump()); assertFalse(hull.isSinking());
        hull.slowTick(List.of()); assertTrue(hull.isSinking()); assertEquals(1, hull.getSinkProgress());
        hull.getHealthData().setDamage(100); hull.setSinkProgress(99); hull.slowTick(List.of());
        assertEquals(100, hull.getSinkProgress());
        hull.getHealthData().setDamage(0); hull.slowTick(List.of());
        assertFalse(hull.isSinking()); assertEquals(99, hull.getSinkProgress());
        config.set("power", 40); Pump pump = new Pump(config); hull.setPump(pump);
        assertTrue(hull.hasPump()); assertSame(pump, hull.getPump());
        hull.setSinkProgress(20); hull.slowTick(List.of()); assertEquals(16, hull.getSinkProgress());
        when(f.behaviour.shouldFloat()).thenReturn(false); hull.slowTick(List.of()); assertEquals(16, hull.getSinkProgress());
        when(f.behaviour.shouldFloat()).thenReturn(true); hull.setSinkProgress(0); hull.slowTick(List.of());
        assertFalse(hull.hasSinkProgress());
        config.set("power", 0); hull.setPump(new Pump(config)); hull.getHealthData().setDamage(30);
        hull.slowTick(List.of()); assertEquals(2, hull.getSinkProgress());
    }

    @Test void damagedPumpAndWingsLosePowerWithoutNegativeLift() {
        EngineFixture f = engine(false); YamlConfiguration config = config(false);
        config.set("power", 80); config.set("lift", .8); config.set("turn-rate", .4); config.set("damage-factor", 2);
        Pump pump = new Pump(new Pump(config), f.vehicle, f.model, new IncompleteComponent(Component.PUMP, 25, 0, 0));
        assertEquals(80, pump.getBasePower()); assertEquals(60, pump.getPower());
        Wings wings = new Wings(f.vehicle, new Wings(config), f.model, null);
        when(f.vehicle.getSpeed()).thenReturn(.5); assertEquals(.2, wings.getTurnRate(), 1e-7);
        assertEquals(.8, wings.getBaseLift()); assertEquals(2, wings.getDamageFactor());
        assertEquals(.8, wings.getLift()); wings.getHealthData().setDamage(75); assertEquals(0, wings.getLift());
        wings.slowTick(List.of());
    }

    @Test void balloonLiftRespondsToDamageAndItsControlDeltaDecaysSmoothly() {
        EngineFixture f = engine(false); YamlConfiguration config = config(false); config.set("lift", .5);
        Balloon balloon = new Balloon(new Balloon(config), f.vehicle, f.model, null);
        assertEquals(.5, balloon.getBaseLift()); assertEquals(.5, balloon.getLift());
        balloon.setDelta(.08); assertEquals(.03, balloon.getDelta(), 1e-9); assertEquals(0, balloon.getDelta());
        balloon.setDelta(-.08); assertEquals(-.03, balloon.getDelta(), 1e-9); assertEquals(0, balloon.getDelta());
        balloon.getHealthData().setDamage(50); assertEquals(0, balloon.getLift());
        when(f.vehicle.isDestroyed()).thenReturn(true); assertEquals(-2, balloon.getLift());
        balloon.slowTick(List.of());
    }

    @Test void throttleLimitsInputNormalizesReverseAndHonoursDamagedAccelerationCap() {
        EngineFixture f = engine(false); Throttle throttle = f.throttle();
        throttle.setThrottle(500); assertEquals(120, throttle.getCurrent());
        throttle.increase(); assertEquals(120, throttle.getCurrent());
        throttle.setThrottle(-500); assertEquals(-100, throttle.getCurrent());
        throttle.decrease(); assertEquals(-100, throttle.getCurrent());
        throttle.change(-100); assertEquals(-100, throttle.getCurrent());
        throttle.change(500); assertEquals(120, throttle.getCurrent());
        throttle.setThrottle(-2); throttle.normalize(); assertEquals(-1, throttle.getCurrent());
        throttle.normalize(); assertEquals(0, throttle.getCurrent());
        f.engine.getHealthData().setDamage(50); throttle.setThrottle(60); throttle.increase();
        assertEquals(60, throttle.getCurrent()); throttle.decrease(); throttle.increase(); assertEquals(60, throttle.getCurrent());
        throttle.stepToward(60); assertEquals(60, throttle.getCurrent());
    }

    @Test void containerCopyResolvesModelBonesAndKeepsItsSeatAndAllowList() {
        EngineFixture f = engine(false); YamlConfiguration config = containerConfig();
        config.set("bones", List.of("base", "missing")); config.set("allow-items", List.of("coal", "", " "));
        when(f.model.getBone("missing")).thenReturn(Optional.empty());
        Container template = new Container("cargo", config), container = new Container(f.vehicle, template);
        assertEquals("cargo", container.getId()); assertEquals("Cargo", container.getName());
        assertEquals(9, container.getSize()); assertEquals("passenger", container.getSeat());
        assertEquals(List.of("base", "missing"), template.getBoneList());
        assertEquals(List.of("coal"), container.getAllowItems());
        assertEquals(List.of(f.model.getBone("base").orElseThrow()), container.getBones());
    }

    @Test void containerFilterReturnsDisallowedItemsAndDropsInventoryOverflow() {
        ItemAPI api = items(); Container container = container(List.of("coal"));
        ItemStack coal = stack("coal", 3), rejected = stack("sand", 2), air = stack("air", 1);
        Inventory inventory = inventory(9); inventory.setItem(0, coal); inventory.setItem(1, rejected); inventory.setItem(2, air);
        Player player = mock(Player.class); PlayerInventory backpack = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(backpack); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        ItemStack leftover = stack("sand", 1); when(backpack.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0, leftover)));
        container.stripDisallowed(inventory, player);
        assertSame(coal, inventory.getItem(0)); assertNull(inventory.getItem(1)); assertSame(air, inventory.getItem(2));
        verify(world).dropItemNaturally(any(Location.class), same(leftover));
        assertTrue(container.allows(null)); assertTrue(container.allows(air)); assertTrue(container.allows(coal));
        assertFalse(container.allows(rejected)); assertTrue(container(List.of()).allows(rejected));
        when(api.getChecker().checkItemWithPath(rejected, "coal")).thenThrow(new IllegalStateException("Unknown item template"));
        assertFalse(container.allows(rejected));
        container.stripDisallowed(null, player); container(List.of()).stripDisallowed(inventory, player);
    }

    @Test void givingContainerItemsHandlesEmptyStacksNullPlayersAndNoDropWorld() {
        Container container = container(List.of()); Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        ItemStack item = stack("coal", 3); container.giveOrDrop(null, item); container.giveOrDrop(player, null);
        container.giveOrDrop(player, stack("air", 1)); verifyNoInteractions(player);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        container.giveOrDrop(player, item); verify(player, never()).getWorld();
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0, item)));
        container.giveOrDrop(player, item); verify(player).getWorld(); verifyNoInteractions(world);
    }

    @Test void closedContainerTakesOneMatchingFuelAndPreservesOtherSlots() {
        ItemAPI api = items(); Container container = container(List.of());
        ItemStack wrong = stack("sand", 3), coal = stack("coal", 2), problematic = stack("broken", 1);
        container.getItems().addAll(Arrays.asList(null, stack("air", 1), wrong, problematic, coal));
        when(api.getChecker().checkItemWithPath(problematic, "coal")).thenThrow(new IllegalStateException("unavailable"));
        assertNull(container.takeOneMatching(null)); assertNull(container.takeOneMatching(" "));
        ItemStack one = container.takeOneMatching("coal"); assertEquals(1, one.getAmount()); assertNotSame(coal, one);
        assertEquals(1, coal.getAmount()); assertSame(wrong, container.getItems().get(2));
        assertNotNull(container.takeOneMatching("coal")); assertNull(container.getItems().get(4));
        assertNull(container.takeOneMatching("coal"));
    }

    @Test void openContainerSharesOneInventoryAndFuelDrainSynchronizesSavedContents() {
        items(); Container container = container(List.of()); EngineFixture f = engine(false);
        Inventory live = inventory(9); bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), eq("Cargo"))).thenReturn(live);
        ItemStack coal = stack("coal", 2); container.getItems().add(coal);
        Player first = mock(Player.class), second = mock(Player.class);
        container.open(f.vehicle, first); container.open(f.vehicle, second);
        verify(first).openInventory(live); verify(second).openInventory(live);
        assertSame(coal, live.getItem(0)); assertNull(live.getItem(1));
        ItemStack one = container.takeOneMatching("coal"); assertEquals(1, one.getAmount());
        assertEquals(1, live.getItem(0).getAmount()); assertEquals(1, container.getItems().get(0).getAmount());
        container.close(live); assertNotSame(live.getItem(0), container.getItems().get(0));
        Inventory external = inventory(9); ItemStack iron = stack("iron", 4); external.setItem(3, iron);
        container.close(external); assertEquals(4, live.getItem(3).getAmount()); assertNull(live.getItem(0));
        assertNotSame(iron, container.getItems().get(3)); container.close(null);
    }

    @Test void loadingAnEmptyContainerSnapshotClearsItsOpenInventory() {
        Container container = container(List.of()); EngineFixture f = engine(false);
        Inventory live = inventory(9); bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), eq("Cargo"))).thenReturn(live);
        container.getItems().add(stack("coal", 2)); container.open(f.vehicle, mock(Player.class));
        container.loadFromJson(new JsonObject());
        assertNull(live.getItem(0), "Loading empty contents must replace the current live contents");
        assertTrue(container.getItems().stream().allMatch(Objects::isNull));
    }

    @Test void containerSnapshotRoundTripKeepsSnbtAndFindsFreeSlotsForDuplicates() {
        NbtFixture nbt = nbt(); Container container = container(List.of());
        nbt.decoded.put("coal:3", stack("coal", 3)); nbt.decoded.put("iron:2", stack("iron", 2));
        nbt.decoded.put("sand:1", stack("sand", 1));
        JsonObject json = payload(entry(3, new JsonPrimitive("coal:3")), entry(3, new JsonPrimitive("iron:2")),
                entry("bad-slot", new JsonPrimitive("sand:1")));
        container.loadFromJson(json);
        assertEquals("coal", itemPaths.get(container.getItems().get(3)));
        assertEquals("iron", itemPaths.get(container.getItems().get(0)));
        assertEquals("sand", itemPaths.get(container.getItems().get(1)));
        JsonObject saved = container.getAsJson(); assertEquals("cargo", saved.get("id").getAsString());
        assertEquals(3, saved.getAsJsonArray("items").size());
        assertEquals("coal:3", saved.getAsJsonArray("items").get(2).getAsJsonArray().get(1).getAsString());
    }

    @Test void failedAndExcessContainerEntriesArePreservedWithoutLosingLoadedItems() {
        NbtFixture nbt = nbt(); YamlConfiguration config = containerConfig(); config.set("size", 1);
        Container container = new Container("tiny", config);
        nbt.decoded.put("coal:3", stack("coal", 3));
        JsonArray valid = entry(0, new JsonPrimitive("coal:3"));
        JsonArray excess = entry(9, new JsonPrimitive("coal:3"));
        JsonArray broken = entry(4, new JsonPrimitive("broken-snbt"));
        JsonArray empty = entry(5, JsonNull.INSTANCE);
        JsonObject json = payload(valid, excess, broken, empty);
        json.getAsJsonArray("items").add("not-an-entry"); JsonArray malformed = new JsonArray(); malformed.add(7); json.getAsJsonArray("items").add(malformed);
        container.loadFromJson(json); JsonArray saved = container.getAsJson().getAsJsonArray("items");
        assertEquals(4, saved.size()); assertEquals(excess, saved.get(1)); assertEquals(broken, saved.get(2)); assertEquals(empty, saved.get(3));
        Location location = new Location(world, 0, 64, 0); container.destroy(location);
        verify(world).dropItem(location, container.getItems().get(0));
    }

    @Test void legacyContainerItemFallsBackToOriginalEncodingWhenRestoredTypesFail() {
        NbtFixture nbt = nbt(); Container container = container(List.of());
        JsonObject legacy = JsonParser.parseString("{count:'1b',id:'coal'}").getAsJsonObject();
        nbt.decoded.put(legacy.toString(), stack("coal", 1));
        container.loadFromJson(payload(entry(0, legacy)));
        assertEquals("coal", itemPaths.get(container.getItems().get(0)));
        assertEquals(1, container.getAsJson().getAsJsonArray("items").size());
    }

    @Test void destroyingAnOpenContainerUsesItsCurrentLiveStacksOnly() {
        Container container = container(List.of()); EngineFixture f = engine(false);
        Inventory live = inventory(9); bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), eq("Cargo"))).thenReturn(live);
        container.open(f.vehicle, mock(Player.class)); ItemStack item = stack("coal", 5); live.setItem(4, item); live.setItem(5, stack("air", 1));
        container.destroy(new Location(world, 0, 64, 0));
        verify(world).dropItem(any(Location.class), argThat(stack -> "coal".equals(itemPaths.get(stack)) && stack.getAmount() == 5));
    }

    @Test void containerHandlerOpensOnlyTheRidersAssignedStorageAndDestroysItsCargo() {
        EngineFixture f = engine(false); YamlConfiguration config = new YamlConfiguration();
        config.set("cargo.name", "Cargo"); config.set("cargo.size", 9); config.set("cargo.seat", "passenger");
        ContainerHandler handler = new ContainerHandler(f.vehicle, f.model, new ContainerHandler(config));
        assertEquals(1, handler.getContainers().size()); assertSame(handler.get("CARGO"), handler.getBySeat("PASSENGER"));
        assertNull(handler.get("missing")); assertNull(handler.getBySeat("missing"));
        Player player = mock(Player.class); assertFalse(handler.open(player));
        when(f.vehicle.getSeat(player)).thenReturn(new Seat(SeatType.PASSENGER, "missing")); assertFalse(handler.open(player));
        when(f.vehicle.getSeat(player)).thenReturn(new Seat(SeatType.PASSENGER, "passenger"));
        Inventory live = inventory(9); bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), eq("Cargo"))).thenReturn(live);
        assertTrue(handler.open(player)); verify(player).openInventory(live);
        ItemStack coal = stack("coal", 2); live.setItem(0, coal); handler.destroy(new Location(world, 0, 64, 0));
        verify(world).dropItem(any(Location.class), argThat(item -> item.getAmount() == 2));
        assertFalse(new ContainerHandler(new YamlConfiguration()).open(player));
    }

    @Test void harnessUsesHorseSpeedAndRefusesMoreMountsThanConfiguredSeats() {
        EngineFixture f = engine(false); Harness harness = harness(f, "base", "align");
        assertFalse(harness.hasMounts()); assertTrue(harness.getMountedEntities().isEmpty());
        Player driver = mock(Player.class); AbstractHorse horse = horse(.4); LivingEntity creature = mock(LivingEntity.class);
        harness.mount(driver, horse); harness.mount(driver, creature); harness.mount(driver, mock(Entity.class));
        assertTrue(harness.hasMounts()); assertEquals(.2, harness.getSpeed());
        assertEquals(List.of(horse, creature), harness.getMountedEntities());
        verify(driver).sendMessage("§cThere is no room to hitch the creature");
        harness.tick(List.of()); verify(f.panel).setSpeed(.2); verify(f.panel).setTurnRate(2.5);
        harness.slowTick(List.of());
    }

    @Test void harnessDismountDropsLeadReturnsHorseToPlayerAndPreventsNegativeSpeed() {
        EngineFixture f = engine(false); Harness harness = harness(f, "base", "align");
        keep(mockConstruction(ItemStack.class)); Player driver = mock(Player.class); AbstractHorse horse = horse(.4);
        assertFalse(harness.dismount(driver)); harness.mount(driver, horse);
        when(horse.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED).getBaseValue()).thenReturn(.8);
        assertTrue(harness.dismount(driver)); assertEquals(0, harness.getSpeed()); assertFalse(harness.hasMounts());
        verify(horse).teleport(driver); verify(world).dropItem(any(Location.class), any(ItemStack.class));
    }

    @Test void mountedEntitiesFollowTheModelBoneAndDeadMountsAreRemoved() {
        EngineFixture f = engine(false); Harness harness = harness(f, "base", "align");
        keep(mockConstruction(ItemStack.class));
        net.tfminecraft.vehicleframework.bones.VectorBone vector = mock(net.tfminecraft.vehicleframework.bones.VectorBone.class);
        when(f.behaviour.getVector()).thenReturn(vector); when(vector.getVector()).thenReturn(new Vector(1, 0, 0));
        Entity cargo = mock(Entity.class); when(cargo.getWorld()).thenReturn(world); when(cargo.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        AbstractHorse horse = horse(.4); harness.mount(mock(Player.class), cargo); harness.mount(mock(Player.class), horse);
        harness.syncMountedEntities(); verify(cargo).teleport(new Location(world, 0, 65, 0, -90, 0));
        verify(horse).teleport(new Location(world, 0, 66, 0, -90, 0));
        assertEquals(List.of(horse), harness.getMountedEntities());
        when(cargo.isDead()).thenReturn(true); harness.syncMountedEntities();
        assertFalse(harness.getMounts().get(0).isOccupied()); assertTrue(harness.hasMounts());
        harness.syncMountedEntities();
        verify(cargo).teleport(new Location(world, 0, 65, 0, -90, 0));
        verify(horse, times(3)).teleport(new Location(world, 0, 66, 0, -90, 0));
    }

    @Test void harnessWaitsForMissingModelDirectionAndMountBones() {
        YamlConfiguration config = config(false); Harness template = new Harness(config); template.syncMountedEntities();
        assertTrue(template.getMounts().isEmpty()); assertEquals(2.5, template.getTurnRate());
        EngineFixture f = engine(false); Harness harness = harness(f, "missing"); Entity cargo = mock(Entity.class);
        harness.mount(mock(Player.class), cargo);
        when(f.vehicle.getModel()).thenReturn(null); harness.syncMountedEntities();
        when(f.vehicle.getModel()).thenReturn(f.model); when(f.vehicle.getBehaviourHandler()).thenReturn(null); harness.syncMountedEntities();
        when(f.vehicle.getBehaviourHandler()).thenReturn(f.behaviour); harness.syncMountedEntities();
        net.tfminecraft.vehicleframework.bones.VectorBone vector = mock(net.tfminecraft.vehicleframework.bones.VectorBone.class);
        when(f.behaviour.getVector()).thenReturn(vector); harness.syncMountedEntities();
        when(vector.getVector()).thenReturn(new Vector(0, 0, 1)); when(f.model.getBone("missing")).thenReturn(Optional.empty());
        harness.syncMountedEntities(); verify(cargo, never()).teleport(any(Location.class));
    }

    @Test void harnessSteeringScalesWithVelocityAndStopsBelowItsMovementThreshold() {
        EngineFixture f = engine(false); Harness harness = harness(f, "base");
        assertEquals(2.5, harness.getTurnRate()); when(f.behaviour.turnScale()).thenReturn(true);
        when(f.entity.getVelocity()).thenReturn(new Vector(.01, 0, 0)); assertEquals(0, harness.getTurnRate());
        when(f.entity.getVelocity()).thenReturn(new Vector(.5, 0, 0)); assertEquals(1.25, harness.getTurnRate());
        when(f.entity.getVelocity()).thenReturn(new Vector(2, 0, 0)); assertEquals(2.5, harness.getTurnRate());
    }

    @Test void configuredFuelTankAcceptsOnlyPermittedStateAndIdleEngine() {
        Fuel fuel = mock(Fuel.class); fuels.when(() -> FuelLoader.getByString("coal")).thenReturn(fuel);
        YamlConfiguration config = config(false); config.set("fuel", "coal"); config.set("fuel-capacity", 100);
        config.set("refuel-states", List.of("ground", "not-a-state")); FuelTank tank = new FuelTank(config);
        assertTrue(tank.hasInput()); assertTrue(tank.useFuel()); assertEquals(List.of(State.GROUND), tank.getStates());
        EngineFixture f = engine(false); Player player = mock(Player.class);
        var currentState = f.states.getCurrentState(); when(f.vehicle.getCurrentState()).thenReturn(currentState);
        when(f.states.getCurrentState().getType()).thenReturn(State.FLYING); tank.refuel(player, f.vehicle, 10);
        verify(player).sendMessage(contains("Cannot refuel in this state")); assertEquals(0, tank.getCurrent());
        when(f.states.getCurrentState().getType()).thenReturn(State.GROUND); when(f.vehicle.getThrottle()).thenReturn(f.throttle());
        f.throttle().setThrottle(10); tank.refuel(player, f.vehicle, 10);
        verify(player).sendMessage("§cCannot refuel while the engine is on");
        tank.setFuel(100); tank.refuel(player, f.vehicle, 10); verify(player).sendMessage("§cFuel tank is full");
        tank.setFuel(0); f.throttle().setThrottle(0); tank.refuel(player, f.vehicle, -1); assertEquals(0, tank.getCurrent());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void successfulRefuelConsumesOneHeldItemAndPlaysConfiguredOrDefaultSound(boolean custom) {
        EngineFixture f = engine(false); Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack can = stack("fuel", 3); when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(can);
        when(player.getWorld()).thenReturn(world);
        Fuel fuel = mock(Fuel.class); when(fuel.getSound()).thenReturn(custom ? "refuel" : "");
        when(fuel.getSoundVolume()).thenReturn(.7f); when(fuel.getSoundPitch()).thenReturn(.9f);
        FuelTank tank = new FuelTank(90, 100, 2, List.of(), fuel); tank.refuel(player, f.vehicle, 20);
        assertEquals(100, tank.getCurrent()); assertEquals(100, tank.getPercentage()); assertEquals(2, can.getAmount());
        verify(player).sendMessage("§aFuel: §e100/100");
        if (custom) verify(world).playSound(any(Location.class), eq("refuel"), eq(SoundCategory.BLOCKS), eq(.7f), eq(.9f));
        else verify(world).playSound(any(Location.class), eq(Sound.ITEM_BUCKET_FILL), eq(1f), eq(.8f));
    }

    @Test void refuelWithoutWorldStillUpdatesFuelAndBurningCannotMakeFuelNegative() {
        EngineFixture f = engine(false); Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack item = stack("fuel", 2); when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(item);
        FuelTank tank = new FuelTank(0, 100, 2, List.of(), null); tank.refuel(player, f.vehicle, 1);
        assertEquals(1, tank.getCurrent()); assertEquals(1, item.getAmount());
        f.throttle().setThrottle(-100); tank.tick(f.throttle()); assertEquals(0, tank.getCurrent());
        assertFalse(tank.addFuel(0)); assertTrue(tank.addFuel(500)); assertEquals(100, tank.getCurrent());
        assertFalse(tank.addFuel(1)); tank.setFuel(-5); assertEquals(0, tank.getCurrent());
        tank.tick(f.throttle()); assertEquals(0, tank.getCurrent());
    }

    private EngineFixture engine(boolean requiresStart) { return engine(new Engine(config(requiresStart))); }
    private EngineFixture engine(Engine template) {
        ActiveVehicle vehicle = mock(ActiveVehicle.class); Entity entity = mock(Entity.class);
        ActiveModel model = model(65);
        when(vehicle.getEntity()).thenReturn(entity); when(vehicle.getModel()).thenReturn(model);
        when(entity.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(entity.getVelocity()).thenReturn(new Vector());
        BehaviourHandler behaviour = mock(BehaviourHandler.class); when(vehicle.getBehaviourHandler()).thenReturn(behaviour);
        SeatHandler seats = mock(SeatHandler.class); when(vehicle.getSeatHandler()).thenReturn(seats);
        when(seats.hasPassengers()).thenReturn(true);
        StateHandler states = mock(StateHandler.class, RETURNS_DEEP_STUBS); when(vehicle.getStateHandler()).thenReturn(states);
        when(states.getCurrentState().getType()).thenReturn(State.GROUND);
        TrainHandler train = mock(TrainHandler.class); when(vehicle.isTrain()).thenReturn(true);
        when(vehicle.getTrainHandler()).thenReturn(train); when(train.playbackThrottle(any())).thenReturn(null);
        when(train.isRecording()).thenReturn(true); when(train.getOverdrive()).thenReturn(new LocomotiveOverdrive());
        AccessPanel panel = mock(AccessPanel.class); when(vehicle.getAccessPanel()).thenReturn(panel);
        VehicleMovementController controls = mock(VehicleMovementController.class); when(vehicle.getMoveControls()).thenReturn(controls);
        Engine engine = new Engine(vehicle, template, entity, model, null);
        return new EngineFixture(vehicle, entity, model, engine, behaviour, seats, states, train, panel, controls);
    }
    private ActiveModel model(double y) {
        ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS); ModelBone base = mock(ModelBone.class), align = mock(ModelBone.class);
        when(base.getBoneId()).thenReturn("base"); when(align.getBoneId()).thenReturn("align");
        when(base.getLocation()).thenAnswer(call -> new Location(world, 0, y, 0));
        when(align.getLocation()).thenAnswer(call -> new Location(world, 0, y + 1, 0));
        when(model.getBone("base")).thenReturn(Optional.of(base)); when(model.getBone("align")).thenReturn(Optional.of(align));
        return model;
    }
    private YamlConfiguration config(boolean requiresStart) {
        YamlConfiguration config = new YamlConfiguration(); config.set("alias", "Motor"); config.set("health", 100);
        config.set("requires-start", requiresStart); config.set("speed", 1); config.set("turn-rate", 2.5);
        config.set("min", -100); config.set("max", 120); return config;
    }
    private void fuelRequired() {
        Fuel fuel = mock(Fuel.class); fuels.when(() -> FuelLoader.getByString("none")).thenReturn(fuel);
    }
    private YamlConfiguration containerConfig() {
        YamlConfiguration config = new YamlConfiguration(); config.set("name", "Cargo"); config.set("size", 9);
        config.set("seat", "passenger"); return config;
    }
    private Harness harness(EngineFixture f, String... mounts) {
        YamlConfiguration config = config(false); config.set("mount-bones", List.of(mounts));
        return new Harness(new Harness(config), f.vehicle, f.model, null);
    }
    private AbstractHorse horse(double speed) {
        AbstractHorse horse = mock(AbstractHorse.class);
        org.bukkit.attribute.AttributeInstance attribute = mock(org.bukkit.attribute.AttributeInstance.class);
        when(attribute.getBaseValue()).thenReturn(speed);
        when(horse.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)).thenReturn(attribute);
        when(horse.getWorld()).thenReturn(world); when(horse.getLocation()).thenReturn(new Location(world, 0, 64, 0)); return horse;
    }
    private Container container(List<String> allow) {
        YamlConfiguration config = containerConfig(); config.set("allow-items", allow); return new Container("cargo", config);
    }
    private ItemAPI items() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        MockedStatic<TLibs> libs = keep(mockStatic(TLibs.class)); libs.when(TLibs::getItemAPI).thenReturn(api);
        when(api.getChecker().checkItemWithPath(nullable(ItemStack.class), anyString())).thenAnswer(call ->
                call.<String>getArgument(1).equals(itemPaths.get(call.getArgument(0))));
        return api;
    }
    private ItemStack stack(String path, int amount) {
        ItemStack item = mock(ItemStack.class); Material material = mock(Material.class);
        when(material.isAir()).thenReturn(path.equals("air")); when(item.getType()).thenReturn(material);
        int[] count = {amount}; when(item.getAmount()).thenAnswer(call -> count[0]);
        doAnswer(call -> { count[0] = call.getArgument(0); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> stack(path, count[0])); itemPaths.put(item, path); return item;
    }
    private Inventory inventory(int size) {
        Inventory inventory = mock(Inventory.class); ItemStack[] contents = new ItemStack[size];
        when(inventory.getSize()).thenReturn(size); when(inventory.getContents()).thenAnswer(call -> contents.clone());
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[call.<Integer>getArgument(0)]);
        doAnswer(call -> { contents[call.<Integer>getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        return inventory;
    }
    private JsonArray entry(Object slot, JsonElement encoded) {
        JsonArray entry = new JsonArray(); if (slot instanceof Number number) entry.add(number); else entry.add(slot.toString());
        entry.add(encoded); return entry;
    }
    private JsonObject payload(JsonArray... entries) {
        JsonArray items = new JsonArray(); for (JsonArray entry : entries) items.add(entry);
        JsonObject json = new JsonObject(); json.add("items", items); return json;
    }
    private NbtFixture nbt() {
        NbtFixture f = new NbtFixture();
        keep(mockStatic(NBT.class, call -> {
            String method = call.getMethod().getName();
            if (method.equals("parseNBT")) {
                String encoded = call.getArgument(0);
                if (encoded.startsWith("broken")) throw new IllegalArgumentException("bad item");
                return f.compound(encoded);
            }
            if (method.equals("itemStackFromNBT")) return f.decoded.get(f.encoded.get(call.getArgument(0)));
            if (method.equals("itemStackToNBT")) {
                ItemStack item = call.getArgument(0); return f.compound(itemPaths.get(item) + ":" + item.getAmount());
            }
            return RETURNS_DEFAULTS.answer(call);
        }));
        return f;
    }
    private static final class NbtFixture {
        final Map<String, ItemStack> decoded = new HashMap<>();
        final Map<ReadWriteNBT, String> encoded = new IdentityHashMap<>();
        ReadWriteNBT compound(String text) {
            ReadWriteNBT value = mock(ReadWriteNBT.class); when(value.toString()).thenReturn(text); encoded.put(value, text); return value;
        }
    }
    private BukkitTask task(Runnable runnable, List<Runnable> target) {
        target.add(runnable); BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(later.size() + timers.size()); return task;
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private record EngineFixture(ActiveVehicle vehicle, Entity entity, ActiveModel model, Engine engine,
            BehaviourHandler behaviour, SeatHandler seats, StateHandler states, TrainHandler train,
            AccessPanel panel, VehicleMovementController controls) {
        Throttle throttle() { return engine.getThrottle(); }
    }
}
