package net.tfminecraft.vehicleframework.vehicles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4d;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import com.google.gson.JsonObject;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.*;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.database.*;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.events.VehicleRemoveEvent;
import net.tfminecraft.vehicleframework.loaders.FuelLoader;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.util.ConditionChecker;
import net.tfminecraft.vehicleframework.util.VehicleEntityCleanup;
import net.tfminecraft.vehicleframework.vehicles.component.*;
import net.tfminecraft.vehicleframework.vehicles.component.fuel.FuelTank;
import net.tfminecraft.vehicleframework.vehicles.component.gear.Gear;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.controller.ScoreboardController;
import net.tfminecraft.vehicleframework.vehicles.fuel.Fuel;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.SeatHandler.MountResult;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.ConsistRelinker;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;

class ActiveVehicleTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ModelEngineAPI> models;
    private MockedStatic<VehicleEntityCleanup> cleanup;
    private MockedStatic<ConsistRelinker> relinker;
    private final World world = mock(World.class);
    private final Entity entity = mock(Entity.class);
    private final ActiveModel model = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
    private final Vehicle stored = mock(Vehicle.class);
    private final VehicleManager manager = mock(VehicleManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private Consumer<ComponentHandler> components = ignored -> {};
    private Consumer<BehaviourHandler> behaviour = ignored -> {};
    private Consumer<WeaponHandler> weapons = ignored -> {};
    private Consumer<ContainerHandler> containers = ignored -> {};
    private Consumer<ActiveVehicle> constructing = ignored -> {};
    private int previousDistance;

    @BeforeEach
    void setup() {
        previousDistance = Cache.despawnDistance;
        Cache.despawnDistance = 100;
        bukkit = keep(mockStatic(Bukkit.class));
        models = keep(mockStatic(ModelEngineAPI.class));
        cleanup = keep(mockStatic(VehicleEntityCleanup.class));
        relinker = keep(mockStatic(ConsistRelinker.class));
        keep(mockStatic(VFLogger.class));
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 1, 64, 2, 35, 0));
        when(entity.getVelocity()).thenReturn(new Vector(1, 2, 3));
        when(stored.getId()).thenReturn("cart");
        when(stored.getName()).thenReturn("Cart");
        when(stored.getModel()).thenReturn("cart_model");
        keep(mockConstruction(SkinHandler.class));
        keep(mockConstruction(BehaviourHandler.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
                (handler, context) -> {
                    when(handler.getRotator().getAngles()).thenReturn(new AxisAngle4d());
                    behaviour.accept(handler);
                }));
        keep(mockConstruction(StateHandler.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)));
        keep(mockConstruction(ComponentHandler.class, (handler, context) -> {
            components.accept(handler);
            constructing.accept((ActiveVehicle) context.arguments().getFirst());
        }));
        keep(mockConstruction(SeatHandler.class));
        keep(mockConstruction(WeaponHandler.class, (handler, context) -> weapons.accept(handler)));
        keep(mockConstruction(EffectHandler.class));
        keep(mockConstruction(DeathHandler.class));
        keep(mockConstruction(ScoreboardController.class));
        keep(mockConstruction(ContainerHandler.class, (handler, context) -> containers.accept(handler)));
        keep(mockConstruction(TowHandler.class));
        keep(mockConstruction(UtilityHandler.class));
    }

    @AfterEach
    void cleanupScopes() throws Exception {
        Collections.reverse(scopes);
        for (AutoCloseable scope : scopes) scope.close();
        Cache.despawnDistance = previousDistance;
    }

    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    private ActiveVehicle create() { return create(null); }
    private ActiveVehicle create(IncompleteVehicle saved) { return new ActiveVehicle(stored, entity, model, manager, saved); }
    private Player player(double x, World in) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(in);
        when(player.getLocation()).thenReturn(new Location(in, x, 64, 2));
        return player;
    }

    @Test
    void constructorBindsIdentityHandlersAndInitialState() {
        when(stored.isFixed()).thenReturn(true);
        when(stored.isTowable()).thenReturn(true);
        when(stored.getEntitySeatWhitelist()).thenReturn(List.of("v.cow"));
        ActiveVehicle v = create();
        assertTrue(v.isInitialized());
        assertFalse(v.isDestroyed());
        assertTrue(v.isFixed());
        assertTrue(v.isTowable());
        assertEquals("cart", v.getId());
        assertEquals("Cart", v.getName());
        assertDoesNotThrow(() -> UUID.fromString(v.getUUID()));
        assertTrue(v.getSpawnTime() <= System.currentTimeMillis());
        assertSame(manager, v.getVehicleManager());
        assertSame(model, v.getModel());
        assertSame(entity, v.getEntity());
        assertEquals(entity.getLocation(), v.getLocation());
        assertNotNull(v.getAccessPanel());
        assertNotNull(v.getOwnerData());
        assertNotNull(v.getComponentHandler());
        assertNotNull(v.getSeatHandler());
        assertNotNull(v.getStateHandler());
        assertNotNull(v.getEffectHandler());
        assertNotNull(v.getBehaviourHandler());
        assertNotNull(v.getDeathHandler());
        assertNotNull(v.getSkinHandler());
        assertNotNull(v.getWeaponHandler());
        assertFalse(v.hasTowHandler());
        assertFalse(v.hasUtilityHandler());
        assertFalse(v.hasContainers());
        assertEquals(List.of("v.cow"), v.getEntitySeatWhitelist());
        verify(v.getStateHandler()).tick();
        verify(v.getAnimationHandler()).animate(Animation.DEFAULT);
        verify(entity).setRotation(35, 0);
        v.setName("Express");
        assertEquals("Express", v.getName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void incompleteWithoutAnIdentityKeepsFreshUuid(String uuid) {
        IncompleteVehicle saved = mock(IncompleteVehicle.class);
        when(saved.getUUID()).thenReturn(uuid);
        assertDoesNotThrow(() -> UUID.fromString(create(saved).getUUID()));
    }

    @Test
    void incompleteRestoresIdentityOwnershipFuelEngineWeaponsAndComponentState() {
        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
        SinkableHull hull = mock(SinkableHull.class, RETURNS_DEEP_STUBS);
        when(hull.getType()).thenReturn(Component.HULL);
        ActiveWeapon cannon = mock(ActiveWeapon.class, RETURNS_DEEP_STUBS);
        when(cannon.getId()).thenReturn("cannon");
        IncompleteWeapon savedWeapon = mock(IncompleteWeapon.class);
        when(savedWeapon.getId()).thenReturn("CANNON");
        when(savedWeapon.getDamage()).thenReturn(14d);
        when(savedWeapon.getCount()).thenReturn(5);
        components = handler -> {
            when(handler.hasComponent(Component.ENGINE)).thenReturn(true);
            when(handler.getComponent(Component.ENGINE)).thenReturn(engine);
            when(handler.getComponents()).thenReturn(List.of(hull));
            when(engine.getFuelTank().hasInput()).thenReturn(true);
        };
        weapons = handler -> when(handler.getWeapons()).thenReturn(List.of(cannon));
        when(stored.getContainerHandler()).thenReturn(mock(ContainerHandler.class));
        Container container = mock(Container.class);
        containers = handler -> when(handler.get("cargo")).thenReturn(container);
        JsonObject json = new JsonObject(); json.addProperty("id", "cargo");
        IncompleteVehicle saved = new IncompleteVehicle("saved-uuid", "cart", "Saved cart", "blue",
                List.of(new IncompleteComponent(Component.HULL, 25, 30, 40)), List.of(savedWeapon),
                List.of(), List.of(), List.of(json), 20, 2, 90, 42, "player_A", true, List.of("player_B"));
        ActiveVehicle v = create(saved);
        assertEquals("saved-uuid", v.getUUID());
        assertEquals("Saved cart", v.getName());
        assertEquals("player_A", v.getOwnerData().getOwner());
        assertTrue(v.getOwnerData().isWhiteListed());
        assertEquals(List.of("player_B"), v.getOwnerData().getWhiteList());
        verify(engine).setStarted(true);
        verify(engine.getThrottle()).setThrottle(20);
        verify(engine.getFuelTank()).setFuel(42);
        verify(hull.getHealthData()).setDamage(25);
        verify(hull).setSinkProgress(40);
        verify(hull).startFire();
        verify(hull.getFire()).setProgress(30);
        verify(cannon.getHealthData()).setDamage(14);
        verify(cannon.getAmmunitionHandler()).setAmmo(null, 5);
        verify(container).loadFromJson(json);
        assertSame(container, v.getContainerHandler().get("cargo"));
    }

    @Test
    void savedTrainRestoresGearConsistAndRotations() {
        GearedEngine engine = mock(GearedEngine.class, RETURNS_DEEP_STUBS);
        BoneRotator rotator = mock(BoneRotator.class);
        when(rotator.getId()).thenReturn("rudder");
        components = handler -> {
            when(handler.hasComponent(Component.GEARED_ENGINE)).thenReturn(true);
            when(handler.getComponent(Component.GEARED_ENGINE)).thenReturn(engine);
        };
        behaviour = handler -> when(handler.isTrain()).thenReturn(true);
        IncompleteVehicle saved = mock(IncompleteVehicle.class);
        when(saved.getUUID()).thenReturn("train");
        when(saved.getThrottle()).thenReturn(12);
        when(saved.getGear()).thenReturn(3);
        net.tfminecraft.vehicleframework.tracks.ThrottleTape tape = new net.tfminecraft.vehicleframework.tracks.ThrottleTape("line");
        when(saved.getThrottleTape()).thenReturn(tape);
        constructing = v -> v.getAccessPanel().addRotator(rotator);
        RotationData rotation = mock(RotationData.class);
        when(rotation.getRotator()).thenReturn("RUDDER");
        when(rotation.getX()).thenReturn(1f); when(rotation.getY()).thenReturn(2f);
        when(rotation.getZ()).thenReturn(3f); when(rotation.getW()).thenReturn(4f);
        when(saved.getRotations()).thenReturn(List.of(rotation));
        ActiveVehicle v = create(saved);
        verify(engine).setStarted(true);
        verify(engine).setCurrentGear(3);
        verify(engine.getGear().getThrottle()).setThrottle(12);
        verify(v.getTrainHandler()).applyConsist(null);
        verify(v.getTrainHandler().getOverdrive()).restore(null);
        verify(v.getTrainHandler()).setInstalledTape(tape);
        verify(rotator).rawSet(1, 2, 3, 4);
        ActiveVehicle parent = mock(ActiveVehicle.class);
        v.setParent(v);
        assertFalse(v.hasParent());
        v.setParent(parent);
        assertSame(parent, v.getParent());
        assertSame(parent, v.ticketSource());
        verify(v.getTrainHandler()).setPendingParent(null);
        when(v.getTrainHandler().isLocomotive()).thenReturn(true);
        assertTrue(v.isLocomotive());
        v.setParent(null);
        assertSame(v, v.ticketSource());
    }

    @Test
    void skinChangeRebindsAllModelConsumersOnlyAfterValidation() {
        ActiveVehicle v = create();
        assertFalse(v.changeSkin("missing"));
        verify(model, never()).destroy();
        when(v.getSkinHandler().canChangeSkin("blue", false)).thenReturn(true);
        when(v.getSkinHandler().changeSkin("blue")).thenReturn("blue_model");
        when(model.getBlueprint().getName()).thenReturn("old_model");
        ActiveModel replacement = mock(ActiveModel.class, RETURNS_DEEP_STUBS);
        var mounts = mock(com.ticxo.modelengine.api.model.bone.manager.MountManager.class,
                withSettings().extraInterfaces(com.ticxo.modelengine.api.model.bone.manager.BehaviorManager.class));
        doReturn(Optional.of(mounts)).when(replacement).getMountManager();
        ModeledEntity modeled = mock(ModeledEntity.class);
        models.when(() -> ModelEngineAPI.getModeledEntity(entity)).thenReturn(modeled);
        models.when(() -> ModelEngineAPI.createActiveModel("blue_model")).thenReturn(replacement);
        assertTrue(v.changeSkin("blue"));
        assertSame(replacement, v.getModel());
        verify(model).destroy();
        verify(modeled).removeModel("old_model");
        verify(modeled).addModel(replacement, true);
        verify(replacement.getMountManager().get()).setCanRide(true);
        verify(v.getBehaviourHandler()).updateModel(replacement);
        verify(v.getStateHandler()).updateModel(replacement);
        verify(v.getComponentHandler()).updateModel(replacement);
        verify(v.getSeatHandler()).updateModel(replacement);
        verify(v.getWeaponHandler()).updateModel(replacement);
    }

    @Test
    void nearbyPlayersAndBoardsRespectWorldDistanceAndPassengerType() {
        ActiveVehicle v = create();
        Player near = player(2, world), far = player(50, world), otherWorld = player(2, mock(World.class));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(near, far, otherWorld));
        v.updateNearby();
        assertEquals(List.of(near), v.getNearbyPlayers());
        when(v.getSeatHandler().getPassengers()).thenReturn(List.of(near, entity));
        v.updateBoard();
        verify(v.sb).scoreboard(near);
        v.removeBoard(near);
        verify(v.sb).removeScoreboard(near);
    }

    @Test
    void riderInputDamageAndAnimationReachTheirHandlers() {
        ActiveVehicle v = create();
        Player player = player(2, world);
        v.key(player, Keybind.W);
        verify(v.getStateHandler(), never()).key(any(), any());
        when(v.getSeatHandler().isMounted(player)).thenReturn(true);
        v.key(player, Keybind.W);
        verify(v.getStateHandler()).key(player, Keybind.W);
        verify(v.getWeaponHandler()).input(v.getNearbyPlayers(), Keybind.W, player);
        v.damage("SUFFOCATION", 7);
        verify(v.getComponentHandler(), never()).damage(anyString(), anyDouble());
        v.damage("fire", 8);
        verify(v.getComponentHandler()).damage("fire", 8);
        verify(v.getWeaponHandler()).damage("fire", 8);
        v.randomFire(); verify(v.getComponentHandler()).randomFire();
        v.animate(Animation.DEFAULT); v.stopAnimation(Animation.DEFAULT); v.setAnimationSpeed(Animation.DEFAULT, 2);
        verify(v.getAnimationHandler(), times(2)).animate(Animation.DEFAULT);
        verify(v.getAnimationHandler()).stop(Animation.DEFAULT);
        verify(v.getAnimationHandler()).setSpeed(Animation.DEFAULT, 2);
        assertSame(v.getStateHandler().getMoveControls(), v.getMoveControls());
        assertSame(v.getStateHandler().getCurrentState(), v.getCurrentState());
        assertFalse(v.shouldFloat());
        assertSame(v, v.ticketSource());
        assertFalse(v.isLocomotive());
        assertEquals(1, v.getParameterValue("vx"));
        assertEquals(2, v.getParameterValue("vY"));
        assertEquals(3, v.getParameterValue("vz"));
        for (String parameter : List.of("yaw", "pitch", "roll", "unknown")) assertEquals(0, v.getParameterValue(parameter));
        when(v.getEffectHandler().hasEffect(CustomAction.values()[0])).thenReturn(true);
        assertTrue(v.hasEffect(CustomAction.values()[0]));
        v.playEffect(CustomAction.values()[0]);
        verify(v.getEffectHandler()).playEffect(v.getNearbyPlayers(), v, CustomAction.values()[0]);
    }

    @Test
    void optionalUtilitiesAndTickHandlersAreInvoked() {
        when(stored.getTowHandler()).thenReturn(mock(TowHandler.class));
        when(stored.getUtilityHandler()).thenReturn(mock(UtilityHandler.class));
        ActiveVehicle v = create();
        Player player = player(2, world);
        v.toggleLights(player); v.honk(player); v.tick(); v.slowTick();
        assertTrue(v.hasTowHandler()); assertTrue(v.hasUtilityHandler());
        verify(v.getUtilityHandler()).toggleLights(player);
        verify(v.getUtilityHandler()).honk(player, v.getLocation());
        verify(v.getUtilityHandler()).tick(v.getNearbyPlayers());
        verify(v.getTowHandler()).tick();
        verify(v.getBehaviourHandler()).tick(v);
        verify(v.getWeaponHandler()).tick();
        verify(v.getComponentHandler()).fireSpread();
        verify(v.getSeatHandler()).slowTick();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enginesExposeFuelThrottleAndBrakeWithoutCrossingMinimum(boolean geared) {
        ActiveVehicle v = create();
        FuelTank tank = mock(FuelTank.class);
        Throttle throttle = mock(Throttle.class);
        if (geared) {
            GearedEngine engine = mock(GearedEngine.class, RETURNS_DEEP_STUBS);
            when(v.componentHandler.hasComponent(Component.GEARED_ENGINE)).thenReturn(true);
            when(v.componentHandler.getComponent(Component.GEARED_ENGINE)).thenReturn(engine);
            when(engine.getFuelTank()).thenReturn(tank);
            when(engine.getGear().getThrottle()).thenReturn(throttle);
            when(engine.getGear().getAcceleration()).thenReturn(3);
        } else {
            Engine engine = mock(Engine.class);
            when(v.componentHandler.hasComponent(Component.ENGINE)).thenReturn(true);
            when(v.componentHandler.getComponent(Component.ENGINE)).thenReturn(engine);
            when(engine.getFuelTank()).thenReturn(tank);
            when(engine.getThrottle()).thenReturn(throttle);
            when(engine.getSpeed()).thenReturn(4.5);
            assertEquals(4.5, v.getSpeed());
            assertTrue(v.shouldAutoMove());
        }
        assertSame(throttle, v.getThrottle());
        assertFalse(v.usesFuel());
        v.setFuel(30); verify(tank, never()).setFuel(anyDouble());
        when(tank.hasInput()).thenReturn(true);
        assertTrue(v.usesFuel());
        v.setFuel(30); verify(tank).setFuel(30);
        assertFalse(v.hasFuel()); when(tank.getCurrent()).thenReturn(2d); assertTrue(v.hasFuel());
        when(throttle.getCurrent()).thenReturn(5);
        when(throttle.getMin()).thenReturn(0);
        v.applyBreakBraking();
        if (geared) verify(throttle).change(-3); else verify(throttle).decrease();
        clearInvocations(throttle);
        when(throttle.getCurrent()).thenReturn(0);
        v.applyBreakBraking();
        verify(throttle, never()).decrease(); verify(throttle, never()).change(anyInt());
    }

    @Test
    void refuelingRejectsUnknownAndWrongFuelAndDelegatesMatchingInput() {
        ActiveVehicle v = create(); Player player = player(1, world);
        try (MockedStatic<FuelLoader> loader = mockStatic(FuelLoader.class)) {
            assertFalse(v.hasFuel()); assertFalse(v.usesFuel()); assertNull(v.getThrottle());
            assertEquals(0, v.getSpeed()); assertFalse(v.shouldAutoMove());
            v.setFuel(1); v.refuel(player, "unknown"); v.applyBreakBraking(); v.honk(player); v.toggleLights(player);
            Fuel fuel = mock(Fuel.class); when(fuel.getId()).thenReturn("coal"); when(fuel.getAmount()).thenReturn(5);
            loader.when(() -> FuelLoader.getByInput("coal-item")).thenReturn(fuel);
            v.refuel(player, "coal-item");
            Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
            when(v.componentHandler.hasComponent(Component.ENGINE)).thenReturn(true);
            when(v.componentHandler.getComponent(Component.ENGINE)).thenReturn(engine);
            v.refuel(player, "coal-item");
            FuelTank tank = engine.getFuelTank();
            when(tank.hasInput()).thenReturn(true);
            when(tank.getInput().getId()).thenReturn("diesel");
            when(tank.getInput().getName()).thenReturn("Diesel");
            v.refuel(player, "coal-item");
            verify(player).sendMessage("§cThis vehicle only accepts Diesel §cas fuel");
            when(tank.getInput().getId()).thenReturn("COAL");
            v.refuel(player, "coal-item");
            verify(tank).refuel(player, v, 5);
        }
    }

    @Test
    void gearedEngineRefuelsFromItsOwnTank() {
        ActiveVehicle v = create(); Player player = player(1, world);
        GearedEngine engine = mock(GearedEngine.class, RETURNS_DEEP_STUBS);
        when(v.componentHandler.hasComponent(Component.GEARED_ENGINE)).thenReturn(true);
        when(v.componentHandler.getComponent(Component.GEARED_ENGINE)).thenReturn(engine);
        when(engine.getFuelTank().hasInput()).thenReturn(true);
        when(engine.getFuelTank().getInput().getId()).thenReturn("coal");
        Fuel fuel = mock(Fuel.class); when(fuel.getId()).thenReturn("coal"); when(fuel.getAmount()).thenReturn(3);
        try (MockedStatic<FuelLoader> loader = mockStatic(FuelLoader.class)) {
            loader.when(() -> FuelLoader.getByInput("item")).thenReturn(fuel);
            v.refuel(player, "item");
        }
        verify(engine.getFuelTank()).refuel(player, v, 3);
    }

    @Test
    void seatsRequireOccupancyAndSuccessfulMountsBeforeUpdatingBoards() {
        ActiveVehicle v = create(); Player player = player(1, world);
        Seat seat = mock(Seat.class), empty = mock(Seat.class);
        when(seat.getEntity()).thenReturn(player); when(seat.getType()).thenReturn(SeatType.ENTITY);
        v.panel.addSeat(empty); v.panel.addSeat(seat);
        assertFalse(v.isPassenger(player, true));
        when(seat.isOccupied()).thenReturn(true);
        assertTrue(v.isPassenger(player, true)); assertFalse(v.isPassenger(entity, true));
        assertTrue(v.isPassenger(player, SeatType.ENTITY)); assertFalse(v.isPassenger(player, SeatType.HARNESS));
        assertFalse(v.isPassenger(entity, SeatType.ENTITY));
        when(v.seatHandler.isPassenger(player)).thenReturn(true); assertTrue(v.isPassenger(player, false));
        when(v.seatHandler.getSeat("entity")).thenReturn(seat); assertSame(seat, v.getSeat("entity"));
        assertEquals(MountResult.UNAVAILABLE, v.changeSeat(player, seat));
        when(v.seatHandler.getSeat(player)).thenReturn(seat); assertSame(seat, v.getSeat(player));
        for (MountResult result : MountResult.values()) {
            when(v.seatHandler.addPassenger(player, seat)).thenReturn(result);
            when(v.seatHandler.changeSeat(player, seat)).thenReturn(result);
            assertEquals(result, v.addPassenger(player, seat));
            assertEquals(result, v.changeSeat(player, seat));
        }
        v.dismountPassenger(player, true); verify(v.seatHandler).dismountPassenger(player, true);
        v.dismountAll(); verify(v.seatHandler).dismountAll(); verify(seat).dismount(); verify(empty, never()).dismount();
        VehicleComponent hull = mock(VehicleComponent.class);
        List<VehicleComponent> components = List.of(hull);
        when(v.componentHandler.getComponents()).thenReturn(components);
        when(v.componentHandler.getComponents(Component.HULL)).thenReturn(components);
        assertSame(components, v.getComponents());
        assertSame(components, v.getComponents(Component.HULL));
    }

    @Test
    void savedPassengersSkipMissingDeadOfflineAndOccupiedSeatsAndRecoverRejectedEntities() {
        ActiveVehicle v = create();
        v.restorePassengers(null);
        IncompleteVehicle saved = mock(IncompleteVehicle.class);
        Entity animal = mock(Entity.class); UUID animalId = UUID.randomUUID();
        Entity dead = mock(Entity.class); UUID deadId = UUID.randomUUID(); when(dead.isDead()).thenReturn(true);
        Player player = player(1, world), offline = player(1, world); when(player.isOnline()).thenReturn(true);
        bukkit.when(() -> Bukkit.getEntity(animalId)).thenReturn(animal);
        bukkit.when(() -> Bukkit.getEntity(deadId)).thenReturn(dead);
        bukkit.when(() -> Bukkit.getPlayerExact("present")).thenReturn(player);
        bukkit.when(() -> Bukkit.getPlayerExact("offline")).thenReturn(offline);
        Seat seat = mock(Seat.class); when(seat.getBone()).thenReturn("seat");
        when(v.seatHandler.getSeat("seat")).thenReturn(seat);
        when(v.seatHandler.addPassenger(animal, seat)).thenReturn(MountResult.REJECTED);
        when(saved.getPassengers()).thenReturn(List.of(new PassengerData(animalId, "seat"),
                new PassengerData(deadId, "seat"), new PassengerData(UUID.randomUUID(), "seat"),
                new PassengerData("present", "seat"), new PassengerData("offline", "seat"), new PassengerData("absent", "seat")));
        v.restorePassengers(saved);
        verify(manager).mount(player, "seat", v);
        verify(manager).recoverRejectedMount(v, animal, "seat", null);
        verify(v.seatHandler).addPassenger(animal, seat);
        when(seat.isOccupied()).thenReturn(true);
        v.restorePassengers(saved);
        verify(v.seatHandler, times(1)).addPassenger(animal, seat);
    }

    @ParameterizedTest
    @EnumSource(VehicleDeath.class)
    void eachConfiguredDeathPersistsDismountsAndDispatchesItsHandler(VehicleDeath cause) {
        DeathData data = mock(DeathData.class); when(data.getType()).thenReturn(cause);
        when(stored.getDeathData()).thenReturn(List.of(data));
        when(stored.getContainerHandler()).thenReturn(mock(ContainerHandler.class));
        ActiveVehicle v = create();
        assertTrue(v.hasDeathData(cause)); assertSame(data, v.getDeathData(cause));
        v.kill(cause);
        assertTrue(v.isDestroyed());
        verify(manager).persistDestroy(v);
        verify(v.seatHandler).dismountAll();
        switch (cause) {
            case CRASH -> verify(v.deathHandler).crash();
            case EXPLODE -> verify(v.deathHandler).explode(true);
            case SINK -> verify(v.deathHandler).sink();
            case DIE -> verify(v.deathHandler).die();
        }
        verify(v.containerHandler).destroy(v.getLocation());
        v.remove();
        ArgumentCaptor<VehicleRemoveEvent> event = ArgumentCaptor.forClass(VehicleRemoveEvent.class);
        verify(plugins).callEvent(event.capture());
        assertEquals(Optional.of(cause), event.getValue().getPayload().getDeathCause());
    }

    @Test
    void unconfiguredDeathIsIgnoredAndMatchingOverrideChoosesConfiguredDeath() {
        ActiveVehicle v = create();
        assertNull(v.getDeathData(VehicleDeath.EXPLODE));
        v.kill(VehicleDeath.EXPLODE); assertFalse(v.isDestroyed());
        DeathData explosion = mock(DeathData.class), sink = mock(DeathData.class);
        when(explosion.getType()).thenReturn(VehicleDeath.EXPLODE); when(sink.getType()).thenReturn(VehicleDeath.SINK);
        DeathOverride skip = mock(DeathOverride.class), match = mock(DeathOverride.class);
        when(skip.getConditions()).thenReturn(List.of("false")); when(match.getConditions()).thenReturn(List.of("true"));
        when(match.getDeath()).thenReturn(VehicleDeath.SINK);
        when(explosion.hasOverrides()).thenReturn(true); when(explosion.getOverrides()).thenReturn(List.of(skip, match));
        v.deathData = List.of(explosion, sink);
        try (MockedStatic<ConditionChecker> conditions = mockStatic(ConditionChecker.class)) {
            conditions.when(() -> ConditionChecker.checkConditions(v, List.of("true"))).thenReturn(true);
            v.kill(VehicleDeath.EXPLODE);
        }
        verify(v.deathHandler).sink(); verify(v.deathHandler, never()).explode(anyBoolean());
    }

    @Test
    void componentTicksHandleFireFatalDamageAndSinking() {
        ActiveVehicle v = create();
        SinkableHull hull = mock(SinkableHull.class, RETURNS_DEEP_STUBS);
        when(v.componentHandler.getComponents()).thenReturn(List.of(hull));
        assertFalse(v.isOnFire()); when(hull.isOnFire()).thenReturn(true); assertTrue(v.isOnFire());
        when(hull.isFatal()).thenReturn(true);
        when(hull.getFire().getProgress()).thenReturn(100);
        when(hull.hasSinkProgress()).thenReturn(true); when(hull.getSinkProgress()).thenReturn(100);
        v.tick(); v.slowTick();
        verify(hull).tick(v.nearby); verify(hull).slowTick(v.nearby);
        v.forceDestroy(); v.tick(); assertTrue(v.isDestroyed());
    }

    @Test
    void removalIsIdempotentAndRejectsSubsequentRiderInput() {
        ActiveVehicle v = create(); Player player = player(1, world);
        when(v.seatHandler.isMounted(player)).thenReturn(true);
        v.remove((VehicleRemovePayload) null); v.remove(); v.key(player, Keybind.W);
        verify(v.seatHandler).dismountAll(); verify(manager).unregister(entity);
        cleanup.verify(() -> VehicleEntityCleanup.remove(entity));
        verify(v.stateHandler, never()).key(any(), any());
        ArgumentCaptor<VehicleRemoveEvent> event = ArgumentCaptor.forClass(VehicleRemoveEvent.class);
        verify(plugins).callEvent(event.capture());
        assertEquals(Optional.of(VehicleRemoveReason.GENERIC), event.getValue().getPayload().getRemoveReason());
    }

    @Test
    void removalAlwaysCleansTrainDeckAndEntityEvenWhenEventsFail() {
        behaviour = handler -> when(handler.isTrain()).thenReturn(true);
        ActiveVehicle v = create(); v.forceDestroy();
        RuntimeException failedEvent = new IllegalStateException("listener failed");
        doThrow(failedEvent).when(plugins).callEvent(any());
        assertSame(failedEvent, assertThrows(RuntimeException.class, () -> v.remove((VehicleRemoveReason) null)));
        verify(manager).unregister(entity); verify(v.getTrainHandler()).removeDeck();
        cleanup.verify(() -> VehicleEntityCleanup.remove(entity));
    }
}
