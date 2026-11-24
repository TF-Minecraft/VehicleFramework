package net.tfminecraft.vehicleframework.bones;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.util.ConditionChecker;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.Wings;

public class RotationTarget {
	private BoneRotator rotator;
	private String rotatorOverride;
	private List<String> conditions = new ArrayList<>();
	/** {@link #conditions} parsed once (at load, or on first use) and shared by copies; they are tested every tick. */
	private ConditionChecker.Condition parsedConditions;
	private float yaw;
	private float pitch;
	private float roll;
	
	private boolean shouldYaw = false;
	private boolean shouldPitch = false;
	private boolean shouldRoll = false;
	
	private float interval;
	
	private boolean active;
	
	public RotationTarget(ConfigurationSection config) {
		rotatorOverride = config.getString("rotator", "none");
		conditions = config.getStringList("conditions");
		parsedConditions = ConditionChecker.compile(conditions);
		yaw = (float) config.getDouble("yaw", 0);
		if(config.contains("yaw")) shouldYaw = true;
		pitch = (float) config.getDouble("pitch", 0);
		if(config.contains("pitch")) shouldPitch = true;
		roll = (float) config.getDouble("roll", 0);
		if(config.contains("roll")) shouldRoll = true;
		interval = (float) config.getDouble("interval", 1);
	}
	
	public void updateModel() {
		//active = false; //No longer neccesary since the rotations are persistent now
	}
	
	public RotationTarget(ActiveVehicle v, RotationTarget another, BoneRotator rotator) {
		this.rotator = rotator;
		rotatorOverride = another.getRotatorOverride();
		if(!rotatorOverride.equalsIgnoreCase("none")) {
			BoneRotator override = v.getAccessPanel().getRotator(rotatorOverride);
			if(override == null) VFLogger.log("rotator is null in the rotation target for "+v.getName());
			if(override != null) this.rotator = override;
		}
		conditions = another.getConditions();
		parsedConditions = another.parsedConditions;
		yaw = another.getYaw();
		pitch = another.getPitch();
		roll = another.getRoll();
		
		shouldYaw = another.shouldYaw();
		shouldPitch = another.shouldPitch();
		shouldRoll = another.shouldRoll();
		
		active = false;
		interval = another.getInterval();
	}
	
	private boolean checkConditions(ActiveVehicle vehicle) {
		if (parsedConditions == null) parsedConditions = ConditionChecker.compile(conditions);
		return parsedConditions.test(vehicle);
	}
	
	//Every tick
	public void run(ActiveVehicle vehicle) {
		if(!checkConditions(vehicle)) {
			if(active) active = false;
			return;
		}
		if(active) return;
		active = rotator.rotateToTarget(yaw, pitch, roll, interval, shouldYaw, shouldPitch, shouldRoll);
	}
	
	public void setRotator(BoneRotator r) {
		rotator = r;
	}

	public BoneRotator getRotator() {
		return rotator;
	}

	public List<String> getConditions() {
		return conditions;
	}

	public float getYaw() {
		return yaw;
	}
	
	public float getPitch() {
		return pitch;
	}

	public float getRoll() {
		return roll;
	}
	
	

	public boolean shouldYaw() {
		return shouldYaw;
	}

	public boolean shouldPitch() {
		return shouldPitch;
	}

	public boolean shouldRoll() {
		return shouldRoll;
	}

	public boolean isActive() {
		return active;
	}

	public float getInterval() {
		return interval;
	}
	
	public String getRotatorOverride() {
		return rotatorOverride;
	}
}
