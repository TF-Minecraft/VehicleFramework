package net.tfminecraft.vehicleframework.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.VFLogger;

/**
 * Shifts a player by an amount on their own client, keeping whatever they are doing,
 * such as walking or jumping, on top of it.
 *
 * <p>A Bukkit teleport, even a relative one, makes the server ignore the player's
 * movement until the client confirms it. Sent every tick to a player with any lag, it
 * never gets confirmed in time, so the player freezes on the server. This sends the
 * client the same relative move under an id the server never uses, so the server ignores
 * the confirmation and keeps taking the player's movement, which now includes the shift.
 */
public final class RelativeMove {
	// The server numbers its own teleports up from 0, so a negative id is never one of them.
	private static final int ID = -1;
	private static boolean ready;
	private static boolean broken;
	private static Constructor<?> vec3;
	private static Constructor<?> change;
	private static Constructor<?> packet;
	private static Set<?> relatives;
	private static Method handle;
	private static Field connection;
	private static Method send;

	private RelativeMove() {
	}

	/** Moves the player by (dx, dy, dz) and turns them by {@code yaw} degrees. */
	public static boolean send(Player player, double dx, double dy, double dz, float yaw) {
		if (!ready && !load(player)) {
			return false;
		}
		try {
			Object zero = vec3.newInstance(0.0, 0.0, 0.0);
			Object by = change.newInstance(vec3.newInstance(dx, dy, dz), zero, yaw, 0f);
			Object move = packet.newInstance(ID, by, relatives);
			send.invoke(connection.get(handle.invoke(player)), move);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	private static synchronized boolean load(Player player) {
		if (ready) {
			return true;
		}
		if (broken) {
			return false;
		}
		try {
			ClassLoader loader = player.getClass().getClassLoader();
			Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3", false, loader);
			Class<?> changeClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation", false, loader);
			Class<?> relativeClass = Class.forName("net.minecraft.world.entity.Relative", false, loader);
			Class<?> packetClass = Class.forName(
					"net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket", false, loader);
			Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet", false, loader);
			vec3 = vec3Class.getConstructor(double.class, double.class, double.class);
			change = changeClass.getConstructor(vec3Class, vec3Class, float.class, float.class);
			packet = packetClass.getConstructor(int.class, changeClass, Set.class);
			// Every part relative: position, facing and speed all carry on from the client's own.
			relatives = (Set<?>) relativeClass.getField("ALL").get(null);
			handle = player.getClass().getMethod("getHandle");
			Object serverPlayer = handle.invoke(player);
			connection = serverPlayer.getClass().getField("connection");
			send = connection.getType().getMethod("send", packetType);
			ready = true;
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			fail(e);
			return false;
		}
	}

	private static void fail(Exception e) {
		if (!broken) {
			VFLogger.log("Cannot move players with trains on this server version: " + e);
		}
		broken = true;
		ready = false;
	}
}
