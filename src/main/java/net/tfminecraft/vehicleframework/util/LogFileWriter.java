package net.tfminecraft.vehicleframework.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Appends lines to the plugin's own log files from one background thread, so the server thread
 * never creates folders or opens, writes and closes a file for them. Lines go out in the order
 * they were appended. The thread writes whatever is waiting, then closes each file until the
 * next line, so a log someone deletes is simply made again. If it falls {@link #MAX_WAITING}
 * lines behind, further lines are dropped and counted rather than held.
 */
public final class LogFileWriter {
	static final int MAX_WAITING = 50_000;
	// How long a flush or wipe waits for the writer before going on without it.
	static long waitMs = 10_000;
	private static final Logger LOGGER = Logger.getLogger(LogFileWriter.class.getName());

	private static final BlockingQueue<Job> JOBS = new LinkedBlockingQueue<>();
	private static final AtomicInteger WAITING = new AtomicInteger();
	private static final AtomicLong DROPPED = new AtomicLong();
	// Open files, touched only by the writer thread.
	private static final Map<Path, Open> OPEN = new LinkedHashMap<>();
	private static Thread thread;

	private LogFileWriter() {
	}

	private interface Job {
		void run();
	}

	record Open(BufferedWriter writer, Logger logger, String name) { }

	/**
	 * Queues one line for {@code file}. A failure is reported to {@code logger} as
	 * "Failed to write {@code name}: ...", as each log reported it before.
	 */
	public static void append(Logger logger, String name, Path file, String line) {
		if (WAITING.incrementAndGet() > MAX_WAITING) {
			WAITING.decrementAndGet();
			DROPPED.incrementAndGet();
			return;
		}
		submit(() -> {
			WAITING.decrementAndGet();
			write(logger, name, file, line);
		});
	}

	/** Deletes {@code file} after every line queued before it is written, and waits for that. */
	public static void wipe(Logger logger, String name, Path file) {
		await(() -> {
			close(file);
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				logger.warning("Failed to wipe " + name + ": " + e.getMessage());
			}
		});
	}

	/** Waits until every line queued so far is written and its file closed. */
	public static void flush() {
		await(() -> { });
	}

	/** Writes what is queued and stops the thread; a later line starts it again. */
	public static void shutdown() {
		Thread stopping;
		synchronized (LogFileWriter.class) {
			stopping = thread;
		}
		if (stopping == null) {
			return;
		}
		await(() -> {
			synchronized (LogFileWriter.class) {
				thread = null;
			}
			throw new Stop();
		});
	}

	private static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Stop() {
			super(null, null, false, false);
		}
	}

	private static void await(Runnable action) {
		CountDownLatch done = new CountDownLatch(1);
		submit(() -> {
			closeAll();
			reportDropped();
			try {
				action.run();
			} finally {
				done.countDown();
			}
		});
		try {
			if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
				LOGGER.warning("The log writer is still busy; going on without waiting for it");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void submit(Job job) {
		JOBS.add(job);
		startIfStopped();
	}

	private static synchronized void startIfStopped() {
		if (thread == null && !JOBS.isEmpty()) {
			thread = new Thread(LogFileWriter::work, "VehicleFramework log writer");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void work() {
		while (true) {
			Job job;
			try {
				job = JOBS.take();
			} catch (InterruptedException e) {
				// Nothing should interrupt it; keep writing until the plugin stops it.
				continue;
			}
			do {
				try {
					job.run();
				} catch (Stop stop) {
					// Its job closed every file. A new thread takes lines queued since.
					startIfStopped();
					return;
				} catch (Throwable e) {
					// Keep the thread alive: nothing else would write the lines queued behind.
					LOGGER.warning("Log writer failed: " + e);
				}
			} while ((job = JOBS.poll()) != null);
			closeAll();
			reportDropped();
		}
	}

	private static void reportDropped() {
		long dropped = DROPPED.getAndSet(0);
		if (dropped > 0) {
			LOGGER.warning("Dropped " + dropped + " log lines: the log writer fell "
					+ MAX_WAITING + " lines behind");
		}
	}

	private static void write(Logger logger, String name, Path file, String line) {
		try {
			Open open = OPEN.get(file);
			if (open == null) {
				Files.createDirectories(file.getParent());
				open = new Open(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND), logger, name);
				OPEN.put(file, open);
			}
			open.writer().write(line);
			open.writer().newLine();
		} catch (IOException e) {
			close(file);
			logger.warning("Failed to write " + name + ": " + e.getMessage());
		}
	}

	private static void close(Path file) {
		Open open = OPEN.remove(file);
		if (open != null) {
			closeQuietly(open);
		}
	}

	// Closing writes out what is buffered, so a failure is a failed write.
	static void closeQuietly(Open open) {
		try {
			open.writer().close();
		} catch (IOException e) {
			open.logger().warning("Failed to write " + open.name() + ": " + e.getMessage());
		}
	}

	private static void closeAll() {
		for (Path file : OPEN.keySet().toArray(new Path[0])) {
			close(file);
		}
	}
}
