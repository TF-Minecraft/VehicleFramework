package net.tfminecraft.VehicleFramework.Managers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.tfminecraft.VehicleFramework.Database.IncompleteVehicle;

class SpawnManagerPersistenceTest {
	@Test
	void enqueueUsesUuidNotJsonFilename() {
		assertEquals("u1", SpawnManager.stripJson("u1.json"));
		IncompleteVehicle incomplete = typedVehicle("horse_cart");
		assertTrue(SpawnManager.isComplete(incomplete));
	}

	@Test
	void isComplete_nullIncompleteDoesNotThrow() {
		assertDoesNotThrow(() -> SpawnManager.isComplete(null));
		assertFalse(SpawnManager.isComplete(null));
		assertFalse(SpawnManager.isComplete(blankVehicle()));
		assertTrue(SpawnManager.isComplete(typedVehicle("horse_cart")));
	}

	@Test
	void stripJson_usesUuidNotFilename() {
		assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", SpawnManager.stripJson("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.json"));
		assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", SpawnManager.stripJson("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
	}

	@Test
	void invalidUnloadingEntityStillBelongsToItsChunkWithoutLoadingChunks() {
		org.bukkit.World world = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
				org.bukkit.World.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "equals" -> proxy == args[0];
					default -> throw new AssertionError(method);
				});
		org.bukkit.Chunk chunk = (org.bukkit.Chunk) java.lang.reflect.Proxy.newProxyInstance(
				org.bukkit.Chunk.class.getClassLoader(), new Class<?>[]{org.bukkit.Chunk.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getWorld" -> world;
					case "getX" -> -1;
					case "getZ" -> 2;
					default -> throw new AssertionError(method);
				});
		org.bukkit.Location location = new org.bukkit.Location(world, -0.5, 64, 32) {
			@Override public org.bukkit.Chunk getChunk() { throw new AssertionError("Must not load chunks"); }
		};
		org.bukkit.entity.Entity entity = (org.bukkit.entity.Entity) java.lang.reflect.Proxy.newProxyInstance(
				org.bukkit.entity.Entity.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Entity.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "isValid" -> false;
					case "isDead" -> true;
					case "getLocation" -> location;
					default -> throw new AssertionError(method);
				});
		assertTrue(SpawnManager.entityInChunk(entity, chunk));
		location.setX(0);
		assertFalse(SpawnManager.entityInChunk(entity, chunk));
	}

	private static IncompleteVehicle blankVehicle() {
		return new IncompleteVehicle(
				"uuid",
				null,
				"name",
				"skin",
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				0,
				1,
				0f,
				0d,
				"none",
				false,
				java.util.List.of());
	}

	private static IncompleteVehicle typedVehicle(String id) {
		return new IncompleteVehicle(
				"uuid",
				id,
				"name",
				"skin",
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				0,
				1,
				0f,
				0d,
				"none",
				false,
				java.util.List.of());
	}
}
