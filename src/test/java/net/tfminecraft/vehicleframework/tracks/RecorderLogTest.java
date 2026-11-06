package net.tfminecraft.vehicleframework.tracks;

import net.tfminecraft.vehicleframework.util.LogFileWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderLogTest {

	@TempDir
	Path tempDir;

	@Test
	void append_writesToLogsFolder() throws Exception {
		RecorderLog.configure(true, tempDir.toFile());
		RecorderLog.append("lap-probe");

		LogFileWriter.flush();
		String content = Files.readString(tempDir.resolve("logs").resolve("recorder.log"));
		assertTrue(content.contains("SESSION"));
		assertTrue(content.contains("lap-probe"));
	}

	@Test
	void append_noOpWhenDisabled() throws Exception {
		RecorderLog.configure(false, tempDir.toFile());
		RecorderLog.append("hello");

		LogFileWriter.flush();
		assertEquals(false, Files.exists(tempDir.resolve("logs").resolve("recorder.log")));
	}
}
