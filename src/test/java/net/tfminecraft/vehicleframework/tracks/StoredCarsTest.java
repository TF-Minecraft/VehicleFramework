package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.database.ConsistData;
import net.tfminecraft.vehicleframework.database.VehicleRepository;
import net.tfminecraft.vehicleframework.database.VehicleSnapshot;
import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;

class StoredCarsTest {
	@TempDir Path directory;
	private VehicleRepository repository;
	private MockedStatic<VehicleLoader> loader;

	@BeforeEach
	void setup() {
		repository = VehicleRepository.open(directory.resolve("cars.db").toFile());
		loader = mockStatic(VehicleLoader.class);
	}

	@AfterEach
	void cleanup() {
		loader.close();
		repository.close();
	}

	private void save(String uuid, String type, String payload) {
		repository.saveLive(new VehicleSnapshot(uuid, type, "Car", "character:a", "world", 1, 64, 2, 90, 0, 0,
				payload, 2, 1, false, 1));
	}

	@Test
	void aRowIsMovedAndRecoupledKeepingEverythingElse() throws Exception {
		save("car", "wagon", """
				{"id":"wagon","parent":"loco","child":"tail","splineId":"old","s":3.0,"travelSign":1,
				 "junction":"j","diverge":true,"junctions":{"j":true},"name":"Car","weapons":{"gun":{"count":3}},
				 "components":{"engine":{"fuel":1.0,"throttle":40,"damage":2.0},"hull":{"damage":1.0}}}""");
		StoredCars.Row row = StoredCars.read(repository, "car").orElseThrow();
		assertEquals("character:a", row.owner());
		assertEquals("wagon", row.typeId());
		StoredCars.putConsist(row.json(), new ConsistData("ignored", "ignored", "new-track", 12.5, -1, null, null,
				-1, Map.of("k", false)));
		StoredCars.putEngine(row.json(), 80, 0);
		row.place("world2", 100.5, 70, -40.25, 45);
		assertTrue(StoredCars.write(repository, row));
		VehicleSnapshot saved = repository.findLive("car").orElseThrow();
		assertEquals("world2", saved.getWorld());
		assertEquals(100.5, saved.getX());
		assertEquals(6, saved.getChunkX());
		assertEquals(-3, saved.getChunkZ());
		assertEquals(45f, saved.getYaw());
		JSONObject json = (JSONObject) new JSONParser().parse(saved.getPayloadJson());
		ConsistData consist = ConsistData.fromJson(json);
		assertEquals("loco", consist.getParent());
		assertEquals("tail", consist.getChild());
		assertEquals("new-track", consist.getSplineId());
		assertEquals(12.5, consist.getS());
		assertEquals(-1, consist.getTravelSign());
		assertEquals(-1, consist.getOrientation());
		assertNull(consist.getJunctionId());
		assertEquals(Map.of("k", false), consist.getJunctions());
		JSONObject engine = (JSONObject) ((JSONObject) json.get("components")).get("engine");
		assertEquals(80.0, ((Number) engine.get("fuel")).doubleValue());
		assertEquals(0L, ((Number) engine.get("throttle")).longValue());
		assertEquals(2.0, ((Number) engine.get("damage")).doubleValue());
		assertTrue(json.containsKey("weapons"));
	}

	@Test
	void missingOrUnreadableRowsAreEmpty() {
		assertTrue(StoredCars.read(repository, "nothing").isEmpty());
		save("broken", "wagon", "not json");
		assertTrue(StoredCars.read(repository, "broken").isEmpty());
		save("array", "wagon", "[1,2]");
		assertTrue(StoredCars.read(repository, "array").isEmpty());
		save("plain", "wagon", "{}");
		StoredCars.Row row = StoredCars.read(repository, "plain").orElseThrow();
		StoredCars.putEngine(row.json(), 10, 0);
		assertFalse(row.json().containsKey("components"));
		row.json().put("components", new JSONObject());
		StoredCars.putEngine(row.json(), 10, 0);
		assertTrue(((JSONObject) row.json().get("components")).isEmpty());
	}

	@Test
	void containersComeFromTheTypeAndTheirSavedItems() throws Exception {
		YamlConfiguration config = new YamlConfiguration();
		config.set("name", "Hold");
		config.set("size", 9);
		Container hold = new Container("hold", config);
		Container spare = new Container("spare", config);
		Vehicle type = mock(Vehicle.class);
		ContainerHandler handler = mock(ContainerHandler.class);
		HashMap<String, Container> templates = new HashMap<>(Map.of("a", hold, "b", spare));
		when(handler.getContainers()).thenReturn(templates);
		when(type.getContainerHandler()).thenReturn(handler);
		loader.when(() -> VehicleLoader.getByString("wagon")).thenReturn(type);
		save("car", "wagon", "{\"containers\":{\"HOLD\":{\"id\":\"HOLD\",\"items\":[]},\"x\":{\"items\":[]}}}");
		StoredCars.Row row = StoredCars.read(repository, "car").orElseThrow();
		List<Container> containers = StoredCars.containers(row);
		assertEquals(2, containers.size());
		for (Container container : containers) {
			assertEquals(9, container.contents().length);
			assertNotSame(hold, container);
		}
		StoredCars.putContainers(row, containers);
		JSONObject section = (JSONObject) row.json().get("containers");
		assertTrue(section.containsKey("hold"));
		assertTrue(section.containsKey("spare"));
		// A row without containers gets the section; a type that is gone or has none gives none.
		save("bare", "wagon", "{}");
		StoredCars.Row bare = StoredCars.read(repository, "bare").orElseThrow();
		StoredCars.putContainers(bare, StoredCars.containers(bare));
		assertEquals(2, ((JSONObject) bare.json().get("containers")).size());
		save("other", "unknown", "{}");
		assertTrue(StoredCars.containers(StoredCars.read(repository, "other").orElseThrow()).isEmpty());
		when(type.getContainerHandler()).thenReturn(null);
		assertTrue(StoredCars.containers(row).isEmpty());
	}
}
