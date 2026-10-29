package net.tfminecraft.vehicleframework.bones;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.joml.AxisAngle4d;
import org.joml.Quaternionf;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.bone.SimpleManualAnimator;

import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class BoneRotator {
	private String id;
	private ModelBone bone;
	private Entity e;
	private SimpleManualAnimator animator;
	
	private double smoothX = 0f;
	private double smoothY = 0f;
	private double smoothZ = 0f;

	private double yawOffset = 0f;
	private float driveYaw = 0f;

	private RotationLimits limits;

	// The last rotateToTarget call that left the bone exactly as it was. The turn depends
	// only on its arguments and the bone's rotation, so repeating it from that same
	// rotation changes nothing either. A train standing on its track repeats it every tick.
	private boolean settled;
	private final Quaternionf settledRotation = new Quaternionf();
	private int settledYaw;
	private int settledPitch;
	private int settledRoll;
	private int settledSlerp;
	private int settledAxes;
	private boolean settledResult;

	public BoneRotator(ActiveVehicle v, Entity e, ModelBone bone, RotationLimits limits) {
		this.e = e;
		id = bone.getBoneId();
		this.bone = bone;
		animator = new SimpleManualAnimator(bone);
		bone.setManualAnimator(animator);
		captureRestYaw(new ConvertedAngle(animator.getRotation()));
		v.getAccessPanel().addRotator(this);
		this.limits = limits;
	}
	
	public void updateModel(ActiveModel m) {
		bone = m.getBone(bone.getBoneId()).get();
		id = bone.getBoneId();

		Quaternionf temp = new Quaternionf(animator.getRotation());
		ConvertedAngle a = new ConvertedAngle(temp);
		animator = new SimpleManualAnimator(bone);
		rotateToTarget(a.getYaw(), a.getPitch(), a.getRoll(), 1f, true, true, true);
		bone.setManualAnimator(animator);
	}

	private void captureRestYaw(ConvertedAngle angles) {
		yawOffset = angles.getYaw();
		driveYaw = angles.getYaw();
	}

	public float getDriveYaw() {
		return ConvertedAngle.wrapDegrees(driveYaw);
	}

	public String getId() {
		return id;
	}

	public ModelBone getBone() {
		return bone;
	}

	public SimpleManualAnimator getAnimator() {
		return animator;
	}

	public void rawSet(float x, float y, float z, float w) {
		animator.getRotation().set(x, y, z, w);
		syncDriveYaw();
		//animator.animate(bone);
	}

	// Turning steps from driveYaw, so a stale value snaps the bone back to it.
	private void syncDriveYaw() {
		driveYaw = new ConvertedAngle(animator.getRotation()).getYaw();
	}
	
	public void rotateEntity(float yaw, float pitch) {
		Location loc = e.getLocation().clone();
		loc.setYaw(loc.getYaw()+yaw);
		loc.setPitch(loc.getPitch()+pitch);
		e.teleport(loc);
	}
	
	public void rotateSmoothed(double targetX, double targetY, double targetZ) {
		targetX /= 10;
	    targetY /= 10;
	    targetZ /= 10;
		
	    double delta = 0.05; // Smoothing factor, adjust as necessary
		
		smoothX += (targetX - smoothX) * delta;
		smoothY += (targetY - smoothY) * delta;
		smoothZ += (targetZ - smoothZ) * delta;
		
		rotateNoAdd(smoothX, smoothY, smoothZ);
	}

	public void resetSmoothing() {
		smoothX = 0;
		smoothY = 0;
		smoothZ = 0;
	}
	
	public void rotate(double targetX, double targetY, double targetZ) {
	    targetX /= 10;
	    targetY /= 10;
	    targetZ /= 10;
		rotateNoAdd(targetX, targetY, targetZ);
	}
	
	public void reset() {
		Quaternionf q = new Quaternionf();
		animator.getRotation().set(q.x, q.y, q.z, q.w);
		syncDriveYaw();
	    //animator.animate(bone);
	}
	
	public void setRotation(double yaw, double pitch, double roll, boolean shouldYaw, boolean shouldPitch, boolean shouldRoll) {
		rotateToTarget((float) yaw, (float) pitch, (float) roll, 1f, shouldYaw, shouldPitch, shouldRoll);
	}
	public boolean rotateToTarget(
		    float targetYawDeg,
		    float targetPitchDeg,
		    float targetRollDeg,
		    float slerpFactor,
		    boolean shouldYaw,
		    boolean shouldPitch,
		    boolean shouldRoll
		) {
		int axes = (shouldYaw ? 1 : 0) | (shouldPitch ? 2 : 0) | (shouldRoll ? 4 : 0);
		if (repeatsSettled(targetYawDeg, targetPitchDeg, targetRollDeg, slerpFactor, axes)) {
			return settledResult;
		}
		Quaternionf before = new Quaternionf(animator.getRotation());
		boolean result = turnToTarget(targetYawDeg, targetPitchDeg, targetRollDeg, slerpFactor,
				shouldYaw, shouldPitch, shouldRoll);
		settled = animator.getRotation().equals(before);
		if (settled) {
			settledRotation.set(before);
			settledYaw = Float.floatToIntBits(targetYawDeg);
			settledPitch = Float.floatToIntBits(targetPitchDeg);
			settledRoll = Float.floatToIntBits(targetRollDeg);
			settledSlerp = Float.floatToIntBits(slerpFactor);
			settledAxes = axes;
			settledResult = result;
		}
		return result;
	}

	// Whether this call is the settled one again, from the rotation it left. Turning to a
	// yaw also sets driveYaw, so that must still hold the target too.
	private boolean repeatsSettled(float yaw, float pitch, float roll, float slerp, int axes) {
		return settled
				&& settledAxes == axes
				&& settledYaw == Float.floatToIntBits(yaw)
				&& settledPitch == Float.floatToIntBits(pitch)
				&& settledRoll == Float.floatToIntBits(roll)
				&& settledSlerp == Float.floatToIntBits(slerp)
				&& ((axes & 1) == 0 || Float.floatToIntBits(driveYaw) == settledYaw)
				&& animator.getRotation().equals(settledRotation);
	}

	//Voodoo
	private boolean turnToTarget(
		    float targetYawDeg,
		    float targetPitchDeg,
		    float targetRollDeg,
		    float slerpFactor,
		    boolean shouldYaw,
		    boolean shouldPitch,
		    boolean shouldRoll
		) {
		    // 1. Get the current quaternion
		    Quaternionf currentQ = new Quaternionf(animator.getRotation());

		    // 2. Extract current angles in degrees via your existing ConvertedAngle
		    ConvertedAngle currentAngles = new ConvertedAngle(currentQ);
		    float currentPitch = currentAngles.getPitch();
		    float currentYaw   = currentAngles.getYaw();
		    float currentRoll  = currentAngles.getRoll();

		    // 3. Decide which angles to use (if a flag is false, keep the current axis)
		    float finalPitch = shouldPitch ? targetPitchDeg : currentPitch;
		    float finalYaw   = shouldYaw   ? targetYawDeg   : currentYaw;
		    float finalRoll  = shouldRoll  ? targetRollDeg  : currentRoll;
		    if (shouldYaw) {
		        driveYaw = targetYawDeg;
		    }

		    // 4. Build the target quaternion from these "final" angles
		    //    NOTE: rotateXYZ means (pitch -> yaw -> roll) in X->Y->Z intrinsic order
		    Quaternionf targetQ = new Quaternionf().rotateYXZ(
		    	(float) Math.toRadians(finalYaw),
		        (float) Math.toRadians(finalPitch),
		        (float) Math.toRadians(finalRoll)
		    );

		    // 5. SLERP from currentQ to targetQ by slerpFactor
		    //    - If slerpFactor = 1.0f, you'll snap instantly
		    //    - If slerpFactor < 1.0f, you'll smoothly blend
		    
		    if (
		    		Math.abs(finalYaw-currentYaw) < 0.1 &&
		    		Math.abs(finalPitch-currentPitch) < 0.1 &&
		    		Math.abs(finalRoll-currentRoll) < 0.1
		    		) {
		        // Essentially the same orientation; snap to target & return true
		        animator.getRotation().set(targetQ.x, targetQ.y, targetQ.z, targetQ.w);
		        //animator.animate(bone);
		        return true;
		    }
		    
		    currentQ.slerp(targetQ, slerpFactor);

		    // 6. Apply the new slerped orientation to the animator
		    animator.getRotation().set(currentQ.x, currentQ.y, currentQ.z, currentQ.w);
		    //animator.animate(bone);
		    return false;
		}

	private void rotateNoAdd(double x, double y, double z) {
	    // Get the current rotation as a quaternion
	    Quaternionf currentRotation = animator.getRotation();
		
	    // Apply the incremental rotation only to the axes that need to change
	    if (x != 0.0 || y != 0.0 || z != 0.0) {
	        // Create a new incremental quaternion based on the given Euler angles
	        Quaternionf incrementalRotation = new Quaternionf()
	            .rotateXYZ((float) x, (float) y, (float) z);
			Quaternionf check = new Quaternionf(currentRotation).mul(incrementalRotation);

			ConvertedAngle currentAngles = new ConvertedAngle(check);
			//Bukkit.getPlayerExact("drefvelin").sendMessage(currentAngles.getYaw()+"; "+currentAngles.getPitch()+"; "+currentAngles.getRoll() + " offset "+yawOffset);
			if(limits.withinAll((float) (currentAngles.getYaw()-yawOffset), currentAngles.getPitch(), currentAngles.getRoll())) {
				// Combine the current rotation with the incremental rotation
				currentRotation.mul(incrementalRotation);
				
				// Normalize to avoid floating-point precision errors
				currentRotation.normalize();

				// Apply the new rotation back to the animator
				animator.getRotation().set(currentRotation.x, currentRotation.y, currentRotation.z, currentRotation.w);
				// Local pitch while rolled turns the heading too (banked turns), so re-read it.
				syncDriveYaw();
			}
	    }
	    // Trigger the animation (update the model with the new rotation)
	    //animator.animate(bone);
	}
	
	public void normalize(boolean nx, boolean ny, boolean nz) {
		rotateToTarget(0f, 0f, 0f, 1f, ny, nx, nz);
	}
	
	public AxisAngle4d getAngles() {
		return animator.getRotation().get(new AxisAngle4d());
	}
	
	public ConvertedAngle getConvertedAngles() {
		return new ConvertedAngle(animator.getRotation());
	}
}
