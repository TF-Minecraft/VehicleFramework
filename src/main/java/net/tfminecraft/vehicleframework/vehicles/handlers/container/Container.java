package net.tfminecraft.vehicleframework.vehicles.handlers.container;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ticxo.modelengine.api.model.bone.ModelBone;

import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

public class Container {

    private String id;
    private String name;
    private int size;
    private String seat;

    //Visual stuff
    private List<String> boneList = new ArrayList<>();
    private List<ModelBone> bones = new ArrayList<>();

    //items
    private List<ItemStack> items = new ArrayList<>();
    private List<String> allowItems = new ArrayList<>();
    private Inventory live;
    // Saved entries that did not load; written back unchanged on the next save.
    private List<JsonArray> unreadEntries = new ArrayList<>();

    private static final Pattern TYPED_NUMBER =
            Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?[bBsSlLfFdD]");

    public Container(String key, ConfigurationSection config) {
        id = key;
        name = StringFormatter.formatHex(config.getString("name", "Container"));
        size = config.getInt("size", 27);
        seat = config.getString("seat", "none");
        if(config.contains("bones")) {
            for(String s : config.getStringList("bones")) {
                boneList.add(s);
            }
        }
        if (config.contains("allow-items")) {
            for (String path : config.getStringList("allow-items")) {
                if (path != null && !path.isBlank()) {
                    allowItems.add(path);
                }
            }
        }
    }

    public Container(ActiveVehicle v, Container stored) {
        id = stored.getId();
        name = stored.getName();
        size = stored.getSize();
        seat = stored.getSeat();
        allowItems.addAll(stored.getAllowItems());
        for(String bone : stored.getBoneList()) {
            Optional<ModelBone> opt = v.getModel().getBone(bone);
            if(opt.isEmpty()) {
                VFLogger.log(v.getModel().getBlueprint().getName()+" has no bone called "+bone);
                continue;
            }
            bones.add(opt.get());
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getSize() {
        return size;
    }

    public String getSeat() {
        return seat;
    }

    public List<String> getBoneList() {
        return boneList;
    }

    public List<ModelBone> getBones() {
        return bones;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public List<String> getAllowItems() {
        return allowItems;
    }

    public boolean allows(ItemStack item) {
        return decideAllow(allowItems.isEmpty(), isEmpty(item), matchesAllowList(item));
    }

    static boolean decideAllow(boolean emptyList, boolean itemEmpty, boolean pathHit) {
        if (itemEmpty) {
            return true;
        }
        if (emptyList) {
            return true;
        }
        return pathHit;
    }

    private boolean matchesAllowList(ItemStack item) {
        for (String path : allowItems) {
            try {
                if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public ItemStack takeOneMatching(String tlibsPath) {
        if (tlibsPath == null || tlibsPath.isBlank()) {
            return null;
        }
        int slots = live != null ? live.getSize() : items.size();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = live != null ? live.getItem(i) : (i < items.size() ? items.get(i) : null);
            if (isEmpty(stack)) {
                continue;
            }
            try {
                if (!TLibs.getItemAPI().getChecker().checkItemWithPath(stack, tlibsPath)) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            ItemStack one = stack.clone();
            one.setAmount(1);
            int left = stack.getAmount() - 1;
            ItemStack remain = left <= 0 ? null : stack;
            if (remain != null) {
                remain.setAmount(left);
            }
            if (live != null) {
                live.setItem(i, remain);
            } else {
                items.set(i, remain);
            }
            pullLive();
            updateBoneVisibility();
            return one;
        }
        return null;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    public void giveOrDrop(Player player, ItemStack item) {
        if (isEmpty(item) || player == null) {
            return;
        }
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        if (leftover.isEmpty() || player.getWorld() == null) {
            return;
        }
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    public void stripDisallowed(Inventory inventory, Player player) {
        if (inventory == null || allowItems.isEmpty()) {
            return;
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (allows(stack)) {
                continue;
            }
            inventory.setItem(i, null);
            giveOrDrop(player, stack);
        }
    }

    public void open(ActiveVehicle v, Player player) {
        player.openInventory(ensureLive(v));
    }

    public void close(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        if (live != null && inventory == live) {
            pullLive();
        } else {
            items.clear();
            ItemStack[] contents = inventory.getContents();
            for (ItemStack item : contents) {
                items.add(item != null ? item.clone() : null);
            }
            pushLive();
        }
        updateBoneVisibility();
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private Inventory ensureLive(ActiveVehicle v) {
        if (live != null) {
            return live;
        }
        live = Bukkit.createInventory(
                new VFInventoryHolder(id, VFGUI.CONTAINER, v),
                size,
                name);
        pushLive();
        return live;
    }

    private void pullLive() {
        if (live == null) {
            return;
        }
        items.clear();
        for (ItemStack item : live.getContents()) {
            items.add(item != null ? item.clone() : null);
        }
    }

    private void pushLive() {
        if (live == null) {
            return;
        }
        for (int i = 0; i < live.getSize(); i++) {
            live.setItem(i, i < items.size() ? items.get(i) : null);
        }
    }

    public JsonObject getAsJson() {
        pullLive();
        JsonObject root = new JsonObject();
        root.addProperty("id", id);

        JsonArray itemsArray = new JsonArray();

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.getType().isAir()) continue;

            ReadWriteNBT nbt = NBT.itemStackToNBT(stack); // Full item representation

            JsonArray entry = new JsonArray();
            entry.add(i); // slot index
            entry.add(nbt.toString()); // SNBT kept as a string so typed numbers (0b, 1.0d) survive

            itemsArray.add(entry);
        }
        for (JsonArray entry : unreadEntries) {
            itemsArray.add(entry.deepCopy());
        }

        root.add("items", itemsArray);
        return root;
    }

    public void loadFromJson(JsonObject json) {

        items.clear();
        unreadEntries.clear();
        for (int i = 0; i < size; i++) {
            items.add(null);
        }

        if (!json.has("items")) {
            pushLive();
            updateBoneVisibility();
            return;
        }
        JsonArray itemsArray = json.getAsJsonArray("items");

        for (JsonElement elem : itemsArray) {
            if (!elem.isJsonArray()) continue;
            JsonArray entry = elem.getAsJsonArray();
            if (entry.size() != 2) continue;

            int slot = readSlot(entry.get(0));
            ItemStack stack = readItem(slot, entry.get(1));
            int target = stack == null ? -1 : freeSlot(slot);
            if (target < 0) {
                // Keep the saved data so a failed load never deletes the item.
                unreadEntries.add(entry.deepCopy());
                continue;
            }
            items.set(target, stack);
        }
        pushLive();
        updateBoneVisibility();
    }

    // -1 sends the item to the first free slot.
    private static int readSlot(JsonElement saved) {
        try {
            return saved.getAsInt();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private ItemStack readItem(int slot, JsonElement saved) {
        Exception failure = null;
        List<String> candidates = snbtCandidates(saved);
        for (String snbt : candidates) {
            try {
                ItemStack stack = NBT.itemStackFromNBT(NBT.parseNBT(snbt));
                if (!isEmpty(stack)) {
                    return stack;
                }
            } catch (Exception e) {
                failure = e;
            }
        }
        String reason = failure == null ? "empty item" : String.valueOf(failure.getMessage());
        VFLogger.log("Container " + id + " could not load the item in slot " + slot
                + "; keeping its saved data: " + reason.substring(0, Math.min(reason.length(), 300)));
        return null;
    }

    private int freeSlot(int preferred) {
        if (preferred >= 0 && preferred < items.size() && items.get(preferred) == null) {
            return preferred;
        }
        return items.indexOf(null);
    }

    /**
     * New saves hold the item SNBT as a string. Older saves ran the SNBT through a lenient JSON
     * parse, which turned typed numbers such as {@code 0b} or {@code 1.0d} into strings. Those
     * are read with the types restored first, then as saved in case the restore misjudged text.
     */
    static List<String> snbtCandidates(JsonElement saved) {
        if (saved == null || saved.isJsonNull()) {
            return List.of();
        }
        if (saved.isJsonPrimitive() && saved.getAsJsonPrimitive().isString()) {
            return List.of(saved.getAsString());
        }
        return List.of(legacySnbt(saved), saved.toString());
    }

    static String legacySnbt(JsonElement element) {
        StringBuilder out = new StringBuilder();
        appendLegacy(element, out);
        return out.toString();
    }

    private static void appendLegacy(JsonElement element, StringBuilder out) {
        if (element.isJsonObject()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonNull()) continue;
                if (!first) out.append(',');
                first = false;
                appendQuoted(e.getKey(), out);
                out.append(':');
                appendLegacy(e.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            String prefix = typedArrayPrefix(array);
            out.append('[');
            boolean first = true;
            if (prefix != null) {
                out.append(prefix).append(';');
            }
            for (int i = prefix == null ? 0 : 1; i < array.size(); i++) {
                if (array.get(i).isJsonNull()) continue;
                if (!first) out.append(',');
                first = false;
                appendLegacy(array.get(i), out);
            }
            out.append(']');
            return;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString() && !TYPED_NUMBER.matcher(primitive.getAsString()).matches()) {
            appendQuoted(primitive.getAsString(), out);
        } else {
            out.append(primitive.getAsString());
        }
    }

    // [I; 1, 2] came out of the lenient parse as ["I", 1, 2].
    private static String typedArrayPrefix(JsonArray array) {
        if (array.size() < 2 || !array.get(0).isJsonPrimitive()) {
            return null;
        }
        String head = array.get(0).getAsString();
        if (!head.equals("B") && !head.equals("I") && !head.equals("L")) {
            return null;
        }
        for (int i = 1; i < array.size(); i++) {
            JsonElement value = array.get(i);
            if (!value.isJsonPrimitive()) {
                return null;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (!primitive.isNumber() && !TYPED_NUMBER.matcher(primitive.getAsString()).matches()) {
                return null;
            }
        }
        return head;
    }

    private static void appendQuoted(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }

    public void updateBoneVisibility() {
        /*
        if (bones.isEmpty()) {
            VFLogger.creatorLog("No bones to update for container " + id);
            return;
        }

        VFLogger.creatorLog("Updating bone visibility for container: " + id);

        // Debug: size vs items
        VFLogger.creatorLog("Container size: " + size + ", items.size(): " + items.size());

        int filledSlots = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            boolean filled = item != null && !item.getType().isAir();
            VFLogger.creatorLog("Slot " + i + ": " + (filled ? item.getType() : "empty"));
            if (filled) filledSlots++;
        }

        double fillRatio = size == 0 ? 0.0 : (double) filledSlots / size;
        VFLogger.creatorLog("Filled slots: " + filledSlots + "/" + size + " => fillRatio: " + fillRatio);

        int visibleCount = (int) Math.round(fillRatio * bones.size());
        VFLogger.creatorLog("Showing " + visibleCount + " of " + bones.size() + " bones");

        for (int i = 0; i < bones.size(); i++) {
            boolean visible = i < visibleCount;
            ModelBone bone = bones.get(i);
            bone.setVisible(visible);
            VFLogger.creatorLog("Bone " + i + " (" + bones.get(i).getBoneId() + "): " + (visible ? "VISIBLE" : "HIDDEN"));
        }
        */
    }

    public void destroy(Location loc) {
        pullLive();
        for(ItemStack item : items) {
            if (isEmpty(item)) continue;
            loc.getWorld().dropItem(loc, item);
        }
        for (JsonArray entry : unreadEntries) {
            VFLogger.log("Container " + id + " was destroyed with an item it could not load: " + entry);
        }
    }

}
