package net.tfminecraft.vehicleframework.vehicles.handlers.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.animation.property.SimpleProperty;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.utils.data.io.SavedData;

import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Direction;

public class AnimationHandler {
	private HashMap<Animation, List<String>> animations = new HashMap<>();
	
	private ActiveModel m;
	
	public AnimationHandler(ConfigurationSection config) {
		for (Animation animation : Animation.values()) {
	        String enumString = animation.name().toLowerCase(java.util.Locale.ROOT);
	        if (config.contains(enumString)) {
	            animations.put(animation, config.getStringList(enumString));
	        } else {
	        	animations.put(animation, new ArrayList<String>());
	        }
	    }
	}
	
	public AnimationHandler() {
		for (Animation animation : Animation.values()) {
			animations.put(animation, new ArrayList<String>());
	    }
	}
	
	public AnimationHandler(ActiveModel m, AnimationHandler other) {
		this.m = m;
        for (Map.Entry<Animation, List<String>> entry : other.animations.entrySet()) {
            animations.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }
	
	public List<String> get(Animation a){
		return animations.get(a);
	}
	
	public boolean hasModel() {
		return m != null;
	}
	
	public void updateModel(ActiveModel model) {
		m = model;
	}
	
	public void animate(Animation a) {
		stopOtherAnimations(a);
		for(String anim : animations.get(a)) {
			castAnimation(anim);
		}
	}
	
	public void animate(Animation a, int i) {
		stopOtherAnimations(a);
		if(animations.get(a).size() < i+1) return;
		castAnimation(animations.get(a).get(i));
	}
	
	public void animate(String anim) { //if you want to use an animation not specified in the config, for example for a weapon shoot animation
		castAnimation(anim);
	}
	
	private void castAnimation(String s) {
		BlueprintAnimation anim = m.getBlueprint().getAnimations().get(s);
		if(anim == null) return;
		if(m.getAnimationHandler().isPlayingAnimation(s)) return;
		m.getAnimationHandler().playAnimation(new SimpleProperty(m, anim), true);
	}
	
	/**
	 * Holds train wheels at rest and matches the phase of paired, mirrored movement
	 * loops on reversal. Speed is wheel turns per second; lists pair by configured order.
	 */
	public void animateWheels(Direction direction, double speed) {
		if (m == null) {
			return;
		}
		if (direction == Direction.STILL || speed == 0) {
			pauseWheels(Animation.FORWARD);
			pauseWheels(Animation.BACKWARD);
			return;
		}
		Animation target = direction == Direction.BACKWARD ? Animation.BACKWARD : Animation.FORWARD;
		Animation opposite = target == Animation.FORWARD ? Animation.BACKWARD : Animation.FORWARD;
		List<String> names = animations.get(target);
		List<String> previous = animations.get(opposite);
		var engine = m.getAnimationHandler();
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			BlueprintAnimation blueprint = m.getBlueprint().getAnimations().get(name);
			if (blueprint == null) {
				continue;
			}
			IAnimationProperty playing = engine.getAnimation(name);
			if (playing == null || playing.isEnded()) {
				SimpleProperty next = new SimpleProperty(m, blueprint);
				IAnimationProperty from = i < previous.size() ? engine.getAnimation(previous.get(i)) : null;
				if (from != null && !from.isEnded()
						&& from.getBlueprintAnimation().getLoopMode() == BlueprintAnimation.LoopMode.LOOP
						&& blueprint.getLoopMode() == BlueprintAnimation.LoopMode.LOOP
						&& from.getBlueprintAnimation().getLength() > 0 && blueprint.getLength() > 0) {
					double phase = Math.max(0, Math.min(1,
							from.getTime() / from.getBlueprintAnimation().getLength()));
					seek(next, (1 - phase) * blueprint.getLength());
				}
				next.setSpeed(speed * blueprint.getLength());
				engine.playAnimation(next, true);
			} else {
				if (blueprint.getLoopMode() == BlueprintAnimation.LoopMode.LOOP) {
					playing.setForceLoopMode(null);
				}
				playing.setSpeed(speed * blueprint.getLength());
			}
		}
		for (String name : previous) {
			if (!names.contains(name)) {
				engine.forceStopAnimation(name);
			}
		}
	}

	private void pauseWheels(Animation direction) {
		for (String name : animations.get(direction)) {
			IAnimationProperty playing = m.getAnimationHandler().getAnimation(name);
			if (playing != null) {
				// LOOP at speed zero wraps its endpoint to frame zero; HOLD retains it.
				if (playing.getBlueprintAnimation().getLoopMode() == BlueprintAnimation.LoopMode.LOOP) {
					playing.setForceLoopMode(BlueprintAnimation.LoopMode.HOLD);
				}
				playing.setSpeed(0);
			}
		}
	}

	// ModelEngine exposes seeking through its saved playback state, not a time setter.
	// Seed both times and enter PLAY so the first update does not reset to frame zero.
	private static void seek(SimpleProperty animation, double time) {
		SavedData state = new SavedData();
		animation.save(state);
		state.putDouble("time", time);
		state.putDouble("last_time", time);
		state.putString("phase", IAnimationProperty.Phase.PLAY.name());
		animation.load(state);
	}

	public void stopAllAnimations() {
		for(Animation a : animations.keySet()) {
			stop(a);
		}
	}
	
	private void stopOtherAnimations(Animation a) {
		switch(a) {
			case BACKWARD:
				stop(Animation.FORWARD);
				break;
			case DEFAULT:
				break;
			case EXPLODE:
				break;
			case FORWARD:
				stop(Animation.BACKWARD);
				break;
			case LEFT:
				break;
			case RIGHT:
				break;
			case SINK:
				break;
			default:
				break;
		}
	}
	
	/** Plays this move's animations at the given speed, where 1 is as authored. */
	public void setSpeed(Animation a, double speed) {
		if (m == null) {
			return;
		}
		for (String s : animations.get(a)) {
			IAnimationProperty playing = m.getAnimationHandler().getAnimation(s);
			if (playing != null) {
				playing.setSpeed(speed);
			}
		}
	}

	public void stop(Animation a) {
		for(String s : animations.get(a)) {
			BlueprintAnimation anim = m.getBlueprint().getAnimations().get(s);
			if(anim == null) continue;
			if(!m.getAnimationHandler().isPlayingAnimation(s)) continue;
			m.getAnimationHandler().stopAnimation(s);
		}	
	}
}
