package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.database.VehiclePersistence;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.events.VehicleOwnerClaimedEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangeEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipChangedEvent;
import net.tfminecraft.vehicleframework.events.VehicleOwnershipEditCheckEvent;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.managers.inventory.WhitelistPickerHolder;
import net.tfminecraft.vehicleframework.managers.inventory.WhitelistPickerHolder.Candidate;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions;
import net.tfminecraft.vehicleframework.ownership.OwnershipFixture;
import net.tfminecraft.vehicleframework.ownership.PendingActions.Kind;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class OwnershipManagerTest {
	@org.junit.jupiter.api.BeforeAll
	static void initializeSounds() {
		net.tfminecraft.vehicleframework.test.RegistryFixture.initialize();
	}

	/** Stands in for TLibs 2.5.0's facade. */
	public static final class Lookup {
		static final List<CharacterInfo> results = new ArrayList<>();
		static String asked;

		public static List<CharacterInfo> findByName(String name) {
			asked = name;
			return results;
		}
	}

	private final List<AutoCloseable> scopes = new ArrayList<>();
	private final List<Event> events = new ArrayList<>();
	private final List<Runnable> tasks = new ArrayList<>();
	private final PluginManager plugins = mock(PluginManager.class);
	private final ServicesManager services = mock(ServicesManager.class);
	private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
	private final VehiclePersistence persistence = mock(VehiclePersistence.class);
	private final VehicleManager vehicles = mock(VehicleManager.class);
	private final World world = mock(World.class);
	private final List<Player> online = new ArrayList<>();
	private MockedStatic<Characters> characters;
	private MockedConstruction<OwnershipGUIManager> guis;
	private OwnershipManager manager;
	private OwnershipGUIManager gui;
	private VehicleFramework previousPlugin;
	private boolean previousAllowWhitelist;

	private Player alex, staff, bystander;
	private final CharacterSession kira = OwnershipFixture.session("alex", "kira", 1);
	private final CharacterSession warden = OwnershipFixture.session("staff", "warden", 2);
	private final CharacterSession bren = OwnershipFixture.session("other", "bren", 3);

	@BeforeEach
	void setup() {
		previousPlugin = VehicleFramework.plugin;
		VehicleFramework plugin = mock(VehicleFramework.class);
		when(plugin.getName()).thenReturn("VehicleFramework"); when(plugin.namespace()).thenReturn("vehicleframework");
		VehicleFramework.plugin = plugin;
		MockedStatic<Bukkit> bukkit = keep(mockStatic(Bukkit.class));
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		bukkit.when(Bukkit::getServicesManager).thenReturn(services);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> online);
		doAnswer(call -> events.add(call.getArgument(0))).when(plugins).callEvent(any(Event.class));
		when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(call -> task(call.getArgument(1)));
		when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenAnswer(call -> task(call.getArgument(1)));
		characters = keep(mockStatic(Characters.class));
		keep(mockStatic(VFLogger.class));
		keep(mockStatic(VehiclePersistence.class)).when(VehiclePersistence::current).thenReturn(persistence);
		when(persistence.saveLive(any(ActiveVehicle.class))).thenReturn(true);
		guis = keep(mockConstruction(OwnershipGUIManager.class));
		alex = player("Alex", kira, 0); staff = player("Warden", warden, 2); bystander = player("Bren", bren, 5);
		when(staff.hasPermission("vf.admin")).thenReturn(true);
		previousAllowWhitelist = Cache.allowWhitelist; Cache.allowWhitelist = true;
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
		OwnershipFixture.lookup(Lookup.class);
		Lookup.results.clear();
		manager = new OwnershipManager(vehicles);
		gui = guis.constructed().getFirst();
	}

	@AfterEach
	void cleanup() throws Exception {
		for (int i = scopes.size() - 1; i >= 0; i--) scopes.get(i).close();
		VehicleFramework.plugin = previousPlugin;
		Cache.allowWhitelist = previousAllowWhitelist;
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		OwnershipFixture.lookup(null);
	}

	private <T extends AutoCloseable> T keep(T scope) {
		scopes.add(scope);
		return scope;
	}

	private BukkitTask task(Runnable runnable) {
		tasks.add(runnable);
		BukkitTask task = mock(BukkitTask.class);
		when(task.getTaskId()).thenReturn(tasks.size());
		return task;
	}

	private Player player(String name, CharacterSession session, double x) {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		when(player.getUniqueId()).thenReturn(session.ref().ownerId());
		when(player.getWorld()).thenReturn(world);
		when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
		playing(player, session);
		characters.when(() -> Characters.displayName(player)).thenReturn("Masked " + name);
		online.add(player);
		return player;
	}

	private void playing(Player player, CharacterSession session) {
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.ofNullable(session));
		if (session != null) characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
	}

	private ActiveVehicle vehicle(String owner) {
		ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
		OwnerData data = new OwnerData();
		data.setOwner(owner);
		when(vehicle.getOwnerData()).thenReturn(data);
		when(vehicle.getUUID()).thenReturn(UUID.randomUUID().toString());
		when(vehicle.getName()).thenReturn("Cart");
		when(vehicle.ticketSource()).thenReturn(vehicle);
		when(vehicle.getEntity().getLocation()).thenReturn(new Location(world, 0, 64, 0));
		when(vehicle.isDestroyed()).thenReturn(false);
		when(vehicles.get(vehicle.getEntity())).thenReturn(vehicle);
		return vehicle;
	}

	private static String owner(CharacterSession session) {
		return VehicleOwners.character(session.ref().characterId());
	}

	private ItemStack item(Material material, String entry) {
		ItemStack item = mock(ItemStack.class, RETURNS_DEEP_STUBS);
		when(item.getType()).thenReturn(material);
		when(item.getItemMeta().getPersistentDataContainer().get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(entry);
		return item;
	}

	private InventoryClickEvent click(Player player, InventoryHolder holder, int slot, ItemStack item) {
		InventoryClickEvent event = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
		Inventory top = mock(Inventory.class);
		when(top.getHolder()).thenReturn(holder);
		when(event.getView().getTopInventory()).thenReturn(top);
		when(event.getWhoClicked()).thenReturn(player);
		when(event.getCurrentItem()).thenReturn(item);
		when(event.getSlot()).thenReturn(slot);
		return event;
	}

	private InventoryClickEvent menu(Player player, ActiveVehicle v, int slot) {
		return click(player, new VFInventoryHolder(v.getUUID(), VFGUI.OWNERSHIP, v), slot, item(Material.PAPER, null));
	}

	private <T extends Event> List<T> fired(Class<T> type) {
		return events.stream().filter(type::isInstance).map(type::cast).toList();
	}

	private void chat(Player player, String message) {
		AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, player, message, new HashSet<>());
		manager.chat(chat);
		assertTrue(chat.isCancelled());
		tasks.removeLast().run();
	}

	private static CharacterInfo info(CharacterSession session, String name) {
		return new CharacterInfo(session.ref(), name, CharacterStatus.ALIVE, null, Set.of(), 0, Instant.EPOCH);
	}

	// ── Claims ──────────────────────────────────────────────────────────────

	@Test
	void aClaimStampsTheCurrentCharacterAndDropsThePreviousCrew() {
		ActiveVehicle v = vehicle("none");
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of("char_old")));
		v.getOwnerData().setWhiteListed(true);
		assertTrue(manager.claim(alex, v));
		assertEquals(owner(kira), v.getOwnerData().getOwner());
		assertTrue(v.getOwnerData().getWhiteList().isEmpty()); assertFalse(v.getOwnerData().isWhiteListed());
		VehicleOwnershipChangeEvent pre = fired(VehicleOwnershipChangeEvent.class).getFirst();
		assertEquals(OwnershipReason.CLAIM, pre.getReason()); assertEquals(kira.ref(), pre.getActor().orElseThrow());
		assertEquals(kira.ref(), pre.getTarget().orElseThrow());
		assertEquals(owner(kira), fired(VehicleOwnerClaimedEvent.class).getFirst().getNewOwner());
		assertTrue(fired(VehicleOwnershipChangedEvent.class).getFirst().isSuccess());
		verify(persistence).saveLive(v);
	}

	@Test
	void claimsFailClosedWithoutACharacterOnOwnedVehiclesAndWithoutTheAuthority() {
		ActiveVehicle v = vehicle("none");
		playing(alex, null);
		assertFalse(manager.claim(alex, v));
		playing(alex, kira);
		for (String owned : List.of("char_none", "player_Alex", owner(bren))) {
			v.getOwnerData().setOwner(owned);
			assertFalse(manager.claim(alex, v), owned);
		}
		v.getOwnerData().setOwner("none");
		when(plugins.getPlugin("SimpleFactions")).thenReturn(mock(Plugin.class));
		assertFalse(manager.claim(alex, v));
		assertTrue(events.isEmpty());
		assertTrue(manager.claim(staff, v), "staff are not held back by the missing authority");
		characters.when(() -> Characters.isCurrent(any())).thenReturn(false);
		v.getOwnerData().setOwner("none");
		assertFalse(manager.claim(staff, v), "a provider that reports no current session refuses");
	}

	@Test
	void namesModeClaimsKeepTodaysAccountNamesAndWhitelist() {
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		ActiveVehicle v = vehicle("none");
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of("player_Bob")));
		v.getOwnerData().setWhiteListed(true);
		assertTrue(manager.claim(alex, v));
		assertEquals("player_Alex", v.getOwnerData().getOwner());
		assertEquals(List.of("player_Bob"), v.getOwnerData().getWhiteList()); assertTrue(v.getOwnerData().isWhiteListed());
		assertTrue(fired(VehicleOwnershipChangeEvent.class).getFirst().getActor().isEmpty());
		characters.verifyNoInteractions();
	}

	@Test
	void aClaimThatCannotBeSavedIsUndoneAndReported() {
		ActiveVehicle v = vehicle("none");
		when(persistence.saveLive(v)).thenReturn(false);
		assertFalse(manager.claim(alex, v));
		assertEquals("none", v.getOwnerData().getOwner());
		verify(alex).sendMessage(OwnershipActions.SAVE_FAILED);
	}

	// ── Ownership menu ──────────────────────────────────────────────────────

	@Test
	void everyMenuClickChecksTheOwnerAgainSoAStaleMenuIsRefused() {
		ActiveVehicle v = vehicle(owner(kira));
		InventoryClickEvent toggle = menu(alex, v, 0);
		manager.ownershipClick(toggle);
		verify(toggle).setCancelled(true);
		assertTrue(v.getOwnerData().isWhiteListed());
		verify(gui).ownershipGui(toggle.getView().getTopInventory(), alex, v, false);
		v.getOwnerData().setOwner(owner(warden));
		manager.ownershipClick(menu(alex, v, 6));
		verify(alex).sendMessage(OwnershipActions.NOT_OWNER);
		assertEquals(owner(warden), v.getOwnerData().getOwner());
		playing(alex, OwnershipFixture.session("alex", "alt", 4));
		v.getOwnerData().setOwner(owner(kira));
		manager.ownershipClick(menu(alex, v, 6));
		verify(alex, times(2)).sendMessage(OwnershipActions.NOT_OWNER);
		assertEquals(owner(kira), v.getOwnerData().getOwner(), "a sibling character on the same account is a stranger");
	}

	@Test
	void clearingOwnershipEmptiesTheCrewInCharactersModeOnly() {
		ActiveVehicle v = vehicle(owner(kira));
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of("char_crew"))); v.getOwnerData().setWhiteListed(true);
		manager.ownershipClick(menu(alex, v, 6));
		assertEquals("none", v.getOwnerData().getOwner());
		assertTrue(v.getOwnerData().getWhiteList().isEmpty()); assertFalse(v.getOwnerData().isWhiteListed());
		verify(alex).sendMessage("§7Ownership of §fCart§7 has been removed.");
		assertEquals(OwnershipReason.CLEAR, fired(VehicleOwnershipChangeEvent.class).getFirst().getReason());
		assertEquals("none", fired(VehicleOwnershipChangeEvent.class).getFirst().getNewOwner());
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		ActiveVehicle named = vehicle("player_Alex");
		named.getOwnerData().setWhiteList(new ArrayList<>(List.of("player_Bob"))); named.getOwnerData().setWhiteListed(true);
		manager.ownershipClick(menu(alex, named, 6));
		assertEquals("none", named.getOwnerData().getOwner());
		assertEquals(List.of("player_Bob"), named.getOwnerData().getWhiteList()); assertFalse(named.getOwnerData().isWhiteListed());
	}

	@Test
	void theTicketsToggleChangesAndChecksTheLocomotive() {
		ActiveVehicle car = vehicle(owner(kira)), loco = vehicle(owner(bren));
		when(car.ticketSource()).thenReturn(loco);
		manager.ownershipClick(menu(alex, car, 8));
		verify(alex).sendMessage(OwnershipActions.NOT_OWNER);
		assertFalse(loco.getOwnerData().isTicketsEnabled());
		loco.getOwnerData().setOwner(owner(kira));
		manager.ownershipClick(menu(alex, car, 8));
		assertTrue(loco.getOwnerData().isTicketsEnabled()); assertFalse(car.getOwnerData().isTicketsEnabled());
		VehicleOwnershipChangeEvent pre = fired(VehicleOwnershipChangeEvent.class).getFirst();
		assertSame(loco, pre.getVehicle()); assertEquals(OwnershipReason.TICKETS_TOGGLE, pre.getReason());
		assertSame(loco, fired(VehicleOwnershipEditCheckEvent.class).getLast().getVehicle());
	}

	@Test
	void anEditCheckVetoRefusesTheClickBeforeAnyChange() {
		ActiveVehicle v = vehicle(owner(kira));
		doAnswer(call -> {
			if (call.getArgument(0) instanceof VehicleOwnershipEditCheckEvent check) check.deny();
			return null;
		}).when(plugins).callEvent(any(VehicleOwnershipEditCheckEvent.class));
		manager.ownershipClick(menu(alex, v, 0));
		verify(alex).sendMessage(OwnershipActions.NOT_ALLOWED);
		assertFalse(v.getOwnerData().isWhiteListed());
		assertTrue(fired(VehicleOwnershipChangeEvent.class).isEmpty());
	}

	@Test
	void theWhitelistViewIsForTheOwnerOrStaff() {
		ActiveVehicle v = vehicle(owner(kira));
		manager.ownershipClick(menu(alex, v, 4));
		verify(gui).whitelistGui(null, alex, v, true);
		manager.ownershipClick(menu(staff, v, 4));
		verify(gui).whitelistGui(null, staff, v, true);
		manager.ownershipClick(menu(bystander, v, 4));
		verify(bystander).sendMessage(OwnershipActions.NOT_OWNER);
		playing(bystander, null);
		manager.ownershipClick(menu(bystander, v, 4));
		verify(bystander).sendMessage(OwnershipActions.NEED_CHARACTER);
		verify(gui, never()).whitelistGui(null, bystander, v, true);
	}

	@Test
	void menuClicksIgnoreOtherMenusFillerAndDestroyedVehicles() {
		ActiveVehicle v = vehicle(owner(kira));
		InventoryClickEvent other = click(alex, new VFInventoryHolder(v.getUUID(), VFGUI.SEAT_SELECTION, v), 0, item(Material.PAPER, null));
		manager.ownershipClick(other); verify(other, never()).setCancelled(true);
		InventoryClickEvent foreign = click(alex, mock(InventoryHolder.class), 0, item(Material.PAPER, null));
		manager.whitelistClick(foreign); verify(foreign, never()).setCancelled(true);
		InventoryClickEvent detached = click(alex, new VFInventoryHolder(v.getUUID(), VFGUI.OWNERSHIP), 0, item(Material.PAPER, null));
		manager.ownershipClick(detached); verify(detached).setCancelled(true);
		manager.ownershipClick(click(alex, new VFInventoryHolder(v.getUUID(), VFGUI.OWNERSHIP, v), 0, null));
		manager.ownershipClick(click(alex, new VFInventoryHolder(v.getUUID(), VFGUI.OWNERSHIP, v), 0, item(Material.GRAY_STAINED_GLASS_PANE, null)));
		manager.ownershipClick(menu(alex, v, 13));
		assertFalse(v.getOwnerData().isWhiteListed());
		when(v.isDestroyed()).thenReturn(true);
		manager.ownershipClick(menu(alex, v, 0));
		verify(alex).closeInventory();
		assertTrue(events.isEmpty());
	}

	@Test
	void openingTheMenuFromTheSeatMenuNeedsOwnershipOrStaff() {
		ActiveVehicle v = vehicle(owner(kira));
		manager.openMenu(alex, v);
		verify(gui).ownershipGui(null, alex, v, true);
		manager.openMenu(bystander, v);
		verify(gui, never()).ownershipGui(null, bystander, v, true);
		assertSame(gui, manager.getGui()); assertNotNull(manager.getPending());
	}

	// ── Whitelist ───────────────────────────────────────────────────────────

	@Test
	void removingAnEntryNamesItsCharacterOrRawLegacyValue() {
		ActiveVehicle v = vehicle(owner(kira));
		String crew = VehicleOwners.whitelistEntry(bren.ref(), "Bren");
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of(crew, "player_Old")));
		VFInventoryHolder holder = new VFInventoryHolder(v.getUUID(), VFGUI.WHITELIST, v);
		InventoryClickEvent remove = click(alex, holder, 0, item(Material.PLAYER_HEAD, crew));
		manager.whitelistClick(remove);
		verify(alex).sendMessage("§eRemoved §fBren§e from the whitelist.");
		VehicleOwnershipChangeEvent pre = fired(VehicleOwnershipChangeEvent.class).getFirst();
		assertEquals(OwnershipReason.WHITELIST_REMOVE, pre.getReason());
		assertEquals(bren.ref(), pre.getTarget().orElseThrow()); assertEquals(crew, pre.getRawEntry().orElseThrow());
		manager.whitelistClick(click(alex, holder, 1, item(Material.PLAYER_HEAD, "player_Old")));
		verify(alex).sendMessage("§eRemoved §fOld§e from the whitelist.");
		assertTrue(v.getOwnerData().getWhiteList().isEmpty());
		assertTrue(fired(VehicleOwnershipChangeEvent.class).getLast().getTarget().isEmpty());
		manager.whitelistClick(click(alex, holder, 2, item(Material.PLAYER_HEAD, null)));
		manager.whitelistClick(click(alex, holder, OwnershipGUIManager.BACK_SLOT, item(Material.BARRIER, null)));
		verify(gui).ownershipGui(null, alex, v, true);
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of(crew)));
		manager.whitelistClick(click(bystander, holder, 0, item(Material.PLAYER_HEAD, crew)));
		verify(bystander).sendMessage(OwnershipActions.NOT_OWNER);
		assertEquals(List.of(crew), v.getOwnerData().getWhiteList());
	}

	@Test
	void thePickerOffersNearbyAndBoardPlayersWithACharacterWhoAreNotListed() {
		ActiveVehicle v = vehicle(owner(kira));
		Player far = player("Far", OwnershipFixture.session("far", "far", 9), 40);
		Player aboard = player("Aboard", OwnershipFixture.session("aboard", "aboard", 10), 60);
		when(v.isPassenger(aboard, true)).thenReturn(true);
		Player lobby = player("Lobby", OwnershipFixture.session("lobby", "lobby", 11), 1);
		playing(lobby, null);
		Player listed = player("Listed", OwnershipFixture.session("listed", "listed", 12), 3);
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of(VehicleOwners.whitelistEntry(
				OwnershipFixture.session("listed", "listed", 12).ref(), "Listed"), "player_Junk")));
		Player elsewhere = player("Elsewhere", OwnershipFixture.session("else", "else", 13), 0);
		when(elsewhere.getWorld()).thenReturn(mock(World.class));
		List<Candidate> found = manager.candidates(v);
		assertEquals(List.of("Masked Warden", "Masked Bren", "Masked Aboard"), found.stream().map(Candidate::name).toList());
		assertEquals(warden, found.getFirst().session());
		assertFalse(found.stream().anyMatch(c -> c.name().contains("Far") || c.name().contains("Lobby") || c.name().contains("Listed")));
		when(v.getEntity().getLocation()).thenReturn(new Location(null, 0, 64, 0));
		assertEquals(List.of("Masked Aboard"), manager.candidates(v).stream().map(Candidate::name).toList());
		assertNotNull(far); assertNotNull(listed);
	}

	@Test
	void addToWhitelistOpensThePickerInCharactersModeAndChatInNamesMode() {
		ActiveVehicle v = vehicle(owner(kira));
		manager.ownershipClick(menu(alex, v, 2));
		verify(gui).pickerGui(eq(alex), eq(v), anyList());
		manager.ownershipClick(menu(bystander, v, 2));
		verify(bystander).sendMessage(OwnershipActions.NOT_OWNER);
		Cache.allowWhitelist = false;
		manager.ownershipClick(menu(alex, v, 2));
		verify(gui, times(1)).pickerGui(eq(alex), eq(v), anyList());
		Cache.allowWhitelist = true;
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		ActiveVehicle named = vehicle("player_Alex");
		manager.ownershipClick(menu(alex, named, 2));
		verify(alex).sendMessage("§aType the player name in chat to add them to the whitelist. Type §ccancel §ato abort.");
		chat(alex, " Bob ");
		assertEquals(List.of("player_Bob"), named.getOwnerData().getWhiteList());
		verify(alex).sendMessage("§aAdded §fBob§a to the whitelist.");
		manager.ownershipClick(menu(alex, named, 2)); chat(alex, "Bob");
		verify(alex).sendMessage("§cBob is already on the whitelist.");
		manager.ownershipClick(menu(alex, named, 2)); chat(alex, "CANCEL");
		verify(alex).sendMessage("§cCancelled.");
		assertNull(fired(VehicleOwnershipChangeEvent.class).getFirst().getTarget().orElse(null));
		assertEquals("player_Bob", fired(VehicleOwnershipChangeEvent.class).getFirst().getRawEntry().orElseThrow());
	}

	@Test
	void pickingAPlayerStoresTheirCharacterAndTheNameShownWhenPicked() {
		ActiveVehicle v = vehicle(owner(kira));
		String existing = VehicleOwners.whitelistEntry(warden.ref(), "Warden");
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of(existing)));
		WhitelistPickerHolder holder = new WhitelistPickerHolder(v, List.of(new Candidate(bren, "Masked Bren")));
		manager.pickerClick(click(bystander, holder, 0, item(Material.PLAYER_HEAD, null)));
		verify(bystander).sendMessage(OwnershipActions.NOT_OWNER);
		manager.pickerClick(click(alex, holder, 0, item(Material.PLAYER_HEAD, null)));
		String entry = VehicleOwners.whitelistEntry(bren.ref(), "Masked Bren");
		assertEquals(List.of(existing, entry), v.getOwnerData().getWhiteList());
		verify(alex).sendMessage("§aAdded §fMasked Bren§a to the whitelist.");
		verify(gui).pickerGui(eq(alex), eq(v), anyList());
		VehicleOwnershipChangeEvent pre = fired(VehicleOwnershipChangeEvent.class).getFirst();
		assertEquals(OwnershipReason.WHITELIST_ADD, pre.getReason()); assertEquals(bren.ref(), pre.getTarget().orElseThrow());
		manager.pickerClick(click(alex, holder, 0, item(Material.PLAYER_HEAD, null)));
		verify(alex).sendMessage("§cMasked Bren is already on the whitelist.");
		manager.pickerClick(click(alex, holder, 7, item(Material.PAPER, null)));
		characters.when(() -> Characters.isCurrent(bren)).thenReturn(false);
		v.getOwnerData().setWhiteList(new ArrayList<>());
		manager.pickerClick(click(alex, holder, 0, item(Material.PLAYER_HEAD, null)));
		verify(alex).sendMessage("§cThat player is no longer playing the same character.");
		assertTrue(v.getOwnerData().getWhiteList().isEmpty());
		manager.pickerClick(click(alex, holder, OwnershipGUIManager.BACK_SLOT, item(Material.BARRIER, null)));
		verify(gui).ownershipGui(null, alex, v, true);
	}

	@Test
	void typedNamesNeedExactlyOneLivingMatchAndRefuseZeroAndManyAlike() {
		ActiveVehicle v = vehicle(owner(kira));
		WhitelistPickerHolder holder = new WhitelistPickerHolder(v, List.of());
		InventoryClickEvent typed = click(alex, holder, OwnershipGUIManager.PICKER_TYPED_SLOT, item(Material.WRITABLE_BOOK, null));
		manager.pickerClick(typed);
		assertTrue(manager.getPending().has(alex, Kind.WHITELIST_ADD));
		chat(alex, "Nobody");
		String refusal = String.format(OwnershipManager.NO_SINGLE_MATCH, "Nobody");
		verify(alex).sendMessage(refusal);
		Lookup.results.addAll(List.of(info(bren, "Bren"), info(warden, "Bren")));
		manager.pickerClick(typed); chat(alex, "Bren");
		verify(alex).sendMessage(String.format(OwnershipManager.NO_SINGLE_MATCH, "Bren"));
		Lookup.results.removeLast();
		manager.pickerClick(typed); chat(alex, "bren");
		assertEquals(List.of(VehicleOwners.whitelistEntry(bren.ref(), "Bren")), v.getOwnerData().getWhiteList());
		manager.pickerClick(click(bystander, holder, OwnershipGUIManager.PICKER_TYPED_SLOT, item(Material.WRITABLE_BOOK, null)));
		verify(bystander).sendMessage(OwnershipActions.NOT_OWNER);
		OwnershipFixture.lookup(String.class);
		manager.pickerClick(typed);
		verify(alex).sendMessage(OwnershipManager.TYPED_NAMES_OFF);
	}

	@Test
	void aPendingWhitelistEntryEndsWithTheSessionThatStartedIt() {
		ActiveVehicle v = vehicle(owner(kira));
		InventoryClickEvent typed = click(alex, new WhitelistPickerHolder(v, List.of()), OwnershipGUIManager.PICKER_TYPED_SLOT, item(Material.WRITABLE_BOOK, null));
		manager.pickerClick(typed);
		manager.sessionEnd(new CharacterSessionEndEvent(alex, kira, CharacterSessionEndEvent.Reason.SWITCH));
		verify(alex).sendMessage("§cYour pending vehicle action was cancelled.");
		assertFalse(manager.getPending().has(alex, Kind.WHITELIST_ADD));
		AsyncPlayerChatEvent ignored = new AsyncPlayerChatEvent(true, alex, "Bren", new HashSet<>());
		manager.chat(ignored); assertFalse(ignored.isCancelled());
		manager.sessionEnd(new CharacterSessionEndEvent(alex, kira, CharacterSessionEndEvent.Reason.QUIT));
		verify(alex, times(1)).sendMessage("§cYour pending vehicle action was cancelled.");
		manager.pickerClick(typed);
		characters.when(() -> Characters.isCurrent(kira)).thenReturn(false);
		Lookup.results.add(info(bren, "Bren"));
		chat(alex, "Bren");
		verify(alex).sendMessage(OwnershipManager.SESSION_CHANGED);
		assertTrue(v.getOwnerData().getWhiteList().isEmpty());
		characters.when(() -> Characters.isCurrent(kira)).thenReturn(true);
		manager.pickerClick(typed);
		assertTrue(manager.getPending().has(alex, Kind.WHITELIST_ADD));
		PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(alex);
		manager.quit(quit);
		assertFalse(manager.getPending().has(alex, Kind.WHITELIST_ADD));
	}

	@Test
	void aPendingWhitelistEntryForAStaleVehicleChangesAndSavesNothing() {
		ActiveVehicle v = vehicle(owner(kira));
		InventoryClickEvent typed = click(alex, new WhitelistPickerHolder(v, List.of()), OwnershipGUIManager.PICKER_TYPED_SLOT, item(Material.WRITABLE_BOOK, null));
		Lookup.results.add(info(bren, "Bren"));
		manager.pickerClick(typed);
		when(v.isDestroyed()).thenReturn(true);
		chat(alex, "Bren");
		when(v.isDestroyed()).thenReturn(false);
		manager.pickerClick(typed);
		when(vehicles.get(v.getEntity())).thenReturn(mock(ActiveVehicle.class));
		chat(alex, "Bren");
		manager.pickerClick(typed);
		when(vehicles.get(v.getEntity())).thenReturn(null);
		chat(alex, "Bren");
		verify(alex, times(3)).sendMessage(OwnershipManager.VEHICLE_GONE);
		assertTrue(v.getOwnerData().getWhiteList().isEmpty());
		verify(persistence, never()).saveLive(any(ActiveVehicle.class));
		assertTrue(fired(VehicleOwnershipChangeEvent.class).isEmpty());
	}

	// ── Rename ──────────────────────────────────────────────────────────────

	@Test
	void renamingRecordsTheSessionAndAppliesOnlyWhileItIsCurrent() {
		ActiveVehicle v = vehicle("none");
		manager.startRename(alex, v);
		verify(alex).sendTitle("", "§eType the §aName §ein chat", 10, 80, 10);
		chat(alex, "Express_Cart");
		verify(v).setName("Express Cart");
		manager.startRename(alex, v);
		characters.when(() -> Characters.isCurrent(kira)).thenReturn(false);
		chat(alex, "Stolen");
		verify(alex).sendMessage(OwnershipManager.SESSION_CHANGED);
		verify(v, never()).setName("Stolen");
		playing(alex, null);
		manager.startRename(alex, v);
		verify(alex).sendMessage(OwnershipActions.NEED_CHARACTER);
		playing(alex, kira);
		manager.startRename(alex, v);
		when(vehicles.get(v.getEntity())).thenReturn(null);
		chat(alex, "Gone");
		verify(v, never()).setName("Gone");
		verify(alex).sendMessage(OwnershipManager.VEHICLE_GONE);
		tasks.getFirst().run();
		verify(alex, never()).sendMessage("§cNaming timed out.");
	}

	// ── Takeover ────────────────────────────────────────────────────────────

	@Test
	void staffTakeoverOfAnOwnedVehicleGoesThroughTheTakeoverEvent() {
		ActiveVehicle v = vehicle(owner(kira));
		v.getOwnerData().setWhiteList(new ArrayList<>(List.of("char_crew"))); v.getOwnerData().setWhiteListed(true);
		assertFalse(manager.takeover(staff, v));
		manager.startTakeover(staff, null);
		verify(staff).sendMessage("§aRight-click a vehicle within 60 seconds to claim ownership.");
		assertTrue(manager.takeover(staff, v));
		assertEquals(owner(warden), v.getOwnerData().getOwner());
		assertTrue(v.getOwnerData().getWhiteList().isEmpty());
		VehicleOwnershipChangeEvent pre = fired(VehicleOwnershipChangeEvent.class).getFirst();
		assertEquals(OwnershipReason.TAKEOVER, pre.getReason()); assertTrue(pre.isStaff());
		assertEquals(warden.ref(), pre.getTarget().orElseThrow());
		assertTrue(fired(VehicleOwnerClaimedEvent.class).isEmpty());
		verify(staff).sendMessage("§aYou are now the owner of §eCart§a.");
	}

	@Test
	void staffTakeoverOfAnUnownedVehicleIsAClaimAndCanNameAnotherCharacter() {
		Lookup.results.add(info(bren, "Bren"));
		manager.startTakeover(staff, "Bren");
		verify(staff).sendMessage("§aRight-click a vehicle within 60 seconds to give it to §eBren§a.");
		ActiveVehicle v = vehicle("none");
		assertTrue(manager.takeover(staff, v));
		assertEquals(owner(bren), v.getOwnerData().getOwner());
		assertEquals(OwnershipReason.CLAIM, fired(VehicleOwnershipChangeEvent.class).getFirst().getReason());
		assertEquals(1, fired(VehicleOwnerClaimedEvent.class).size());
		verify(staff).sendMessage("§eCart§a now belongs to §eBren§a.");
		Lookup.results.clear();
		manager.startTakeover(staff, "Bren");
		verify(staff).sendMessage(String.format(OwnershipManager.NO_SINGLE_MATCH, "Bren"));
		OwnershipFixture.lookup(String.class);
		manager.startTakeover(staff, "Bren");
		verify(staff).sendMessage(OwnershipManager.TYPED_NAMES_OFF);
	}

	@Test
	void aTakeoverIsReverifiedWhenTheVehicleIsClicked() {
		ActiveVehicle v = vehicle(owner(kira));
		manager.startTakeover(staff, null);
		when(staff.hasPermission("vf.admin")).thenReturn(false);
		assertTrue(manager.takeover(staff, v));
		verify(staff).sendMessage(OwnershipActions.NOT_ALLOWED);
		when(staff.hasPermission("vf.admin")).thenReturn(true);
		manager.startTakeover(staff, null);
		characters.when(() -> Characters.isCurrent(warden)).thenReturn(false);
		assertTrue(manager.takeover(staff, v));
		verify(staff).sendMessage(OwnershipManager.SESSION_CHANGED);
		CharacterSession next = OwnershipFixture.session("staff", "warden", 20);
		manager.startTakeover(staff, null);
		assertEquals(OwnershipActions.NEED_CHARACTER, captureLast(staff));
		playing(staff, next);
		manager.startTakeover(staff, null);
		OwnershipFixture.mode(OwnershipIdentity.DISABLED);
		assertTrue(manager.takeover(staff, v));
		verify(staff).sendMessage(OwnershipActions.DISABLED);
		assertEquals(owner(kira), v.getOwnerData().getOwner());
		manager.startTakeover(staff, null);
		verify(staff, times(2)).sendMessage(OwnershipActions.DISABLED);
	}

	private String captureLast(Player player) {
		var captor = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(player, atLeastOnce()).sendMessage(captor.capture());
		return captor.getValue();
	}

	@Test
	void takeoverNamesMayContainSpacesAndAreMatchedWhole() {
		Lookup.results.add(info(bren, "Bren of the Vale"));
		manager.startTakeover(staff, "  Bren   of the  Vale ");
		assertEquals("Bren of the Vale", Lookup.asked);
		verify(staff).sendMessage("§aRight-click a vehicle within 60 seconds to give it to §eBren of the Vale§a.");
		ActiveVehicle v = vehicle(owner(kira));
		assertTrue(manager.takeover(staff, v));
		assertEquals(owner(bren), v.getOwnerData().getOwner());
		manager.startTakeover(staff, " ");
		verify(staff).sendMessage(OwnershipManager.TAKEOVER_USAGE);
	}

	@Test
	void takeoverAcceptsACharacterIdForAnyKnownCharacterEvenWithoutTypedNames() {
		OwnershipFixture.lookup(String.class);
		UUID id = bren.ref().characterId();
		CharacterInfo dead = new CharacterInfo(bren.ref(), "Bren", CharacterStatus.DEAD, null, Set.of(), 0, Instant.EPOCH);
		characters.when(() -> Characters.info(id)).thenReturn(Optional.of(dead));
		for (String form : List.of("id:" + id, "ID: " + id, id.toString().toUpperCase())) {
			ActiveVehicle v = vehicle(owner(kira));
			manager.startTakeover(staff, form);
			assertTrue(manager.takeover(staff, v), form);
			assertEquals(owner(bren), v.getOwnerData().getOwner(), form);
		}
		UUID unknown = UUID.randomUUID();
		manager.startTakeover(staff, "id:" + unknown);
		verify(staff).sendMessage(String.format(OwnershipManager.UNKNOWN_ID, unknown));
		manager.startTakeover(staff, "id:bren");
		verify(staff).sendMessage(String.format(OwnershipManager.NOT_AN_ID, "id:bren"));
		manager.startTakeover(staff, "Bren");
		verify(staff).sendMessage(OwnershipManager.TYPED_NAMES_OFF);
		assertFalse(manager.getPending().has(staff, Kind.TAKEOVER));
	}

	@Test
	void takeoverCompletionOffersIdsAndOnlineCharacterNamesWordByWord() {
		characters.when(() -> Characters.info(kira.ref().characterId())).thenReturn(Optional.of(info(kira, "Kira of the Vale")));
		characters.when(() -> Characters.info(bren.ref().characterId())).thenReturn(Optional.of(info(bren, "Bren")));
		assertEquals(List.of("Kira of the Vale"), OwnershipManager.takeoverSuggestions(new String[]{"k"}));
		assertEquals(List.of("id:"), OwnershipManager.takeoverSuggestions(new String[]{"I"}));
		assertEquals(List.of("id:", "Kira of the Vale", "Bren"), OwnershipManager.takeoverSuggestions(new String[]{""}));
		assertEquals(List.of("of the Vale"), OwnershipManager.takeoverSuggestions(new String[]{"kira", ""}));
		assertEquals(List.of("Vale"), OwnershipManager.takeoverSuggestions(new String[]{"Kira", "of", "the", "V"}));
		assertTrue(OwnershipManager.takeoverSuggestions(new String[]{"Kira", "x"}).isEmpty());
		assertTrue(OwnershipManager.takeoverSuggestions(new String[0]).isEmpty());
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		assertTrue(OwnershipManager.takeoverSuggestions(new String[]{""}).isEmpty());
	}

	@Test
	void namesModeTakeoverUsesTheAccountNameAndRefusesTypedTargets() {
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		manager.startTakeover(staff, "Bren");
		verify(staff).sendMessage(OwnershipManager.NAMES_MODE_TAKEOVER);
		ActiveVehicle v = vehicle("player_Other");
		manager.startTakeover(staff, null);
		manager.takeover(staff, v);
		assertEquals("player_Warden", v.getOwnerData().getOwner());
		tasks.getFirst().run();
		verify(staff, never()).sendMessage("§cTakeover expired.");
		manager.startTakeover(staff, null);
		tasks.getLast().run();
		verify(staff).sendMessage("§cTakeover expired.");
	}
}
