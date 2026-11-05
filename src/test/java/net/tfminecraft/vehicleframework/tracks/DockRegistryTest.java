package net.tfminecraft.vehicleframework.tracks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.tracks.DockRegistry.Mode;

class DockRegistryTest {
	@TempDir Path directory;
	private MockedStatic<VFLogger> logger;

	@BeforeEach
	void setup() {
		logger = mockStatic(VFLogger.class);
	}

	@AfterEach
	void cleanup() {
		DockRegistry.load(null);
		logger.close();
	}

	@Test
	void docksCycleSaveAndLoadBack() {
		DockRegistry.load(directory.toFile());
		assertEquals(0, DockRegistry.size());
		DockRegistry.Dock dock = DockRegistry.set("world", 10, 64, -20, Mode.LOAD, "character:a");
		assertEquals(dock, DockRegistry.at("world", 10, 64, -20));
		DockRegistry.set("world", 40, 64, 40, Mode.UNLOAD, null);
		assertEquals("none", DockRegistry.at("world", 40, 64, 40).owner());
		DockRegistry.load(directory.toFile());
		assertEquals(2, DockRegistry.size());
		assertEquals(Mode.LOAD, DockRegistry.at("world", 10, 64, -20).mode());
		assertEquals("character:a", DockRegistry.at("world", 10, 64, -20).owner());
		DockRegistry.set("world", 10, 64, -20, Mode.UNLOAD, "character:a");
		assertEquals(Mode.UNLOAD, DockRegistry.at("world", 10, 64, -20).mode());
		assertNull(DockRegistry.set("world", 10, 64, -20, null, null));
		assertNull(DockRegistry.at("world", 10, 64, -20));
		assertNull(DockRegistry.set("world", 1, 1, 1, null, null));
		DockRegistry.load(directory.toFile());
		assertEquals(1, DockRegistry.size());
	}

	@Test
	void nearFindsDocksAcrossChunkEdgesWithinReach() {
		DockRegistry.load(directory.toFile());
		DockRegistry.set("world", 15, 64, 0, Mode.LOAD, "o");
		DockRegistry.set("world", 16, 64, 0, Mode.LOAD, "o");
		DockRegistry.set("world", -1, 64, -1, Mode.LOAD, "o");
		DockRegistry.set("nether", 16, 64, 0, Mode.LOAD, "o");
		assertEquals(2, DockRegistry.near("world", 16, 64.5, 0.5, 2).size());
		assertEquals(1, DockRegistry.near("world", -0.5, 64.5, -0.5, 1).size());
		assertEquals(0, DockRegistry.near("world", 16, 80, 0, 2).size());
		assertEquals(0, DockRegistry.near("end", 16, 64, 0, 2).size());
		assertEquals(0, DockRegistry.near("world", 16, 64, 0, -1).size());
	}

	@Test
	void malformedEntriesAreSkippedAndUnwritableFilesLogged() throws Exception {
		File file = directory.resolve("docks.yml").toFile();
		Files.writeString(file.toPath(), """
				docks:
				  a: {world: world, x: 1, y: 2, z: 3, mode: unload, owner: o}
				  b: {world: world, x: 1, y: 2, z: 4, mode: sideways}
				  c: {x: 1, y: 2, z: 5, mode: load}
				  d: {world: ' ', mode: load}
				  e: plain
				""");
		DockRegistry.load(directory.toFile());
		assertEquals(1, DockRegistry.size());
		logger.verify(() -> VFLogger.log(startsWith("Skipping malformed dock")), times(4));
		Files.writeString(file.toPath(), "other: 1\n");
		DockRegistry.load(directory.toFile());
		assertEquals(0, DockRegistry.size());
		// A directory where the file should be cannot be written.
		Files.delete(file.toPath());
		Files.createDirectory(file.toPath());
		DockRegistry.set("world", 1, 2, 3, Mode.LOAD, "o");
		logger.verify(() -> VFLogger.log(startsWith("Could not save docks.yml")));
		DockRegistry.load(null);
		DockRegistry.set("world", 1, 2, 3, Mode.LOAD, "o");
		assertEquals(1, DockRegistry.size());
	}

	@Test
	void modesCycleLoadUnloadOff() {
		assertEquals(Mode.UNLOAD, Mode.LOAD.next());
		assertNull(Mode.UNLOAD.next());
		assertNull(Mode.parse(null));
		assertEquals(Mode.LOAD, Mode.parse(" Load "));
		assertEquals("w;1;2;3", DockRegistry.key("w", 1, 2, 3));
		assertFalse(TrackTools.isDock(null));
		assertNotEquals(DockRegistry.chunkKey(0, 1), DockRegistry.chunkKey(1, 0));
	}
}
