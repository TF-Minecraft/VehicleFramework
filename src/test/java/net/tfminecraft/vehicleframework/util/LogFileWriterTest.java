package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileWriterTest {
	@TempDir Path folder;
	private final Logger logger = Logger.getLogger("LogFileWriterTest");
	private final Logger writerLogger = Logger.getLogger(LogFileWriter.class.getName());
	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private final Handler capture = new Handler() {
		@Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
		@Override public void flush() { }
		@Override public void close() { }
	};

	@AfterEach
	void tidy() {
		logger.removeHandler(capture);
		writerLogger.removeHandler(capture);
		LogFileWriter.waitMs = 10_000;
		LogFileWriter.flush();
	}

	@Test
	void linesReachEachFileInOrderAndADeletedLogIsMadeAgain() throws Exception {
		Path first = folder.resolve("logs/first.log");
		Path second = folder.resolve("other/second.log");
		for (int i = 0; i < 200; i++) {
			LogFileWriter.append(logger, "first.log", first, "a" + i);
			LogFileWriter.append(logger, "second.log", second, "b" + i);
		}
		LogFileWriter.flush();
		assertEquals(200, Files.readAllLines(first).size());
		assertEquals("a199", Files.readAllLines(first).get(199));
		assertEquals("b0", Files.readAllLines(second).get(0));
		// Files are closed between batches, so someone deleting a log gets a new one.
		Files.delete(first);
		LogFileWriter.append(logger, "first.log", first, "fresh");
		LogFileWriter.flush();
		assertEquals(List.of("fresh"), Files.readAllLines(first));
	}

	@Test
	void failuresAreReportedLikeTheLogsDidAndWipesWaitForEarlierLines() throws Exception {
		logger.addHandler(capture);
		Path blocked = folder.resolve("logs");
		Files.writeString(blocked, "not a folder");
		LogFileWriter.append(logger, "blocked.log", blocked.resolve("blocked.log"), "lost");
		LogFileWriter.flush();
		assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to write blocked.log:")), warnings.toString());
		assertEquals("not a folder", Files.readString(blocked));

		Path log = folder.resolve("wiped/track.log");
		LogFileWriter.append(logger, "track.log", log, "before wipe");
		LogFileWriter.wipe(logger, "track.log", log);
		assertFalse(Files.exists(log));
		Path full = folder.resolve("full");
		Files.createDirectories(full);
		Files.writeString(full.resolve("keep"), "keep");
		LogFileWriter.wipe(logger, "full", full);
		assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to wipe full:")), warnings.toString());

		BufferedWriter failing = new BufferedWriter(new Writer() {
			@Override public void write(char[] buffer, int offset, int length) { }
			@Override public void flush() { }
			@Override public void close() throws IOException { throw new IOException("disk gone"); }
		});
		LogFileWriter.closeQuietly(new LogFileWriter.Open(failing, logger, "closing.log"));
		assertTrue(warnings.contains("Failed to write closing.log: disk gone"), warnings.toString());

		writerLogger.addHandler(capture);
		LogFileWriter.append(logger, "nowhere", null, "no file");
		LogFileWriter.flush();
		assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Log writer failed:")), warnings.toString());
	}

	@Test
	void aWriterThatFallsBehindDropsLinesAndSaysSo() throws Exception {
		writerLogger.addHandler(capture);
		Field waiting = LogFileWriter.class.getDeclaredField("WAITING");
		waiting.setAccessible(true);
		AtomicInteger count = (AtomicInteger) waiting.get(null);
		LogFileWriter.flush();
		count.addAndGet(LogFileWriter.MAX_WAITING);
		Path log = folder.resolve("busy.log");
		LogFileWriter.append(logger, "busy.log", log, "dropped");
		count.addAndGet(-LogFileWriter.MAX_WAITING);
		LogFileWriter.append(logger, "busy.log", log, "kept");
		LogFileWriter.flush();
		assertEquals(List.of("kept"), Files.readAllLines(log));
		assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Dropped 1 log lines")), warnings.toString());
	}

	@Test
	void shutdownWritesWhatIsLeftAndALaterLineStartsAgain() throws Exception {
		Path log = folder.resolve("stop.log");
		LogFileWriter.append(logger, "stop.log", log, "last");
		Thread before = thread();
		LogFileWriter.shutdown();
		assertEquals(List.of("last"), Files.readAllLines(log));
		before.join(5000);
		assertFalse(before.isAlive());
		assertNull(thread());
		LogFileWriter.shutdown();
		LogFileWriter.append(logger, "stop.log", log, "again");
		LogFileWriter.flush();
		assertNotSame(before, thread());
		assertEquals(List.of("last", "again"), Files.readAllLines(log));
		// An interrupt while it waits for lines does not stop it.
		Thread writer = thread();
		long until = System.currentTimeMillis() + 5000;
		while (writer.getState() != Thread.State.WAITING && System.currentTimeMillis() < until) {
			Thread.onSpinWait();
		}
		writer.interrupt();
		// Let it take the interrupt before a new line could wake it first.
		Thread.sleep(200);
		LogFileWriter.append(logger, "stop.log", log, "after interrupt");
		LogFileWriter.flush();
		assertEquals("after interrupt", Files.readAllLines(log).get(2));
	}

	@Test
	void aCallerDoesNotWaitForeverForABusyWriter() throws Exception {
		writerLogger.addHandler(capture);
		// A slow warning handler holds the writer thread up.
		Handler slow = new Handler() {
			@Override public void publish(LogRecord record) {
				if (record.getMessage().startsWith("Failed to write slow")) {
					try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				}
			}
			@Override public void flush() { }
			@Override public void close() { }
		};
		logger.addHandler(slow);
		try {
			Files.writeString(folder.resolve("slow"), "file, not folder");
			LogFileWriter.append(logger, "slow", folder.resolve("slow/slow.log"), "stuck");
			LogFileWriter.waitMs = 20;
			LogFileWriter.flush();
			assertTrue(warnings.stream().anyMatch(w -> w.startsWith("The log writer is still busy")), warnings.toString());
			// An interrupted caller stops waiting too, and keeps its interrupt.
			Thread.currentThread().interrupt();
			LogFileWriter.flush();
			assertTrue(Thread.interrupted());
		} finally {
			logger.removeHandler(slow);
		}
	}

	@Test
	void aFailingLogHandlerCannotStopTheWriter() throws Exception {
		Handler throwing = new Handler() {
			@Override public void publish(LogRecord record) { throw new IllegalStateException("handler down"); }
			@Override public void flush() { }
			@Override public void close() { }
		};
		// The writer's own logger fails while a job fails; the lines behind still go out.
		writerLogger.addHandler(throwing);
		logger.addHandler(throwing);
		try {
			LogFileWriter.warn(logger, "lost", "");
			LogFileWriter.append(logger, "nowhere", null, "no file");
			Path blocked = folder.resolve("blocked");
			Files.writeString(blocked, "file");
			LogFileWriter.append(logger, "blocked", blocked.resolve("x.log"), "fails quietly");
			Path log = folder.resolve("after.log");
			LogFileWriter.append(logger, "after.log", log, "still written");
			LogFileWriter.flush();
			assertEquals(List.of("still written"), Files.readAllLines(log));
		} finally {
			writerLogger.removeHandler(throwing);
			logger.removeHandler(throwing);
		}
	}

	private static Thread thread() throws Exception {
		Field field = LogFileWriter.class.getDeclaredField("thread");
		field.setAccessible(true);
		synchronized (LogFileWriter.class) {
			return (Thread) field.get(null);
		}
	}
}
