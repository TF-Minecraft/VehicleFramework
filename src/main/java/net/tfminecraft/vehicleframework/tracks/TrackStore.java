package net.tfminecraft.vehicleframework.tracks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.tfminecraft.vehicleframework.VFLogger;

public final class TrackStore {
	private final File root;
	// Latest pending write per track file; a newer save replaces an older one not yet written.
	private final Map<File, Runnable> pending = new ConcurrentHashMap<>();
	private ExecutorService writer;

	public TrackStore(File dataFolder) {
		this.root = new File(dataFolder, "data/tracks");
	}

	/**
	 * Writes track files on a background thread from now on. Saving a long
	 * track takes tens of milliseconds, too long for the server thread.
	 */
	public synchronized void writeInBackground() {
		if (writer == null) {
			writer = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "VehicleFramework-TrackStore");
				thread.setDaemon(true);
				return thread;
			});
		}
	}

	/**
	 * Waits until every queued write has finished, then goes back to writing on
	 * the caller's thread. Saves made meanwhile wait too, so none overtakes a
	 * queued one.
	 */
	public synchronized void close() {
		if (writer == null) {
			return;
		}
		writer.shutdown();
		boolean interrupted = false;
		while (true) {
			try {
				if (writer.awaitTermination(10, TimeUnit.SECONDS)) {
					break;
				}
				VFLogger.log("Still waiting for track saves to finish");
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		writer = null;
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private synchronized void submit(File file, Runnable write) {
		if (writer == null) {
			write.run();
			return;
		}
		if (pending.put(file, write) != null) {
			// The write already queued for this file picks up the newest one.
			return;
		}
		writer.execute(() -> {
			Runnable latest = pending.remove(file);
			if (latest != null) {
				latest.run();
			}
		});
	}

	public static String sanitizeWorld(String world) {
		if (world == null || world.isBlank()) {
			return "unknown";
		}
		return world.replace('\\', '_').replace('/', '_').replace(':', '_');
	}

	public File fileFor(TrackSpline spline) {
		return fileFor(spline.getWorld(), spline.getId());
	}

	public File fileFor(String world, UUID id) {
		return new File(new File(root, sanitizeWorld(world)), id.toString() + ".json");
	}

	public void save(TrackSpline spline) {
		// Splines are immutable, so the writer thread can serialise this one safely.
		File file = fileFor(spline);
		submit(file, () -> write(file, spline));
	}

	private static void write(File file, TrackSpline spline) {
		file.getParentFile().mkdirs();
		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		try {
			String json = spline.toJson().toJSONString();
			try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
				writer.write(json);
			}
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			VFLogger.log("Failed to save track " + spline.getId());
			e.printStackTrace();
		}
	}

	public void delete(TrackSpline spline) {
		File file = fileFor(spline);
		submit(file, () -> {
			if (file.exists() && !file.delete()) {
				VFLogger.log("Failed to delete track file " + file.getName());
			}
		});
	}

	public File junctionFileFor(String world, UUID id) {
		return new File(new File(new File(root, sanitizeWorld(world)), "junctions"), id.toString() + ".json");
	}

	public void saveJunction(String world, TrackJunction junction) {
		File file = junctionFileFor(world, junction.id);
		file.getParentFile().mkdirs();
		JSONObject json = junction.toJson();
		try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
			writer.write(json.toJSONString());
		} catch (Exception e) {
			VFLogger.log("Failed to save junction " + junction.id);
			e.printStackTrace();
		}
	}

	public void deleteJunction(String world, UUID id) {
		File file = junctionFileFor(world, id);
		if (file.exists() && !file.delete()) {
			VFLogger.log("Failed to delete junction file " + file.getName());
		}
	}

	public static final class LoadedJunction {
		public final String world;
		public final TrackJunction junction;

		public LoadedJunction(String world, TrackJunction junction) {
			this.world = world;
			this.junction = junction;
		}
	}

	public List<LoadedJunction> loadAllJunctions() {
		List<LoadedJunction> loaded = new ArrayList<>();
		if (!root.exists() || !root.isDirectory()) {
			return loaded;
		}
		File[] worlds = root.listFiles();
		if (worlds == null) {
			return loaded;
		}
		JSONParser parser = new JSONParser();
		for (File worldDir : worlds) {
			if (!worldDir.isDirectory()) {
				continue;
			}
			File junctionDir = new File(worldDir, "junctions");
			if (!junctionDir.isDirectory()) {
				continue;
			}
			File[] files = junctionDir.listFiles((dir, name) -> name.endsWith(".json"));
			if (files == null) {
				continue;
			}
			for (File file : files) {
				try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
					Object parsed = parser.parse(reader);
					if (parsed instanceof JSONObject json) {
						loaded.add(new LoadedJunction(worldDir.getName(), TrackJunction.fromJson(json)));
					}
				} catch (Exception e) {
					VFLogger.log("Failed to load junction " + file.getName());
					e.printStackTrace();
				}
			}
		}
		return loaded;
	}

	public List<TrackSpline> loadAll() {
		List<TrackSpline> loaded = new ArrayList<>();
		if (!root.exists() || !root.isDirectory()) {
			return loaded;
		}
		File[] worlds = root.listFiles();
		if (worlds == null) {
			return loaded;
		}
		JSONParser parser = new JSONParser();
		for (File worldDir : worlds) {
			if (!worldDir.isDirectory()) {
				continue;
			}
			File[] files = worldDir.listFiles((dir, name) -> name.endsWith(".json"));
			if (files == null) {
				continue;
			}
			for (File file : files) {
				try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
					Object parsed = parser.parse(reader);
					if (parsed instanceof JSONObject json) {
						loaded.add(TrackSpline.fromJson(json));
					}
				} catch (Exception e) {
					VFLogger.log("Failed to load track " + file.getName());
					e.printStackTrace();
				}
			}
		}
		return loaded;
	}
}
