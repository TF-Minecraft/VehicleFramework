package net.tfminecraft.vehicleframework.weapons;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.ticxo.modelengine.api.model.ActiveModel;

import net.tfminecraft.vehicleframework.bones.RotationLimits;
import net.tfminecraft.vehicleframework.data.DamageData;
import net.tfminecraft.vehicleframework.data.HealthData;
import net.tfminecraft.vehicleframework.database.IncompleteWeapon;
import net.tfminecraft.vehicleframework.enums.Keybind;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.AnimationHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.InputHandler;
import net.tfminecraft.vehicleframework.weapons.ammunition.Bullet;
import net.tfminecraft.vehicleframework.weapons.ammunition.data.AmmunitionData;
import net.tfminecraft.vehicleframework.weapons.controller.WeaponMovementController;
import net.tfminecraft.vehicleframework.weapons.data.WeaponData;
import net.tfminecraft.vehicleframework.weapons.handlers.AmmunitionHandler;

public class ActiveWeapon {
	protected String uuid;
	
	protected String id;
	protected String name;
	
	protected WeaponData weaponData;
	protected HealthData healthData;
	protected DamageData damageData;
	
	protected AmmunitionHandler ammunitionHandler;
	protected AnimationHandler animationHandler;
	protected InputHandler inputHandler;
	protected WeaponMovementController moveControls;
	
	protected String seat;
	protected WeaponAimMode aimMode;
	
	protected Integer projectileDamage;
	protected String projectileDamageType;
	protected Double projectileSpeed;
	protected Float projectileYield;
	protected Integer projectileRadius;
	protected Boolean projectileExplosive;
	protected Integer projectileClusterAmount;
	
	protected Player controller;
	
	public ActiveWeapon(ActiveModel m, ActiveVehicle vehicle, Weapon stored, IncompleteWeapon i) {
		uuid = UUID.randomUUID().toString();
		id = stored.getId();
		name = stored.getName();
		weaponData = stored.getWeaponData();
		healthData = new HealthData(stored.getHealthData().getHealth(), stored.getHealthData().getDamage(), stored.getHealthData().getBaseRepairTime());
		damageData = stored.getDamageData();
		ammunitionHandler = new AmmunitionHandler(m, vehicle, this, stored.getAmmunitionHandler(), stored.getBones());
		animationHandler = new AnimationHandler(m, stored.getAnimationHandler());
		inputHandler = new InputHandler(stored.getInputHandler());
		moveControls = new WeaponMovementController(vehicle, m, this, stored, stored.getLimits());
		seat = stored.getSeat();
		aimMode = stored.getAimMode();
		projectileDamage = stored.getProjectileDamage();
		projectileDamageType = stored.getProjectileDamageType();
		projectileSpeed = stored.getProjectileSpeed();
		projectileYield = stored.getProjectileYield();
		projectileRadius = stored.getProjectileRadius();
		projectileExplosive = stored.getProjectileExplosive();
		projectileClusterAmount = stored.getProjectileClusterAmount();
		
	}
	
	public void updateModel(ActiveModel m) {
		ammunitionHandler.updateModel(m);
		animationHandler.updateModel(m);
		moveControls.updateModel(m);
	}
	
	public boolean isControlled() {
		return controller != null;
	}
	public Player getController() {
		return controller;
	}
	public void setController(Player p) {
		controller = p;
	}
	public void disconnect() {
		controller = null;
	}
	
	public void input(List<Player> nearby, Keybind key) {
		if(!isControlled()) return;
		moveControls.input(nearby, inputHandler.getInput(key), controller);
	}
	
	public void tick() {
		if (!isControlled()) {
			moveControls.normalize();
		} else if (aimMode == WeaponAimMode.CURSOR) {
			moveControls.trackCursor(controller);
		}
		moveControls.move();
	}
	
	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}
	
	public WeaponData getWeaponData() {
		return weaponData;
	}
	public HealthData getHealthData() {
		return healthData;
	}
	
	public DamageData getDamageData() {
		return damageData;
	}
	
	public String getSeat() {
		return seat;
	}
	
	public AnimationHandler getAnimationHandler() {
		return animationHandler;
	}
	
	public InputHandler getInputHandler() {
		return inputHandler;
	}

	public AmmunitionHandler getAmmunitionHandler() {
		return ammunitionHandler;
	}

	public int effectiveDamage(AmmunitionData ammo) {
		return Weapon.effectiveDamage(projectileDamage, ammo);
	}

	public String effectiveDamageType(AmmunitionData ammo) {
		return Weapon.effectiveDamageType(projectileDamageType, ammo);
	}

	public double effectiveBulletSpeed(Bullet bullet) {
		return Weapon.effectiveProjectileSpeed(projectileSpeed, bullet);
	}

	public double effectiveProjectileVelocity() {
		return Weapon.effectiveProjectileSpeed(projectileSpeed, weaponData.getVelocity());
	}
	
	public void damage(String cause, double a) {
		if(damageData.hasModifier(cause)) {
			a = a*damageData.getModifier(cause);
		}
		healthData.damage(a);
	}
}
