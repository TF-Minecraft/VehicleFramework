package net.tfminecraft.vehicleframework.vehicles.component;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import com.ticxo.modelengine.api.model.ActiveModel;

import net.tfminecraft.vehicleframework.database.IncompleteComponent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class SinkableHull extends Hull{
	
	private Pump pump;
	
	private boolean sinking;
	
	private double sinkProgress;
	
	public SinkableHull(ConfigurationSection config) {
		super(config);
		sinkProgress = 0;
		sinking = false;
	}
	public SinkableHull(ActiveVehicle v, SinkableHull another, ActiveModel m, IncompleteComponent ic) {
		super(another, v, m, ic);
		sinkProgress = ic.getSinkProgress();
		sinking = ic.isSinking();
	}
	
	public boolean hasPump() {
		return pump != null;
	}
	
	public Pump getPump() {
		return pump;
	}
	
	public void setPump(Pump p) {
		pump = p;
	}
	
	public boolean isSinking() {
		return sinking;
	}
	
	public boolean hasSinkProgress() {
		return sinkProgress > 0;
	}

	public void setSinkProgress(int i) {
		sinkProgress = i;
	}

	public int getSinkProgress() {
		return (int) Math.round(sinkProgress);
	}

	/** Hull health percentage below which this hull takes on water. */
	public double getSinkThreshold() {
		return sinkThreshold(hasPump() ? pump.getPower() : null);
	}

	/** Sink threshold for a hull whose pump has {@code pumpPower} left, or no pump when null. */
	public static double sinkThreshold(Integer pumpPower) {
		double pumpThreshold = 80;
		if(pumpPower != null) {
			pumpThreshold = 100 - pumpPower;
		}
		return Math.min(pumpThreshold, 80);
	}
	@Override
	public void slowTick(List<Player> nearby) {
		super.slowTick(nearby);
		if(!v.getBehaviourHandler().shouldFloat()) return;
		double hullStrength = healthData.getHealthPercentage();
		double pumpThreshold = getSinkThreshold();
	    if (hullStrength < pumpThreshold) {
	    	double rate = Math.round((pumpThreshold - hullStrength)/5);
	    	if(rate < 1) rate = 1;
	    	if(rate > 7) rate = 4;
	        sinkProgress += rate;
	        if(sinkProgress > 100) sinkProgress = 100;
	        sinking = true;
	    } else {
	    	sinking = false;
	        if(sinkProgress > 0) {
	        	if(hasPump()) {
				sinkProgress = Math.max(0, sinkProgress - pump.getPower()/10);
	        	} else {
	        		sinkProgress -= 1;
	        	}
	        }
	    }
	}

}
