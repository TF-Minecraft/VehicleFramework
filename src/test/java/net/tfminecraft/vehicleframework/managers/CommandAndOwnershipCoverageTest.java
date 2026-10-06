package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.*;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.enums.Input;
import net.tfminecraft.vehicleframework.loaders.*;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.tracks.*;
import net.tfminecraft.vehicleframework.util.TabCompletion;
import net.tfminecraft.vehicleframework.vehicles.*;
import net.tfminecraft.vehicleframework.vehicles.state.VehicleState;
import net.tfminecraft.vehicleframework.weapons.ammunition.Ammunition;

class CommandAndOwnershipCoverageTest {
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private final VehicleManager vehicles = mock(VehicleManager.class);
    private final Player player = mock(Player.class, RETURNS_DEEP_STUBS);
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final Command command = mock(Command.class);
    private final CommandManager commands = new CommandManager();
    private final OwnershipGUIManager ownership = new OwnershipGUIManager();
    private final List<Inventory> menus = new ArrayList<>();
    private final OwnerData owner = new OwnerData();
    private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
    private VehicleFramework previousPlugin, plugin;
    private boolean previousAllowWhitelist;
    private MockedStatic<VehicleFramework> framework;
    private final Map<String, Ammunition> previousAmmo = new HashMap<>(AmmunitionLoader.get());
    private final Map<String, Vehicle> previousVehicles = new HashMap<>(VehicleLoader.get());

    @BeforeEach void setup() {
        previousPlugin = VehicleFramework.plugin; plugin = mock(VehicleFramework.class); VehicleFramework.plugin = plugin;
        when(plugin.getName()).thenReturn("VehicleFramework"); when(plugin.namespace()).thenReturn("vehicleframework");
        when(plugin.getServer()).thenReturn(server); when(command.getName()).thenReturn("vf");
        when(player.getName()).thenReturn("Alex"); when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world"); when(player.getLocation()).thenReturn(new Location(world, 2, 64, 3));
        framework = keep(mockStatic(VehicleFramework.class)); framework.when(VehicleFramework::getVehicleManager).thenReturn(vehicles);
        framework.when(VehicleFramework::getInstance).thenReturn(plugin);
        when(vehicle.getUUID()).thenReturn("vehicle-id"); when(vehicle.getOwnerData()).thenReturn(owner); when(vehicle.ticketSource()).thenReturn(vehicle);
        when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
            Inventory menu = inventory(call.getArgument(1), call.getArgument(0)); menus.add(menu); return menu;
        });
        keep(mockConstruction(ItemStack.class, (item, context) -> configure(item, (Material) context.arguments().get(0))));
        previousAllowWhitelist = Cache.allowWhitelist; Cache.allowWhitelist = true;
        AmmunitionLoader.get().clear(); VehicleLoader.get().clear();
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin; Cache.allowWhitelist = previousAllowWhitelist;
        AmmunitionLoader.get().clear(); AmmunitionLoader.get().putAll(previousAmmo);
        VehicleLoader.get().clear(); VehicleLoader.get().putAll(previousVehicles);
    }

    @Test void bareVehicleCommandReturnsUsageInsteadOfThrowing() {
        assertFalse(assertDoesNotThrow(() -> run(player)));
    }

    @Test void ownershipRefreshRemovesDisabledWhitelistControls() {
        Inventory menu = inventory(27, null); ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals(Material.WRITABLE_BOOK, menu.getItem(2).getType());
        Cache.allowWhitelist = false; ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(2).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(4).getType());
    }

    @Test void commandsRespectNamePermissionAndUnknownArguments() {
        when(command.getName()).thenReturn("other"); assertFalse(run(player, "reload"));
        when(command.getName()).thenReturn("VF"); assertTrue(run(player, "spawn", "cart"));
        verify(player).sendMessage("§cYou do not have access to this command!"); verifyNoInteractions(vehicles);
        when(player.hasPermission("vehicleframework.spawn")).thenReturn(true);
        assertTrue(run(player, "reload")); verify(plugin, never()).reload();
        when(player.hasPermission("vf.admin")).thenReturn(true); assertFalse(run(player, "unknown"));
    }

    @ParameterizedTest @ValueSource(strings = {"keybinds", "ammo", "kill", "spawn", "takeover"})
    void playerOnlyCommandsRejectConsoleWithoutPlayerCasts(String verb) {
        CommandSender console = mock(CommandSender.class); when(console.hasPermission("vf.admin")).thenReturn(true);
        assertTrue(verb.equals("kill") || verb.equals("spawn") ? run(console, verb, "10") : run(console, verb));
        verifyNoInteractions(vehicles);
    }

    @Test void keybindsReportTheCurrentVehicleStateAndSkipUnboundActions() {
        assertFalse(run(player, "keybinds")); verify(player).sendMessage("§cYou are not in a vehicle");
        when(vehicles.getByPassenger(player)).thenReturn(vehicle);
        VehicleState state = mock(VehicleState.class, RETURNS_DEEP_STUBS); when(vehicle.getCurrentState()).thenReturn(state);
        when(state.getType()).thenReturn(State.GROUND);
        when(state.getInputHandler().getMappings()).thenReturn(new HashMap<>(Map.of(Keybind.W, Input.THROTTLE_UP, Keybind.S, Input.NONE)));
        assertTrue(run(player, "keybinds")); verify(player).sendMessage("§bKeybinds for state: §aGround");
        verify(player).sendMessage("§eW §f-> §aSpeed Up"); verify(player, never()).sendMessage("§eS §f-> §aNone");
    }

    @Test void ownedVehicleListShowsLoadedCoordinatesAndUnknownStoredLocations() {
        assertTrue(run(player, "findvehicles")); verify(player).sendMessage("§7You do not own any vehicles.");
        OwnedVehicleSummary loaded = mock(OwnedVehicleSummary.class), stored = mock(OwnedVehicleSummary.class), lostWorld = mock(OwnedVehicleSummary.class);
        when(loaded.getName()).thenReturn("Cart"); when(loaded.getLocation()).thenReturn(Optional.of(new Location(world, 10, 64, -12)));
        when(stored.getName()).thenReturn("Stored"); when(stored.getLocation()).thenReturn(Optional.empty());
        when(lostWorld.getName()).thenReturn("Lost"); when(lostWorld.getLocation()).thenReturn(Optional.of(new Location(null, 0, 64, 0)));
        when(vehicles.listOwnedVehicles("player_Alex")).thenReturn(List.of(loaded, stored, lostWorld));
        assertTrue(run(player, "findvehicles")); verify(player).sendMessage("§eCart §7- §fworld 10, 64, -12");
        verify(player).sendMessage("§eStored §7- §7location unknown (stored)"); verify(player).sendMessage("§eLost §7- §7location unknown (stored)");
        CommandSender console = mock(CommandSender.class); assertTrue(run(console, "findvehicles"));
        verify(console).sendMessage("§cOnly players can use this command.");
    }

    @Test void spawnKillAmmoTakeoverAndReloadReachTheirAuthorizedActions() {
        when(player.hasPermission("vf.admin")).thenReturn(true); assertTrue(run(player, "spawn", "cart"));
        verify(vehicles).spawn(player.getLocation(), "cart"); assertTrue(run(player, "kill", "invalid"));
        verify(player).sendMessage("§cPlease enter a valid number for the radius.");
        when(vehicles.kill(player, player.getLocation(), 20)).thenReturn(3); assertTrue(run(player, "kill", "20"));
        verify(player).sendMessage("§a[VehicleFramework] §e§aKilled 3 entities within 20 blocks.");
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS); keep(mockStatic(TLibs.class)).when(TLibs::getItemAPI).thenReturn(api);
        YamlConfiguration config = new YamlConfiguration(); config.set("input", "custom.shell");
        AmmunitionLoader.get().put("shell", new Ammunition("shell", config)); ItemStack shell = mock(ItemStack.class);
        when(api.getCreator().getItemFromPath("custom.shell")).thenReturn(shell);
        assertTrue(run(player, "ammo")); verify(shell).setAmount(64); verify(player.getInventory()).addItem(shell);
        assertTrue(run(player, "takeover")); verify(vehicles).startTakeover(player);
        assertTrue(run(player, "reload")); verify(plugin).reload();
        verify(player).sendMessage("§a[VehicleFramework] §eReload complete!");
        CommandSender console = mock(CommandSender.class); when(console.hasPermission("vf.admin")).thenReturn(true);
        assertTrue(run(console, "reload")); verify(plugin, times(2)).reload();
    }

    @Test void trackCommandDelegatesItsArgumentsAndResult() {
        String[] args = {"track", "list"}; MockedStatic<TrackCommands> tracks = keep(mockStatic(TrackCommands.class));
        tracks.when(() -> TrackCommands.handle(player, args)).thenReturn(true);
        assertTrue(commands.onCommand(player, command, "vf", args)); tracks.verify(() -> TrackCommands.handle(player, args));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownershipMenuReflectsWhitelistTicketsAndLinkedTrainOwnership(boolean enabled) {
        owner.setWhiteListed(enabled); owner.setWhiteList(new ArrayList<>(List.of("player_Alex", "group_staff")));
        ActiveVehicle locomotive = mock(ActiveVehicle.class); OwnerData trainOwner = new OwnerData(); trainOwner.setTicketsEnabled(enabled);
        when(locomotive.getOwnerData()).thenReturn(trainOwner); when(vehicle.ticketSource()).thenReturn(locomotive);
        ownership.ownershipGui(null, player, vehicle, true); Inventory menu = menus.get(0);
        assertEquals(enabled ? Material.LIME_DYE : Material.GRAY_DYE, menu.getItem(0).getType());
        assertEquals(enabled ? "§aTickets: ON" : "§7Tickets: OFF", menu.getItem(8).getItemMeta().getDisplayName());
        assertEquals(Material.WRITABLE_BOOK, menu.getItem(2).getType()); assertEquals(Material.BARRIER, menu.getItem(6).getType());
        assertTrue(menu.getItem(4).getItemMeta().getLore().contains("§7Players on whitelist: §f2"));
        assertEquals(VFGUI.OWNERSHIP, ((VFInventoryHolder) menu.getHolder()).getType());
        assertSame(vehicle, ((VFInventoryHolder) menu.getHolder()).getVehicle().orElseThrow()); verify(player).openInventory(menu);
        ItemStack button = ownership.createOwnershipButton(); assertEquals(Material.GOLDEN_HELMET, button.getType());
        assertEquals("§6Ownership Settings", button.getItemMeta().getDisplayName());
    }

    @Test void whitelistMenuStoresOriginalNamesReservesBackButtonAndClearsRemovedEntries() {
        List<String> names = new ArrayList<>(List.of("player_Alex", "staff"));
        for (int i = 2; i < 30; i++) names.add("player_" + i); owner.setWhiteList(names);
        ownership.whitelistGui(null, player, vehicle, true); Inventory menu = menus.get(0);
        assertEquals("§eAlex", menu.getItem(0).getItemMeta().getDisplayName()); assertEquals("§estaff", menu.getItem(1).getItemMeta().getDisplayName());
        assertEquals("player_Alex", menu.getItem(0).getItemMeta().getPersistentDataContainer().get(new NamespacedKey("vehicleframework", "vf_whitelist_entry"), PersistentDataType.STRING));
        assertEquals(Material.PLAYER_HEAD, menu.getItem(25).getType()); assertEquals("§7Back", menu.getItem(26).getItemMeta().getDisplayName());
        assertEquals(VFGUI.WHITELIST, ((VFInventoryHolder) menu.getHolder()).getType());
        owner.setWhiteList(List.of()); ownership.whitelistGui(menu, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(0).getType()); verify(player).openInventory(menu);
    }

    @Test void completionsFollowPermissionsAndOfferKnownVehiclesAndWorldTracks() {
        TabCompletion completion = new TabCompletion();
        assertNull(completion.onTabComplete(mock(CommandSender.class), command, "vf", new String[]{""}));
        assertEquals(List.of("findvehicles", "keybinds"), completion.onTabComplete(player, command, "vf", new String[]{""}));
        assertTrue(completion.onTabComplete(player, command, "vf", new String[]{"spawn", ""}).isEmpty());
        when(player.hasPermission("vehicleframework.spawn")).thenReturn(true);
        assertEquals(List.of("findvehicles", "keybinds", "spawn", "kill", "ammo"), completion.onTabComplete(player, command, "vf", new String[]{""}));
        Vehicle cart = mock(Vehicle.class); when(cart.getId()).thenReturn("cart"); VehicleLoader.get().put("cart", cart);
        assertEquals(List.of("cart"), completion.onTabComplete(player, command, "vf", new String[]{"spawn", ""}));
        assertEquals(List.of("10", "20", "50"), completion.onTabComplete(player, command, "vf", new String[]{"kill", ""}));
        when(player.hasPermission("vf.admin")).thenReturn(true);
        assertTrue(completion.onTabComplete(player, command, "vf", new String[]{""}).containsAll(List.of("takeover", "reload", "track")));
        assertEquals(List.of("start", "end", "list", "info", "particles", "clearance", "dump", "delete", "bind", "unbind", "resync"),
                completion.onTabComplete(player, command, "vf", new String[]{"track", ""}));
        assertTrue(completion.onTabComplete(player, command, "vf", new String[]{"track", "delete", ""}).isEmpty());
        TrackRegistry registry = mock(TrackRegistry.class); TrackSpline track = mock(TrackSpline.class); UUID id = UUID.randomUUID(); when(track.getId()).thenReturn(id);
        when(registry.inWorld("world")).thenReturn(List.of(track)); framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
        for (String verb : List.of("delete", "clearance")) assertEquals(List.of(id.toString()), completion.onTabComplete(player, command, "vf", new String[]{"track", verb, ""}));
        assertNull(completion.onTabComplete(player, command, "vf", new String[]{"track", "unknown", ""}));
        assertNull(completion.onTabComplete(player, command, "vf", new String[]{"unknown", ""}));
        when(command.getName()).thenReturn("other"); assertNull(completion.onTabComplete(player, command, "other", new String[]{""}));
    }

    private boolean run(CommandSender sender, String... args) { return commands.onCommand(sender, command, "vf", args); }
    private Inventory inventory(int size, InventoryHolder holder) {
        Inventory menu = mock(Inventory.class); ItemStack[] contents = new ItemStack[size];
        when(menu.getSize()).thenReturn(size); when(menu.getHolder()).thenReturn(holder);
        when(menu.getItem(anyInt())).thenAnswer(call -> contents[call.<Integer>getArgument(0)]);
        doAnswer(call -> { contents[call.<Integer>getArgument(0)] = call.getArgument(1); return null; }).when(menu).setItem(anyInt(), nullable(ItemStack.class));
        doAnswer(call -> { Arrays.fill(contents, null); return null; }).when(menu).clear(); return menu;
    }
    private void configure(ItemStack item, Material type) {
        ItemMeta meta = mock(ItemMeta.class); PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, String> tags = new HashMap<>(); String[] name = {""}; List<List<String>> lore = new ArrayList<>(List.of(List.of()));
        when(item.getType()).thenReturn(type); when(item.getItemMeta()).thenReturn(meta); when(item.setItemMeta(meta)).thenReturn(true);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        doAnswer(call -> { tags.put(call.getArgument(0), call.getArgument(2)); return null; }).when(data).set(any(), eq(PersistentDataType.STRING), anyString());
        when(data.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> tags.get(call.getArgument(0)));
        doAnswer(call -> { name[0] = call.getArgument(0); return null; }).when(meta).setDisplayName(anyString()); when(meta.getDisplayName()).thenAnswer(call -> name[0]);
        doAnswer(call -> { lore.set(0, List.copyOf(call.getArgument(0))); return null; }).when(meta).setLore(anyList()); when(meta.getLore()).thenAnswer(call -> lore.get(0));
    }
    private <T extends AutoCloseable> T keep(T scope) { scopes.add(scope); return scope; }
}
