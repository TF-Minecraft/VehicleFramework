package net.tfminecraft.vehicleframework.data;

import net.tfminecraft.vehicleframework.api.VehicleOwners;

public final class StoredVehicleMeta {
    private final String uuid;
    private final String name;
    private final String typeId;
    private final String owner;

    public StoredVehicleMeta(String uuid, String name, String typeId, String owner) {
        this.uuid = uuid;
        this.name = name;
        this.typeId = typeId;
        this.owner = owner;
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getTypeId() {
        return typeId;
    }

    public String getOwner() {
        return owner;
    }

    /** A character ({@code char_<uuid>}) or legacy account ({@code player_<name>}) owner. */
    public static boolean isPlayerOwner(String owner) {
        return VehicleOwners.isPersonalOwner(owner);
    }
}
