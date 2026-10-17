package net.tfminecraft.vehicleframework.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.tfminecraft.vehicleframework.api.AccessGroup;
import net.tfminecraft.vehicleframework.cache.Cache;

public class OwnerData {
    private String owner;
    private List<String> whiteList = new ArrayList<>();
    private boolean whiteListed = false;
    private boolean ticketsEnabled = false;
    private String ticketId;
    private EnumSet<AccessGroup> accessGroups = EnumSet.noneOf(AccessGroup.class);

    public OwnerData() {
        owner = "none";
        whiteListed = Cache.allowWhitelist && Cache.whitelistedByDefault;
    }

    /** A detached copy of every field. */
    public OwnerData copy() {
        OwnerData copy = new OwnerData();
        copy.restore(this);
        return copy;
    }

    /** Puts back every field of an earlier {@link #copy()}. */
    public void restore(OwnerData from) {
        this.owner = from.owner;
        this.whiteList = new ArrayList<>(from.whiteList);
        this.whiteListed = from.whiteListed;
        this.ticketsEnabled = from.ticketsEnabled;
        this.ticketId = from.ticketId;
        this.accessGroups = EnumSet.copyOf(from.accessGroups);
    }

    // Owner
    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getOwner() {
        return owner;
    }

    // Whitelist
    public void setWhiteList(List<String> whiteList) {
        this.whiteList = whiteList;
    }

    public List<String> getWhiteList() {
        return whiteList;
    }

    // Add/remove helpers (optional but useful)
    public void addToWhiteList(String player) {
        this.whiteList.add(player);
    }

    public void removeFromWhiteList(String player) {
        this.whiteList.remove(player);
    }

    // Whitelisted toggle
    public void setWhiteListed(boolean whiteListed) {
        this.whiteListed = whiteListed;
    }

    public boolean isWhiteListed() {
        return whiteListed;
    }

    // Group switches
    public Set<AccessGroup> getAccessGroups() {
        return Set.copyOf(accessGroups);
    }

    public boolean hasAccessGroup(AccessGroup group) {
        return accessGroups.contains(group);
    }

    public void setAccessGroups(Collection<AccessGroup> groups) {
        accessGroups = EnumSet.noneOf(AccessGroup.class);
        if (groups != null) {
            groups.stream().filter(Objects::nonNull).forEach(accessGroups::add);
        }
    }

    public void toggleAccessGroup(AccessGroup group) {
        if (!accessGroups.remove(group)) {
            accessGroups.add(group);
        }
    }

    public boolean isTicketsEnabled() {
        return ticketsEnabled;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = blankToNull(ticketId);
    }

    public void setTicketsEnabled(boolean ticketsEnabled) {
        this.ticketsEnabled = ticketsEnabled;
        if (ticketsEnabled && this.ticketId == null) {
            this.ticketId = UUID.randomUUID().toString();
        }
    }

    public void toggleTickets() {
        setTicketsEnabled(!ticketsEnabled);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}