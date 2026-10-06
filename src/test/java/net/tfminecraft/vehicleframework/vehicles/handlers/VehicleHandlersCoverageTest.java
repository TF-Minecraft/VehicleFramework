package net.tfminecraft.vehicleframework.vehicles.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.joml.Vector3f;
import com.google.gson.*;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.model.bone.manager.MountManager;
import com.ticxo.modelengine.api.model.bone.type.Mount;
import com.ticxo.modelengine.api.mount.controller.MountController;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.CustomAction;
import net.tfminecraft.vehicleframework.enums.Direction;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.loaders.*;
import net.tfminecraft.vehicleframework.projectiles.Fragment;
import net.tfminecraft.vehicleframework.util.ExplosionCreator;
import net.tfminecraft.vehicleframework.util.LightEffect;
import net.tfminecraft.vehicleframework.vehicles.*;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.component.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.skins.VehicleSkin;
import net.tfminecraft.vehicleframework.vehicles.handlers.utility.DirectionalLight;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.weapons.*;

class VehicleHandlersCoverageTest {
    @BeforeAll static void registry() { net.tfminecraft.vehicleframework.test.RegistryFixture.initialize(); }
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>(), timers = new ArrayList<>();
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final World world = mock(World.class);
    private MockedStatic<ModelEngineAPI> modelApi;
    private VehicleFramework previousPlugin;
    private String previousTicket;

    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin; previousTicket = Cache.ticketItem;
        VehicleFramework.plugin = mock(VehicleFramework.class); when(VehicleFramework.plugin.getName()).thenReturn("VehicleFramework");
        when(VehicleFramework.plugin.namespace()).thenReturn("vehicleframework");
        keep(mockStatic(VFLogger.class)); keep(mockStatic(PersistenceLog.class));
        var bukkit = keep(mockStatic(Bukkit.class)); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenAnswer(c -> task(c.getArgument(1), later));
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenAnswer(c -> task(c.getArgument(1), timers));
        modelApi = keep(mockStatic(ModelEngineAPI.class, RETURNS_DEEP_STUBS));
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin; Cache.ticketItem = previousTicket;
    }

    @Test void vehiclesWithoutComponentsSafelyIgnoreRandomFire() {
        ComponentHandler handler = new ComponentHandler(yaml());
        assertDoesNotThrow(handler::randomFire); assertTrue(handler.getComponents().isEmpty());
    }

    @Test void unknownSeatTypeUsesTheDocumentedPassengerFallback() {
        Seat seat = new Seat("visitor(bench)", "cart");
        assertEquals(SeatType.PASSENGER, seat.getType()); assertEquals("bench", seat.getBone());
    }

    @Test void malformedSeatReportsTheVehicleAndExpectedSyntax() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new Seat("captain", "cart"));
        assertTrue(failure.getMessage().contains("cart")); assertTrue(failure.getMessage().contains("seat"));
    }

    @Test void unknownFloatMaterialIsSkippedAndValidConfiguredMaterialsSurvive() {
        YamlConfiguration config = behaviourConfig(); config.set("float", true); config.set("float-in", List.of("water", "not_a_block", "lava"));
        BehaviourHandler handler = new BehaviourHandler(config);
        assertTrue(handler.shouldFloat()); assertEquals(List.of(Material.WATER, Material.LAVA), handler.getFloatsIn());
    }

    @Test void healthDecayNeverRepairsDamageAlreadyBeyondItsMinimumRemainingHealth() {
        assertEquals(99, VehicleHealthDecay.nextDamage(99, 100, .2, .03));
        assertEquals(99, VehicleHealthDecay.nextDamage(99, 100, 0, .03));
        ActiveVehicle vehicle = vehicle(); Hull hull = new Hull(new Hull(componentConfig()), vehicle, model(0), null);
        hull.getHealthData().setDamage(99); when(vehicle.getComponents()).thenReturn(List.of(hull));
        VehicleHealthDecay.applyToLive(vehicle, .2, .03); assertEquals(99, hull.getHealthData().getDamage());
        JsonObject stored = JsonParser.parseString("{components:{hull:{damage:99}}}").getAsJsonObject();
        Vehicle template = mock(Vehicle.class); ComponentHandler components = new ComponentHandler(yaml()); components.getComponents().add(hull);
        when(template.getComponentHandler()).thenReturn(components); when(template.getWeapons()).thenReturn(List.of());
        assertFalse(VehicleHealthDecay.applyToJson(stored, VehicleHealthDecay.lookupFromTemplate(template), .2, .03));
        assertEquals(99, stored.getAsJsonObject("components").getAsJsonObject("hull").get("damage").getAsDouble());
    }

    @Test void configuredDieBehaviorIsAvailableToTheVehicleDeathDispatcher() {
        YamlConfiguration config = vehicleConfig(); config.set("death.die.duration", 9);
        Vehicle template = new Vehicle("cart", config);
        assertTrue(template.getDeathData().stream().anyMatch(data -> data.getType() == VehicleDeath.DIE && data.getDuration() == 9));
    }

    @Test void occupiedSeatCannotAttachOrReparentASecondVehicle() {
        ActiveVehicle parent = vehicle(), first = vehicle(), second = vehicle();
        Seat seat = new Seat(parent, new Seat(SeatType.TOWING, "tow")); seat.mount(first, parent); seat.mount(second, parent);
        assertSame(first.getEntity(), seat.getEntity()); assertSame(first, seat.getMountedVehicle());
        verify(second, never()).setParent(any()); seat.dismount(); verify(first).setParent(null);
    }

    @Test void templateLoadsOptionalSystemsAndResolvesWeaponsAndEveryDeathType() {
        YamlConfiguration config = vehicleConfig(); config.set("name", "Harbour tug"); config.set("fixed", true); config.set("towable", true);
        config.set("seats", List.of("captain(driver)", "passenger(bench)"));
        config.set("behaviour.rotator", "base"); config.set("behaviour.vector", "base.align");
        config.set("custom-effects.engine_start", List.of()); config.set("weapons.cannon.name", "Cannon"); config.createSection("weapons.cannon.data");
        config.set("weapons.unavailable.template", "nonexistent-template-for-test");
        config.set("towing.bone", "tow"); config.set("containers.cargo.size", 9); config.set("utilities.horn.sound", "custom.horn");
        config.set("entity-seat-whitelist", List.of("horse", "pig"));
        for (VehicleDeath death : VehicleDeath.values()) config.set("death." + death.name().toLowerCase() + ".duration", 8);
        Vehicle template = new Vehicle("tug", config);
        assertEquals("tug", template.getId()); assertEquals("Harbour tug", template.getName()); assertEquals("red", template.getModel());
        assertTrue(template.isFixed()); assertTrue(template.isTowable()); assertNotNull(template.getBehaviourHandler());
        assertEquals(2, template.getSeatHandler().getSeats().size()); assertEquals(1, template.getWeapons().size()); assertEquals("cannon", template.getWeapons().get(0).getId());
        assertNotNull(template.getTowHandler()); assertNotNull(template.getContainerHandler()); assertNotNull(template.getUtilityHandler());
        assertEquals(List.of("horse", "pig"), template.getEntitySeatWhitelist()); assertNotNull(template.getSkinHandler());
        assertNotNull(template.getStateHandler()); assertNotNull(template.getComponentHandler()); assertNotNull(template.getEffectHandler());
        assertEquals(4, template.getDeathData().size());
        YamlConfiguration minimal = vehicleConfig(); minimal.set("model", null);
        Vehicle unnamed = new Vehicle("default-name", minimal); assertEquals("default-name", unnamed.getName());
        assertTrue(unnamed.getDeathData().isEmpty()); assertTrue(unnamed.getSeatHandler().getSeats().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"skins", "states", "components"})
    void missingRequiredTemplateSectionsReportTheirVehicleAndSection(String section) {
        YamlConfiguration config = vehicleConfig(); config.set(section, null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new Vehicle("broken-cart", config));
        assertTrue(failure.getMessage().contains("broken-cart")); assertTrue(failure.getMessage().contains(section));
    }

    @Test void healthDecayUsesTemplateHealthForLiveAndSerializedComponentsAndWeapons() {
        ActiveVehicle live = vehicle(); Hull hull = new Hull(new Hull(componentConfig()), live, model(0), null);
        ActiveWeapon gun = mock(ActiveWeapon.class); HealthData gunHealth = new HealthData(50, 0, 20); when(gun.getHealthData()).thenReturn(gunHealth);
        VehicleComponent missingHealth = mock(VehicleComponent.class); when(missingHealth.getType()).thenReturn(Component.PUMP);
        when(live.getComponents()).thenReturn(Arrays.asList(null, missingHealth, new Hull(componentConfig()), hull));
        when(live.getWeaponHandler().getWeapons()).thenReturn(Arrays.asList(null, mock(ActiveWeapon.class), gun));
        VehicleHealthDecay.applyToLive(live, .2, .03); assertEquals(20, hull.getHealthData().getDamage()); assertEquals(10, gunHealth.getDamage());
        VehicleHealthDecay.applyToLive(null, .2, .03); when(live.getComponents()).thenReturn(null); when(live.getWeaponHandler()).thenReturn(null);
        VehicleHealthDecay.applyToLive(live, .2, .03);
        Vehicle template = mock(Vehicle.class); ComponentHandler components = new ComponentHandler(yaml());
        components.getComponents().addAll(Arrays.asList(null, mock(VehicleComponent.class), missingHealth, hull));
        when(template.getComponentHandler()).thenReturn(components);
        Weapon weapon = mock(Weapon.class); when(weapon.getId()).thenReturn("Cannon"); when(weapon.getHealthData()).thenReturn(new HealthData(50, 0, 20));
        Weapon missingWeaponHealth = mock(Weapon.class); when(missingWeaponHealth.getId()).thenReturn("incomplete");
        when(template.getWeapons()).thenReturn(Arrays.asList(null, mock(Weapon.class), missingWeaponHealth, weapon));
        var lookup = VehicleHealthDecay.lookupFromTemplate(template);
        assertEquals(100, lookup.componentMaxHealth("HULL")); assertEquals(0, lookup.componentMaxHealth(null)); assertEquals(0, lookup.componentMaxHealth("absent"));
        assertEquals(50, lookup.weaponMaxHealth("Cannon")); assertEquals(50, lookup.weaponMaxHealth("cannon"));
        assertEquals(0, lookup.weaponMaxHealth(null)); assertEquals(0, lookup.weaponMaxHealth("absent"));
        assertEquals(0, VehicleHealthDecay.lookupFromTemplate(null).componentMaxHealth("hull"));
        assertFalse(VehicleHealthDecay.applyToJson(null, lookup, .2, .03)); assertFalse(VehicleHealthDecay.applyToJson(new JsonObject(), null, .2, .03));
        for (String damage : List.of("null", "\"bad\"", "{}", "[]", "0")) {
            JsonObject stored = JsonParser.parseString("{components:{hull:{damage:" + damage + ",fire:12},absent:{damage:1},bad:[]},weapons:{cannon:{}}}").getAsJsonObject();
            assertTrue(VehicleHealthDecay.applyToJson(stored, lookup, .2, .03));
            assertEquals(20, stored.getAsJsonObject("components").getAsJsonObject("hull").get("damage").getAsDouble());
            assertEquals(12, stored.getAsJsonObject("components").getAsJsonObject("hull").get("fire").getAsInt());
            assertEquals(10, stored.getAsJsonObject("weapons").getAsJsonObject("cannon").get("damage").getAsDouble());
        }
        assertFalse(VehicleHealthDecay.applyToJson(JsonParser.parseString("{components:[],weapons:null}").getAsJsonObject(), lookup, .2, .03));
        assertEquals(4, VehicleHealthDecay.nextDamage(4, 0, .2, .03)); assertEquals(0, VehicleHealthDecay.nextDamage(-50, 100, .2, .03));
    }

    @Test void effectsCloneConfiguredActionsAndPlayTheSelectedActionOnly() {
        YamlConfiguration config = yaml(); config.set("engine_start", List.of("delay(1)"));
        EffectHandler template = new EffectHandler(config); EffectHandler handler = new EffectHandler(template); ActiveVehicle vehicle = vehicle();
        assertTrue(handler.hasEffect(CustomAction.ENGINE_START)); assertFalse(handler.hasEffect(CustomAction.DIE));
        assertNotSame(template.getEffect(CustomAction.ENGINE_START), handler.getEffect(CustomAction.ENGINE_START));
        assertSame(handler.getEffect(CustomAction.DIE), handler.playEffect(List.of(), vehicle, CustomAction.DIE));
        var effect = handler.playEffect(List.of(), vehicle, CustomAction.ENGINE_START); assertFalse(effect.isFinished()); later.get(0).run(); assertTrue(effect.isFinished());
        assertFalse(new EffectHandler().hasEffect(CustomAction.ENGINE_START));
    }

    @Test void componentConfigurationClonesEverySupportedTypeAndRestoresPersistedDamage() {
        YamlConfiguration config = yaml();
        for (Component type : Component.values()) config.createSection(type.name().toLowerCase(), componentConfig().getValues(true));
        config.set("geared_engine.gears.first.name", "First"); config.set("geared_engine.gears.first.max", 100);
        config.createSection("geared_engine.gears.first.engine-sound"); config.createSection("geared_engine.gears.first.accelerate-sound");
        ComponentHandler template = new ComponentHandler(config); assertEquals(7, template.getComponents().size());
        ActiveVehicle vehicle = vehicle(); ActiveModel model = model(0); IncompleteVehicle saved = mock(IncompleteVehicle.class);
        when(saved.getComponents()).thenReturn(List.of(new IncompleteComponent(Component.HULL, 30, 0, 0)));
        ComponentHandler live = new ComponentHandler(vehicle, vehicle.getEntity(), model, saved, template);
        assertEquals(7, live.getComponents().size()); assertEquals(30, live.getComponent(Component.HULL).getHealthData().getDamage());
        assertTrue(live.hasComponent(Component.PUMP)); assertFalse(live.hasComponent(Component.STEERING));
        assertNull(live.getComponent(Component.STEERING)); assertEquals(1, live.getComponents(Component.WINGS).size());
        assertTrue(live.getComponents(Component.STEERING).isEmpty());
        live.damage("fall", 2); assertEquals(32, live.getComponent(Component.HULL).getHealthData().getDamage());
        live.updateModel(model(10)); live.slowTick();
        config.set("hull.sinkable", true); ComponentHandler sinkTemplate = new ComponentHandler(config);
        ComponentHandler sink = new ComponentHandler(vehicle, vehicle.getEntity(), model, null, sinkTemplate);
        assertInstanceOf(SinkableHull.class, sink.getComponent(Component.HULL));
        for (VehicleComponent component : sink.getComponents()) assertEquals(0, component.getHealthData().getDamage());
    }

    @Test void randomFireIgnitesAnAvailableComponentAndHandlesAnAlreadyBurningVehicle() {
        YamlConfiguration config = yaml(); config.createSection("hull", componentConfig().getValues(true));
        ActiveVehicle vehicle = vehicle(); ComponentHandler live = new ComponentHandler(vehicle, vehicle.getEntity(), model(0), null, new ComponentHandler(config));
        live.randomFire(); assertTrue(live.getComponent(Component.HULL).isOnFire());
        live.randomFire(); assertTrue(live.getComponent(Component.HULL).isOnFire()); live.slowTick();
    }

    @Test void behaviourCopiesFloatSettingsAndBindsRotatorsAndTargetsToReplacementModels() {
        ActiveVehicle vehicle = vehicle(); ActiveModel model = model(0);
        YamlConfiguration config = behaviourConfig(); config.set("float", true); config.set("secondary-rotators", List.of("second"));
        config.set("rotation-targets.level.pitch", 10); config.set("rotation-targets.level.interval", 2);
        config.createSection("train");
        BehaviourHandler template = new BehaviourHandler(config); BehaviourHandler live = new BehaviourHandler(vehicle, vehicle.getEntity(), model, template);
        assertTrue(live.shouldFloat()); assertEquals(List.of(Material.WATER), live.getFloatsIn()); assertTrue(live.turnScale());
        assertEquals("base.align", live.getVectorString()); assertEquals("base", live.getRotatorString());
        assertEquals(List.of("second"), template.getSecondaryRotatorStrings()); assertEquals(1, live.getRotationTargets().size());
        assertTrue(live.isTrain()); assertNotNull(live.getTrainHandler()); assertEquals(new Vector(0, 0, 1), live.getVector().getVector());
        live.tick(vehicle); live.updateModel(model(10)); assertEquals(10, live.getRotator().getBone().getLocation().getX());
        assertEquals(10, live.getVector().getBaseLocation().getX());
        assertFalse(new BehaviourHandler().shouldFloat()); assertFalse(new BehaviourHandler(yaml()).shouldFloat());
        config.set("float", false); assertTrue(new BehaviourHandler(config).getFloatsIn().isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void towingMaintainsAttachmentPoseReversalAndVehicleControls(boolean reversed) {
        ActiveVehicle vehicle = vehicle(), trailer = vehicle(); ActiveModel model = model(0); when(vehicle.getModel()).thenReturn(model);
        when(vehicle.getBehaviourHandler().getVector().getVector()).thenReturn(new Vector(1, 0, 0));
        YamlConfiguration config = yaml(); config.set("bone", "tow"); config.set("reversed", reversed);
        TowHandler handler = new TowHandler(vehicle, new TowHandler(config)); assertFalse(handler.isOccupied()); handler.tick(); handler.animate(Direction.FORWARD);
        handler.attach(trailer); handler.attach(vehicle); assertTrue(handler.isOccupied()); assertSame(trailer, handler.getTowPoint().getMountedVehicle());
        verify(trailer).setParent(vehicle); verify(trailer.getBehaviourHandler().getRotator()).reset(); handler.tick();
        ArgumentCaptor<Location> target = ArgumentCaptor.forClass(Location.class); verify(trailer.getEntity()).teleport(target.capture());
        assertEquals(reversed ? 90 : -90, target.getValue().getYaw()); assertEquals(handler.getTowLocation().toVector(), target.getValue().toVector());
        handler.animate(Direction.FORWARD); verify(trailer.getMoveControls()).animateMove(reversed ? Direction.BACKWARD : Direction.FORWARD);
        handler.animate(Direction.BACKWARD); verify(trailer.getMoveControls()).animateMove(reversed ? Direction.FORWARD : Direction.BACKWARD);
        handler.animate(Direction.STILL); verify(trailer.getMoveControls()).animateMove(Direction.STILL);
        when(trailer.getEntity().isDead()).thenReturn(true); handler.tick(); assertFalse(handler.isOccupied()); verify(trailer).setParent(null);
        handler.getTowPoint().mount(mock(Entity.class)); handler.animate(Direction.FORWARD); handler.unattach(); assertFalse(handler.isOccupied());
    }

    @Test void deathEffectsScheduleFragmentsAndDelayedRemovalAndCancelTheirParticleTask() {
        ActiveVehicle vehicle = vehicle(); Player viewer = mock(Player.class); when(vehicle.getNearbyPlayers()).thenReturn(List.of(viewer));
        when(vehicle.hasEffect(any())).thenReturn(true); DeathData data = death(VehicleDeath.EXPLODE, 7);
        SoundData sound = mock(SoundData.class); data.getSfx().add(sound);
        when(vehicle.getDeathData(VehicleDeath.EXPLODE)).thenReturn(data);
        var fragments = keep(mockConstruction(Fragment.class)); var explosions = keep(mockStatic(ExplosionCreator.class));
        new DeathHandler(vehicle).explode(true);
        assertEquals(2, fragments.constructed().size()); verify(vehicle).playEffect(CustomAction.EXPLODE); verify(vehicle.getAnimationHandler()).animate(Animation.EXPLODE);
        explosions.verify(() -> ExplosionCreator.triggerExplosion(any(), eq(1d), eq(3d), eq(5d), eq("ENTITY_EXPLOSION")));
        verify(viewer, times(50)).spawnParticle(eq(Particle.CAMPFIRE_COSY_SMOKE), any(Location.class), eq(0), anyDouble(), anyDouble(), anyDouble(), eq(3d));
        verify(sound).playSound(eq(List.of(viewer)), any(Location.class), eq(1f));
        assertEquals(1, later.size()); later.get(0).run(); verify(vehicle).remove();
        assertEquals(1, timers.size()); timers.get(0).run(); timers.get(0).run(); timers.get(0).run();
        verify(viewer, times(20)).spawnParticle(eq(Particle.EXPLOSION_EMITTER), any(Location.class), eq(0), anyDouble(), anyDouble(), anyDouble(), eq(.2));
        verify(scheduler).cancelTask(1);
    }

    @Test void crashIgnoresAirAndLightThenExplodesOnTheFirstSolidCollision() {
        ActiveVehicle vehicle = vehicle(); Block block = mock(Block.class); when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getType()).thenReturn(Material.AIR); when(vehicle.hasEffect(CustomAction.CRASH)).thenReturn(true);
        when(vehicle.getDeathData(VehicleDeath.EXPLODE)).thenReturn(death(VehicleDeath.EXPLODE, 0));
        keep(mockStatic(ExplosionCreator.class)); keep(mockConstruction(Fragment.class));
        new DeathHandler(vehicle).crash(); Runnable crash = timers.get(0); crash.run(); when(block.getType()).thenReturn(Material.LIGHT); crash.run();
        verify(vehicle, never()).remove(); when(block.getType()).thenReturn(Material.STONE); crash.run();
        verify(vehicle).playEffect(CustomAction.CRASH); verify(vehicle).remove(); verify(scheduler).cancelTask(1);
        assertEquals(0, later.size()); assertEquals(2, timers.size());
    }

    @Test void crashWithoutACollisionEventuallyRemovesTheVehicle() {
        ActiveVehicle vehicle = vehicle(); Block block = mock(Block.class); when(block.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        new DeathHandler(vehicle).crash(); Runnable crash = timers.get(0);
        for (int i = 0; i <= 601; i++) crash.run(); verify(vehicle).remove(); verify(scheduler).cancelTask(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sinkingAndDyingUseConfiguredSoundsAndDurationOrRemoveImmediately(boolean sink) {
        ActiveVehicle vehicle = vehicle(); VehicleDeath type = sink ? VehicleDeath.SINK : VehicleDeath.DIE;
        CustomAction action = sink ? CustomAction.SINK : CustomAction.DIE; when(vehicle.hasEffect(action)).thenReturn(true);
        when(vehicle.getDeathData(type)).thenReturn(null);
        DeathHandler handler = new DeathHandler(vehicle);
        if (sink) handler.sink(); else handler.die(); verify(vehicle).remove();
        DeathData data = death(type, 12); SoundData sound = mock(SoundData.class); data.getSfx().add(sound);
        when(vehicle.getDeathData(type)).thenReturn(data); if (sink) handler.sink(); else handler.die();
        verify(vehicle, times(2)).playEffect(action); verify(sound).playSound(anyList(), any(Location.class), eq(1f));
        verify(scheduler).runTaskLater(eq(VehicleFramework.plugin), any(Runnable.class), eq(12L)); later.get(0).run(); verify(vehicle, times(2)).remove();
    }

    @Test void skinChangesRespectPassengersFlyingEnginePowerAndMissingModels() {
        ActiveVehicle vehicle = vehicle(); YamlConfiguration config = yaml();
        config.set("red.model", "red_model"); config.set("red.name", "Red"); config.set("blue.model", "blue_model"); config.set("gone.model", "missing_model");
        modelApi.when(() -> ModelEngineAPI.getBlueprint("missing_model")).thenReturn(null);
        SkinHandler template = new SkinHandler("red", config), handler = new SkinHandler(vehicle, "red", template);
        assertFalse(SkinHandler.isModelAvailable((String)null)); assertFalse(SkinHandler.isModelAvailable(" ")); assertFalse(SkinHandler.isModelAvailable((VehicleSkin)null));
        assertFalse(handler.canChangeSkin("red", false)); assertFalse(handler.canChangeSkin("absent", true)); assertFalse(handler.canChangeSkin("gone", false));
        assertNull(handler.changeSkin("absent")); assertNull(handler.changeSkin("gone"));
        assertTrue(handler.canChangeSkin("blue", false)); when(vehicle.getSeatHandler().hasPassengers()).thenReturn(true);
        assertFalse(handler.canChangeSkin("blue", false)); assertTrue(handler.canChangeSkin("blue", true));
        when(vehicle.getSeatHandler().hasPassengers()).thenReturn(false); when(vehicle.getStateHandler().getCurrentState().getType()).thenReturn(State.FLYING);
        assertFalse(handler.canChangeSkin("blue", false)); when(vehicle.getStateHandler().getCurrentState().getType()).thenReturn(State.GROUND);
        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS); when(vehicle.hasComponent(Component.ENGINE)).thenReturn(true); when(vehicle.getComponent(Component.ENGINE)).thenReturn(engine);
        when(engine.requiresStart()).thenReturn(true); when(engine.isStarted()).thenReturn(true); assertFalse(handler.canChangeSkin("blue", false));
        when(engine.isStarted()).thenReturn(false); when(engine.getThrottle().getCurrent()).thenReturn(-1); assertFalse(handler.canChangeSkin("blue", false));
        when(engine.getThrottle().getCurrent()).thenReturn(0); assertTrue(handler.canChangeSkin("blue", false));
        assertEquals("blue_model", handler.changeSkin("blue")); assertEquals("blue", handler.getCurrentSkin().getId());
        assertEquals("blue", handler.getCurrentSkin().getName()); assertEquals(3, handler.getSkins().size());
    }

    @Test void utilityLightsAdvanceAlongTheirBoneVectorAndHornSharesPlayerCooldown() {
        YamlConfiguration config = yaml(); config.set("lights.front.vector", "base.align"); config.set("lights.front.power", 4); config.set("lights.front.falloff", 2);
        config.set("horn.sound", "minecraft:entity.goat.screaming.ambient");
        UtilityHandler template = new UtilityHandler(config); UtilityHandler handler = new UtilityHandler(model(0), template);
        assertSame(template.getHorn(), handler.getHorn()); assertEquals(1, handler.getLights().size());
        DirectionalLight light = handler.getLights().get(0); assertEquals("base.align", light.getVectorString());
        assertEquals(4, light.getPower()); assertEquals(2, light.getFalloff());
        var lights = keep(mockConstruction(LightEffect.class)); handler.tick(List.of()); assertTrue(lights.constructed().isEmpty());
        Player pilot = mock(Player.class); handler.toggleLights(pilot); handler.toggleLights(pilot); handler.tick(List.of(pilot));
        assertEquals(2, lights.constructed().size()); verify(lights.constructed().get(0)).createTemporaryLight(new Location(world, 0, 64, 1), 15);
        verify(lights.constructed().get(1)).createTemporaryLight(new Location(world, 0, 64, 2), 15);
        light.updateModel(model(5)); assertEquals(5, light.getVector().getBaseLocation().getX());
        handler.honk(pilot, new Location(world, 0, 64, 0)); verify(world, never()).playSound(any(Location.class), anyString(), anyFloat(), anyFloat());
        Player other = mock(Player.class); handler.honk(other, new Location(world, 0, 64, 0)); handler.honk(other, new Location(world, 0, 64, 0));
        verify(world).playSound(eq(new Location(world, 0, 64, 0)), anyString(), anyFloat(), anyFloat());
        new UtilityHandler(yaml()).honk(pilot, new Location(world, 0, 64, 0));
        YamlConfiguration invalid = yaml(); invalid.set("vector", "base.align"); invalid.set("power", 99); invalid.set("falloff", -3);
        DirectionalLight normalized = new DirectionalLight(invalid); assertEquals(8, normalized.getPower()); assertEquals(1, normalized.getFalloff());
    }

    @Test void fireSpreadRollsOnceForEachUnburningComponentAndHonorsTheFireProgress() {
        YamlConfiguration config = yaml(); config.createSection("hull", componentConfig().getValues(true)); config.createSection("pump", componentConfig().getValues(true));
        ActiveVehicle vehicle = vehicle(); ComponentHandler live = spy(new ComponentHandler(vehicle, vehicle.getEntity(), model(0), null, new ComponentHandler(config)));
        live.getComponent(Component.HULL).startFire(); live.getComponent(Component.HULL).getFire().setProgress(50);
        doReturn(99d).when(live).rollFireSpreadChance(); live.slowTick(); assertFalse(live.getComponent(Component.PUMP).isOnFire());
        doReturn(0d).when(live).rollFireSpreadChance(); live.slowTick(); assertTrue(live.getComponent(Component.PUMP).isOnFire());
        ComponentHandler realRoll = new ComponentHandler(yaml()); double rolled = realRoll.rollFireSpreadChance();
        assertTrue(rolled >= 0 && rolled < 100); assertEquals(Math.floor(rolled), rolled);
    }

    @Test void seatsConnectOneWeaponAtATimeAndDebounceCyclingBeforeWrapping() throws InterruptedException {
        ActiveVehicle vehicle = vehicle(); Seat seat = new Seat(vehicle, new Seat("gunner(bench)", "cart"));
        ActiveWeapon first = mock(ActiveWeapon.class), second = mock(ActiveWeapon.class); Player pilot = player("Pilot");
        seat.changeWeapon(); seat.connectWeapon(first); seat.mount(pilot); assertSame(first, seat.getWeapon()); verify(first).setController(pilot);
        seat.mount(player("Other")); assertSame(pilot, seat.getEntity()); seat.changeWeapon(); assertSame(first, seat.getWeapon());
        seat.connectWeapon(second); Thread.sleep(320); seat.changeWeapon(); assertSame(second, seat.getWeapon());
        seat.changeWeapon(); assertSame(second, seat.getWeapon()); verify(first).disconnect(); verify(second).setController(pilot);
        Thread.sleep(320); seat.changeWeapon(); assertSame(first, seat.getWeapon()); verify(second).disconnect();
        seat.dismount(); assertFalse(seat.isOccupied()); verify(first, times(2)).disconnect();
        seat.mount(mock(Entity.class)); seat.changeWeapon(); assertSame(first, seat.getWeapon());
        assertFalse(seat.mountedIsVehicle()); verify(vehicle.getAccessPanel()).addSeat(seat);
    }

    @Test void seatHandlerMaintainsCaptainAndPassengerMembershipAcrossSwitchesAndDismounts() {
        Seats f = new Seats(); Player pilot = player("Pilot"); Seat driver = f.handler.getSeat("DRIVER"), bench = f.handler.getSeat("bench");
        assertFalse(f.handler.hasCaptain()); assertFalse(f.handler.isCaptain(null)); assertNull(f.handler.getSeat("missing"));
        assertEquals(SeatHandler.MountResult.UNAVAILABLE, f.handler.addPassenger(null, driver));
        assertEquals(SeatHandler.MountResult.UNAVAILABLE, f.handler.changeSeat(pilot, null));
        assertEquals(SeatHandler.MountResult.MOUNTED, f.handler.addPassenger(pilot, driver));
        assertTrue(f.handler.hasCaptain()); assertSame(pilot, f.handler.captainPlayer()); assertTrue(f.handler.isCaptain(pilot));
        assertEquals(List.of(pilot), f.handler.getPassengers()); assertNull(f.handler.getSeat(player("Not aboard")));
        assertFalse(f.handler.isCaptain(player("Other"))); assertTrue(f.handler.isMounted(pilot)); assertSame(driver, f.handler.getSeat(pilot));
        assertEquals(SeatHandler.MountResult.MOUNTED, f.handler.changeSeat(pilot, bench)); assertFalse(f.handler.hasCaptain());
        assertSame(bench, f.handler.getSeat(pilot)); assertTrue(f.handler.hasPassengers());
        f.handler.slowTick(); assertTrue(f.handler.isMounted(pilot)); f.handler.dismountAll();
        assertFalse(f.handler.hasPassengers()); assertNull(f.handler.getSeat(pilot)); verify(pilot).closeInventory();
        verify(f.vehicle.getVehicleManager()).dismount(pilot); verify(f.vehicle).removeBoard(pilot);
        Entity horse = mock(Entity.class); when(horse.getUniqueId()).thenReturn(UUID.randomUUID());
        assertEquals(SeatHandler.MountResult.MOUNTED, f.handler.addPassenger(horse, driver)); assertFalse(f.handler.hasCaptain()); f.handler.dismountAll();
    }

    @Test void seatHandlerRecoversStaleMountsAndRejectsRidersWhenTheModelLosesItsManager() {
        Seats f = new Seats(); Player rider = player("Rider"); Seat bench = f.handler.getSeat("bench"); f.handler.addPassenger(rider, bench);
        f.controllers.clear(); f.handler.slowTick(); assertTrue(f.handler.isMounted(rider));
        f.controllers.clear(); f.accept = false; f.handler.slowTick(); assertFalse(f.handler.isPassenger(rider)); assertFalse(bench.isOccupied());
        verify(f.vehicle.getVehicleManager()).recoverRejectedMount(f.vehicle, rider, "bench", rider);
        f.accept = true; f.handler.addPassenger(rider, bench); ActiveModel replacement = model(5); when(replacement.getMountManager()).thenReturn(Optional.empty());
        f.handler.updateModel(replacement); f.handler.slowTick(); assertFalse(f.handler.hasPassengers());
        verify(f.vehicle.getVehicleManager(), times(2)).recoverRejectedMount(f.vehicle, rider, "bench", rider);
        SeatHandler noManager = new SeatHandler(f.vehicle, replacement, new SeatHandler(List.of("passenger(bench)"), f.template));
        assertEquals(SeatHandler.MountResult.REJECTED, noManager.addPassenger(rider, noManager.getSeat("bench")));
    }

    @Test void rejectedSeatSwitchRollsBackPassengersAndAcceptedButDetachedMountIsRejected() {
        Seats f = new Seats(); Player rider = player("Rider"); f.handler.addPassenger(rider, f.handler.getSeat("bench"));
        f.accept = false; assertEquals(SeatHandler.MountResult.REJECTED, f.handler.changeSeat(rider, f.handler.getSeat("driver")));
        assertFalse(f.handler.hasPassengers()); assertFalse(f.handler.getSeat("bench").isOccupied());
        f.accept = true; f.registerController = false;
        assertEquals(SeatHandler.MountResult.REJECTED, f.handler.addPassenger(rider, f.handler.getSeat("bench")));
        assertFalse(f.handler.isPassenger(rider)); assertFalse(f.passengers.containsKey(rider));
    }

    @Test void externalSeatResetCleansOrphanedPassengersAndNonPlayerRecovery() {
        Seats f = new Seats(); Player rider = player("Rider"); f.handler.addPassenger(rider, f.handler.getSeat("bench"));
        f.handler.resetSeat(rider); f.handler.slowTick(); assertFalse(f.handler.isPassenger(rider)); verify(f.vehicle).removeBoard(rider);
        Entity pig = mock(Entity.class); when(pig.getUniqueId()).thenReturn(UUID.randomUUID()); f.handler.addPassenger(pig, f.handler.getSeat("bench"));
        f.controllers.clear(); f.accept = false; f.handler.slowTick();
        verify(f.vehicle.getVehicleManager()).recoverRejectedMount(f.vehicle, pig, "bench", null);
    }

    @Test void ticketMetadataRoundTripsAndSearchIncludesOffhandAndStorageOnly() {
        ItemStack ticket = stack(1); assertNull(VehicleTicketItems.readId(null)); assertNull(VehicleTicketItems.readId(mock(ItemStack.class)));
        assertNull(VehicleTicketItems.readId(ticket)); VehicleTicketItems.write(ticket, " ", "ignored"); assertNull(VehicleTicketItems.readId(ticket));
        VehicleTicketItems.write(null, "id", "Cart"); VehicleTicketItems.write(mock(ItemStack.class), "id", "Cart");
        VehicleTicketItems.write(ticket, "ABC", null); assertEquals("ABC", VehicleTicketItems.readId(ticket));
        verify(ticket.getItemMeta()).setDisplayName("§eTicket: vehicle"); verify(ticket.getItemMeta()).setLore(List.of("§7Valid for this vehicle"));
        ticket.getItemMeta().getPersistentDataContainer().set(VehicleTicketItems.key(), PersistentDataType.STRING, " "); assertNull(VehicleTicketItems.readId(ticket));
        VehicleTicketItems.write(ticket, "ABC", "Cart");
        Player rider = player("Rider"); assertFalse(VehicleTicketItems.inventoryHas(null, "ABC")); assertFalse(VehicleTicketItems.inventoryHas(rider, " "));
        when(rider.getInventory().getStorageContents()).thenReturn(null); assertFalse(VehicleTicketItems.inventoryHas(rider, "ABC"));
        when(rider.getInventory().getItemInOffHand()).thenReturn(ticket); assertTrue(VehicleTicketItems.inventoryHas(rider, "abc"));
        when(rider.getInventory().getItemInOffHand()).thenReturn(null);
        ItemStack unrelated = stack(1); when(rider.getInventory().getStorageContents()).thenReturn(new ItemStack[]{null, unrelated, ticket});
        assertTrue(VehicleTicketItems.inventoryHas(rider, "abc")); assertFalse(VehicleTicketItems.inventoryHas(rider, "other"));
    }

    @Test void ticketInteractionRequiresBlankTicketItemAndOwnerOrAdministrator() {
        ItemAPI items = ticketApi(); ActiveVehicle vehicle = vehicle(); Player rider = player("Pilot"); OwnerData owner = new OwnerData();
        when(vehicle.ticketSource()).thenReturn(vehicle); when(vehicle.getOwnerData()).thenReturn(owner);
        assertFalse(VehicleTicketInteract.handle(null, vehicle)); assertFalse(VehicleTicketInteract.handle(rider, null));
        assertFalse(VehicleTicketInteract.handle(rider, vehicle));
        ItemStack blank = stack(1); when(rider.getInventory().getItemInMainHand()).thenReturn(blank);
        Cache.ticketItem = null; assertFalse(VehicleTicketInteract.handle(rider, vehicle)); Cache.ticketItem = " "; assertFalse(VehicleTicketInteract.handle(rider, vehicle));
        Cache.ticketItem = "custom:ticket"; when(blank.getType()).thenReturn(Material.AIR); assertFalse(VehicleTicketInteract.handle(rider, vehicle));
        when(blank.getType()).thenReturn(Material.PAPER); when(items.getChecker().checkItemWithPath(blank, Cache.ticketItem)).thenReturn(false);
        assertFalse(VehicleTicketInteract.handle(rider, vehicle)); when(items.getChecker().checkItemWithPath(blank, Cache.ticketItem)).thenReturn(true);
        assertFalse(VehicleTicketInteract.handle(rider, vehicle)); owner.setOwner(null); assertFalse(VehicleTicketInteract.handle(rider, vehicle));
        owner.setOwner("PLAYER_pilot"); assertTrue(VehicleTicketInteract.handle(rider, vehicle));
        verify(rider).sendMessage("§cTickets are not enabled on this vehicle"); owner.setTicketsEnabled(true); owner.setTicketId(null);
        assertTrue(VehicleTicketInteract.handle(rider, vehicle)); assertNull(VehicleTicketItems.readId(blank));
        owner.setTicketId("ticket-id"); assertTrue(VehicleTicketInteract.handle(rider, vehicle)); assertEquals("ticket-id", VehicleTicketItems.readId(blank));
        assertFalse(VehicleTicketInteract.handle(rider, vehicle)); verify(rider).sendMessage("§aCreated a ticket for §eCart");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ticketStacksMintExactlyOneAndReturnOrDropInventoryLeftovers(boolean full) {
        ticketApi(); ActiveVehicle vehicle = vehicle(), locomotive = vehicle(); Player admin = player("Admin");
        when(admin.hasPermission("vf.admin")).thenReturn(true); when(vehicle.ticketSource()).thenReturn(locomotive);
        OwnerData owner = new OwnerData(); owner.setTicketsEnabled(true); owner.setTicketId("train-pass"); when(locomotive.getOwnerData()).thenReturn(owner);
        ItemStack blank = stack(4); when(admin.getInventory().getItemInMainHand()).thenReturn(blank);
        when(admin.getInventory().addItem(any(ItemStack.class))).thenAnswer(c -> {
            ItemStack minted = c.getArgument(0); assertEquals(1, minted.getAmount()); assertEquals("train-pass", VehicleTicketItems.readId(minted));
            HashMap<Integer, ItemStack> leftovers = new HashMap<>(); if (full) leftovers.put(0, minted); return leftovers;
        });
        assertTrue(VehicleTicketInteract.handle(admin, vehicle)); assertEquals(3, blank.getAmount()); assertNull(VehicleTicketItems.readId(blank));
        verify(admin.getInventory()).addItem(any(ItemStack.class));
        if (full) verify(world).dropItemNaturally(any(Location.class), any(ItemStack.class)); else verify(world, never()).dropItemNaturally(any(), any());
    }

    private final class Seats {
        final ActiveVehicle vehicle = vehicle(); final Vehicle template = mock(Vehicle.class);
        final MountManager manager = mock(MountManager.class); final Map<Entity, Mount> passengers = new HashMap<>();
        final Map<UUID, MountController> controllers = new HashMap<>(); final Map<String, Mount> mounts = new HashMap<>();
        final Map<Mount, Set<Entity>> occupants = new HashMap<>(); final SeatHandler handler;
        boolean accept = true, registerController = true;
        Seats() {
            keep(mockStatic(VehicleFramework.class, RETURNS_DEEP_STUBS)); when(template.getId()).thenReturn("cart");
            ActiveModel model = model(0); doReturn(Optional.of(manager)).when(model).getMountManager();
            handler = new SeatHandler(vehicle, model, new SeatHandler(List.of("captain(driver)", "passenger(bench)"), template));
            when(manager.getPassengerSeatMap()).thenReturn(passengers);
            for (String bone : List.of("driver", "bench")) {
                Mount mount = mock(Mount.class); Set<Entity> riders = new HashSet<>(); mounts.put(bone, mount); occupants.put(mount, riders);
                when(mount.getPassengers()).thenReturn(riders); doReturn(Optional.of(mount)).when(manager).getSeat(bone);
            }
            when(ModelEngineAPI.getMountPairManager().getController(any(UUID.class))).thenAnswer(c -> controllers.get(c.getArgument(0)));
            when(manager.mountPassenger(anyString(), any(Entity.class), any())).thenAnswer(c -> {
                if (!accept) return false; Entity rider = c.getArgument(1); Mount mount = mounts.get(c.getArgument(0));
                passengers.put(rider, mount); occupants.get(mount).add(rider);
                if (registerController) { MountController controller = mock(MountController.class); when(controller.getMount()).thenReturn(mount); controllers.put(rider.getUniqueId(), controller); }
                return true;
            });
            doAnswer(c -> { Entity rider = c.getArgument(0); Mount mount = passengers.remove(rider); if (mount != null) occupants.get(mount).remove(rider); controllers.remove(rider.getUniqueId()); return null; })
                    .when(manager).dismountPassenger(any(Entity.class));
        }
    }

    private Player player(String name) {
        Player player = mock(Player.class); PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getName()).thenReturn(name); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(c -> new Location(world, 0, 64, 0)); when(player.getInventory()).thenReturn(inventory); return player;
    }
    private ItemStack stack(int amount) {
        ItemStack item = mock(ItemStack.class); ItemMeta meta = mock(ItemMeta.class); PersistentDataContainer data = mock(PersistentDataContainer.class);
        AtomicInteger count = new AtomicInteger(amount); Map<NamespacedKey, String> values = new HashMap<>();
        when(item.getType()).thenReturn(Material.PAPER); when(item.getAmount()).thenAnswer(c -> count.get()); doAnswer(c -> { count.set(c.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(c -> values.get(c.getArgument(0)));
        doAnswer(c -> { values.put(c.getArgument(0), c.getArgument(2)); return null; }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        when(item.clone()).thenAnswer(c -> { ItemStack copy = stack(count.get()); for (var entry : values.entrySet()) copy.getItemMeta().getPersistentDataContainer().set(entry.getKey(), PersistentDataType.STRING, entry.getValue()); return copy; });
        return item;
    }
    private ItemAPI ticketApi() {
        ItemAPI items = mock(ItemAPI.class, RETURNS_DEEP_STUBS); keep(mockStatic(TLibs.class)).when(TLibs::getItemAPI).thenReturn(items);
        Cache.ticketItem = "custom:ticket"; when(items.getChecker().checkItemWithPath(any(ItemStack.class), eq(Cache.ticketItem))).thenReturn(true); return items;
    }

    private ActiveVehicle vehicle() {
        ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS); Entity entity = mock(Entity.class);
        when(vehicle.getEntity()).thenReturn(entity); when(vehicle.getId()).thenReturn("cart"); when(vehicle.getName()).thenReturn("Cart");
        when(entity.getWorld()).thenReturn(world); when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getLocation()).thenAnswer(c -> new Location(world, 0, 64, 0)); when(entity.getVelocity()).thenReturn(new Vector());
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 1, 65, 1));
        ActiveModel model = model(0); when(vehicle.getModel()).thenReturn(model); when(vehicle.getStateHandler().getCurrentState().getType()).thenReturn(State.GROUND);
        when(vehicle.getComponents()).thenReturn(List.of()); when(vehicle.getNearbyPlayers()).thenReturn(List.of());
        when(vehicle.getWeaponHandler().getWeapons()).thenReturn(List.of()); return vehicle;
    }
    private ActiveModel model(double x) {
        ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
        for (String name : List.of("base", "align", "second", "tow", "driver", "bench")) {
            ModelBone bone = mock(ModelBone.class, RETURNS_DEEP_STUBS); BlueprintBone blueprint = new BlueprintBone(); blueprint.setRotatedGlobalPosition(new Vector3f());
            when(bone.getLocalTransform().getSafePosition()).thenReturn(new Vector3f());
            when(bone.getLocalTransform().getSafeLeftQuaternion()).thenReturn(new org.joml.Quaternionf());
            when(bone.getLocalTransform().getSafeScale()).thenReturn(new Vector3f(1));
            when(bone.getBoneId()).thenReturn(name); when(bone.getBlueprintBone()).thenReturn(blueprint); when(bone.getActiveModel()).thenReturn(model);
            when(bone.getLocation()).thenAnswer(c -> new Location(world, x, 64, name.equals("align") ? 1 : 0));
            when(model.getBone(name)).thenReturn(Optional.of(bone));
        }
        return model;
    }
    private YamlConfiguration componentConfig() { YamlConfiguration config = yaml(); config.set("health", 100); config.set("damage-chance", 1); return config; }
    private YamlConfiguration vehicleConfig() {
        YamlConfiguration config = yaml(); config.set("model", "red"); config.set("skins.red.model", "red_model");
        config.createSection("states"); config.createSection("components"); return config;
    }
    private YamlConfiguration behaviourConfig() { YamlConfiguration config = yaml(); config.set("rotator", "base"); config.set("vector", "base.align"); return config; }
    private DeathData death(VehicleDeath type, int duration) { YamlConfiguration config = yaml(); config.set("duration", duration); config.set("fragments", 2); return new DeathData(type, config); }
    private static YamlConfiguration yaml() { return new YamlConfiguration(); }
    private BukkitTask task(Runnable runnable, List<Runnable> target) {
        target.add(runnable); BukkitTask task = mock(BukkitTask.class); when(task.getTaskId()).thenReturn(later.size() + timers.size()); return task;
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
}
