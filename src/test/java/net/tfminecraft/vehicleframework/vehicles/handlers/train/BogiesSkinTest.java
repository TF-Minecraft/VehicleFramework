package net.tfminecraft.vehicleframework.vehicles.handlers.train;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;

/** A locomotive whose skins differ: one rests on bogies, the other is built without them. */
class BogiesSkinTest {
    private static final List<String> BONES = List.of("bogey", "bogey2");

    private final AccessPanel panel = new AccessPanel();
    private final ActiveVehicle loco = mock(ActiveVehicle.class);
    private final List<BoneRotator> made = new ArrayList<>();
    private final Bogies bogies = new Bogies(loco, BONES, (v, bone) -> {
        BoneRotator rotator = mock(BoneRotator.class);
        made.add(rotator);
        v.getAccessPanel().addRotator(rotator);
        return rotator;
    });

    BogiesSkinTest() {
        when(loco.getAccessPanel()).thenReturn(panel);
    }

    @Test
    void aSkinWithoutBogieBonesIsPlacedRigidAndNotLookedUpAgain() {
        ActiveModel plain = model(Map.of());
        when(loco.getModel()).thenReturn(plain);
        assertFalse(bogies.isReady());
        assertFalse(bogies.isReady());
        verify(plain, times(1)).getBone(anyString());
        assertTrue(made.isEmpty());
    }

    @Test
    void changingToASkinWithoutBogiesDropsTheBogieRotators() {
        ActiveModel scotsman = model(Map.of("bogey", -70f / 16, "bogey2", 45f / 16));
        when(loco.getModel()).thenReturn(scotsman);
        assertTrue(bogies.isReady());
        assertEquals(2, panel.getRotators().size());

        ActiveModel plain = model(Map.of());
        when(loco.getModel()).thenReturn(plain);
        bogies.updateModel(plain);
        assertFalse(bogies.isReady());
        assertTrue(panel.getRotators().isEmpty());
    }

    @Test
    void changingToASkinWithBogiesRestsTheCarOnThem() {
        ActiveModel plain = model(Map.of());
        when(loco.getModel()).thenReturn(plain);
        assertFalse(bogies.isReady());

        ActiveModel scotsman = model(Map.of("bogey", -70f / 16, "bogey2", 45f / 16));
        when(loco.getModel()).thenReturn(scotsman);
        bogies.updateModel(scotsman);
        assertTrue(bogies.isReady());
        assertArrayEquals(new double[] {-70d / 16, 45d / 16}, bogies.offsets(), 1e-9);
        assertEquals(2, made.size());
    }

    @Test
    void skinsWithTheSameBogiesKeepTheirRotatorsAndTakeTheNewOffsets() {
        ActiveModel closed = model(Map.of("bogey", -34f / 16, "bogey2", 34f / 16));
        when(loco.getModel()).thenReturn(closed);
        assertTrue(bogies.isReady());

        ActiveModel open = model(Map.of("bogey", -36f / 16, "bogey2", 36f / 16));
        when(loco.getModel()).thenReturn(open);
        bogies.updateModel(open);
        assertTrue(bogies.isReady());
        assertArrayEquals(new double[] {-36d / 16, 36d / 16}, bogies.offsets(), 1e-9);
        assertEquals(2, made.size());
        for (BoneRotator rotator : made) {
            verify(rotator).updateModel(open);
        }
        assertEquals(2, panel.getRotators().size());
    }

    @Test
    void bogiesAtOnePointLeaveTheCarRigid() {
        ActiveModel stacked = model(Map.of("bogey", 0f, "bogey2", 0.1f));
        when(loco.getModel()).thenReturn(stacked);
        assertFalse(bogies.isReady());
        assertTrue(made.isEmpty());
    }

    private static ActiveModel model(Map<String, Float> bogieZ) {
        ActiveModel model = mock(ActiveModel.class);
        when(model.getScale()).thenReturn(new Vector3f(1));
        when(model.getBone(anyString())).thenReturn(Optional.empty());
        bogieZ.forEach((name, z) -> {
            BlueprintBone blueprint = mock(BlueprintBone.class);
            when(blueprint.getRotatedGlobalPosition()).thenReturn(new Vector3f(0, 6f / 16, z));
            ModelBone bone = mock(ModelBone.class);
            when(bone.getBlueprintBone()).thenReturn(blueprint);
            when(bone.getBoneId()).thenReturn(name);
            when(model.getBone(name)).thenReturn(Optional.of(bone));
        });
        return model;
    }
}
