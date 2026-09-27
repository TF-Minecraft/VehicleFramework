package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Deck boxes sit over their car, so players hit and click them instead of it. Hits go on
 * to the car, and nothing else touches the boxes. Right clicks are passed on by
 * {@code VehicleManager}, which handles clicks on vehicles.
 */
public class DeckListener implements Listener {

	@EventHandler(priority = EventPriority.LOWEST)
	public void attack(PrePlayerAttackEntityEvent e) {
		if (!DeckBody.isPart(e.getAttacked())) {
			return;
		}
		e.setCancelled(true);
		ActiveVehicle owner = DeckBody.owner(e.getAttacked());
		Entity car = owner == null ? null : owner.getEntity();
		if (car != null && car.isValid()) {
			e.getPlayer().attack(car);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void damage(EntityDamageEvent e) {
		if (DeckBody.isPart(e.getEntity())) {
			e.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void interactAt(PlayerInteractAtEntityEvent e) {
		if (DeckBody.isPart(e.getRightClicked())) {
			e.setCancelled(true);
		}
	}
}
