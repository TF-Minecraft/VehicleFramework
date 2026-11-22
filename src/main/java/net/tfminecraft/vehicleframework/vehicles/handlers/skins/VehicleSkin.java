package net.tfminecraft.vehicleframework.vehicles.handlers.skins;

import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class VehicleSkin {
	private String id;
	private String name;
	private String model;
	/** Blank means anyone who may paint the vehicle may pick this skin. */
	private String permission;
	/** The account that uploaded a website skin; only that player may pick it. */
	private UUID owner;
	/** Loaded from player-skins.yml (website uploads) rather than the vehicle's own config. */
	private boolean playerSkin;

	public VehicleSkin(String key, ConfigurationSection config) {
		id = key;
		name = config.getString("name", key);
		model = config.getString("model");
		permission = blankToNull(config.getString("permission"));
	}

	/** A website skin, owned by the account that uploaded it. */
	public VehicleSkin(String id, String name, String model, UUID owner) {
		this.id = id;
		this.name = name == null || name.isBlank() ? id : name;
		this.model = model;
		this.owner = owner;
		this.playerSkin = true;
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

	public UUID getOwner() {
		return owner;
	}

	public boolean isPlayerSkin() {
		return playerSkin;
	}

	/**
	 * A website skin: only its uploader, by account (ops and wildcard permissions do not count).
	 * A config skin: anyone, or holders of its permission when it has one.
	 */
	public boolean mayUse(Player painter) {
		if (playerSkin) return owner != null && painter != null && owner.equals(painter.getUniqueId());
		if (permission == null) return true;
		return painter != null && painter.hasPermission(permission);
	}
}
