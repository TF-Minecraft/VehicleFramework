package net.tfminecraft.vehicleframework.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.loaders.AmmunitionLoader;
import net.tfminecraft.vehicleframework.tracks.ThrottleTape;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.SinkableHull;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;

@SuppressWarnings("unchecked")
class VehiclePayloadCoverageTest {
    static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test void completePayloadRoundTripsStateWithoutDependingOnDefaultLocale() {
        Locale previous = Locale.getDefault();
        Ammunition ammo = mock(Ammunition.class);
        when(ammo.getId()).thenReturn("shell");
        try (var loader = mockStatic(AmmunitionLoader.class)) {
            loader.when(() -> AmmunitionLoader.getByString("shell")).thenReturn(ammo);
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            IncompleteVehicle decoded = VehiclePayloadCodec.decode("""
                {"id":"locomotive","name":"Express","skin":"blue","owner":"player_Alice",
                 "yaw":"45.5","uuid":"payload-id","whitelisted":true,"whitelist":["Bob",null],
                 "ticketsEnabled":true,"ticketId":"rail-pass",
                 "components":{"geared_engine":{"damage":"3.5","fire":"8","sinkprogress":2,"gear":"3","fuel":"12.5","throttle":"60"},"wings":{"damage":4}},
                 "rotators":{"turret":{"x":0.1,"y":0.2,"z":0.3,"w":0.9}},
                 "weapons":{"cannon":{"damage":"7.5","ammo":"shell","count":"9"},"empty":{"damage":0}},
                 "passengers":{"front":{"player":"Alice"},"back":{"entity":"11111111-2222-3333-4444-555555555555"}},
                 "containers":{"box":{"id":"box","items":[]}},
                 "parent":"parent","child":"child","splineId":"track","s":4.5,"travelSign":-1,
                 "throttleTape":{"splineId":"track","samples":[{"s":0,"sign":1,"throttle":60}]},
                 "locomotiveOverdrive":{"remaining":100}}
                """, ID).orElseThrow();
            assertEquals(2, decoded.getComponents().size());
            String encoded = VehiclePayloadCodec.encode(decoded);
            assertTrue(encoded.contains("geared_engine"));
            assertTrue(encoded.contains("wings"));
            IncompleteVehicle restored = VehiclePayloadCodec.decode(encoded, ID).orElseThrow();
            assertEquals("Express", restored.getName());
            assertEquals("blue", restored.getSkin());
            assertEquals("player_Alice", restored.getOwner());
            assertEquals(ID, restored.getUUID());
            assertEquals(45.5f, restored.getYaw());
            assertEquals(3, restored.getGear());
            assertEquals(60, restored.getThrottle());
            assertEquals(12.5, restored.getFuel());
            assertEquals(List.of("Bob"), restored.getWhitelist());
            assertTrue(restored.isWhitelisted());
            assertTrue(restored.isTicketsEnabled());
            assertEquals("rail-pass", restored.getTicketId());
            assertEquals(2, restored.getPassengers().size());
            assertEquals(9, restored.getWeapons().stream().filter(w -> w.getId().equals("cannon")).findFirst().orElseThrow().getCount());
            assertEquals(0.9f, restored.getRotations().getFirst().getW());
            assertEquals("box", restored.getContainers().getFirst().get("id").getAsString());
            assertEquals("parent", restored.getConsist().getParent());
            assertEquals(60, restored.getThrottleTape().lookup(0, 1));
            assertNotNull(restored.getLocomotiveOverdrive());
        } finally { Locale.setDefault(previous); }
    }

    @Test void malformedAndLegacyPayloadsAreHandledWithoutInventingVehicles() {
        for (String input : Arrays.asList(null, "", " ", "{", "[]", "null", "{}", "{\"id\":\" \"}"))
            assertTrue(VehiclePayloadCodec.decode(input, null).isEmpty());
        assertTrue(VehiclePayloadCodec.decode((JSONObject) null, ID).isEmpty());
        assertEquals("{}", VehiclePayloadCodec.encode(null));
        IncompleteVehicle legacy = VehiclePayloadCodec.decode("{\"id\":\"cart\",\"uuid\":\"legacy\",\"name\":\" \",\"owner\":null}", " ").orElseThrow();
        assertEquals("legacy", legacy.getUUID());
        assertEquals("cart", legacy.getName());
        assertEquals("none", legacy.getOwner());
        JSONObject root = object("{\"id\":\"cart\",\"yaw\":\"bad\",\"components\":{},\"rotators\":{},\"weapons\":{},\"passengers\":{},\"containers\":{\"absent\":null}}");
        for (String section : List.of("components", "rotators", "weapons", "passengers")) {
            JSONObject entries = (JSONObject) root.get(section);
            entries.put(1, new JSONObject()); entries.put("invalid", "text");
        }
        JSONObject components = (JSONObject) root.get("components");
        components.put("unknown", new JSONObject());
        components.put("engine", object("{\"damage\":\"bad\",\"throttle\":null,\"fuel\":null,\"fire\":\"bad\"}"));
        components.put("hull", object("{\"damage\":null}"));
        JSONObject passengers = (JSONObject) root.get("passengers");
        passengers.put("bad-id", object("{\"entity\":\"bad\"}"));
        passengers.put("empty", new JSONObject());
        IncompleteVehicle result = VehiclePayloadCodec.decode(root, ID).orElseThrow();
        assertEquals(2, result.getComponents().size());
        assertEquals(0, result.getThrottle());
        assertEquals(0, result.getFuel());
        assertEquals(0, result.getYaw());
        assertTrue(result.getPassengers().isEmpty());
        root.put("yaw", null);
        assertEquals(0, VehiclePayloadCodec.decode(root, ID).orElseThrow().getYaw());
        ((JSONObject) root.get("rotators")).put("incomplete", new JSONObject());
        assertTrue(VehiclePayloadCodec.decode(root, ID).isEmpty());
        assertTrue(VehiclePayloadCodec.loadContainers(null).isEmpty());
    }

    @Test void nonFiniteNumbersCannotPoisonStoredVehicleState() {
        for (Object invalid : List.of("NaN", "Infinity", Double.NaN, Double.POSITIVE_INFINITY)) {
            JSONObject payload = object("{\"id\":\"cart\",\"components\":{\"engine\":{}}}");
            payload.put("yaw", invalid);
            JSONObject engine = (JSONObject) ((JSONObject) payload.get("components")).get("engine");
            engine.put("fuel", invalid); engine.put("damage", invalid);
            IncompleteVehicle loaded = VehiclePayloadCodec.decode(payload, ID).orElseThrow();
            assertEquals(0f, loaded.getYaw());
            assertEquals(0d, loaded.getFuel());
            assertEquals(0d, loaded.getComponents().getFirst().getDamage());
            assertDoesNotThrow(() -> VehiclePayloadCodec.encode(loaded));
        }
    }

    @Test void incompleteCollectionsCanBeEmptyOrContainUnusableOptionalEntries() {
        IncompleteVehicle empty = new IncompleteVehicle(ID, "cart", "Cart", "cart", null, null, null, null, null, 0, 1, 0, 0, "none", false, null, null);
        assertTrue(VehiclePayloadCodec.decode(VehiclePayloadCodec.encode(empty), ID).isPresent());
        JsonObject valid = JsonParser.parseString("{\"id\":\"box\",\"items\":[]}").getAsJsonObject();
        IncompleteVehicle partial = new IncompleteVehicle(ID, "cart", "Cart", "cart",
            Arrays.asList(null, new IncompleteComponent(null, 0, 0, 0), new IncompleteComponent(Component.ENGINE, 5, 4, 2)),
            List.of(), List.of(), List.of(), Arrays.asList(null, new JsonObject(), valid), 40, 2, 25, 3, "none", false, List.of());
        partial.setThrottleTape(new ThrottleTape("track"));
        IncompleteVehicle reloaded = VehiclePayloadCodec.decode(VehiclePayloadCodec.encode(partial), ID).orElseThrow();
        assertEquals(1, reloaded.getComponents().size());
        assertEquals(40, reloaded.getThrottle());
        assertEquals(1, reloaded.getContainers().size());
        assertNull(reloaded.getThrottleTape());
    }

    @Test void liveSnapshotsRejectMissingIdentityOrUnreadableStateWithUsefulReasons() {
        assertEquals("vehicle is null", ActiveVehicleSnapshotFactory.tryFromLive(null).failureReason());
        ActiveVehicle v = mock(ActiveVehicle.class);
        assertEquals("no UUID", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        when(v.getUUID()).thenReturn(" ");
        assertTrue(ActiveVehicleSnapshotFactory.fromLive(v).isEmpty());
        when(v.getUUID()).thenReturn(ID);
        assertEquals("entity invalid", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        Entity e = mock(Entity.class); when(v.getEntity()).thenReturn(e); when(e.isDead()).thenReturn(true);
        assertEquals("entity invalid", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        when(e.isDead()).thenReturn(false); when(e.getLocation()).thenThrow(new IllegalStateException());
        assertEquals("could not read location", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        doReturn(null).when(e).getLocation();
        assertEquals("no world", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        when(e.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        assertEquals("no world", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        when(e.getLocation()).thenReturn(new Location(mock(World.class), 0, 0, 0));
        when(v.getSkinHandler()).thenThrow(new IllegalStateException());
        assertEquals("encode failed", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
        doThrow(new IllegalStateException("missing skin")).when(v).getSkinHandler();
        assertEquals("encode failed: missing skin", ActiveVehicleSnapshotFactory.tryFromLive(v).failureReason());
    }

    @Test void liveSnapshotPreservesContainersSeatsWeaponsTrainAndNegativeChunkCoordinates() {
        ActiveVehicle v = liveVehicle();
        Engine engine = mock(Engine.class, RETURNS_DEEP_STUBS);
        when(engine.getType()).thenReturn(Component.ENGINE);
        when(engine.getThrottle().getCurrent()).thenReturn(40); when(engine.getFuelTank().getCurrent()).thenReturn(12d);
        GearedEngine geared = mock(GearedEngine.class, RETURNS_DEEP_STUBS);
        when(geared.getType()).thenReturn(Component.GEARED_ENGINE); when(geared.getCurrentGear()).thenReturn(3);
        when(geared.getGear().getThrottle().getCurrent()).thenReturn(60); when(geared.getFuelTank().getCurrent()).thenReturn(12d);
        SinkableHull hull = mock(SinkableHull.class, RETURNS_DEEP_STUBS);
        when(hull.getType()).thenReturn(Component.HULL); when(hull.isSinking()).thenReturn(true); when(hull.getSinkProgress()).thenReturn(8);
        when(hull.isOnFire()).thenReturn(true); when(hull.getFire().getProgress()).thenReturn(9);
        when(v.getComponents()).thenReturn(List.of(engine, geared, hull));
        Container container = mock(Container.class); when(container.getId()).thenReturn("box");
        when(container.getAsJson()).thenReturn(JsonParser.parseString("{\"id\":\"box\",\"items\":[]}").getAsJsonObject());
        when(v.hasContainers()).thenReturn(true); when(v.getContainerHandler().getContainers()).thenReturn(new HashMap<>(Map.of("box", container)));
        Player player = mock(Player.class); when(player.getName()).thenReturn("Alice");
        Entity animal = mock(Entity.class); when(animal.getUniqueId()).thenReturn(UUID.fromString(ID));
        Entity unseated = mock(Entity.class);
        when(v.getSeatHandler().getPassengers()).thenReturn(List.of(player, animal, unseated));
        Seat front = mock(Seat.class); when(front.getBone()).thenReturn("front"); when(v.getSeat(player)).thenReturn(front);
        Seat back = mock(Seat.class); when(back.getBone()).thenReturn("back"); when(v.getSeat(animal)).thenReturn(back); when(v.getSeat(unseated)).thenReturn(null);
        BoneRotator rotator = mock(BoneRotator.class, RETURNS_DEEP_STUBS); when(rotator.getId()).thenReturn("turret");
        when(rotator.getAnimator().getRotation()).thenReturn(new Quaternionf(0, 0, 0, 1));
        when(v.getAccessPanel().getRotators()).thenReturn(List.of(rotator));
        ActiveWeapon weapon = mock(ActiveWeapon.class, RETURNS_DEEP_STUBS); when(weapon.getId()).thenReturn("gun");
        when(weapon.getAmmunitionHandler().hasAmmo()).thenReturn(true); when(weapon.getAmmunitionHandler().getAmmo().getId()).thenReturn("shell");
        when(weapon.getAmmunitionHandler().getCount()).thenReturn(6); when(v.getWeaponHandler().getWeapons()).thenReturn(List.of(weapon));
        when(v.isTrain()).thenReturn(true); when(v.isLocomotive()).thenReturn(true);
        when(v.getTrainHandler().toConsistData()).thenReturn(new ConsistData(null, "child", "track", 5d));
        when(v.getTrainHandler().getOverdrive().toJson()).thenReturn(object("{\"active\":true}"));
        when(v.getTrainHandler().getInstalledTape()).thenReturn(new ThrottleTape("track", List.of(new ThrottleTape.Sample(0, 1, 60))));
        when(v.getOwnerData().getTicketId()).thenReturn("ticket"); when(v.getOwnerData().getWhiteList()).thenReturn(List.of("Bob"));
        VehicleSnapshot snapshot = ActiveVehicleSnapshotFactory.fromLive(v).orElseThrow();
        assertEquals(-2, snapshot.getChunkX()); assertEquals(-1, snapshot.getChunkZ());
        assertEquals("world", snapshot.getWorld()); assertEquals("player_Alice", snapshot.getOwner());
        JsonObject payload = JsonParser.parseString(snapshot.getPayloadJson()).getAsJsonObject();
        assertEquals(2, payload.getAsJsonObject("passengers").size());
        assertEquals(8, payload.getAsJsonObject("components").getAsJsonObject("hull").get("sinkprogress").getAsInt());
        assertEquals(6, payload.getAsJsonObject("weapons").getAsJsonObject("gun").get("count").getAsInt());
        assertEquals("child", payload.get("child").getAsString()); assertTrue(payload.has("throttleTape"));
        when(v.hasParent()).thenReturn(true); when(v.isLocomotive()).thenReturn(false);
        assertFalse(JsonParser.parseString(ActiveVehicleSnapshotFactory.fromLive(v).orElseThrow().getPayloadJson()).getAsJsonObject().has("throttleTape"));
    }

    static ActiveVehicle liveVehicle() {
        ActiveVehicle v = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
        World world = mock(World.class); when(world.getName()).thenReturn("world");
        when(v.getUUID()).thenReturn(ID); when(v.getId()).thenReturn("cart"); when(v.getName()).thenReturn("Cart");
        Location location = spy(new Location(world, -16.5, 64, -0.5, 45, 0));
        doReturn(world).when(location).getWorld();
        when(v.getEntity().getLocation()).thenReturn(location);
        when(v.getSkinHandler().getCurrentSkin().getId()).thenReturn("blue");
        when(v.getOwnerData().getOwner()).thenReturn("player_Alice");
        return v;
    }
    static JSONObject object(String json) { return (JSONObject) JSONValue.parse(json); }
}
