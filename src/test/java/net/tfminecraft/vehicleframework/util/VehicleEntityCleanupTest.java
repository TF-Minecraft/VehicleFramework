package net.tfminecraft.vehicleframework.util;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

class VehicleEntityCleanupTest {
    @Test
    void removesModelBeforeBaseEntity() {
        List<String> calls = new ArrayList<>();
        VehicleEntityCleanup.remove(entity(calls), () -> calls.add("model"));
        assertEquals(List.of("model", "entity"), calls);
    }

    @Test
    void modelFailureStillRemovesBaseEntityAndIsReported() {
        List<String> calls = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("broken mount cleanup");
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> VehicleEntityCleanup.remove(entity(calls), () -> { throw failure; })));
        assertEquals(List.of("entity"), calls);
    }

    @Test
    void partialSpawnWithoutAModelStillRemovesBaseEntity() {
        List<String> calls = new ArrayList<>();
        VehicleEntityCleanup.remove(entity(calls), () -> {});
        assertEquals(List.of("entity"), calls);
    }

    private Entity entity(List<String> calls) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("remove")) { calls.add("entity"); return null; }
                    throw new AssertionError(method);
                });
    }
}
