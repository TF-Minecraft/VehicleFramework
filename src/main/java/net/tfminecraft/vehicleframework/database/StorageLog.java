package net.tfminecraft.vehicleframework.database;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.util.LogFileWriter;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ItemCounts;

/**
 * Who put what into, or took it out of, a vehicle's storage. Vehicle storage is not a block, so
 * CoreProtect's container log cannot hold it. Each change goes to {@code logs/storage.log} in the
 * plugin folder, written off the server thread, and, with CoreProtect, into its chat log under
 * the player's name, starting "[VF storage]", with an interaction at the vehicle. Staff find it
 * with {@code /co lookup u:<player> a:chat} or {@code /co lookup a:chat r:<radius>}. Turned off
 * with {@code storage-logging: false} in config.yml.
 */
public final class StorageLog {
	private static final Logger LOGGER = Logger.getLogger(StorageLog.class.getName());
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());
	static final String LOG_DIRECTORY = "logs";
	static final String LOG_FILE_NAME = "storage.log";
	static final String CHAT_PREFIX = "[VF storage] ";
	private static final Pattern COLOUR = Pattern.compile("(?i)§[0-9A-FK-ORX]");

	private static volatile boolean enabled = true;
	private static volatile Path logFile;

	private StorageLog() {
	}

	public static void configure(boolean loggingEnabled, File dataFolder) {
		enabled = loggingEnabled;
		logFile = dataFolder == null ? null : dataFolder.toPath().resolve(LOG_DIRECTORY).resolve(LOG_FILE_NAME);
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** A player opened a vehicle's storage. */
	public static void opened(Player player, ActiveVehicle vehicle, Container container) {
		if (!enabled) {
			return;
		}
		write("OPEN " + who(player) + " " + where(vehicle, container));
		CoreProtectAPI coreProtect = coreProtect();
		Location at = location(vehicle);
		if (coreProtect != null && at != null) {
			coreProtect.logInteraction(player.getName(), at);
		}
	}

	/** A player put {@code amount} of an item in, or took {@code -amount} out. */
	public static void changed(Player player, ActiveVehicle vehicle, Container container, ItemStack item, int amount) {
		if (!enabled || amount == 0) {
			return;
		}
		String verb = amount > 0 ? "PUT" : "TOOK";
		int count = Math.abs(amount);
		Described described = describe(item);
		write(verb + " " + who(player) + " amount=" + count + " " + described.file() + " " + where(vehicle, container));
		CoreProtectAPI coreProtect = coreProtect();
		if (coreProtect != null) {
			coreProtect.logChat(player, CHAT_PREFIX + (amount > 0 ? "put " : "took ") + count + " " + described.chat()
					+ (amount > 0 ? " into " : " from ") + plain(vehicle.getName()) + " (" + vehicle.getId() + " "
					+ vehicle.getUUID() + ") storage " + container.getId() + " at " + blockPosition(vehicle));
		}
	}

	private static CoreProtectAPI coreProtect() {
		return Cache.coreProtect ? VehicleFramework.getCoreProtect() : null;
	}

	private static String who(Player player) {
		return "player=" + player.getName() + " (" + player.getUniqueId() + ")";
	}

	private static String where(ActiveVehicle vehicle, Container container) {
		return "storage=" + container.getId() + " " + quoted(plain(container.getName()))
				+ " vehicle=" + vehicle.getId() + " " + quoted(plain(vehicle.getName())) + " uuid=" + vehicle.getUUID()
				+ " at " + worldName(vehicle) + " " + blockPosition(vehicle);
	}

	/** One item as the log file and the CoreProtect chat line put it. */
	record Described(String file, String chat) { }

	// What an item is: its type, its TLibs path (MMOItems, ItemsAdder), its name, and what a
	// bundle or shulker box holds, since taking one takes all of that. Worked out once per
	// change; an item that cannot be described further is logged by its type alone.
	static Described describe(ItemStack item) {
		String type = type(item);
		try {
			String path = path(item);
			String name = name(item);
			String contents = contents(item);
			return new Described("item=" + type
					+ (path == null ? "" : " path=" + pathField(path))
					+ (name == null ? "" : " name=" + quoted(name))
					+ (contents == null ? "" : " contents=[" + contents + "]"),
					type + (name == null ? "" : " " + quoted(name))
					+ (contents == null ? "" : " [" + contents + "]"));
		} catch (RuntimeException | LinkageError odd) {
			return new Described("item=" + type, type);
		}
	}

	private static String type(ItemStack item) {
		return item.getType().name().toLowerCase(Locale.ROOT);
	}

	// A vanilla item's path only repeats its type. Without TLibs there is none.
	private static String path(ItemStack item) {
		try {
			String path = TLibs.getItemAPI().getChecker().getAsStringPath(item);
			return path == null || path.isBlank() || path.startsWith("v.") ? null : path;
		} catch (RuntimeException | LinkageError unavailable) {
			return null;
		}
	}

	private static String name(ItemStack item) {
		ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
		if (meta == null) {
			return null;
		}
		// Plugins may leave legacy colour codes inside a component's text.
		if (meta.hasDisplayName() && meta.displayName() != null) {
			return plain(PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
		}
		if (meta.hasItemName()) {
			return plain(PlainTextComponentSerializer.plainText().serialize(meta.itemName()));
		}
		return null;
	}

	private static String contents(ItemStack item) {
		ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
		ItemStack[] inside;
		if (meta instanceof BundleMeta bundle) {
			inside = bundle.getItems().toArray(new ItemStack[0]);
		} else if (meta instanceof BlockStateMeta state && state.hasBlockState()
				&& state.getBlockState() instanceof org.bukkit.block.Container box) {
			inside = box.getSnapshotInventory().getContents();
		} else {
			return null;
		}
		List<String> kinds = new ArrayList<>();
		for (ItemCounts.Change kind : ItemCounts.of(inside).since(new ItemCounts())) {
			String name = name(kind.item());
			kinds.add(kind.amount() + " " + type(kind.item()) + (name == null ? "" : " " + quoted(name)));
		}
		return String.join(", ", kinds);
	}

	// A TLibs path is normally an id such as m.MATERIAL.STEEL_INGOT and is written bare. Any other
	// path (a renamed vanilla item's can carry its name) is quoted like a name, so it cannot add
	// fields or break the line.
	private static final Pattern PLAIN_PATH = Pattern.compile("[A-Za-z0-9_.:/-]+");

	static String pathField(String path) {
		return PLAIN_PATH.matcher(path).matches() ? path : quoted(path);
	}

	// One line per change: a name cannot break a line or end its quotes early.
	static String quoted(String text) {
		StringBuilder out = new StringBuilder(text.length() + 2).append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' || c == '"') {
				out.append('\\').append(c);
			} else if (c < 0x20 || c == 0x7f) {
				out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.append('"').toString();
	}

	private static Location location(ActiveVehicle vehicle) {
		Entity entity = vehicle.getEntity();
		return entity == null ? null : entity.getLocation();
	}

	private static String worldName(ActiveVehicle vehicle) {
		Location at = location(vehicle);
		return at == null || at.getWorld() == null ? "?" : at.getWorld().getName();
	}

	private static String blockPosition(ActiveVehicle vehicle) {
		Location at = location(vehicle);
		return at == null ? "?" : at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
	}

	// Names may carry colour codes, hex ones too.
	private static String plain(String text) {
		return text == null ? "" : COLOUR.matcher(text).replaceAll("");
	}

	private static void write(String line) {
		Path file = logFile;
		if (file != null) {
			LogFileWriter.append(LOGGER, LOG_FILE_NAME, file, TIME.format(Instant.now()) + " " + line);
		}
	}
}
