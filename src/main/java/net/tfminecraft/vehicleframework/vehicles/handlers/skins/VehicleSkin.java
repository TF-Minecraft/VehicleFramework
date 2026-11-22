package net.tfminecraft.vehicleframework.vehicles.handlers.skins;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;

public class VehicleSkin {
	private String id;
	private String name;
	private String model;
	/** Blank means anyone who may paint the vehicle may pick this skin. */
	private String permission;
	/** Loaded from player-skins.yml (website uploads) rather than the vehicle's own config. */
	private boolean playerSkin;

	public VehicleSkin(String key, ConfigurationSection config) {
		id = key;
		name = config.getString("name", key);
		model = config.getString("model");
		permission = blankToNull(config.getString("permission"));
	}

	public VehicleSkin(String id, String name, String model, String permission, boolean playerSkin) {
		this.id = id;
		this.name = name == null || name.isBlank() ? id : name;
		this.model = model;
		this.permission = blankToNull(permission);
		this.playerSkin = playerSkin;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getModel() {
		return model;
	}

	public String getPermission() {
		return permission;
	}

	public boolean isPlayerSkin() {
		return playerSkin;
	}

	/** True when the skin has no permission, or the painter holds it. */
	public boolean mayUse(Permissible painter) {
		if (permission == null) return true;
		return painter != null && painter.hasPermission(permission);
	}
}
