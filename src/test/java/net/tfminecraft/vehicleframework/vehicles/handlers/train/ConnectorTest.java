package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.tracks.TrackPose;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.BehaviourHandler;

class ConnectorTest {
    // ModelEngine converts Blockbench pixels to (-x, y, -z) / 16.
    private static final Vector3f LOCO_BACK = new Vector3f(0, 15f / 16, -57f / 16);
    private static final Vector3f LOCO_PIVOT = new Vector3f(0, 5f / 16, -1f / 16);
    private static final Vector3f PASSENGER_FRONT = new Vector3f(0, 15f / 16, 78f / 16);

    @Test
    void spacingUsesLongitudinalModelSizeWithoutCouplerHeightOrAnimationLag() {
        Connector back = connector(LOCO_BACK, LOCO_PIVOT, 137, new Vector3f(1));
        Connector front = connector(PASSENGER_FRONT, new Vector3f(), -85, new Vector3f(1));
        assertEquals(135d / 16, back.getTrackReach() + front.getTrackReach(), 1e-8);
        // No live bone locations are provided: even the first placement has the right dimensions.
        Vector3f scale = new Vector3f(2);
        Connector scaled = connector(PASSENGER_FRONT, new Vector3f(), 0, scale);
        assertEquals(156d / 16, scaled.getTrackReach(), 1e-8);
        scale.set(1);
        assertEquals(78d / 16, scaled.getTrackReach(), 1e-8);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 0, 1", "45, 12, 73, 1", "179, -10, -90, 1.5", "-170, 0, 135, 2"})
    void passengerFrontMeetsLocomotiveBackWithRealPivotsAndBaseRotation(
            float yaw, float pitch, float entityYaw, float scale) {
        Vector3f size = new Vector3f(scale, scale * 0.8f, scale * 1.2f);
        Connector back = connector(LOCO_BACK, LOCO_PIVOT, entityYaw, size);
        Connector front = connector(PASSENGER_FRONT, new Vector3f(), -entityYaw, size);
        TrackPose parent = new TrackPose(100, 64, 200, yaw, pitch);
        Vector expectedBack = renderedAnchor(parent, LOCO_BACK, LOCO_PIVOT, entityYaw, size);
        assertEquals(0, expectedBack.distance(back.positionAt(parent)), 1e-5);

        TrackPose nominal = new TrackPose(96, 64, 194, yaw - 25, pitch);
        TrackPose coupled = front.coupledPose(nominal, back.positionAt(parent));
        Vector renderedFront = renderedAnchor(coupled, PASSENGER_FRONT, new Vector3f(), -entityYaw, size);
        // Couplers meet seen from above; the car stays at its own rail height.
        assertEquals(0, Math.hypot(expectedBack.getX() - renderedFront.getX(),
                expectedBack.getZ() - renderedFront.getZ()), 1e-5);
        assertEquals(nominal.y, coupled.y, 1e-9);
        assertEquals(pitch, coupled.pitch);
        assertEquals(new Vector3f(0, 15f / 16, 78f / 16), PASSENGER_FRONT);
    }

    @Test
    void carOnLevelTrackBehindLocomotiveOnGradeStaysOnItsRail() {
        // Lab case: locomotive 11 degrees nose down, passenger car still on the level.
        Connector back = connector(LOCO_BACK, LOCO_PIVOT, 88, new Vector3f(1));
        Connector front = connector(PASSENGER_FRONT, new Vector3f(), 88, new Vector3f(1));
        TrackPose loco = new TrackPose(5449.57, 430.384, 3462.5, 88.4f, 11.1f);
        TrackPose nominal = new TrackPose(5457.82, 432.0, 3462.49, 88.4f, 0f);
        TrackPose coupled = front.coupledPose(nominal, back.positionAt(loco));
        assertEquals(432.0, coupled.y, 1e-9);
        assertEquals(nominal.x, coupled.x, 0.5);
        assertEquals(nominal.z, coupled.z, 0.5);
    }

    private static Vector renderedAnchor(TrackPose pose, Vector3f point, Vector3f pivot,
            float entityYaw, Vector3f scale) {
        Vector3d result = new Matrix4d().translation(pose.x, pose.y, pose.z)
                .rotateY(Math.toRadians(-entityYaw)).scale(scale.x, scale.y, scale.z)
                .translate(pivot.x, pivot.y, pivot.z)
                .rotateY(Math.toRadians(entityYaw - pose.yaw)).rotateX(Math.toRadians(pose.pitch))
                .translate(-pivot.x, -pivot.y, -pivot.z)
                .transformPosition(new Vector3d(point));
        return new Vector(result.x, result.y, result.z);
    }

    private static Connector connector(Vector3f point, Vector3f pivot, float entityYaw, Vector3f scale) {
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        Entity entity = mock(Entity.class);
        when(entity.getLocation()).thenReturn(new Location(null, 300, 70, 400, entityYaw, 0));
        when(vehicle.getEntity()).thenReturn(entity);
        ActiveModel model = mock(ActiveModel.class);
        when(model.getScale()).thenReturn(scale);
        when(vehicle.getModel()).thenReturn(model);
        ModelBone connectorBone = bone(point);
        when(model.getBone("connector")).thenReturn(Optional.of(connectorBone));
        BehaviourHandler behaviour = mock(BehaviourHandler.class);
        BoneRotator rotator = mock(BoneRotator.class);
        ModelBone bodyBone = bone(pivot);
        when(rotator.getBone()).thenReturn(bodyBone);
        when(behaviour.getRotator()).thenReturn(rotator);
        when(vehicle.getBehaviourHandler()).thenReturn(behaviour);
        return new Connector(vehicle, new Connector("connector"));
    }

    private static ModelBone bone(Vector3f position) {
        BlueprintBone blueprint = new BlueprintBone();
        blueprint.setRotatedGlobalPosition(position);
        ModelBone bone = mock(ModelBone.class);
        when(bone.getBlueprintBone()).thenReturn(blueprint);
        return bone;
    }
}
