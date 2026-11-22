package net.tfminecraft.vehicleframework.vehicles.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

import com.ticxo.modelengine.api.ModelEngineAPI;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.handlers.skins.VehicleSkin;

public class SkinHandler {
	private ActiveVehicle v;
	private VehicleSkin currentSkin;
	/** The skin a vehicle falls back to when its saved skin is gone (the vehicle's model key). */
	private String defaultSkinId;
	/**
	 * The skin id saved with the vehicle. It differs from the current skin while a missing skin
	 * is shown as the default, so a skin that is only briefly unavailable (a ModelEngine reload,
	 * an unreadable player-skins.yml) comes back on the next load instead of being overwritten.
	 */
	private String savedSkinId;
	
	// Insertion order: config skins first, player skins after them. Spawned vehicles share this map.
	private HashMap<String, VehicleSkin> skins = new LinkedHashMap<>();

	public static boolean isModelAvailable(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return false;
		}
		return ModelEngineAPI.getBlueprint(modelId) != null;
	}

	public static boolean isModelAvailable(VehicleSkin skin) {
		return skin != null && isModelAvailable(skin.getModel());
	}

	public SkinHandler(String model, ConfigurationSection config) {
		Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		for(String key : list) {
			VehicleSkin skin = new VehicleSkin(key, config.getConfigurationSection(key));
			if (!isModelAvailable(skin)) {
				VFLogger.log("Skin '" + key + "' references missing ModelEngine model '" + skin.getModel() + "'");
			}
			skins.put(key, skin);
		}
		defaultSkinId = model;
		savedSkinId = model;
		currentSkin = skins.get(model);
	}
	
	public SkinHandler(ActiveVehicle vehicle, String skin, SkinHandler another) {
		v = vehicle;
		skins = another.getSkins();
		defaultSkinId = another.defaultSkinId;
		// The spawner already resolved (and logged) the same fallback.
		VehicleSkin resolved = another.resolve(skin, false);
		currentSkin = resolved != null ? resolved : skins.get(skin);
		savedSkinId = skin != null ? skin : defaultSkinId;
	}

	/**
	 * The skin a saved vehicle spawns with. A skin id that is no longer listed, or a player skin
	 * whose model is gone, falls back to the default skin so a revoked upload never strands the
	 * vehicle. A config skin with a missing model is returned as is (the spawner reports it).
	 */
	public VehicleSkin resolveSpawnSkin(String requested) {
		return resolve(requested, true);
	}

	private VehicleSkin resolve(String requested, boolean log) {
		VehicleSkin skin = requested == null ? null : skins.get(requested);
		if (skin != null && (!skin.isPlayerSkin() || isModelAvailable(skin))) {
			return skin;
		}
		VehicleSkin fallback = defaultSkinId == null ? null : skins.get(defaultSkinId);
		if (log && fallback != null && requested != null && !requested.equals(defaultSkinId)) {
			VFLogger.log("Vehicle skin '" + requested + "' is unavailable; using '" + defaultSkinId + "' instead");
		}
		return fallback != null ? fallback : skin;
	}

	/** Adds a website skin; refuses an id the vehicle's own config already uses. */
	public boolean addPlayerSkin(VehicleSkin skin) {
		if (skin == null || !skin.isPlayerSkin()) return false;
		VehicleSkin existing = skins.get(skin.getId());
		if (existing != null && !existing.isPlayerSkin()) return false;
		skins.put(skin.getId(), skin);
		return true;
	}

	/** Removes every website skin, keeping the vehicle's own config skins. */
	public int removePlayerSkins() {
		int removed = 0;
		Iterator<VehicleSkin> it = skins.values().iterator();
		while (it.hasNext()) {
			if (it.next().isPlayerSkin()) {
				it.remove();
				removed++;
			}
		}
		return removed;
	}
	
	public boolean canChangeSkin(String id, boolean override) {
		if(currentSkin.getId().equalsIgnoreCase(id) && !override) return false;
		if(!skins.containsKey(id)) return false;
		if (!override && !isModelAvailable(skins.get(id))) return false;
		if(v.getSeatHandler().hasPassengers() && !override) return false;
		if(v.getStateHandler().getCurrentState().getType().equals(State.FLYING) && !override) return false;
		if(v.hasComponent(Component.ENGINE) && !override) {
			Engine e = (Engine) v.getComponent(Component.ENGINE);
			if(e.requiresStart() && e.isStarted()) return false;
			if(e.getThrottle().getCurrent() != 0) return false;
		}
		return true;
	}
	
	public String changeSkin(String id) {
		if(!skins.containsKey(id)) return null;
		VehicleSkin skin = skins.get(id);
		if (!isModelAvailable(skin)) return null;
		currentSkin = skin;
		savedSkinId = skin.getId();
		return currentSkin.getModel();
	}

	/** The skin id to save: the painted skin, even while a missing one is shown as the default. */
	public String getSavedSkinId() {
		if (savedSkinId != null) return savedSkinId;
		return currentSkin == null ? null : currentSkin.getId();
	}

	public VehicleSkin getCurrentSkin() {
		return currentSkin;
	}

	public HashMap<String, VehicleSkin> getSkins() {
		return skins;
	}
	
	
}
