package net.tfminecraft.vehicleframework.events;

import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.vehicleframework.cache.Cache;

public class VFExplosionEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private Location location;
    private boolean blockDamage;
    private boolean cancelled;

    public VFExplosionEvent(Location location) {
        this.location = location;
        this.blockDamage = Cache.blockDamage; // Default value from Cache
        this.cancelled = false;
    }

    public Location getLocation() {
        return location;
    }

    public boolean doesBlockDamage() {
        return blockDamage;
    }

    public void setBlockDamage(boolean blockDamage) {
        this.blockDamage = blockDamage;
    }

    // Cancellable Implementation
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    // Required HandlerList methods
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
