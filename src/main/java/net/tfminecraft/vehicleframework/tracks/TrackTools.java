package net.tfminecraft.vehicleframework.tracks;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.vehicleframework.cache.Cache;

public final class TrackTools {
	// The track tools are all iron nuggets, which share a cooldown group, so the
	// remover gets its own. Otherwise the sweep would cover the layer and junction tool too.
	static final Key REMOVER_COOLDOWN = Key.key("vehicleframework", "track_remover");

	private TrackTools() {
	}

	public static boolean isLayer(ItemStack item) {
		return matches(item, Cache.trackLayerItem);
	}

	public static boolean isRemover(ItemStack item) {
		return matches(item, Cache.trackRemoverItem);
	}

	public static boolean isRecorder(ItemStack item) {
		return matches(item, Cache.trackRecorderItem);
	}

	public static boolean isJunction(ItemStack item) {
		return matches(item, Cache.trackJunctionItem);
	}

	/** Shows the wait before the next dig as an item cooldown on the remover in hand. */
	static void showRemoverCooldown(Player player, long cooldownMs) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (cooldownMs <= 0 || !isRemover(hand)) {
			return;
		}
		UseCooldown current = hand.getData(DataComponentTypes.USE_COOLDOWN);
		if (current == null || !REMOVER_COOLDOWN.equals(current.cooldownGroup())) {
			hand.setData(DataComponentTypes.USE_COOLDOWN,
					UseCooldown.useCooldown(cooldownMs / 1000f).cooldownGroup(REMOVER_COOLDOWN).build());
			player.getInventory().setItemInMainHand(hand);
		}
		player.setCooldown(REMOVER_COOLDOWN, (int) Math.ceil(cooldownMs / 50.0));
	}

	private static boolean matches(ItemStack item, String path) {
		if (item == null || path == null || path.isBlank()) {
			return false;
		}
		return TLibs.getItemAPI().getChecker().checkItemWithPath(item, path);
	}
}
