package net.tfminecraft.vehicleframework.vehicles.handlers.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.BlueprintAnimation.LoopMode;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.utils.config.ConfigCache;

import net.tfminecraft.vehicleframework.enums.Animation;
import net.tfminecraft.vehicleframework.enums.Direction;

class WheelAnimationPhaseTest {
    private Field apiField;
    private Object previousApi;
    private AnimationHandler animations;
    private final Map<String, IAnimationProperty> playing = new HashMap<>();
    private final Map<String, BlueprintAnimation> blueprints = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ModelEngineAPI plugin = mock(ModelEngineAPI.class);
        when(plugin.namespace()).thenReturn("modelengine");
        Field cache = ModelEngineAPI.class.getDeclaredField("configCache");
        cache.setAccessible(true);
        cache.set(plugin, mock(ConfigCache.class));
        apiField = ModelEngineAPI.class.getDeclaredField("API");
        apiField.setAccessible(true);
        previousApi = apiField.get(null);
        apiField.set(null, plugin);
        ActiveModel model = mock(ActiveModel.class);
        ModelBlueprint blueprint = mock(ModelBlueprint.class);
        when(model.getBlueprint()).thenReturn(blueprint);
        when(blueprint.getAnimations()).thenReturn(blueprints);
        for (String name : List.of("forward", "backward", "idle")) {
            BlueprintAnimation animation = new BlueprintAnimation(blueprint, name);
            animation.setLength(1);
            animation.setLoopMode(LoopMode.LOOP);
            blueprints.put(name, animation);
        }
        com.ticxo.modelengine.api.animation.handler.AnimationHandler engine =
                mock(com.ticxo.modelengine.api.animation.handler.AnimationHandler.class);
        when(model.getAnimationHandler()).thenReturn(engine);
        when(engine.getAnimation(anyString())).thenAnswer(call -> playing.get(call.getArgument(0)));
        when(engine.isPlayingAnimation(anyString())).thenAnswer(call -> playing.containsKey(call.getArgument(0)));
        when(engine.playAnimation(any(IAnimationProperty.class), anyBoolean())).thenAnswer(call -> {
            IAnimationProperty property = call.getArgument(0);
            playing.put(property.getName(), property);
            return true;
        });
        doAnswer(call -> { playing.remove(call.getArgument(0)); return null; })
                .when(engine).forceStopAnimation(anyString());
        doAnswer(call -> { playing.remove(call.getArgument(0)); return null; })
                .when(engine).stopAnimation(anyString());
        YamlConfiguration config = new YamlConfiguration();
        config.set("forward", List.of("forward"));
        config.set("backward", List.of("backward"));
        config.set("default", List.of("idle"));
        animations = new AnimationHandler(model, new AnimationHandler(config));
    }

    @AfterEach
    void closeApi() throws Exception { if (apiField != null) apiField.set(null, previousApi); }

    private void tick(int count) {
        for (int i = 0; i < count; i++) {
            for (IAnimationProperty p : playing.values()) assertTrue(p.update());
        }
    }

    private double angle() {
        IAnimationProperty p = playing.get("forward");
        if (p != null) return -355 * Math.max(0, p.getTime()) / p.getBlueprintAnimation().getLength();
        p = playing.get("backward");
        return -355 * (1 - Math.max(0, p.getTime()) / p.getBlueprintAnimation().getLength());
    }

    @Test
    void reversingKeepsAngleAndThenTurnsTheOtherWay() {
        animations.animateWheels(Direction.FORWARD, 1);
        tick(8);
        double before = angle();
        animations.animateWheels(Direction.BACKWARD, 1);
        assertFalse(playing.containsKey("forward"));
        assertEquals(before, angle(), 1e-8);
        tick(1);
        assertTrue(angle() > before);
        before = angle();
        animations.animateWheels(Direction.FORWARD, 1);
        assertFalse(playing.containsKey("backward"));
        assertEquals(before, angle(), 1e-8);
        tick(1);
        assertTrue(angle() < before);
    }

    @Test
    void stopHoldsPoseAndResumeKeepsSamePlayback() {
        animations.animateWheels(Direction.FORWARD, 1);
        tick(8);
        IAnimationProperty property = playing.get("forward");
        double before = angle();
        animations.animateWheels(Direction.STILL, 0);
        tick(20);
        assertSame(property, playing.get("forward"));
        assertEquals(before, angle(), 1e-8);
        animations.animateWheels(Direction.FORWARD, 2);
        assertSame(property, playing.get("forward"));
        tick(1);
        assertEquals(before - 35.5, angle(), 1e-8);
    }

    @Test
    void stoppingAtLoopEndpointKeepsPoseAndCanReverseOrResume() {
        animations.animateWheels(Direction.FORWARD, 1.25);
        tick(17);
        assertEquals(1, playing.get("forward").getTime(), 1e-8);
        double before = angle();
        animations.animateWheels(Direction.STILL, 0);
        tick(20);
        assertEquals(before, angle(), 1e-8);
        animations.animateWheels(Direction.BACKWARD, 1.25);
        assertEquals(before, angle(), 1e-8);
        tick(1);
        assertTrue(angle() > before);
        animations.animateWheels(Direction.STILL, 0);
        tick(2);
        animations.animateWheels(Direction.BACKWARD, 1.25);
        tick(30);
        assertEquals(LoopMode.LOOP, playing.get("backward").getLoopMode());
        assertTrue(playing.get("backward").getTime() < 1);
    }

    @Test
    void reverseAfterWaitingKeepsStoppedWheelAngle() {
        animations.animateWheels(Direction.BACKWARD, 1);
        tick(9);
        double before = angle();
        animations.animateWheels(Direction.STILL, 0);
        tick(20);
        assertEquals(before, angle(), 1e-8);
        animations.animateWheels(Direction.FORWARD, 1);
        assertEquals(before, angle(), 1e-8);
    }

    @Test
    void loopWrapsAndDifferentDurationClipsPreserveAngle() {
        blueprints.get("backward").setLength(2);
        animations.animateWheels(Direction.FORWARD, 1);
        tick(28);
        double before = angle();
        animations.animateWheels(Direction.BACKWARD, 1);
        assertEquals(before, angle(), 1e-8);
    }

    @Test
    void zeroSpeedDoesNotStartAnAnimationAndIdleKeepsPlaying() {
        animations.animate(Animation.DEFAULT);
        IAnimationProperty idle = playing.get("idle");
        animations.animateWheels(Direction.STILL, 0);
        assertEquals(1, playing.size());
        animations.animateWheels(Direction.FORWARD, 1);
        tick(5);
        animations.animateWheels(Direction.STILL, 0);
        assertSame(idle, playing.get("idle"));
        assertEquals(1, idle.getSpeed(), 1e-9);
    }
}
