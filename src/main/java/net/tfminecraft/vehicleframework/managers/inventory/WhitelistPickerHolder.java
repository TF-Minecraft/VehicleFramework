package net.tfminecraft.vehicleframework.managers.inventory;

import java.util.List;
import java.util.Optional;

import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/** The nearby-player picker, remembering each head's character session and the name shown. */
public class WhitelistPickerHolder extends VFInventoryHolder {

    /** A player offered by the picker. */
    public record Candidate(CharacterSession session, String name) {
    }

    private final List<Candidate> candidates;

    public WhitelistPickerHolder(ActiveVehicle vehicle, List<Candidate> candidates) {
        super(vehicle.getUUID(), VFGUI.WHITELIST_PICKER, vehicle);
        this.candidates = List.copyOf(candidates);
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    /** The candidate shown in a slot. */
    public Optional<Candidate> candidate(int slot) {
        return slot >= 0 && slot < candidates.size() ? Optional.of(candidates.get(slot)) : Optional.empty();
    }
}
