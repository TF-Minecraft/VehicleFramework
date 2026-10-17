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
import net.tfminecraft.tlibs.character.*;
import net.tfminecraft.vehicleframework.api.*;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipEditCheckEvent;
import net.tfminecraft.vehicleframework.managers.inventory.WhitelistPickerHolder;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions;
import net.tfminecraft.vehicleframework.ownership.OwnershipFixture;

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
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Characters> characters;
    private final org.bukkit.plugin.PluginManager plugins = mock(org.bukkit.plugin.PluginManager.class);
    private final org.bukkit.plugin.ServicesManager services = mock(org.bukkit.plugin.ServicesManager.class);
    private final CharacterSession kira = OwnershipFixture.session("alex", "kira", 1);
    private final CharacterSession bren = OwnershipFixture.session("bren", "bren", 2);
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
        // Ownership buttons ask listeners before they are shown.
        bukkit = keep(mockStatic(Bukkit.class)); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services); bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        characters = keep(mockStatic(Characters.class));
        owner.setOwner("player_Alex");
        AmmunitionLoader.get().clear(); VehicleLoader.get().clear();
    }
    @AfterEach void cleanup() throws Exception {
        Collections.reverse(scopes); for (AutoCloseable scope : scopes) scope.close();
        VehicleFramework.plugin = previousPlugin; Cache.allowWhitelist = previousAllowWhitelist;
        OwnershipFixture.mode(OwnershipIdentity.NAMES); OwnershipFixture.lookup(null);
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
        trainOwner.setOwner("player_Alex");
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

    @Test void completionFiltersEveryCurrentTokenAndConsoleOnlySeesUsableActions() {
        TabCompletion tabs = new TabCompletion();
        when(player.hasPermission("vf.admin")).thenReturn(true);
        assertEquals(List.of("spawn"), tabs.onTabComplete(player, command, "vf", new String[]{"SP"}));
        assertEquals(List.of(), tabs.onTabComplete(player, command, "vf", new String[]{"unmatched"}));
        assertEquals(List.of(), tabs.onTabComplete(player, command, "vf", new String[0]));
        assertEquals(List.of(), tabs.onTabComplete(player, command, "vf", new String[]{"admin", "x"}));
        assertEquals(List.of("quarantine"), tabs.onTabComplete(player, command, "vf", new String[]{"admin", "QU"}));
        assertEquals(List.of("bind"), tabs.onTabComplete(player, command, "vf", new String[]{"track", "bi"}));
        assertEquals(List.of("10"), tabs.onTabComplete(player, command, "vf", new String[]{"kill", "1"}));
        Vehicle wagon = mock(Vehicle.class); when(wagon.getId()).thenReturn("Wagon");
        Vehicle cart = mock(Vehicle.class); when(cart.getId()).thenReturn("cart");
        VehicleLoader.get().put("wagon", wagon); VehicleLoader.get().put("cart", cart);
        assertEquals(List.of("Wagon"), tabs.onTabComplete(player, command, "vf", new String[]{"spawn", "WA"}));
        TrackRegistry registry = mock(TrackRegistry.class);
        TrackSpline track = mock(TrackSpline.class);
        UUID id = UUID.fromString("abcdef00-0000-0000-0000-000000000001"); when(track.getId()).thenReturn(id);
        when(registry.inWorld("world")).thenReturn(List.of(track));
        framework.when(VehicleFramework::getTrackRegistry).thenReturn(registry);
        for (String action : List.of("delete", "clearance", "info", "particles", "bind")) {
            assertEquals(List.of(id.toString()), tabs.onTabComplete(player, command, "vf", new String[]{"track", action, "AB"}));
            assertEquals(List.of(), tabs.onTabComplete(player, command, "vf", new String[]{"track", action, "deadbeef"}));
        }
        var console = mock(CommandSender.class);
        when(console.hasPermission("vf.admin")).thenReturn(true);
        assertEquals(List.of("reload", "admin", "track"), tabs.onTabComplete(console, command, "vf", new String[]{""}));
        assertEquals(List.of("reload"), tabs.onTabComplete(console, command, "vf", new String[]{"RE"}));
        assertEquals(List.of("quarantine"), tabs.onTabComplete(console, command, "vf", new String[]{"admin", "Q"}));
        assertEquals(List.of("resync"), tabs.onTabComplete(console, command, "vf", new String[]{"track", "R"}));
        for (String[] args : new String[][]{{"spawn", ""}, {"track", "info", ""}, {"reload", ""}}) {
            assertEquals(List.of(), tabs.onTabComplete(console, command, "vf", args));
        }
        when(console.hasPermission("vf.admin")).thenReturn(false);
        assertEquals(List.of(), tabs.onTabComplete(console, command, "vf", new String[]{""}));
        when(command.getName()).thenReturn("other");
        assertEquals(List.of(), tabs.onTabComplete(console, command, "other", new String[]{""}));
    }

    @Test void completionsFollowPermissionsAndOfferKnownVehiclesAndWorldTracks() {
        TabCompletion completion = new TabCompletion();
        assertEquals(List.of(), completion.onTabComplete(mock(CommandSender.class), command, "vf", new String[]{""}));
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
        for (String verb : List.of("delete", "clearance", "info", "particles", "bind")) assertEquals(List.of(id.toString()), completion.onTabComplete(player, command, "vf", new String[]{"track", verb, ""}));
        assertNull(completion.onTabComplete(player, command, "vf", new String[]{"track", "unknown", ""}));
        assertNull(completion.onTabComplete(player, command, "vf", new String[]{"unknown", ""}));
        when(command.getName()).thenReturn("other"); assertNull(completion.onTabComplete(player, command, "other", new String[]{""}));
    }

    /** Stands in for TLibs 2.5.0's facade. */
    public static final class Lookup {
        public static List<CharacterInfo> findByName(String name) { return List.of(); }
    }

    private void charactersMode() {
        OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(kira));
        characters.when(() -> Characters.isCurrent(kira)).thenReturn(true);
        owner.setOwner(VehicleOwners.character(kira.ref().characterId()));
        characters.when(() -> Characters.info(kira.ref().characterId())).thenReturn(Optional.of(
                new CharacterInfo(kira.ref(), "Kira", CharacterStatus.ALIVE, null, Set.of(), 0, null)));
        OfflinePlayer account = mock(OfflinePlayer.class); when(account.getName()).thenReturn("Steve");
        bukkit.when(() -> Bukkit.getOfflinePlayer(kira.ref().ownerId())).thenReturn(account);
        OfflinePlayer other = mock(OfflinePlayer.class); when(other.getName()).thenReturn("BrenAccount");
        bukkit.when(() -> Bukkit.getOfflinePlayer(bren.ref().ownerId())).thenReturn(other);
    }

    @Test void characterOwnersSeeEveryControlAndTheirCharactersName() {
        charactersMode();
        ownership.ownershipGui(null, player, vehicle, true); Inventory menu = menus.get(0);
        for (int slot : new int[]{0, 2, 4, 6, 8}) assertNotEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(slot).getType(), "slot " + slot);
        assertEquals("§7Owner: §fKira", menu.getItem(OwnershipGUIManager.OWNER_SLOT).getItemMeta().getDisplayName());
        assertEquals(List.of("§7Click to pick a nearby player"), menu.getItem(2).getItemMeta().getLore());
        when(player.hasPermission("vf.admin")).thenReturn(true);
        ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals("§7Owner: §fKira (Steve)", menu.getItem(OwnershipGUIManager.OWNER_SLOT).getItemMeta().getDisplayName());
    }

    @Test void groupSwitchesShowTheOwnersGroupsAndAFixedFactionAsAlwaysOn() {
        charactersMode();
        UUID id = UUID.randomUUID(); when(vehicle.getUUID()).thenReturn(id.toString());
        String stamp = owner.getOwner();
        VehicleAccessGroups groups = mock(VehicleAccessGroups.class);
        when(groups.ready()).thenReturn(true);
        when(groups.alwaysOpen(id, stamp)).thenReturn(Set.of(AccessGroup.FACTION));
        when(groups.describe(AccessGroup.FACTION, id, stamp)).thenReturn(Optional.of("The Bog"));
        when(groups.describe(AccessGroup.GUILD, id, stamp)).thenReturn(Optional.of("Smiths"));
        when(groups.describe(AccessGroup.ALLY, id, stamp)).thenReturn(Optional.of("The Bog"));
        when(groups.describe(AccessGroup.VASSAL, id, stamp)).thenReturn(Optional.empty());
        when(services.load(VehicleAccessGroups.class)).thenReturn(groups);
        owner.toggleAccessGroup(AccessGroup.ALLY);
        Inventory menu = inventory(27, null); ownership.ownershipGui(menu, player, vehicle, false);
        ItemStack faction = menu.getItem(OwnershipGUIManager.groupSlot(AccessGroup.FACTION));
        assertEquals(Material.LIME_DYE, faction.getType()); assertEquals("§aFaction: ALWAYS ON", faction.getItemMeta().getDisplayName());
        assertEquals(List.of("§7Members of §fThe Bog§7 may board", "§7Counts only while whitelisting is on", "",
                "§7This vehicle always admits its faction"), faction.getItemMeta().getLore());
        ItemStack guild = menu.getItem(12);
        assertEquals("§7Guild: OFF", guild.getItemMeta().getDisplayName());
        assertEquals("§7Members of §fSmiths§7 may board", guild.getItemMeta().getLore().getFirst());
        assertEquals("§eClick to toggle", guild.getItemMeta().getLore().getLast());
        ItemStack allies = menu.getItem(14);
        assertEquals(Material.LIME_DYE, allies.getType()); assertEquals("§aAllies: ON", allies.getItemMeta().getDisplayName());
        assertEquals("§7Factions allied with §fThe Bog§7 may board", allies.getItemMeta().getLore().getFirst());
        assertEquals("§7The owner is not in a faction", menu.getItem(16).getItemMeta().getLore().getFirst());
        assertEquals("§7Vassals: OFF", menu.getItem(16).getItemMeta().getDisplayName());
        when(groups.describe(AccessGroup.GUILD, id, stamp)).thenReturn(Optional.empty());
        when(groups.describe(AccessGroup.VASSAL, id, stamp)).thenReturn(Optional.of("The Bog"));
        ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals("§7The owner is not in a guild", menu.getItem(12).getItemMeta().getLore().getFirst());
        assertEquals("§7Direct vassals of §fThe Bog§7 may board", menu.getItem(16).getItemMeta().getLore().getFirst());
        doAnswer(call -> {
            if (call.getArgument(0) instanceof VehicleOwnershipEditCheckEvent check
                    && check.getReason().orElse(null) == OwnershipReason.VASSAL_ACCESS_TOGGLE) check.deny();
            return null;
        }).when(plugins).callEvent(any(VehicleOwnershipEditCheckEvent.class));
        ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(16).getType(), "a vetoed switch is hidden");
        assertEquals(Material.LIME_DYE, menu.getItem(14).getType());
        Cache.allowWhitelist = false; ownership.ownershipGui(menu, player, vehicle, false);
        for (int slot : new int[]{10, 12, 14, 16}) assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(slot).getType(), "slot " + slot);
        Cache.allowWhitelist = true; when(services.load(VehicleAccessGroups.class)).thenReturn(null);
        ownership.ownershipGui(menu, player, vehicle, false);
        for (int slot : new int[]{10, 12, 14, 16}) assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(slot).getType(), "slot " + slot);
        assertEquals(Optional.of(AccessGroup.VASSAL), OwnershipGUIManager.groupAt(16));
        assertTrue(OwnershipGUIManager.groupAt(11).isEmpty());
    }

    @Test void controlsTheViewerMayNotUseAreHidden() {
        charactersMode();
        doAnswer(call -> {
            if (call.getArgument(0) instanceof VehicleOwnershipEditCheckEvent check
                    && check.getReason().orElse(null) == OwnershipReason.TICKETS_TOGGLE) check.deny();
            return null;
        }).when(plugins).callEvent(any(VehicleOwnershipEditCheckEvent.class));
        Inventory menu = inventory(27, null); ownership.ownershipGui(menu, player, vehicle, false);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(8).getType());
        assertEquals(Material.BARRIER, menu.getItem(6).getType());
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(bren));
        characters.when(() -> Characters.isCurrent(bren)).thenReturn(true);
        ownership.ownershipGui(menu, player, vehicle, false);
        for (int slot : new int[]{0, 2, 6, 8}) assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getItem(slot).getType(), "slot " + slot);
        assertEquals(Material.CHEST, menu.getItem(4).getType());
    }

    @Test void characterWhitelistsShowCapturedNamesAndGreyOutOldEntries() {
        charactersMode();
        String crew = VehicleOwners.whitelistEntry(bren.ref(), "Bren");
        owner.setWhiteList(new ArrayList<>(List.of(crew, "player_Old")));
        ownership.whitelistGui(null, player, vehicle, true); Inventory menu = menus.get(0);
        assertEquals("§eBren", menu.getItem(0).getItemMeta().getDisplayName());
        assertEquals(List.of("§cClick to remove from whitelist"), menu.getItem(0).getItemMeta().getLore());
        assertEquals(crew, menu.getItem(0).getItemMeta().getPersistentDataContainer().get(new NamespacedKey("vehicleframework", "vf_whitelist_entry"), PersistentDataType.STRING));
        assertEquals("§8Old", menu.getItem(1).getItemMeta().getDisplayName());
        assertEquals("§7Old entry: no longer grants access", menu.getItem(1).getItemMeta().getLore().getFirst());
        when(player.hasPermission("vf.admin")).thenReturn(true);
        ownership.whitelistGui(menu, player, vehicle, false);
        assertEquals("§7Account: §fBrenAccount", menu.getItem(0).getItemMeta().getLore().getFirst());
    }

    @Test void thePickerShowsUpToTwentyFiveHeadsAndTypedNamesOnlyWhenTLibsCanFindThem() {
        charactersMode();
        List<WhitelistPickerHolder.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(new WhitelistPickerHolder.Candidate(OwnershipFixture.session("a" + i, "c" + i, i), "Player " + i));
        OwnershipFixture.lookup(Lookup.class);
        ownership.pickerGui(player, vehicle, many); Inventory picker = menus.get(0);
        WhitelistPickerHolder holder = (WhitelistPickerHolder) picker.getHolder();
        assertEquals(VFGUI.WHITELIST_PICKER, holder.getType()); assertEquals(25, holder.getCandidates().size());
        assertEquals("§ePlayer 24", picker.getItem(24).getItemMeta().getDisplayName());
        assertEquals(Material.PLAYER_HEAD, picker.getItem(0).getType());
        assertEquals(Material.WRITABLE_BOOK, picker.getItem(OwnershipGUIManager.PICKER_TYPED_SLOT).getType());
        assertEquals("§7Back", picker.getItem(OwnershipGUIManager.BACK_SLOT).getItemMeta().getDisplayName());
        assertTrue(holder.candidate(25).isEmpty()); assertTrue(holder.candidate(-1).isEmpty());
        assertEquals("Player 3", holder.candidate(3).orElseThrow().name());
        verify(player).openInventory(picker);
        OwnershipFixture.lookup(String.class);
        ownership.pickerGui(player, vehicle, List.of()); Inventory empty = menus.get(1);
        assertEquals("§7Nobody nearby", empty.getItem(13).getItemMeta().getDisplayName());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, empty.getItem(OwnershipGUIManager.PICKER_TYPED_SLOT).getType());
    }

    @Test void findVehiclesListsTheCurrentCharactersVehiclesAndExplainsWhenItCannot() {
        charactersMode();
        assertTrue(run(player, "findvehicles"));
        verify(vehicles).listOwnedVehicles(VehicleOwners.character(kira.ref().characterId()));
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
        assertTrue(run(player, "findvehicles")); verify(player).sendMessage(OwnershipActions.NEED_CHARACTER);
        OwnershipFixture.mode(OwnershipIdentity.DISABLED);
        assertTrue(run(player, "findvehicles")); verify(player).sendMessage(OwnershipActions.DISABLED);
        verify(vehicles, times(1)).listOwnedVehicles(anyString());
    }

    @Test void staffCanNameTheTakeoverTargetAndListTheQuarantine() {
        assertNull(new TabCompletion().onTabComplete(player, command, "vf", new String[]{"admin", ""}));
        when(player.hasPermission("vf.admin")).thenReturn(true);
        assertEquals(List.of("quarantine"), new TabCompletion().onTabComplete(player, command, "vf", new String[]{"admin", ""}));
        assertTrue(new TabCompletion().onTabComplete(player, command, "vf", new String[]{""}).contains("admin"));
        assertTrue(run(player, "takeover", "Bren")); verify(vehicles).startTakeover(player, "Bren");
        assertTrue(run(player, "takeover", "Bren", "of", "the", "Vale")); verify(vehicles).startTakeover(player, "Bren of the Vale");
        assertEquals(List.of(), new TabCompletion().onTabComplete(player, command, "vf", new String[]{"takeover", "Br"}),
                "names mode offers no takeover targets");
        assertFalse(run(player, "admin", "other")); assertFalse(run(player, "admin"));
        when(services.getRegistrations(VehicleQuarantineSource.class)).thenReturn(List.of());
        assertTrue(run(player, "admin", "QUARANTINE"));
        verify(player).sendMessage(contains("No quarantine list is registered"));
        CommandSender console = mock(CommandSender.class); when(console.hasPermission("vf.admin")).thenReturn(true);
        assertTrue(run(console, "admin", "quarantine"));
        verify(console).sendMessage(contains("No quarantine list is registered"));
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
