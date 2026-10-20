package net.tfminecraft.vehicleframework.weapons.shooter;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.interfaces.Shooter;
import net.tfminecraft.vehicleframework.projectiles.HitChecker;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.Weapon;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;
import net.tfminecraft.vehicleframework.weapons.ammunition.ClusterBomb;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.AmmunitionData;

public class DefaultShooter implements Shooter {
	//Regular shooting method for things like cannonballs, explode on impact
	
	// Vanilla armour stand physics per tick, so bomblets fall as they did under vanilla movement.
	private static final double CLUSTER_GRAVITY = 0.08;
	private static final double CLUSTER_VERTICAL_DRAG = 0.98;
	private static final double CLUSTER_HORIZONTAL_DRAG = 0.91;
	// Safety stop for a bomblet that never reaches anything.
	private static final int CLUSTER_MAX_TICKS = 200;
	
	private HitChecker checker = new HitChecker();
	private ProjectileShooter shooter;
	
	public DefaultShooter(ProjectileShooter p) {
		shooter = p;
	}

	@Override
	public void shoot(List<Player> players, Entity o, Location loc, Vector vector, Ammunition a, ActiveWeapon w) {
		AmmunitionData ammoData = a.getData();
	    List<Entity> projectiles = new ArrayList<>();
	    
	    shooter.lightEffect(loc);
	    
	    Entity e = ammoData.spawn(loc);
		Cache.projectiles.add(e);
	    
	    Vector velocity = vector.clone().multiply(Weapon.effectiveProjectileVelocity(w));
	    new BukkitRunnable() {
	        int i = 0;
	        float pitch = 1.2f;
	        
	        // Track the current position of the entity
	        Location currentLocation = loc.clone();
	        
	        public void run() {   
	            i++;

	            // Apply gravity (decrease Y component of velocity)
	            velocity.setY(velocity.getY() - 0.049);  // Gravity affects the Y component

	            // Update the location by adding the velocity (vector + gravity)
	            currentLocation.add(velocity.getX(), velocity.getY(), velocity.getZ());
	            
	            // Teleport the entity to the new calculated location
	            e.teleport(currentLocation);

	            // Check if the projectile has hit something or reached the ground
	            if (i > 5 && (checker.hasHit(e, projectiles) || e.isOnGround() || e.isDead())) {
	                shooter.triggerExplosion(players, e.getLocation(), ammoData, w);
	                e.remove();
	                projectiles.remove(e);
					Cache.projectiles.remove(e);
	                cancel();
	                return;
	            }

	            // Add the offset for visual effects
	            Location visualLocation = currentLocation.clone().add(0, ammoData.getOffset(), 0);

	            // Play a sound effect as the projectile flies through the air
	            if (i > 20) {
	                pitch = pitch - 0.015f;
	                if (pitch < 0.3f) {
	                    pitch = 0.3f;
	                }
	            }
	            a.getData().fx(players, visualLocation, pitch, i);
	            if(a instanceof ClusterBomb) {
	            	ClusterBomb c = (ClusterBomb) a;
	            	if(i >= c.getFuse()) {
	            		int particleCount = (int) Math.round(Weapon.effectiveYield(w, ammoData) * 15);
	            		for(Player p : players) {
	        				p.spawnParticle(Particle.EXPLOSION_EMITTER, e.getLocation(), particleCount, 0, 0, 0, 0);
	        			}
						shooter.triggerExplosion(players, e.getLocation(), ammoData, w);
	            		sendCluster(e.getLocation(), c, players, projectiles, w);
	            		e.remove();
		                projectiles.remove(e);
						Cache.projectiles.remove(e);
		                cancel(); 
	            	}
	            }
	        }
	    }.runTaskTimer(VehicleFramework.plugin, 0L, 1L);
	}
	
	private void sendCluster(Location original, ClusterBomb a, List<Player> players, List<Entity> projectiles, ActiveWeapon w) {
	    World world = original.getWorld();
	    if (world == null) return;
	    int count = Weapon.effectiveClusterAmount(w, a);
	    for (int i = 0; i < count; i++) {
	        Entity armorStand = a.getClusterData().spawn(original);
	        projectiles.add(armorStand);
			Cache.projectiles.add(armorStand);
			// The task below moves it; vanilla gravity would move it a second time near players.
			armorStand.setGravity(false);

	        Vector velocity = new Vector(
	            (Math.random() - 0.5) * a.getSpread(),
	            -3.5, // downward velocity
	            (Math.random() - 0.5) * a.getSpread()
	        );

	        // Handle explosion on ground impact
	        new BukkitRunnable() {
	        	int i = 0;
	        	boolean landed = false;
	        	Location current = armorStand.getLocation();
	            public void run() {
	                if (i > 5 && (landed || checker.hasHit(armorStand, projectiles) || armorStand.isDead())) {
	                    shooter.triggerExplosion(players, armorStand.getLocation(), a.getClusterData(), w);
	                    removeBomblet();
	                    return;
	                }
	                if (i >= CLUSTER_MAX_TICKS) {
	                    removeBomblet();
	                    return;
	                }
	                if (!landed) {
	                    landed = move();
	                }
	                a.getData().fx(players, armorStand.getLocation(), 1f, i);
	                i++;
	            }

	            // Not left to vanilla physics: entities stop moving outside simulation distance.
	            private boolean move() {
	                RayTraceResult hit = world.rayTraceBlocks(current, velocity.clone().normalize(), velocity.length(), FluidCollisionMode.ALWAYS, false);
	                if (hit != null) {
	                    // Stop on the surface instead of passing through thin roofs or into the ground.
	                    current = hit.getHitPosition().toLocation(world, current.getYaw(), current.getPitch());
	                    armorStand.teleport(current);
	                    return true;
	                }
	                current.add(velocity);
	                armorStand.teleport(current);
	                velocity.setX(velocity.getX() * CLUSTER_HORIZONTAL_DRAG);
	                velocity.setY((velocity.getY() - CLUSTER_GRAVITY) * CLUSTER_VERTICAL_DRAG);
	                velocity.setZ(velocity.getZ() * CLUSTER_HORIZONTAL_DRAG);
	                return false;
	            }

	            private void removeBomblet() {
	                armorStand.remove();
	                projectiles.remove(armorStand);
	                Cache.projectiles.remove(armorStand);
	                cancel();
	            }
	        }.runTaskTimer(VehicleFramework.plugin, 0L, 1L);
	    }
	}

}
