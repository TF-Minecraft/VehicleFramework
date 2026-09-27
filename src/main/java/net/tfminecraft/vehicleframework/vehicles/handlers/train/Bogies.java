package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.util.Vector;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.bones.ConvertedAngle;
import net.tfminecraft.vehicleframework.bones.RotationLimits;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackSpline;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * A carriage on two bogies, as real coaches are built. The body rests on the rail under
 * each bogie, so it lies along the line between them, and each bogie turns and tilts to
 * follow the rail under it. Bogie bones must pivot at the bogie's centre, and the body
 * rotator must pivot at the model's origin. A vehicle's skins share its bogie config, so
 * each model is checked for the bogie bones: one without them places the car rigid.
 */
public final class Bogies {
	// Bogies closer than this along the car cannot set the body's angle.
	static final double MIN_SPAN = 0.25;
	private final List<String> bones;
	private ActiveVehicle v;
	private final RotatorFactory rotatorFactory;
	private final List<BoneRotator> rotators = new ArrayList<>();
	// Along the car from the model's origin, in blocks; +z faces +s.
	private double[] offsets;
	// The model the offsets and rotators were taken from. A skin change brings another.
	private ActiveModel readyFor;
	// A model without both bogie bones, such as a skin built without bogies: placed rigid.
	private ActiveModel missingFor;

	/** Makes the rotator that turns one bogie bone. */
	interface RotatorFactory {
		BoneRotator create(ActiveVehicle v, ModelBone bone);
	}

	public Bogies(List<String> bones) {
		this.bones = List.copyOf(bones);
		this.rotatorFactory = null;
	}

	public Bogies(ActiveVehicle v, Bogies another) {
		this(v, another.bones, (car, bone) -> new BoneRotator(car, car.getEntity(), bone, new RotationLimits()));
	}

	Bogies(ActiveVehicle v, List<String> bones, RotatorFactory rotatorFactory) {
		this.bones = List.copyOf(bones);
		this.v = v;
		this.rotatorFactory = rotatorFactory;
	}

	/**
	 * Whether this car rests on two bogies of its current model. A model without both
	 * bogie bones, as a skin may be, places the car as a rigid one.
	 */
	public boolean isReady() {
		if (bones.size() != 2 || v == null) {
			return false;
		}
		ActiveModel model;
		try {
			model = v.getModel();
		} catch (RuntimeException notLoaded) {
			return false;
		}
		if (model == null) {
			return false;
		}
		if (model == readyFor) {
			return true;
		}
		if (model == missingFor) {
			return false;
		}
		release();
		double[] found;
		try {
			found = find(model);
		} catch (RuntimeException notLoaded) {
			// The model's bones are not there yet; place the car as a rigid one.
			return false;
		}
		if (found == null) {
			missingFor = model;
			return false;
		}
		for (String name : bones) {
			rotators.add(rotatorFactory.create(v, model.getBone(name).orElseThrow()));
		}
		offsets = found;
		readyFor = model;
		return true;
	}

	/**
	 * Follows the vehicle to a new model, as on a skin change. Bogies the new model also
	 * has keep their angles; otherwise the car is set up again for the new model.
	 */
	public void updateModel(ActiveModel model) {
		missingFor = null;
		double[] found = null;
		if (model != null && readyFor != null) {
			try {
				found = find(model);
			} catch (RuntimeException notLoaded) {
				found = null;
			}
		}
		if (found == null) {
			release();
			return;
		}
		for (BoneRotator rotator : rotators) {
			rotator.updateModel(model);
		}
		offsets = found;
		readyFor = model;
	}

	// Each bogie's offset along the car on this model, or null if it lacks a bogie bone
	// or has both bogies at one point.
	private double[] find(ActiveModel model) {
		double[] found = new double[2];
		for (int i = 0; i < 2; i++) {
			Optional<ModelBone> bone = model.getBone(bones.get(i));
			if (bone.isEmpty()) {
				return null;
			}
			found[i] = bone.get().getBlueprintBone().getRotatedGlobalPosition().z() * model.getScale().z();
		}
		if (Math.abs(found[0] - found[1]) < MIN_SPAN) {
			// Both bogies at one point along the car: nothing to rest the body between.
			return null;
		}
		return found;
	}

	// Drop the rotators of the last model, so they are not saved or turned for bones it no longer has.
	private void release() {
		if (!rotators.isEmpty()) {
			v.getAccessPanel().getRotators().removeAll(rotators);
			rotators.clear();
		}
		offsets = null;
		readyFor = null;
	}

	/** Along the car from the model's origin to each bogie, in blocks, +z facing +s. */
	public double[] offsets() {
		return offsets.clone();
	}

	/** Where the car's origin goes so each bogie sits on its rail, in {@link #offsets()} order. */
	public TrackPose bodyPose(TrackPose[] rails) {
		return bodyPose(rails[0], rails[1], offsets[0], offsets[1]);
	}

	/** Turns each bogie to the rail under it, relative to the body. */
	public void follow(TrackPose[] rails, TrackPose body) {
		for (int i = 0; i < rotators.size(); i++) {
			float[] turn = turn(body, rails[i]);
			rotators.get(i).rotateToTarget(turn[0], turn[1], 0f, 1f, true, true, false);
		}
	}

	static TrackPose bodyPose(TrackSpline spline, double s, double first, double second) {
		return bodyPose(rail(spline, s + first), rail(spline, s + second), first, second);
	}

	/** The body resting on two rails, each under the bogie at its offset along the car. */
	static TrackPose bodyPose(TrackPose firstRail, TrackPose secondRail, double first, double second) {
		if (Math.abs(first - second) < 1e-6) {
			// One point to rest on: keep the origin back along the rail from it.
			return shifted(firstRail, -first);
		}
		boolean firstAhead = first > second;
		TrackPose a = firstAhead ? firstRail : secondRail;
		TrackPose b = firstAhead ? secondRail : firstRail;
		double front = Math.max(first, second);
		double back = Math.min(first, second);
		Vector along = new Vector(a.x - b.x, a.y - b.y, a.z - b.z);
		double length = along.length();
		if (length < 1e-6) {
			return a;
		}
		along.multiply(1 / length);
		// The body is the straight line between the two bogie centres; the origin sits
		// where it would along that line.
		double middle = (front + back) / 2;
		double x = (a.x + b.x) / 2 - along.getX() * middle;
		double y = (a.y + b.y) / 2 - along.getY() * middle;
		double z = (a.z + b.z) / 2 - along.getZ() * middle;
		float yaw = (float) Math.toDegrees(Math.atan2(-along.getX(), along.getZ()));
		float pitch = (float) Math.toDegrees(Math.atan2(-along.getY(), Math.hypot(along.getX(), along.getZ())));
		return new TrackPose(x, y, z, yaw, pitch);
	}

	/** Yaw and pitch that turn a bogie on this body to the rail under it, as bone angles. */
	static float[] turn(TrackPose body, TrackPose rail) {
		float railYaw = rail.yaw;
		float railPitch = rail.pitch;
		// A bogie on a track laid the other way sees its rail heading backwards.
		if (Math.abs(ConvertedAngle.wrapDegrees(railYaw - body.yaw)) > 90) {
			railYaw += 180;
			railPitch = -railPitch;
		}
		float yaw = ConvertedAngle.wrapDegrees(-(railYaw - body.yaw));
		return new float[] {yaw, railPitch - body.pitch};
	}

	private static TrackPose shifted(TrackPose pose, double along) {
		double yaw = Math.toRadians(pose.yaw);
		double pitch = Math.toRadians(pose.pitch);
		double horizontal = Math.cos(pitch) * along;
		return new TrackPose(pose.x - Math.sin(yaw) * horizontal, pose.y - Math.sin(pitch) * along,
				pose.z + Math.cos(yaw) * horizontal, pose.yaw, pose.pitch);
	}

	// The rail at a distance along the track, carrying on straight past its ends.
	public static TrackPose rail(TrackSpline spline, double at) {
		double end = spline.length();
		double clamped = spline.isLoop() ? at : Math.max(0, Math.min(end, at));
		TrackPose pose = spline.sampleAt(clamped);
		double past = at - clamped;
		return Math.abs(past) < 1e-9 ? pose : shifted(pose, past);
	}
}
