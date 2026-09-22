package net.tfminecraft.vehicleframework.weapons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.weapons.ammunition.Bullet;
import net.tfminecraft.vehicleframework.weapons.data.WeaponData;

class WeaponProjectileSpeedTest {

	@Test
	void effectiveProjectileSpeed_usesOverrideWhenPresent() {
		assertEquals(9.5, Weapon.effectiveProjectileSpeed(9.5, 7.0));
	}

	@Test
	void effectiveProjectileSpeed_fallsBackToBulletSpeed() {
		assertEquals(Bullet.DEFAULT_SPEED, Weapon.effectiveProjectileSpeed((Double) null, (Bullet) null));
		assertEquals(7.0, Weapon.effectiveProjectileSpeed((Double) null, 7.0));
	}

	@Test
	void effectiveProjectileVelocity_fallsBackToWeaponDataVelocity() {
		assertEquals(WeaponData.DEFAULT_VELOCITY, Weapon.effectiveProjectileVelocity((ActiveWeapon) null));
		assertEquals(4.5, Weapon.effectiveProjectileSpeed((Double) null, 4.5));
	}
}
