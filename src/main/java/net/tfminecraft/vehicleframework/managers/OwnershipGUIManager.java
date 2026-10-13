package net.tfminecraft.vehicleframework.managers;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.api.WhitelistEntry;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.managers.inventory.WhitelistPickerHolder;
import net.tfminecraft.vehicleframework.ownership.CharacterLookup;
import net.tfminecraft.vehicleframework.ownership.OwnerNames;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions;
import net.tfminecraft.vehicleframework.ownership.OwnershipMode;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class OwnershipGUIManager {
    public static final int PICKER_TYPED_SLOT = 25;
    public static final int BACK_SLOT = 26;
    public static final int OWNER_SLOT = 22;

    // ── Ownership Settings GUI ──────────────────────────────────────────────

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void ownershipGui(Inventory i, Player p, ActiveVehicle v, boolean open) {
        if (open) {
            i = VehicleFramework.plugin.getServer().createInventory(
                new VFInventoryHolder(v.getUUID(), VFGUI.OWNERSHIP, v), 27, "§7Ownership Settings");
        }

        // Buttons the player may not use are hidden; each click checks again.
        i.clear();
        // Slot 0 – Toggle whitelisting
        if (OwnershipActions.allowed(p, v, OwnershipReason.WHITELIST_TOGGLE)) {
            i.setItem(0, createToggleWhitelistButton(v.getOwnerData().isWhiteListed()));
        }
        // Slot 2 – Add player to whitelist
        if (Cache.allowWhitelist && OwnershipActions.allowed(p, v, OwnershipReason.WHITELIST_ADD)) {
            i.setItem(2, createAddToWhitelistButton());
        }
        // Slot 4 – View / manage whitelist
        if(Cache.allowWhitelist) i.setItem(4, createViewWhitelistButton(v.getOwnerData().getWhiteList().size()));
        // Slot 6 – Remove ownership
        if (OwnershipActions.allowed(p, v, OwnershipReason.CLEAR)) i.setItem(6, createRemoveOwnershipButton());
        // Slot 8 – Toggle tickets on the ticket source
        ActiveVehicle tickets = v.ticketSource();
        if (OwnershipActions.allowed(p, tickets, OwnershipReason.TICKETS_TOGGLE)) {
            i.setItem(8, createToggleTicketsButton(tickets.getOwnerData().isTicketsEnabled()));
        }
        if (OwnershipMode.characters()) {
            i.setItem(OWNER_SLOT, createOwnerInfo(v.getOwnerData().getOwner(), Permissions.isAdmin(p)));
        }

        fillGlass(i);

        if (open) p.openInventory(i);
    }

    // ── Whitelist GUI ────────────────────────────────────────────────────────

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void whitelistGui(Inventory i, Player p, ActiveVehicle v, boolean open) {
        if (open) {
            i = VehicleFramework.plugin.getServer().createInventory(
                new VFInventoryHolder(v.getUUID(), VFGUI.WHITELIST, v), 27, "§7Whitelist");
        }

        i.clear();

        int slot = 0;
        boolean staff = Permissions.isAdmin(p);
        for (String entry : v.getOwnerData().getWhiteList()) {
            if (slot >= 26) break; // slot 26 is the back button
            i.setItem(slot, OwnershipMode.characters() ? createCharacterEntry(entry, staff) : createWhitelistEntry(entry));
            slot++;
        }

        // Slot 26 – Back button
        i.setItem(26, createBackButton());

        fillGlass(i);

        if (open) p.openInventory(i);
    }

    // ── Nearby-player picker (characters mode) ──────────────────────────────

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void pickerGui(Player p, ActiveVehicle v, List<WhitelistPickerHolder.Candidate> candidates) {
        WhitelistPickerHolder holder = new WhitelistPickerHolder(v, candidates.subList(0, Math.min(candidates.size(), PICKER_TYPED_SLOT)));
        Inventory i = VehicleFramework.plugin.getServer().createInventory(holder, 27, "§7Add to Whitelist");
        int slot = 0;
        for (WhitelistPickerHolder.Candidate candidate : holder.getCandidates()) {
            i.setItem(slot++, createCandidate(candidate.name()));
        }
        if (holder.getCandidates().isEmpty()) {
            i.setItem(13, createInfo(Material.PAPER, "§7Nobody nearby", "§7Players within 16 blocks or aboard show here"));
        }
        if (CharacterLookup.typedNamesAvailable()) {
            i.setItem(PICKER_TYPED_SLOT, createInfo(Material.WRITABLE_BOOK, "§aType a Name", "§7Click, then type a character name in chat"));
        }
        i.setItem(BACK_SLOT, createBackButton());
        fillGlass(i);
        p.openInventory(i);
    }

    // ── Seat-selection ownership button (shown to the owner) ─────────────────

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public ItemStack createOwnershipButton() {
        ItemStack i = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§6Ownership Settings");
        List<String> lore = new ArrayList<>();
        lore.add("§7Click to manage ownership");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createToggleWhitelistButton(boolean enabled) {
        ItemStack i = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(enabled ? "§aWhitelisting: ON" : "§7Whitelisting: OFF");
        List<String> lore = new ArrayList<>();
        lore.add("§7When enabled only whitelisted players can enter");
        lore.add("");
        lore.add("§eClick to toggle");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createAddToWhitelistButton() {
        ItemStack i = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§aAdd to Whitelist");
        List<String> lore = new ArrayList<>();
        lore.add(OwnershipMode.characters() ? "§7Click to pick a nearby player" : "§7Click, then type a player name in chat");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createViewWhitelistButton(int count) {
        ItemStack i = new ItemStack(Material.CHEST);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§eView Whitelist");
        List<String> lore = new ArrayList<>();
        lore.add("§7Players on whitelist: §f" + count);
        lore.add("§7Click to manage whitelist");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createToggleTicketsButton(boolean enabled) {
        ItemStack i = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(enabled ? "§aTickets: ON" : "§7Tickets: OFF");
        List<String> lore = new ArrayList<>();
        lore.add("§7When enabled, passenger seats need a ticket");
        lore.add("§7Owner right-clicks with the ticket item to mint");
        lore.add("");
        lore.add("§eClick to toggle");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createRemoveOwnershipButton() {
        ItemStack i = new ItemStack(Material.BARRIER);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§cRemove Ownership");
        List<String> lore = new ArrayList<>();
        lore.add("§7Resets the owner of this vehicle to none");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createWhitelistEntry(String storedName) {
        ItemStack i = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = i.getItemMeta();
        String display = storedName.startsWith("player_") ? storedName.substring(7) : storedName;
        m.setDisplayName("§e" + display);
        List<String> lore = new ArrayList<>();
        lore.add("§cClick to remove from whitelist");
        m.setLore(lore);
        NamespacedKey key = new NamespacedKey(VehicleFramework.plugin, "vf_whitelist_entry");
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, storedName);
        i.setItemMeta(m);
        return i;
    }

    // Character entries show the name captured when added; anything else no longer grants access.
    @SuppressWarnings("deprecation")
    private ItemStack createCharacterEntry(String storedName, boolean staff) {
        ItemStack i = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = i.getItemMeta();
        List<String> lore = new ArrayList<>();
        java.util.Optional<WhitelistEntry> entry = VehicleOwners.parseWhitelistEntry(storedName);
        if (entry.isPresent()) {
            m.setDisplayName("§e" + entry.get().name());
            if (staff) lore.add("§7Account: §f" + OwnerNames.account(entry.get().character().ownerId()));
        } else {
            m.setDisplayName("§8" + OwnerNames.legacy(storedName));
            lore.add("§7Old entry: no longer grants access");
        }
        lore.add("§cClick to remove from whitelist");
        m.setLore(lore);
        NamespacedKey key = new NamespacedKey(VehicleFramework.plugin, "vf_whitelist_entry");
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, storedName);
        i.setItemMeta(m);
        return i;
    }

    // Picker heads never carry the account's skin.
    @SuppressWarnings("deprecation")
    private ItemStack createCandidate(String name) {
        ItemStack i = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§e" + name);
        List<String> lore = new ArrayList<>();
        lore.add("§aClick to add to the whitelist");
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    private ItemStack createOwnerInfo(String owner, boolean staff) {
        return createInfo(Material.NAME_TAG, "§7Owner: §f" + OwnerNames.owner(owner, staff), null);
    }

    @SuppressWarnings("deprecation")
    private ItemStack createInfo(Material material, String title, String line) {
        ItemStack i = new ItemStack(material);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(title);
        List<String> lore = new ArrayList<>();
        if (line != null) lore.add(line);
        m.setLore(lore);
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private ItemStack createBackButton() {
        ItemStack i = new ItemStack(Material.BARRIER);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName("§7Back");
        i.setItemMeta(m);
        return i;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void fillGlass(Inventory i) {
        for (int s = 0; s < i.getSize(); s++) {
            if (i.getItem(s) == null) {
                ItemStack fill = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta fm = fill.getItemMeta();
                fm.setDisplayName("§8 ");
                fill.setItemMeta(fm);
                i.setItem(s, fill);
            }
        }
    }
}
