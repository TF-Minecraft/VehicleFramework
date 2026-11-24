package net.tfminecraft.vehicleframework.vehicles.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import net.tfminecraft.vehicleframework.util.Text;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.Pump;
import net.tfminecraft.vehicleframework.vehicles.component.SinkableHull;
import net.tfminecraft.vehicleframework.vehicles.component.VehicleComponent;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.handlers.AmmunitionHandler;

public class ScoreboardController {
	
	private ActiveVehicle vehicle;
	
	public ScoreboardController(ActiveVehicle v) {
		vehicle = v;
	}
	
	/**
	 * The sidebar last sent to each rider: its title, its lines and the board they are on. Riders'
	 * keys call {@link #scoreboard} every tick, so a board is only built and sent again when its
	 * text changed or the player no longer shows it.
	 */
	private final Map<UUID, Shown> shown = new HashMap<>();

	private record Shown(String title, List<String> lines, Scoreboard board, Objective objective) {}

	@SuppressWarnings("deprecation")
	public void scoreboard(Player p) {
		String title = vehicle.getName();
		List<String> scores = lines(p);
		Shown last = shown.get(p.getUniqueId());
		if (last != null && Objects.equals(last.title(), title) && last.lines().equals(scores) && showing(p, last)) return;
		ScoreboardManager manager = Bukkit.getScoreboardManager();
		Scoreboard board = manager.getNewScoreboard();
		Objective obj = board.registerNewObjective("vehicleDummy", Criteria.DUMMY, title);
		obj.setDisplaySlot(DisplaySlot.SIDEBAR);
		int i = 0;
		for(String score : scores) {
			Score s = obj.getScore(score);
			s.setScore(i);
			i++;
		}
		Score scoreDivider = obj.getScore(ChatColor.RED + "=-=-=-=-=-=-=-=-=-=-=");
		scoreDivider.setScore(i);
		p.setScoreboard(board);
		shown.put(p.getUniqueId(), new Shown(title, scores, board, obj));
	}

	/**
	 * Whether the player still shows that sidebar: on its board, with nothing else put in its slot.
	 * Compared with equals: the server hands out a new objective wrapper on every call.
	 */
	private static boolean showing(Player p, Shown last) {
		return Objects.equals(p.getScoreboard(), last.board())
				&& Objects.equals(last.board().getObjective(DisplaySlot.SIDEBAR), last.objective());
	}

	/** The sidebar's lines from the bottom up, without the divider above them. */
	private List<String> lines(Player p) {
		List<String> scores = new ArrayList<>();
		scores.add("§fType: §e"+Text.capitalize(new String(vehicle.getId()).replace("_", " ")));
		int i = 0;
		for(VehicleComponent c : vehicle.getComponents()) {
			scores.add("§e"+c.getAlias()+":");
			if(c.isOnFire()) {
				scores.add(c.getFire().getFireString()+" §"+i);
			}
			scores.add("§f- "+c.getHealthData().getHealthPercentageString()+" §"+i);
			if(c instanceof Engine) {
				Engine engine = (Engine) vehicle.getComponent(Component.ENGINE);
				scores.add("§f- "+engine.getThrottle().getName()+": §e"+engine.getThrottle().getCurrent()+"%");
				if(engine.getFuelTank().useFuel()) scores.add("§f- Fuel: §e"+engine.getFuelTank().getPercentage()+"%");
			} else if(c instanceof Pump) {
				Pump pump = (Pump) vehicle.getComponent(Component.PUMP);
				scores.add("§f- Power: §e"+pump.getPower());
			} else if(c instanceof GearedEngine) {
				GearedEngine engine = (GearedEngine) vehicle.getComponent(Component.GEARED_ENGINE);
				scores.add("§f- Gear: §e"+engine.getGear().getName());
				scores.add("§f- "+engine.getGear().getThrottle().getName()+": §e"+engine.getGear().getThrottle().getCurrent()+"%");
				scores.add("§f- Fuel: §e"+engine.getFuelTank().getPercentage()+"%");
			}
			i++;
		}
		if (vehicle.isLocomotive() && !vehicle.hasParent()) {
			var overdrive = vehicle.getTrainHandler().getOverdrive();
			long cooldown = overdrive.cooldownSeconds(System.currentTimeMillis());
			long remaining = overdrive.remainingSeconds();
			scores.add(cooldown > 0 ? "§f- Overdrive cooldown: §e" + cooldown + "s"
					: remaining > 0 ? "§f- Overdrive left: §e" + remaining + "s"
					: "§f- Overdrive: §aReady (W > 100%)");
		}
		if(vehicle.hasComponent(Component.HULL)) {
			if(vehicle.getComponent(Component.HULL) instanceof SinkableHull) {
				SinkableHull sHull = (SinkableHull) vehicle.getComponent(Component.HULL);
				if(sHull.hasSinkProgress()) {
					scores.add("§9SINKING!! §b"+sHull.getSinkProgress()+"% §1");
				}
			}
		}
		if(vehicle.isPassenger(p, false) && vehicle.getSeat(p).hasWeapon()) {
			ActiveWeapon w = vehicle.getSeat(p).getWeapon();
			scores.add("§eWeapon: §7"+w.getName());
			AmmunitionHandler h = w.getAmmunitionHandler();
			if(h.hasAmmo()) {
				if(h.getCount() > 0) scores.add(h.getAmmo().getName()+" §fx"+h.getCount());
			}
		}
		Collections.reverse(scores);
		return scores;
	}
	
	public void removeScoreboard(Player p) {
		shown.remove(p.getUniqueId());
		Scoreboard board = p.getScoreboard();
		Objective obj = board.getObjective("vehicleDummy");
		if(obj != null) obj.unregister();
	}
}
