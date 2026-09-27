package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import java.util.ArrayList;
import java.util.List;

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
 * rotator must pivot at the model's origin.
 */
public final class Bogies {
	private final List<String> bones;
	private ActiveVehicle v;
	private final List<BoneRotator> rotators = new ArrayList<>();
	// Along the car from the model's origin, in blocks; +z faces +s.
	private double[] offsets;

	public Bogies(List<String> bones) {
		this.bones = List.copyOf(bones);
	}

	public Bogies(ActiveVehicle v, Bogies another) {
		this.bones = another.bones;
		this.v = v;
	}

	/** Whether this car has two bogies and its model is loaded. */
	public boolean isReady() {
		if (bones.size() != 2 || v == null) {
			return false;
		}
		if (offsets != null) {
			return true;
		}
		try {
			ActiveModel model = v.getModel();
			double[] found = new double[2];
			List<BoneRotator> made = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				ModelBone bone = model.getBone(bones.get(i)).orElseThrow();
				found[i] = bone.getBlueprintBone().getRotatedGlobalPosition().z() * model.getScale().z();
				made.add(new BoneRotator(v, v.getEntity(), bone, new RotationLimits()));
			}
			rotators.addAll(made);
			offsets = found;
			return true;
		} catch (RuntimeException notLoaded) {
			// The model or its bones are not there yet; place the car as a rigid one.
			return false;
		}
	}

	public void updateModel(ActiveModel model) {
		for (BoneRotator rotator : rotators) {
			rotator.updateModel(model);
		}
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
			return firstRail;
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

	// The rail at a distance along the track, carrying on straight past its ends.
	public static TrackPose rail(TrackSpline spline, double at) {
		double end = spline.length();
		double clamped = spline.isLoop() ? at : Math.max(0, Math.min(end, at));
		TrackPose pose = spline.sampleAt(clamped);
		double past = at - clamped;
		if (Math.abs(past) < 1e-9) {
			return pose;
		}
		double yaw = Math.toRadians(pose.yaw);
		double pitch = Math.toRadians(pose.pitch);
		double horizontal = Math.cos(pitch) * past;
		return new TrackPose(pose.x - Math.sin(yaw) * horizontal, pose.y - Math.sin(pitch) * past,
				pose.z + Math.cos(yaw) * horizontal, pose.yaw, pose.pitch);
	}
}
