package net.tfminecraft.vehicleframework.ownership;

import java.util.Locale;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;

/** The identity mode, read once at enable from {@code ownership-identity}. */
public final class OwnershipMode {
	public static final String CONFIG_KEY = "ownership-identity";
	public static final String CHARACTER_PLUGIN = "RPCharacters";

	private static volatile OwnershipIdentity identity = OwnershipIdentity.NAMES;

	private OwnershipMode() {
	}

	public static OwnershipIdentity identity() {
		return identity;
	}

	public static boolean characters() {
		return identity == OwnershipIdentity.CHARACTERS;
	}

	/**
	 * Sets the mode from the config value. A missing value means names mode, unless RPCharacters is
	 * installed, when ownership is refused until staff set the key.
	 */
	public static OwnershipIdentity configure(String value, boolean characterPluginPresent) {
		String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty()) {
			if (characterPluginPresent) {
				refuse(CONFIG_KEY + " is not set and " + CHARACTER_PLUGIN + " is installed.");
				return identity;
			}
			identity = OwnershipIdentity.NAMES;
		} else if (key.equals("characters")) {
			identity = OwnershipIdentity.CHARACTERS;
		} else if (key.equals("names")) {
			identity = OwnershipIdentity.NAMES;
		} else {
			refuse(CONFIG_KEY + " has the unknown value '" + value + "'.");
			return identity;
		}
		VFLogger.info("Vehicle ownership identity: " + identity.name().toLowerCase(Locale.ROOT));
		return identity;
	}

	private static void refuse(String why) {
		identity = OwnershipIdentity.DISABLED;
		VFLogger.severe("============================================================");
		VFLogger.severe("VEHICLE OWNERSHIP IS DISABLED: " + why);
		VFLogger.severe("Claims, whitelists, takeovers and ownership API writes are refused.");
		VFLogger.severe("Set " + CONFIG_KEY + ": characters (or names) in config.yml and restart.");
		VFLogger.severe("============================================================");
	}
}
