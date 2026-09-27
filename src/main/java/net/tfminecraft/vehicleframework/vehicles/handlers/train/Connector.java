package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import org.bukkit.util.Vector;
import org.joml.Vector3fc;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.tracks.TrackSplineMotion;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class Connector {
	private ActiveVehicle v;
	private String boneString;
	private ModelBone bone;
	
	public Connector(String s) {
		boneString = s;
	}
	
	public Connector(ActiveVehicle vehicle, Connector another) {
		v = vehicle;
		bone = v.getModel().getBone(another.getBoneString()).get();
	}
	
	public void updateModel(ActiveModel m) {
		bone = m.getBone(bone.getBoneId()).get();
	}

	public String getBoneString() {
		return boneString;
	}

	public ModelBone getBone() {
		return bone;
	}

	public Vector getOffset() {
		return bone.getLocation().toVector().subtract(v.getEntity().getLocation().toVector());
	}

	/** Longitudinal rest distance; coupler height is not distance along the rails. */
	public double getTrackReach() {
		return Math.abs(bone.getBlueprintBone().getRotatedGlobalPosition().z() * v.getModel().getScale().z());
	}

	/** Predict the anchor from the planned pose, without waiting for a model animation tick. */
	public Vector positionAt(TrackPose pose) {
		return offsetAt(pose).add(new Vector(pose.x, pose.y, pose.z));
	}

	/**
	 * Places a car so this coupler meets {@code target} seen from above. The car keeps the
	 * height of its own rail: where the grade changes between two cars, their couplers
	 * sit at different heights, and meeting exactly would lift or sink the car off its track.
	 */
	public TrackPose coupledPose(TrackPose nominal, Vector target) {
		double dx = target.getX() - nominal.x;
		double dz = target.getZ() - nominal.z;
		float yaw = dx * dx + dz * dz > 1e-8
				? (float) Math.toDegrees(Math.atan2(-dx, dz)) : nominal.yaw;
		TrackPose facing = new TrackPose(nominal.x, nominal.y, nominal.z, yaw, nominal.pitch);
		Vector offset = offsetAt(facing);
		return new TrackPose(target.getX() - offset.getX(), nominal.y,
				target.getZ() - offset.getZ(), yaw, nominal.pitch);
	}

	private Vector offsetAt(TrackPose pose) {
		Vector pivot = new Vector();
		BoneRotator rotator = v.getBehaviourHandler() == null ? null : v.getBehaviourHandler().getRotator();
		if (rotator != null && rotator.getBone() != null) {
			pivot = vector(rotator.getBone().getBlueprintBone().getRotatedGlobalPosition());
		}
		float entityYaw = v.getEntity().getLocation().getYaw();
		Vector offset = vector(bone.getBlueprintBone().getRotatedGlobalPosition()).subtract(pivot);
		// Match the manual body rotation, then model scale and base-entity rotation.
		offset.rotateAroundX(Math.toRadians(TrackSplineMotion.bonePitch(pose.pitch)))
				.rotateAroundY(Math.toRadians(TrackSplineMotion.boneYaw(pose.yaw, entityYaw)))
				.add(pivot).multiply(vector(v.getModel().getScale()))
				.rotateAroundY(Math.toRadians(-entityYaw));
		return offset;
	}

	private static Vector vector(Vector3fc value) {
		return new Vector(value.x(), value.y(), value.z());
	}
	
	
}
