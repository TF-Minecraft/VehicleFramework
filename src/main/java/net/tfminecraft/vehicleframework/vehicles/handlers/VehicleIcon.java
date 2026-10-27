package net.tfminecraft.vehicleframework.vehicles.handlers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.util.LegacyModelData;

/** The item a vehicle shows in its menus and on its tickets, from the vehicle's {@code icon} section. */
public final class VehicleIcon {
	private final Material material;
	private final Integer modelData;

	public VehicleIcon(Material material, Integer modelData) {
		this.material = material;
		this.modelData = modelData;
	}

	/** The icon in a vehicle's {@code icon} section, or null when the section has no known material. */
	public static VehicleIcon load(String vehicle, ConfigurationSection config) {
		String name = config == null ? null : config.getString("material");
		Material material = name == null ? null : Material.matchMaterial(name.trim());
		if (material == null) {
			VFLogger.log(vehicle + " has an icon without a known material: " + name);
			return null;
		}
		Integer modelData = config.contains("model_data") ? config.getInt("model_data") : null;
		return new VehicleIcon(material, modelData);
	}

	public Material getMaterial() {
		return material;
	}

	/** The custom model data, or null when the icon is the plain material. */
	public Integer getModelData() {
		return modelData;
	}

	/** A new single item that looks like this icon. */
	public ItemStack createItem() {
		ItemStack item = new ItemStack(material, 1);
		if (modelData != null) {
			ItemMeta meta = item.getItemMeta();
			LegacyModelData.set(meta, modelData);
			item.setItemMeta(meta);
		}
		return item;
	}

	/** Makes an item of any type render as this icon. */
	public void applyModel(ItemMeta meta) {
		meta.setItemModel(material.getKey());
		LegacyModelData.set(meta, modelData);
	}
}
