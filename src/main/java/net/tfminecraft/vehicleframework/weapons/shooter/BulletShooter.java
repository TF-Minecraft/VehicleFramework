package net.tfminecraft.vehicleframework.weapons.shooter;

import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.tfminecraft.vehicleframework.interfaces.Shooter;
import net.tfminecraft.vehicleframework.projectiles.BulletRaycast;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;
import net.tfminecraft.vehicleframework.weapons.ammunition.Bullet;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.AmmunitionData;
import net.tfminecraft.vehicleframework.weapons.Weapon;
import net.tfminecraft.vehicleframework.weapons.WeaponEntityFilters;

public class BulletShooter implements Shooter {

	private static final double MUZZLE_OFFSET = 0.75;

	private ProjectileShooter shooter;

	public BulletShooter(ProjectileShooter p) {
		shooter = p;
	}

	@Override
	public void shoot(List<Player> players, Entity vehicleRoot, Location loc, Vector vector, Ammunition a, ActiveWeapon w) {
		AmmunitionData ammoData = a.getData();
		Bullet bullet = (Bullet) a;
		Vector direction = vector.clone().normalize();
		Vector velocity = direction.clone().multiply(Weapon.effectiveProjectileSpeed(w, bullet));
		Location start = loc.clone().add(direction.clone().multiply(MUZZLE_OFFSET));
		double maxRangeSquared = bullet.getRange() * bullet.getRange();

		ActiveVehicle shooterVehicle = VehicleFramework.getVehicleManager().get(vehicleRoot);
		Set<Entity> ignoreEntities = WeaponEntityFilters.buildShooterIgnoreSet(null, vehicleRoot, shooterVehicle);

		shooter.lightEffect(start);

		new BukkitRunnable() {
			Location current = start.clone();
			int tick = 0;

			@Override
			public void run() {
				Location previous = current.clone();
				current.add(velocity);

				if (BulletRaycast.handleSegment(
						previous,
						current,
						ignoreEntities,
						shooterVehicle,
						ammoData,
						bullet,
						w,
						players)) {
					cancel();
					return;
				}

				ammoData.trailSegment(players, previous, current, 1f, tick);
				velocity.add(new Vector(0, bullet.getGravity(), 0));
				tick++;

				if (current.distanceSquared(start) > maxRangeSquared) {
					BulletRaycast.triggerExplosion(current, bullet, w);
					cancel();
				}
			}
		}.runTaskTimer(VehicleFramework.plugin, 0L, 1L);
	}
}
