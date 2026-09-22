package net.tfminecraft.VehicleFramework.Util;

import org.bukkit.entity.Entity;
import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ModeledEntity;

/** Shared teardown for normal removal and partially constructed vehicles. */
public final class VehicleEntityCleanup {
    private VehicleEntityCleanup() {}

    public static void remove(Entity entity) {
        remove(entity, () -> {
            ModeledEntity modeled = ModelEngineAPI.getModeledEntity(entity);
            if (modeled != null) {
                // Unregister and destroy now, including during plugin shutdown.
                ModelEngineAPI.getAPI().getModelUpdaters().forceRemoveModeledEntity(modeled);
            }
        });
    }

    static void remove(Entity entity, Runnable removeModel) {
        try {
            removeModel.run();
        } finally {
            // Model callbacks must never prevent removal of the backing entity.
            entity.remove();
        }
    }
}
