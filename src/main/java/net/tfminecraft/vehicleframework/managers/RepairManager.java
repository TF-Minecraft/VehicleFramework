package net.tfminecraft.vehicleframework.managers;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.util.Text;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.events.VehicleRepairStartEvent;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.State;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;

public class RepairManager implements Listener{
	@SuppressWarnings("deprecation")
	private ItemAPI api = TLibs.getItemAPI();
	private VehicleManager manager;
	private InventoryManager inv = new InventoryManager();
	
	private HashMap<Player, ActiveVehicle> repairing = new HashMap<>();
	private HashMap<Player, String> activeTool = new HashMap<>();
	private HashMap<Player, VehicleComponent> beingRepaired = new HashMap<>();
	private HashMap<Player, ActiveWeapon> weaponRepair = new HashMap<>();

	public RepairManager(VehicleManager m) {
		manager = m;
	}

	public String getTool(Player p) {
		return activeTool.get(p);
	}

	public ActiveVehicle getRepairTarget(Player p) {
		return repairing.get(p);
	}
	
	public boolean isBeingRepaired(ActiveWeapon w) {
		return weaponRepair.containsValue(w);
	}
	
	public boolean isBeingRepaired(VehicleComponent c) {
		return beingRepaired.containsValue(c);
	}
	
	public void stop(Player p) {
		repairing.remove(p);
		activeTool.remove(p);
		if(weaponRepair.containsKey(p)) {
			p.sendMessage("§cRepairing cancelled");
			p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1f);
			weaponRepair.get(p).getHealthData().stopRepair();
			weaponRepair.remove(p);
		}
		if(beingRepaired.containsKey(p)) {
			p.sendMessage("§cRepairing cancelled");
			p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1f);
			beingRepaired.get(p).getHealthData().stopRepair();
			beingRepaired.remove(p);
		}
	}

	public void repair(Player p, ActiveVehicle v) {
		if(p == null || v == null) {
			return;
		}
		VehicleRepairStartEvent startEvent = new VehicleRepairStartEvent(p, v);
		Bukkit.getPluginManager().callEvent(startEvent);
		if(startEvent.isCancelled()) {
			return;
		}
		ItemStack i = p.getInventory().getItemInMainHand();
		if(!api.getChecker().checkItemWithPath(i, Cache.repairItem)) return;
		if(v.getSeat(p) == null) {
			if(v.getCurrentState().getType().equals(State.FLYING)) {
				p.sendMessage("§cCannot repair while flying");
				return;
			}
			if(v.getAccessPanel().getSpeed() > 0.2) {
				p.sendMessage("§cCannot repair while moving");
				return;
			}
		} else {
			Seat s = v.getSeat(p);
			if(!s.getType().equals(SeatType.MECHANIC)) {
				p.sendMessage("§cYou are not in a mechanic seat");
				return;
			}
		}
		activeTool.put(p, "repair");
		inv.repairWindow(null, p, v, true, activeTool.get(p));
	}
	
	public void tick() {
		for(Player p : repairing.keySet()) {
			if(!p.getOpenInventory().getTitle().equalsIgnoreCase("§7Repair Vehicle")) {
				stop(p);
				continue;
			}
			inv.repairWindow(p.getOpenInventory().getTopInventory(), p, repairing.get(p), false, activeTool.get(p));
		}
		for(Player p : weaponRepair.keySet()) {
			if(repairing.containsKey(p)) continue;
			if(!p.getOpenInventory().getTitle().equalsIgnoreCase("§7Repair Weapon")) {
				stop(p);
				continue;
			}
			inv.repairWindow(p.getOpenInventory().getTopInventory(), p, repairing.get(p), false, activeTool.get(p));
		}
	}
	
	@EventHandler
	public void repairEvent(InventoryClickEvent e) {
		Player p = (Player) e.getWhoClicked();
		if(!(e.getView().getTopInventory().getHolder() instanceof VFInventoryHolder)) return;
		VFInventoryHolder h = (VFInventoryHolder) e.getView().getTopInventory().getHolder();
		if(!h.getType().equals(VFGUI.REPAIR)) return;
		ActiveVehicle v = manager.get(h.getId());
		e.setCancelled(true);
		ItemStack i = e.getCurrentItem();
		if(i == null) return;
		ItemMeta m = i.getItemMeta();
		NamespacedKey key = new NamespacedKey(VehicleFramework.plugin, "vf_tool_type");
		if(m.getPersistentDataContainer().get(key, PersistentDataType.STRING) != null) {
			String tool = m.getPersistentDataContainer().get(key, PersistentDataType.STRING);
			activeTool.put(p, tool);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
			inv.repairWindow(null, p, v, true, activeTool.get(p));
			return;
		}
		key = new NamespacedKey(VehicleFramework.plugin, "vf_component_type");
		if(m.getPersistentDataContainer().get(key, PersistentDataType.STRING) != null) {
			repairComponent(p, m, key, v);
			return;
		}
		key = new NamespacedKey(VehicleFramework.plugin, "vf_weapon_repair_type");
		if(m.getPersistentDataContainer().get(key, PersistentDataType.STRING) != null) {
			repairWeapon(p, m, key, v);
			return;
		}
	}
	
	public void repairComponent(Player p, ItemMeta m, NamespacedKey key, ActiveVehicle v ) {
		Component type = Component.valueOf(m.getPersistentDataContainer().get(key, PersistentDataType.STRING).toUpperCase());
		VehicleComponent c = v.getComponent(type);
		if(isBeingRepaired(c)) {
			p.sendMessage("§cComponent is already under repair");
			return;
		}
		if(repairing.containsKey(p)) {
			p.sendMessage("§cYou are already repairing something");
			return;
		}
		String tool = activeTool.get(p);
		if(c.isOnFire() && tool.equalsIgnoreCase("water")) {
			c.getFire().fight();
			p.getWorld().playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 1f);
			repairing.get(p).updateBoard();
		} else if(c.getHealthData().getHealthPercentage() < 100 && tool.equalsIgnoreCase("repair")) {
			c.getHealthData().startRepair();
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
			beingRepaired.put(p, c);
			new BukkitRunnable() {
		        @SuppressWarnings("deprecation")
				@Override
		        public void run() {
					if(v.isDestroyed()) return;
		        	if(!beingRepaired.containsKey(p)) return;
		        	v.updateBoard();
		        	beingRepaired.remove(p);
					repairing.remove(p);
		        	p.sendMessage("§aRepaired §e"+Text.capitalize(c.getType().toString().toLowerCase()));
		        	p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
		        }
		    }.runTaskLater(VehicleFramework.plugin, c.getHealthData().getBaseRepairTime()*1L);
		}
	}
	public void repairWeapon(Player p, ItemMeta m, NamespacedKey key, ActiveVehicle v) {
		ActiveWeapon w = v.getWeaponHandler().getWeapon(m.getPersistentDataContainer().get(key, PersistentDataType.STRING));
		if(w == null) return;
		if(isBeingRepaired(w)) {
			p.sendMessage("§cWeapon is already under repair");
			return;
		}
		if(repairing.containsKey(p)) {
			p.sendMessage("§cYou are already repairing something");
			return;
		}
		if(w.getHealthData().getHealthPercentage() < 100) {
			String tool = activeTool.get(p);
			if(!tool.equalsIgnoreCase("repair")) return;
			w.getHealthData().startRepair();
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
			repairing.put(p, v);
			weaponRepair.put(p, w);
			new BukkitRunnable() {
				@Override
				public void run() {
					if(v.isDestroyed()) return;
					if(!weaponRepair.containsKey(p)) return;
					v.updateBoard();
					weaponRepair.remove(p);
					repairing.remove(p);
					p.sendMessage("§aRepaired §e"+w.getName());
					p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
				}
			}.runTaskLater(VehicleFramework.plugin, w.getHealthData().getBaseRepairTime()*1L);
		}
	}
}
