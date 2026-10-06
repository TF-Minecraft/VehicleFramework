package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.enums.Component;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.Container;
import net.tfminecraft.vehicleframework.vehicles.handlers.container.ContainerHandler;
import net.tfminecraft.vehicleframework.vehicles.handlers.skins.VehicleSkin;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.util.Fire;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;

class InventoryManagerCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private VehicleFramework previousPlugin;
    private final Server server = mock(Server.class);
    private final Player player = mock(Player.class);
    private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
    private final SeatHandler seats = mock(SeatHandler.class);
    private final WeaponHandler weapons = mock(WeaponHandler.class);
    private final OwnerData owner = mock(OwnerData.class);
    private final Map<ItemStack, ItemState> itemState = new IdentityHashMap<>();
    private final List<Inventory> created = new ArrayList<>();
    private MockedConstruction<OwnershipGUIManager> ownership;
    private InventoryManager manager;

    @BeforeAll static void bootstrapRegistries() {
        net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
        try (MockedStatic<io.papermc.paper.registry.RegistryAccess> access = mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            Registry<?> registry = mock(Registry.class, call -> call.getMethod().getName().equals("getOrThrow")
                    ? mock(Enchantment.class) : RETURNS_DEFAULTS.answer(call));
            var provider = mock(io.papermc.paper.registry.RegistryAccess.class, call ->
                    registry);
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            assertNotNull(Enchantment.UNBREAKING);
        }
    }
    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin;
        VehicleFramework.plugin = mock(VehicleFramework.class);
        when(VehicleFramework.plugin.getName()).thenReturn("VehicleFramework");
        when(VehicleFramework.plugin.namespace()).thenReturn("vehicleframework");
        when(VehicleFramework.plugin.getServer()).thenReturn(server);
        keep(mockConstruction(ItemStack.class, (item, context) -> configure(item, (Material) context.arguments().get(0))));
        ownership = keep(mockConstruction(OwnershipGUIManager.class));
        when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
            Inventory inventory = inventory(call.getArgument(1), call.getArgument(0));
            created.add(inventory); return inventory;
        });
        when(player.getName()).thenReturn("Alex");
        when(vehicle.getUUID()).thenReturn("vehicle-id"); when(vehicle.getOwnerData()).thenReturn(owner);
        when(owner.getOwner()).thenReturn("player_Alex");
        when(vehicle.getSeatHandler()).thenReturn(seats); when(seats.getSeats()).thenReturn(List.of());
        when(vehicle.getComponents()).thenReturn(List.of()); when(vehicle.getWeaponHandler()).thenReturn(weapons);
        when(weapons.getWeapons()).thenReturn(List.of());
        manager = new InventoryManager();
        ItemStack button = new ItemStack(Material.TRIPWIRE_HOOK);
        when(ownership.constructed().get(0).createOwnershipButton()).thenReturn(button);
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin;
    }

    @Test void seatSelectorShowsTypeOccupancyPassengerAndLinkedStorage() {
        Seat empty = new Seat(SeatType.PASSENGER, "front_seat"), occupied = new Seat(SeatType.CAPTAIN, "driver");
        occupied.mount(mock(Player.class));
        Seat creature = new Seat(SeatType.ENTITY, "pet_seat"), freeCreature = new Seat(SeatType.ENTITY, "free_pet");
        Entity pig = mock(Entity.class); when(pig.getType()).thenReturn(EntityType.PIG); creature.mount(pig);
        when(seats.getSeats()).thenReturn(List.of(empty, occupied, creature, freeCreature));
        when(vehicle.hasContainers()).thenReturn(true); ContainerHandler containers = mock(ContainerHandler.class);
        when(vehicle.getContainerHandler()).thenReturn(containers); Container storage = mock(Container.class);
        when(storage.getName()).thenReturn("Luggage"); when(containers.getBySeat("front_seat")).thenReturn(storage);
        when(vehicle.isPassenger(player, false)).thenReturn(true);
        manager.seatSelection(null, player, vehicle, true); Inventory inventory = created.get(0);
        assertEquals(Material.GREEN_CONCRETE, inventory.getItem(0).getType());
        assertEquals(Material.YELLOW_CONCRETE, inventory.getItem(1).getType());
        assertEquals(Material.GRAY_CONCRETE, inventory.getItem(2).getType());
        assertEquals(Material.CYAN_CONCRETE, inventory.getItem(3).getType());
        assertEquals("§aFront seat", inventory.getItem(0).getItemMeta().getDisplayName());
        assertEquals("front_seat", tag(inventory.getItem(0), "vf_seat_id"));
        assertTrue(inventory.getItem(0).getItemMeta().getLore().contains("§7Storage: §fLuggage"));
        assertTrue(inventory.getItem(2).getItemMeta().getLore().contains("§7Passenger: §ePig"));
        assertTrue(inventory.getItem(3).getItemMeta().getLore().contains("§aClick to choose a passenger to seat here"));
        assertEquals(Material.TRIPWIRE_HOOK, inventory.getItem(25).getType());
        assertEquals("§cDismount", inventory.getItem(26).getItemMeta().getDisplayName());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(24).getType());
        verify(player).openInventory(inventory);
        assertEquals(VFGUI.SEAT_SELECTION, ((VFInventoryHolder) inventory.getHolder()).getType());
    }

    @Test void nonOwnerGetsNoOwnershipButtonAndRefreshDoesNotReopen() {
        when(owner.getOwner()).thenReturn("player_SomeoneElse"); Inventory inventory = inventory(27, null);
        manager.seatSelection(inventory, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(25).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(26).getType());
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void manySeatsDoNotOverwriteOwnershipOrDismountControls(boolean open) {
        List<Seat> many = new ArrayList<>(); for (int i = 0; i < 26; i++) many.add(new Seat(SeatType.PASSENGER, "seat_" + i));
        when(seats.getSeats()).thenReturn(many); when(vehicle.isPassenger(player, false)).thenReturn(true);
        manager.seatSelection(open ? null : inventory(27, null), player, vehicle, open); Inventory inventory = created.get(0);
        long represented = Arrays.stream(inventory.getContents()).filter(Objects::nonNull)
                .filter(item -> tag(item, "vf_seat_id") != null).count();
        assertEquals(26, represented, "Every configured seat must remain selectable");
        assertEquals(Material.TRIPWIRE_HOOK, inventory.getItem(25).getType());
        assertEquals(Material.BARRIER, inventory.getItem(26).getType());
        assertEquals(36, inventory.getSize()); verify(player).openInventory(inventory);
    }

    @Test void seatRefreshRemovesButtonsThatAreNoLongerAvailable() {
        Inventory inventory = inventory(27, null); when(vehicle.isPassenger(player, false)).thenReturn(true);
        manager.seatSelection(inventory, player, vehicle, false);
        when(vehicle.isPassenger(player, false)).thenReturn(false); when(owner.getOwner()).thenReturn("player_Other");
        manager.seatSelection(inventory, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(25).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(26).getType());
    }

    @ParameterizedTest @ValueSource(strings = {"repair", "water"})
    void repairMenuDistinguishesComponentsWeaponsAndSelectedTool(String tool) {
        List<VehicleComponent> components = new ArrayList<>();
        components.add(component(Hull.class, Component.HULL)); components.add(component(Engine.class, Component.ENGINE));
        components.add(component(GearedEngine.class, Component.GEARED_ENGINE)); components.add(component(Pump.class, Component.PUMP));
        components.add(component(Wings.class, Component.WINGS)); components.add(component(Harness.class, Component.HARNESS));
        components.add(component(Balloon.class, Component.BALLOON));
        VehicleComponent burning = components.get(0); Fire fire = new Fire(); fire.setProgress(50);
        when(burning.isOnFire()).thenReturn(true); when(burning.getFire()).thenReturn(fire);
        burning.getHealthData().startRepair();
        when(vehicle.getComponents()).thenReturn(components);
        List<ActiveWeapon> guns = new ArrayList<>(); for (int i = 0; i < 10; i++) guns.add(weapon("gun" + i));
        guns.get(0).getHealthData().startRepair(); when(weapons.getWeapons()).thenReturn(guns);
        manager.repairWindow(null, player, vehicle, true, tool); Inventory inventory = created.get(0);
        Material[] types = {Material.NETHERITE_BLOCK, Material.BLAST_FURNACE, Material.FURNACE, Material.BREWING_STAND,
                Material.WHITE_WOOL, Material.LEAD, Material.GREEN_CONCRETE};
        String[] ids = {"hull", "engine", "engine", "pump", "wings", "harness", "balloon"};
        for (int i = 0; i < types.length; i++) { assertEquals(types[i], inventory.getItem(i).getType()); assertEquals(ids[i], tag(inventory.getItem(i), "vf_component_type")); }
        assertTrue(inventory.getItem(0).getItemMeta().getLore().contains(fire.getFireString()));
        assertTrue(inventory.getItem(0).getItemMeta().getLore().stream().anyMatch(line -> line.contains("Repairing")));
        assertEquals("gun0", tag(inventory.getItem(7), "vf_weapon_repair_type"));
        assertEquals("gun9", tag(inventory.getItem(18), "vf_weapon_repair_type"));
        assertEquals("repair", tag(inventory.getItem(8), "vf_tool_type")); assertEquals("water", tag(inventory.getItem(17), "vf_tool_type"));
        ItemStack selected = inventory.getItem(tool.equals("repair") ? 8 : 17);
        assertTrue(selected.getItemMeta().getLore().contains("§aSelected"));
        verify(selected.getItemMeta()).addEnchant(Enchantment.UNBREAKING, 1, true);
        verify(selected.getItemMeta()).addItemFlags(ItemFlag.HIDE_ENCHANTS);
        verify(player).openInventory(inventory);
    }

    @Test void repairMenuWithNoVehicleStillOffersBothToolsWhenRefreshing() {
        Inventory inventory = inventory(27, null); manager.repairWindow(inventory, player, null, false, "none");
        assertEquals(Material.IRON_SHOVEL, inventory.getItem(8).getType());
        assertEquals(Material.WATER_BUCKET, inventory.getItem(17).getType());
        assertTrue(inventory.getItem(8).getItemMeta().getLore().contains("§eClick to Select"));
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test void repairComponentsSkipReservedToolSlots() {
        List<VehicleComponent> components = new ArrayList<>();
        for (int i = 0; i < 17; i++) components.add(component(Hull.class, Component.HULL));
        when(vehicle.getComponents()).thenReturn(components); Inventory inventory = inventory(27, null);
        manager.repairWindow(inventory, player, vehicle, false, "repair");
        assertEquals("hull", tag(inventory.getItem(18), "vf_component_type"));
        assertEquals("repair", tag(inventory.getItem(8), "vf_tool_type"));
    }

    @Test void skinMenuKeepsActiveMissingModelAndFiltersOtherMissingModels() {
        SkinHandler skins = mock(SkinHandler.class); when(vehicle.getSkinHandler()).thenReturn(skins);
        VehicleSkin active = skin("current", "Current"), available = skin("new", "Available"), unavailable = skin("missing", "Missing");
        when(skins.getCurrentSkin()).thenReturn(active);
        HashMap<String, VehicleSkin> choices = new LinkedHashMap<>(); choices.put("current", active); choices.put("new", available); choices.put("missing", unavailable);
        when(skins.getSkins()).thenReturn(choices);
        MockedStatic<SkinHandler> access = keep(mockStatic(SkinHandler.class));
        access.when(() -> SkinHandler.isModelAvailable(available)).thenReturn(true);
        manager.skinSelection(null, player, vehicle, true); Inventory inventory = created.get(0);
        assertEquals(Material.YELLOW_CONCRETE, inventory.getItem(0).getType());
        assertEquals("§eCurrent", inventory.getItem(0).getItemMeta().getDisplayName());
        assertEquals("§aAvailable", inventory.getItem(1).getItemMeta().getDisplayName());
        assertEquals("new", tag(inventory.getItem(1), "vf_skin_id"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(2).getType());
        manager.skinSelection(inventory, player, vehicle, false); verify(player, times(1)).openInventory(any(Inventory.class));
    }

    private VehicleComponent component(Class<? extends VehicleComponent> type, Component kind) {
        VehicleComponent component = mock(type); HealthData health = new HealthData(100, 20, 60);
        when(component.getType()).thenReturn(kind); when(component.getHealthData()).thenReturn(health); return component;
    }
    private ActiveWeapon weapon(String id) {
        ActiveWeapon weapon = mock(ActiveWeapon.class); when(weapon.getId()).thenReturn(id); when(weapon.getName()).thenReturn(id);
        HealthData health = new HealthData(100, 10, 40); when(weapon.getHealthData()).thenReturn(health); return weapon;
    }
    private VehicleSkin skin(String id, String name) {
        VehicleSkin skin = mock(VehicleSkin.class); when(skin.getId()).thenReturn(id); when(skin.getName()).thenReturn(name); return skin;
    }
    private Inventory inventory(int size, InventoryHolder holder) {
        Inventory inventory = mock(Inventory.class); ItemStack[] contents = new ItemStack[size];
        when(inventory.getSize()).thenReturn(size); when(inventory.getHolder()).thenReturn(holder);
        when(inventory.getContents()).thenAnswer(call -> contents.clone());
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[call.<Integer>getArgument(0)]);
        doAnswer(call -> { contents[call.<Integer>getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        doAnswer(call -> { Arrays.fill(contents, null); return null; }).when(inventory).clear(); return inventory;
    }
    private void configure(ItemStack item, Material type) {
        ItemState state = new ItemState(); state.type = type; itemState.put(item, state);
        ItemMeta meta = mock(ItemMeta.class); PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, String> values = new HashMap<>();
        when(item.getType()).thenAnswer(call -> state.type);
        doAnswer(call -> { state.type = call.getArgument(0); return null; }).when(item).setType(any());
        when(item.getItemMeta()).thenReturn(meta); when(item.setItemMeta(meta)).thenReturn(true);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; }).when(data).set(any(), eq(PersistentDataType.STRING), anyString());
        when(data.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { state.name = call.getArgument(0); return null; }).when(meta).setDisplayName(anyString());
        when(meta.getDisplayName()).thenAnswer(call -> state.name);
        doAnswer(call -> { state.lore = List.copyOf(call.getArgument(0)); return null; }).when(meta).setLore(anyList());
        when(meta.getLore()).thenAnswer(call -> state.lore);
    }
    private String tag(ItemStack item, String name) {
        return item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("vehicleframework", name), PersistentDataType.STRING);
    }
    private static final class ItemState { Material type; String name; List<String> lore = List.of(); }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
}
