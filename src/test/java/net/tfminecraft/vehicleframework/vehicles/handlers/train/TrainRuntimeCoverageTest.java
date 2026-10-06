package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.*;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.VehicleRemovePayload;
import net.tfminecraft.vehicleframework.database.PersistenceLog;
import net.tfminecraft.vehicleframework.enums.VehicleRemoveReason;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.util.RelativeMove;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.TrainHandler;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;

class TrainRuntimeCoverageTest {
    final List<AutoCloseable> scopes = new ArrayList<>();
    final List<Part> spawned = new ArrayList<>();
    final List<DeckBody> bodies = new ArrayList<>();
    final Map<String, ActiveVehicle> fleet = new HashMap<>();
    final World world = mock(World.class);
    final VehicleManager manager = mock(VehicleManager.class);
    final AtomicInteger tick = new AtomicInteger();
    VehicleFramework previousPlugin;
    MockedStatic<VehicleFramework> framework;
    MockedStatic<Bukkit> bukkit;
    MockedStatic<VFLogger> logger;
    double previousCarrySpeed;
    boolean refuseShulker, cancelSpawn, refusePassenger, supplyScale = true;

    @BeforeAll static void registries() { RegistryFixture.initialize(); }
    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin; VehicleFramework.plugin = mock(VehicleFramework.class);
        when(VehicleFramework.plugin.getName()).thenReturn("VehicleFramework");
        when(VehicleFramework.plugin.namespace()).thenReturn("vehicleframework");
        previousCarrySpeed = Cache.trainDeckCarryMaxSpeed; Cache.trainDeckCarryMaxSpeed = 2;
        framework = keep(mockStatic(VehicleFramework.class)); framework.when(VehicleFramework::getVehicleManager).thenReturn(manager);
        keep(mockStatic(PersistenceLog.class)); logger = keep(mockStatic(VFLogger.class));
        bukkit = keep(mockStatic(Bukkit.class)); bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick.get());
        when(manager.getByUUID(anyString())).thenAnswer(call -> fleet.get(call.getArgument(0)));
        when(world.getName()).thenReturn("world"); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.spawn(any(Location.class), any(Class.class), any(Consumer.class))).thenAnswer(call -> {
            Class<? extends Entity> type = call.getArgument(1);
            if (type == Shulker.class && refuseShulker) throw new IllegalStateException("Protection plugin refused the shulker");
            Part part = new Part(type, call.getArgument(0)); spawned.add(part);
            ((Consumer<Entity>) call.getArgument(2)).accept(part.entity); part.valid = !cancelSpawn;
            return part.entity;
        });
    }
    @AfterEach void cleanup() throws Exception {
        for (DeckBody body : bodies) body.remove();
        for (Part part : spawned) part.entity.remove();
        for (int i = 0; i < 42; i++) DeckRiders.tick(List.of());
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin; Cache.trainDeckCarryMaxSpeed = previousCarrySpeed;
    }
    <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
    final class Part {
        final Entity entity; final Map<NamespacedKey, String> tags = new HashMap<>();
        Location location; Entity riding; boolean valid = true;
        Part(Class<? extends Entity> type, Location at) {
            entity = mock(type); location = at.clone();
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID()); when(entity.getWorld()).thenAnswer(call -> location.getWorld());
            when(entity.getLocation()).thenAnswer(call -> location.clone()); when(entity.isValid()).thenAnswer(call -> valid);
            doAnswer(call -> { valid = false; return null; }).when(entity).remove();
            when(entity.getVehicle()).thenAnswer(call -> riding);
            when(entity.teleport(any(Location.class), any(TeleportFlag[].class))).thenAnswer(call -> { location = call.getArgument(0); return true; });
            PersistentDataContainer pdc = mock(PersistentDataContainer.class); when(entity.getPersistentDataContainer()).thenReturn(pdc);
            doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), eq(PersistentDataType.STRING), anyString());
            when(pdc.has(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> tags.containsKey(call.getArgument(0)));
            when(entity.addPassenger(any(Entity.class))).thenAnswer(call -> {
                if (refusePassenger) return false;
                Entity passenger = call.getArgument(0); spawned.stream().filter(p -> p.entity == passenger).findFirst().orElseThrow().riding = entity; return true;
            });
            if (entity instanceof Shulker shulker && supplyScale) when(shulker.getAttribute(any())).thenReturn(mock(AttributeInstance.class));
        }
    }
    final class Car {
        final ActiveVehicle vehicle = mock(ActiveVehicle.class);
        final TrainHandler train;
        final Entity entity = mock(Entity.class);
        ActiveVehicle parent;
        Car() {
            String id = UUID.randomUUID().toString(); when(vehicle.getUUID()).thenReturn(id); when(vehicle.getName()).thenReturn("Coach");
            when(vehicle.isTrain()).thenReturn(true); when(vehicle.getEntity()).thenReturn(entity);
            when(entity.getWorld()).thenReturn(world); when(entity.getLocation()).thenReturn(new Location(world, 0, 64, 0)); when(entity.isValid()).thenReturn(true);
            when(vehicle.getParent()).thenAnswer(call -> parent); when(vehicle.hasParent()).thenAnswer(call -> parent != null);
            doAnswer(call -> { parent = call.getArgument(0); return null; }).when(vehicle).setParent(any());
            train = spy(new TrainHandler(vehicle, new TrainHandler(new YamlConfiguration()))); when(vehicle.getTrainHandler()).thenReturn(train);
            doNothing().when(train).placeLoadedCars(); fleet.put(id, vehicle);
        }
    }
    DeckBody body(Car car) {
        DeckBody body = new DeckBody(car.vehicle, new Deck(-1, 1, -1, 1, 2, 2)); bodies.add(body);
        doReturn(body).when(car.train).getDeckBody(); return body;
    }
    Deck.Frame frame(double x) { return new Deck.Frame(x, 64, 0, 0, 0); }

    @Test void deckFramesPreserveCoordinatesAndComparePositionRotationAndDistance() {
        assertNull(Deck.fromConfig(null));
        Deck.Frame from = frame(0); assertFalse(from.same(null)); assertTrue(from.same(frame(0)));
        assertFalse(from.same(frame(1))); assertFalse(from.same(new Deck.Frame(0, 64, 0, 1, 0))); assertFalse(from.same(new Deck.Frame(0, 64, 0, 0, 1)));
        assertEquals(5, from.distance(new Deck.Frame(3, 68, 0, 0, 0)));
        YamlConfiguration config = new YamlConfiguration(); config.set("x", List.of(0, .1)); config.set("z", List.of(0, 2)); config.set("top", 1); assertNull(Deck.fromConfig(config));
    }

    @Test void decksBuildTagMoveRebuildAndRemoveTheirBoxes() {
        Car car = new Car(); DeckBody body = body(car); assertSame(car.vehicle, body.getVehicle()); assertNotNull(body.getDeck());
        assertNull(body.frame()); assertNull(body.carried()); assertNull(DeckBody.owner(null)); assertFalse(DeckBody.isPart(null)); assertFalse(DeckBody.isStray(null));
        body.place(null); assertTrue(spawned.isEmpty()); body.place(frame(0)); assertEquals(2, spawned.size());
        Part carrier = spawned.get(0), box = spawned.get(1); assertSame(car.vehicle, DeckBody.owner(box.entity)); assertTrue(DeckBody.isPart(carrier.entity)); assertFalse(DeckBody.isStray(box.entity));
        assertTrue(carrier.tags.containsValue(car.vehicle.getUUID())); assertSame(carrier.entity, box.riding);
        assertEquals(frame(0), body.frame()); assertEquals(frame(0), body.carried());
        body.place(frame(0)); verify(carrier.entity, never()).teleport(any(Location.class), any(TeleportFlag[].class));
        body.place(frame(1)); assertEquals(1, carrier.location.getX()); assertEquals(frame(0), body.carried()); body.settle(); assertEquals(frame(1), body.carried());
        box.valid = false; body.place(frame(2)); assertEquals(4, spawned.size()); assertFalse(carrier.valid);
        spawned.get(2).location.setWorld(mock(World.class)); body.place(frame(3)); assertEquals(6, spawned.size());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); body.place(frame(16)); assertNull(body.frame()); assertNull(body.carried());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); supplyScale = false; body.place(frame(16)); assertNotNull(body.frame());
        body.remove(); assertNull(body.frame()); assertNull(DeckBody.owner(box.entity)); assertTrue(DeckBody.isStray(box.entity)); assertTrue(spawned.stream().noneMatch(p -> p.valid));
        when(car.vehicle.getEntity()).thenReturn(null); body.place(frame(0)); when(car.vehicle.getEntity()).thenReturn(car.entity); when(car.entity.getWorld()).thenReturn(null); body.place(frame(0)); assertNull(body.frame());
    }

    @Test void cancelledDeckSpawnBacksOffAndLogsOnlyOnce() {
        Car car = new Car(); DeckBody body = body(car); cancelSpawn = true;
        body.place(frame(0)); assertNull(body.frame()); assertTrue(spawned.stream().noneMatch(p -> p.valid));
        tick.set(99); body.place(frame(0)); assertEquals(2, spawned.size()); tick.set(100); body.place(frame(0)); assertEquals(4, spawned.size());
        logger.verify(() -> VFLogger.log(contains("Could not make the walkable deck")), times(1));
        tick.set(200); cancelSpawn = false; body.place(frame(0)); assertNotNull(body.frame());
    }

    @Test void failedShulkerSpawnDoesNotLeakItsAlreadySpawnedCarrier() {
        Car car = new Car(); DeckBody body = body(car); refuseShulker = true;
        body.place(frame(0)); assertEquals(1, spawned.size()); assertNull(body.frame());
        verify(spawned.getFirst().entity).remove(); assertFalse(spawned.getFirst().valid, "A failed deck must remove its already created display");
    }

    @Test void rejectedPassengerMountDoesNotPublishAnUnsupportedDeck() {
        Car car = new Car(); DeckBody body = body(car); refusePassenger = true;
        body.place(frame(0)); assertNull(body.frame(), "Players must not be carried by an unattached deck box");
        assertTrue(spawned.stream().noneMatch(p -> p.valid));
    }

    @Test void deckEventsProtectPartsForwardAttacksAndRemoveUnownedTaggedEntities() {
        Car car = new Car(); DeckBody body = body(car); body.place(frame(0)); DeckListener listener = new DeckListener(); Player player = mock(Player.class);
        Entity ordinary = new Part(Entity.class, new Location(world, 0, 64, 0)).entity, box = spawned.get(1).entity;
        PrePlayerAttackEntityEvent normal = new PrePlayerAttackEntityEvent(player, ordinary, true); listener.attack(normal); assertFalse(normal.isCancelled());
        PrePlayerAttackEntityEvent attack = new PrePlayerAttackEntityEvent(player, box, true); listener.attack(attack); assertTrue(attack.isCancelled()); verify(player).attack(car.entity);
        when(car.entity.isValid()).thenReturn(false); listener.attack(attack); verify(player, times(1)).attack(car.entity);
        EntityDamageEvent damage = new EntityDamageEvent(box, EntityDamageEvent.DamageCause.FALL, mock(org.bukkit.damage.DamageSource.class), 3); listener.damage(damage); assertTrue(damage.isCancelled());
        EntityDamageEvent unrelated = new EntityDamageEvent(ordinary, EntityDamageEvent.DamageCause.FALL, mock(org.bukkit.damage.DamageSource.class), 3); listener.damage(unrelated); assertFalse(unrelated.isCancelled());
        PlayerInteractAtEntityEvent click = new PlayerInteractAtEntityEvent(player, box, new Vector()); listener.interactAt(click); assertTrue(click.isCancelled());
        listener.interactAt(new PlayerInteractAtEntityEvent(player, ordinary, new Vector()));
        listener.entitiesLoad(new EntitiesLoadEvent(mock(Chunk.class), List.of(box, ordinary))); verify(box, never()).remove();
        body.remove(); listener.attack(new PrePlayerAttackEntityEvent(player, box, true)); listener.entitiesLoad(new EntitiesLoadEvent(mock(Chunk.class), List.of(box, ordinary))); verify(box, times(2)).remove();
    }

    @Test void pendingConsistLinksReconnectInEitherOrderAndPlaceTheBoundLocomotive() {
        Car root = new Car(), middle = new Car(), last = new Car(); root.train.setSplineId(UUID.randomUUID());
        middle.train.setPendingParent(root.vehicle.getUUID()); root.train.setPendingChild(middle.vehicle.getUUID());
        middle.train.setPendingChild(last.vehicle.getUUID()); last.train.setPendingParent(middle.vehicle.getUUID());
        ConsistRelinker.tryLink(middle.vehicle); assertSame(middle.vehicle, root.train.getChild()); assertSame(root.vehicle, middle.parent);
        assertSame(last.vehicle, middle.train.getChild()); assertSame(middle.vehicle, last.parent); assertNull(middle.train.getPendingChild()); assertNull(middle.train.getPendingParent());
        assertNull(root.train.getPendingChild()); assertNull(last.train.getPendingParent()); verify(root.train).placeLoadedCars();
        ConsistRelinker.tryLink(null); ActiveVehicle unrelated = mock(ActiveVehicle.class); ConsistRelinker.tryLink(unrelated);
        root.train.setPendingParent("not-loaded"); last.train.setPendingChild("not-loaded"); ConsistRelinker.tryLink(root.vehicle); ConsistRelinker.tryLink(last.vehicle); assertEquals("not-loaded", last.train.getPendingChild());
        framework.when(VehicleFramework::getVehicleManager).thenReturn(null); ConsistRelinker.tryLink(root.vehicle);
    }

    @ParameterizedTest @ValueSource(strings = {"child", "parent"})
    void restoringPersistedLinksCannotCreateAConsistCycle(String direction) {
        Car root = new Car(), tail = new Car(); root.train.setChild(tail.vehicle); tail.vehicle.setParent(root.vehicle);
        // Observe the mutation boundary without permitting the old code to create a cycle and hang traversal.
        doNothing().when(tail.train).setChild(any()); doNothing().when(root.vehicle).setParent(any());
        if (direction.equals("child")) tail.train.setPendingChild(root.vehicle.getUUID()); else root.train.setPendingParent(tail.vehicle.getUUID());
        ConsistRelinker.tryLink(direction.equals("child") ? tail.vehicle : root.vehicle);
        verify(tail.train, never()).setChild(root.vehicle); verify(root.vehicle, never()).setParent(tail.vehicle);
        assertSame(tail.vehicle, root.train.getChild()); assertSame(root.vehicle, tail.parent);
    }

    @Test void preexistingCorruptParentCyclesDoNotHangRelinkingOrReceiveMoreCars() {
        Car first = new Car(), second = new Car(), incoming = new Car(); first.vehicle.setParent(second.vehicle); second.vehicle.setParent(first.vehicle);
        first.train.setPendingChild(incoming.vehicle.getUUID());
        assertTimeout(java.time.Duration.ofSeconds(2), () -> ConsistRelinker.tryLink(first.vehicle));
        verify(first.train, never()).setChild(incoming.vehicle); assertNull(incoming.parent);
    }

    @Test void unloadingSplitsLiveLinksWhilePermanentRemovalAlsoClearsPendingReferences() {
        Car root = new Car(), middle = new Car(), last = new Car(); root.train.setChild(middle.vehicle); middle.vehicle.setParent(root.vehicle); middle.train.setChild(last.vehicle); last.vehicle.setParent(middle.vehicle);
        ConsistRelinker.onRemove(middle.vehicle, VehicleRemovePayload.remove(VehicleRemoveReason.UNLOAD));
        assertNull(root.train.getChild()); assertNull(middle.parent); assertNull(middle.train.getChild()); assertNull(last.parent);
        assertEquals(middle.vehicle.getUUID(), root.train.getPendingChild()); assertEquals(middle.vehicle.getUUID(), last.train.getPendingParent());
        middle.train.setPendingParent(root.vehicle.getUUID()); middle.train.setPendingChild(last.vehicle.getUUID()); middle.train.setSplineId(UUID.randomUUID());
        root.train.setChild(middle.vehicle); last.vehicle.setParent(middle.vehicle);
        ConsistRelinker.onRemove(middle.vehicle, null); assertNull(root.train.getChild()); assertNull(last.parent); assertNull(root.train.getPendingChild()); assertNull(last.train.getPendingParent());
        assertNull(middle.train.getPendingParent()); assertNull(middle.train.getPendingChild()); assertNull(middle.train.getSplineId());
        root.train.setChild(middle.vehicle); middle.vehicle.setParent(root.vehicle); middle.train.setChild(last.vehicle); last.vehicle.setParent(middle.vehicle);
        ConsistRelinker.onRemove(middle.vehicle, VehicleRemovePayload.remove(null)); assertNull(root.train.getChild()); assertNull(last.parent);
        ConsistRelinker.onRemove(null, null); ConsistRelinker.onRemove(mock(ActiveVehicle.class), null);
        middle.train.setPendingChild("offline"); framework.when(VehicleFramework::getVehicleManager).thenReturn(null); ConsistRelinker.onRemove(middle.vehicle, null); assertNull(middle.train.getPendingChild());
    }

    @Test void permanentRemovalClearsThePendingLinksOfOtherLoadedCars() {
        Car root = new Car(), middle = new Car(), child = new Car();
        root.train.setPendingChild(middle.vehicle.getUUID()); middle.train.setPendingParent(root.vehicle.getUUID()); middle.train.setPendingChild(child.vehicle.getUUID()); child.train.setPendingParent(middle.vehicle.getUUID());
        ConsistRelinker.onRemove(middle.vehicle, null); assertNull(root.train.getPendingChild()); assertNull(child.train.getPendingParent()); assertNull(middle.train.getPendingParent()); assertNull(middle.train.getPendingChild());
    }

    Player rider(double y) {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 0, y, 0)); when(world.getNearbyPlayers(any(Location.class), anyDouble())).thenReturn(List.of(player)); return player;
    }
    @Test void ridersAreCarriedOnceLiftedOntoSupportAndRetainUnconfirmedClientMovement() {
        Car car = new Car(), neighbour = new Car(); DeckBody body = body(car), second = body(neighbour); Player player = rider(65.8); when(player.getPing()).thenReturn(150);
        List<double[]> moves = new ArrayList<>();
        try (var relative = mockStatic(RelativeMove.class)) {
            relative.when(() -> RelativeMove.send(eq(player), anyDouble(), anyDouble(), anyDouble(), anyFloat())).thenAnswer(call -> { moves.add(new double[]{call.getArgument(1), call.getArgument(2), call.getArgument(3)}); return true; });
            body.place(frame(0)); second.place(frame(0)); DeckRiders.tick(List.of(car.vehicle)); assertTrue(moves.isEmpty());
            body.place(frame(.5)); second.place(frame(.5)); DeckRiders.tick(List.of(car.vehicle, neighbour.vehicle)); assertEquals(1, moves.size()); assertArrayEquals(new double[]{.5, .2, 0}, moves.getFirst(), 1e-9);
            body.place(frame(1)); DeckRiders.tick(List.of(car.vehicle)); assertEquals(2, moves.size()); assertArrayEquals(new double[]{.5, 0, 0}, moves.getLast(), 1e-9);
            for (int i = 0; i < 42; i++) DeckRiders.tick(List.of());
            when(player.getLocation()).thenReturn(new Location(world, 1, 66, 0)); body.place(frame(1.5)); DeckRiders.tick(List.of(car.vehicle)); assertEquals(3, moves.size()); assertArrayEquals(new double[]{.5, 0, 0}, moves.getLast(), 1e-9);
            relative.when(() -> RelativeMove.send(eq(player), anyDouble(), anyDouble(), anyDouble(), anyFloat())).thenReturn(false);
            body.place(frame(1)); DeckRiders.tick(List.of(car.vehicle)); assertEquals(frame(1), body.carried());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"offline", "dead", "mounted", "flying", "gliding", "sleeping", "spectator", "outside", "disabled", "too-fast"})
    void ridingRequiresAnEligiblePlayerOnASlowMovingDeck(String reason) {
        Car car = new Car(); DeckBody body = body(car); Player player = rider(66);
        switch (reason) {
            case "offline" -> when(player.isOnline()).thenReturn(false);
            case "dead" -> when(player.isDead()).thenReturn(true);
            case "mounted" -> when(player.isInsideVehicle()).thenReturn(true);
            case "flying" -> when(player.isFlying()).thenReturn(true);
            case "gliding" -> when(player.isGliding()).thenReturn(true);
            case "sleeping" -> when(player.isSleeping()).thenReturn(true);
            case "spectator" -> when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
            case "outside" -> when(player.getLocation()).thenReturn(new Location(world, 5, 66, 5));
            case "disabled" -> Cache.trainDeckCarryMaxSpeed = 0;
            case "too-fast" -> Cache.trainDeckCarryMaxSpeed = .1;
        }
        try (var relative = mockStatic(RelativeMove.class)) {
            body.place(frame(0)); body.place(frame(.5)); DeckRiders.tick(List.of(car.vehicle)); relative.verifyNoInteractions();
        }
    }

    @Test void missingDecksAndUnloadedVehicleEntitiesDoNotCarryPlayers() {
        Car car = new Car(); when(car.vehicle.isTrain()).thenReturn(false); DeckRiders.tick(List.of(car.vehicle));
        when(car.vehicle.isTrain()).thenReturn(true); when(car.vehicle.getEntity()).thenReturn(null); DeckRiders.tick(List.of(car.vehicle));
        when(car.vehicle.getEntity()).thenReturn(car.entity); when(car.entity.getWorld()).thenReturn(null); DeckRiders.tick(List.of(car.vehicle));
        when(car.entity.getWorld()).thenReturn(world); DeckRiders.tick(List.of(car.vehicle)); DeckBody body = body(car); DeckRiders.tick(List.of(car.vehicle)); assertNull(body.frame());
    }

    ActiveModel model() {
        ActiveModel model = mock(ActiveModel.class); when(model.getScale()).thenReturn(new Vector3f(1));
        for (String id : List.of("front", "back")) {
            BlueprintBone blueprint = mock(BlueprintBone.class); when(blueprint.getRotatedGlobalPosition()).thenReturn(new Vector3f(0, 1, id.equals("front") ? 2 : -2));
            ModelBone bone = mock(ModelBone.class); when(bone.getBlueprintBone()).thenReturn(blueprint); when(bone.getBoneId()).thenReturn(id); when(model.getBone(id)).thenReturn(Optional.of(bone));
        }
        return model;
    }
    @Test void bogiesFallBackWhileModelsLoadAndReleaseRotatorsAfterFailedSkinUpdates() {
        Car car = new Car(); AccessPanel panel = new AccessPanel(); when(car.vehicle.getAccessPanel()).thenReturn(panel);
        Bogies bogies = new Bogies(car.vehicle, List.of("front", "back"), (vehicle, bone) -> { BoneRotator rotator = mock(BoneRotator.class); panel.addRotator(rotator); return rotator; });
        assertFalse(new Bogies(List.of()).isReady()); assertFalse(bogies.isReady()); when(car.vehicle.getModel()).thenThrow(new IllegalStateException("Model loading")); assertFalse(bogies.isReady());
        ActiveModel pending = mock(ActiveModel.class); when(pending.getBone(anyString())).thenThrow(new IllegalStateException("Bones loading")); doReturn(pending).when(car.vehicle).getModel(); assertFalse(bogies.isReady());
        ActiveModel ready = model(); when(car.vehicle.getModel()).thenReturn(ready); assertTrue(bogies.isReady()); assertTrue(bogies.isReady()); assertEquals(2, panel.getRotators().size());
        bogies.updateModel(pending); assertTrue(panel.getRotators().isEmpty()); assertTrue(bogies.isReady()); bogies.updateModel(null); assertTrue(panel.getRotators().isEmpty());
        TrackPose single = new TrackPose(0, 64, 0, 10, 2); assertSame(single, Bogies.bodyPose(single, single, 2, -2));
        when(car.vehicle.getModel()).thenReturn(ready); ModelBone front = ready.getBone("front").orElseThrow(); when(front.getLocation()).thenReturn(new Location(world, 1, 66, 2));
        Connector connector = new Connector(car.vehicle, new Connector("front")); assertSame(front, connector.getBone()); assertEquals(new Vector(1, 2, 2), connector.getOffset());
    }
}
