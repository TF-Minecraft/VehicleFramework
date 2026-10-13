package net.tfminecraft.vehicleframework.managers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.vehicleframework.VehicleFramework;
import net.tfminecraft.vehicleframework.api.OwnershipReason;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi.Result;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.api.WhitelistEntry;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.enums.VFGUI;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.managers.inventory.WhitelistPickerHolder;
import net.tfminecraft.vehicleframework.ownership.CharacterLookup;
import net.tfminecraft.vehicleframework.ownership.OwnerNames;
import net.tfminecraft.vehicleframework.ownership.OwnershipAccess;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions.Actor;
import net.tfminecraft.vehicleframework.ownership.OwnershipActions.Outcome;
import net.tfminecraft.vehicleframework.ownership.OwnershipMode;
import net.tfminecraft.vehicleframework.ownership.PendingActions;
import net.tfminecraft.vehicleframework.ownership.PendingActions.Kind;
import net.tfminecraft.vehicleframework.ownership.PendingActions.Pending;
import net.tfminecraft.vehicleframework.ownership.PendingActions.Target;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

/**
 * Claims, the ownership and whitelist menus, takeovers and the chat actions behind them. Every
 * click checks again that the player owns the vehicle (or is staff) before anything changes.
 */
public class OwnershipManager implements Listener {
	public static final double PICKER_RADIUS = 16.0;
	public static final String NO_SINGLE_MATCH = "§cNo single living character is called §f%s§c.";
	public static final String TYPED_NAMES_OFF = "§cTyped character names are not available on this server.";
	public static final String SESSION_CHANGED = "§cYour character changed, so nothing was done.";
	public static final String ID_PREFIX = "id:";
	public static final String TAKEOVER_USAGE = "§cUsage: /vf takeover [character name | id:<character uuid>]";
	public static final String NAMES_MODE_TAKEOVER = "§cThis server identifies owners by account name, so a takeover is always for yourself: use /vf takeover.";
	public static final String NOT_AN_ID = "§f%s§c is not a character ID. Use id:<character uuid>.";
	public static final String UNKNOWN_ID = "§cNo character has the ID §f%s§c.";

	private final VehicleManager vehicles;
	private final OwnershipGUIManager gui = new OwnershipGUIManager();
	private final PendingActions pending = new PendingActions();

	public OwnershipManager(VehicleManager vehicles) {
		this.vehicles = vehicles;
	}

	public OwnershipGUIManager getGui() {
		return gui;
	}

	public PendingActions getPending() {
		return pending;
	}

	// ── Claims and takeovers ─────────────────────────────────────────────────

	/** Claims an unowned vehicle for the player's current character (or name in names mode). */
	public boolean claim(Player p, ActiveVehicle v) {
		if (!VehicleOwners.isUnowned(v.getOwnerData().getOwner())) {
			return false;
		}
		Outcome<Actor> actor = OwnershipActions.actor(p);
		if (actor.refusal() != null || !OwnershipActions.authorityAllows(actor.value())) {
			return false;
		}
		String owner = actor.value().ownerValue();
		return write(actor.value(), v, OwnershipReason.CLAIM, actor.value().ref(), null, owned(owner)) == Result.OK;
	}

	/**
	 * Arms a staff takeover. Without a target the vehicle goes to the staff member's own character.
	 * In characters mode the target may be a character ID ({@code id:<uuid>} or a bare UUID, any
	 * known character) or a real name, which must match exactly one living character.
	 */
	public void startTakeover(Player p, String targetName) {
		Outcome<Actor> actor = OwnershipActions.actor(p);
		if (actor.refusal() != null) {
			p.sendMessage(actor.refusal());
			return;
		}
		Target target;
		if (targetName == null) {
			target = new Target(actor.value().ownerValue(), actor.value().ref(), null);
		} else {
			Optional<Target> named = takeoverTarget(p, targetName.trim().replaceAll("\\s+", " "));
			if (named.isEmpty()) {
				return;
			}
			target = named.get();
		}
		pending.start(p, Kind.TAKEOVER, null, actor.value().session(), target);
		if (target.name() == null) {
			p.sendMessage("§aRight-click a vehicle within 60 seconds to claim ownership.");
		} else {
			p.sendMessage("§aRight-click a vehicle within 60 seconds to give it to §e" + target.name() + "§a.");
		}
	}

	/** Resolves a named takeover target, telling the player why when it cannot. */
	private Optional<Target> takeoverTarget(Player p, String target) {
		if (target.isEmpty()) {
			p.sendMessage(TAKEOVER_USAGE);
			return Optional.empty();
		}
		if (!OwnershipMode.characters()) {
			p.sendMessage(NAMES_MODE_TAKEOVER);
			return Optional.empty();
		}
		Optional<UUID> id = characterId(target);
		if (id.isPresent() || target.regionMatches(true, 0, ID_PREFIX, 0, ID_PREFIX.length())) {
			if (id.isEmpty()) {
				p.sendMessage(String.format(NOT_AN_ID, target));
				return Optional.empty();
			}
			Optional<CharacterInfo> info = CharacterLookup.info(id.get());
			if (info.isEmpty()) {
				p.sendMessage(String.format(UNKNOWN_ID, id.get()));
				return Optional.empty();
			}
			return Optional.of(target(info.get()));
		}
		if (!CharacterLookup.typedNamesAvailable()) {
			p.sendMessage(TYPED_NAMES_OFF);
			return Optional.empty();
		}
		Optional<CharacterInfo> found = single(target);
		if (found.isEmpty()) {
			p.sendMessage(String.format(NO_SINGLE_MATCH, target));
			return Optional.empty();
		}
		return Optional.of(target(found.get()));
	}

	private static Target target(CharacterInfo info) {
		return new Target(VehicleOwners.character(info.ref().characterId()), info.ref(), info.name());
	}

	/**
	 * Tab completions for {@code /vf takeover}: {@code id:} and the real names of online players'
	 * current characters. The typed words are the arguments after "takeover"; a suggestion
	 * continues from the last typed word, so names with spaces complete word by word.
	 */
	public static List<String> takeoverSuggestions(String[] typedWords) {
		List<String> suggestions = new ArrayList<>();
		if (!OwnershipMode.characters() || typedWords.length == 0) {
			return suggestions;
		}
		String typed = String.join(" ", typedWords).toLowerCase(Locale.ROOT);
		if (typedWords.length == 1 && ID_PREFIX.startsWith(typed)) {
			suggestions.add(ID_PREFIX);
		}
		for (Player other : Bukkit.getOnlinePlayers()) {
			CharacterLookup.session(other)
					.flatMap(session -> CharacterLookup.info(session.ref().characterId()))
					.map(CharacterInfo::name)
					.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
					.ifPresent(name -> {
						String[] words = name.split(" ", -1);
						if (words.length >= typedWords.length) {
							suggestions.add(String.join(" ",
									Arrays.copyOfRange(words, typedWords.length - 1, words.length)));
						}
					});
		}
		return suggestions;
	}

	/** The UUID of {@code id:<uuid>} or a bare UUID; empty for anything else. */
	static Optional<UUID> characterId(String target) {
		String value = target.regionMatches(true, 0, ID_PREFIX, 0, ID_PREFIX.length())
				? target.substring(ID_PREFIX.length()).trim()
				: target;
		return VehicleOwners.characterId(VehicleOwners.CHARACTER_PREFIX + value);
	}

	/** Applies a pending takeover to the clicked vehicle; false when none is pending. */
	public boolean takeover(Player p, ActiveVehicle v) {
		Optional<Pending> taken = pending.take(p, Kind.TAKEOVER);
		if (taken.isEmpty()) {
			return false;
		}
		Pending action = taken.get();
		if (!Permissions.isAdmin(p)) {
			p.sendMessage(OwnershipActions.NOT_ALLOWED);
			return true;
		}
		if (!PendingActions.stillCurrent(action)) {
			p.sendMessage(SESSION_CHANGED);
			return true;
		}
		Outcome<Actor> actor = OwnershipActions.actor(p);
		if (actor.refusal() != null) {
			p.sendMessage(actor.refusal());
			return true;
		}
		Target target = action.target();
		OwnershipReason reason = VehicleOwners.isUnowned(v.getOwnerData().getOwner())
				? OwnershipReason.CLAIM
				: OwnershipReason.TAKEOVER;
		Result result = write(actor.value(), v, reason, target.character(), null, owned(target.ownerValue()));
		if (result == Result.OK) {
			p.sendMessage(target.name() == null
					? "§aYou are now the owner of §e" + v.getName() + "§a."
					: "§e" + v.getName() + "§a now belongs to §e" + target.name() + "§a.");
		}
		return true;
	}

	/** Starts renaming with a name tag. */
	@SuppressWarnings("deprecation")
	public void startRename(Player p, ActiveVehicle v) {
		CharacterSession session = OwnershipAccess.session(p).orElse(null);
		if (OwnershipMode.characters() && session == null) {
			p.sendMessage(OwnershipActions.NEED_CHARACTER);
			return;
		}
		pending.start(p, Kind.RENAME, v, session, null);
		p.sendTitle("", "§eType the §aName §ein chat", 10, 80, 10);
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
	}

	/** Opens the ownership menu from the seat menu if the player may see it. */
	public void openMenu(Player p, ActiveVehicle v) {
		if (OwnershipActions.mayOpenMenu(p, v)) {
			p.closeInventory();
			gui.ownershipGui(null, p, v, true);
		}
	}

	// ── Menus ────────────────────────────────────────────────────────────────

	@EventHandler
	public void ownershipClick(InventoryClickEvent e) {
		ActiveVehicle v = menuVehicle(e, VFGUI.OWNERSHIP);
		if (v == null) return;
		Player p = (Player) e.getWhoClicked();
		switch (e.getSlot()) {
			case 0 -> {
				if (change(p, v, OwnershipReason.WHITELIST_TOGGLE, null, null,
						data -> data.setWhiteListed(!data.isWhiteListed()))) {
					gui.ownershipGui(e.getView().getTopInventory(), p, v, false);
				}
			}
			case 2 -> startWhitelistAdd(p, v);
			case 4 -> {
				p.closeInventory();
				if (mayView(p, v)) {
					gui.whitelistGui(null, p, v, true);
				}
			}
			case 6 -> {
				if (change(p, v, OwnershipReason.CLEAR, null, null, data -> {
					data.setOwner(VehicleOwners.NONE);
					data.setWhiteListed(false);
					if (OwnershipMode.characters()) data.setWhiteList(new ArrayList<>());
				})) {
					p.closeInventory();
					p.sendMessage("§7Ownership of §f" + v.getName() + "§7 has been removed.");
				}
			}
			case 8 -> {
				if (change(p, v.ticketSource(), OwnershipReason.TICKETS_TOGGLE, null, null, OwnerData::toggleTickets)) {
					gui.ownershipGui(e.getView().getTopInventory(), p, v, false);
				}
			}
			default -> {
			}
		}
	}

	@EventHandler
	public void whitelistClick(InventoryClickEvent e) {
		ActiveVehicle v = menuVehicle(e, VFGUI.WHITELIST);
		if (v == null) return;
		Player p = (Player) e.getWhoClicked();
		if (e.getSlot() == OwnershipGUIManager.BACK_SLOT) {
			p.closeInventory();
			openMenu(p, v);
			return;
		}
		NamespacedKey key = new NamespacedKey(VehicleFramework.plugin, "vf_whitelist_entry");
		String entry = e.getCurrentItem().getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
		if (entry == null) return;
		CharacterRef target = VehicleOwners.parseWhitelistEntry(entry).map(WhitelistEntry::character).orElse(null);
		if (change(p, v, OwnershipReason.WHITELIST_REMOVE, target, entry, data -> data.removeFromWhiteList(entry))) {
			p.sendMessage("§eRemoved §f" + entryName(entry) + "§e from the whitelist.");
			gui.whitelistGui(e.getView().getTopInventory(), p, v, false);
		}
	}

	@EventHandler
	public void pickerClick(InventoryClickEvent e) {
		ActiveVehicle v = menuVehicle(e, VFGUI.WHITELIST_PICKER);
		if (v == null) return;
		Player p = (Player) e.getWhoClicked();
		WhitelistPickerHolder holder = (WhitelistPickerHolder) e.getView().getTopInventory().getHolder();
		int slot = e.getSlot();
		if (slot == OwnershipGUIManager.BACK_SLOT) {
			p.closeInventory();
			openMenu(p, v);
			return;
		}
		if (slot == OwnershipGUIManager.PICKER_TYPED_SLOT) {
			startTypedAdd(p, v);
			return;
		}
		Optional<WhitelistPickerHolder.Candidate> candidate = holder.candidate(slot);
		if (candidate.isEmpty()) return;
		CharacterSession session = candidate.get().session();
		if (!CharacterLookup.isCurrent(session)) {
			p.sendMessage("§cThat player is no longer playing the same character.");
			gui.pickerGui(p, v, candidates(v));
			return;
		}
		if (addCharacter(p, v, session.ref(), candidate.get().name())) {
			gui.pickerGui(p, v, candidates(v));
		}
	}

	// ── Chat actions ─────────────────────────────────────────────────────────

	// Retain Bukkit chat-event ordering and String message semantics for existing integrations.
	@SuppressWarnings("deprecation")
	@EventHandler
	public void chat(AsyncPlayerChatEvent e) {
		Player p = e.getPlayer();
		boolean whitelist = pending.has(p, Kind.WHITELIST_ADD);
		if (!whitelist && !pending.has(p, Kind.RENAME)) return;
		e.setCancelled(true);
		String message = e.getMessage();
		Bukkit.getScheduler().runTask(VehicleFramework.plugin, () -> {
			if (whitelist) {
				pending.take(p, Kind.WHITELIST_ADD).ifPresent(action -> applyWhitelistAdd(p, action, message));
			} else {
				pending.take(p, Kind.RENAME).ifPresent(action -> applyRename(p, action, message));
			}
		});
	}

	@EventHandler
	public void quit(PlayerQuitEvent e) {
		pending.clear(e.getPlayer());
	}

	@EventHandler
	public void sessionEnd(CharacterSessionEndEvent e) {
		if (pending.clear(e.getPlayer())) {
			e.getPlayer().sendMessage("§cYour pending vehicle action was cancelled.");
		}
	}

	private void startWhitelistAdd(Player p, ActiveVehicle v) {
		if (!Cache.allowWhitelist) return;
		Optional<String> refused = OwnershipActions.refusal(p, v, OwnershipReason.WHITELIST_ADD);
		if (refused.isPresent()) {
			p.sendMessage(refused.get());
			return;
		}
		if (OwnershipMode.characters()) {
			gui.pickerGui(p, v, candidates(v));
			return;
		}
		p.closeInventory();
		pending.start(p, Kind.WHITELIST_ADD, v, null, null);
		p.sendMessage("§aType the player name in chat to add them to the whitelist. Type §ccancel §ato abort.");
	}

	private void startTypedAdd(Player p, ActiveVehicle v) {
		if (!CharacterLookup.typedNamesAvailable()) {
			p.sendMessage(TYPED_NAMES_OFF);
			return;
		}
		Optional<String> refused = OwnershipActions.refusal(p, v, OwnershipReason.WHITELIST_ADD);
		if (refused.isPresent()) {
			p.sendMessage(refused.get());
			return;
		}
		p.closeInventory();
		pending.start(p, Kind.WHITELIST_ADD, v, OwnershipAccess.session(p).orElse(null), null);
		p.sendMessage("§aType the character's name in chat to add them to the whitelist. Type §ccancel §ato abort.");
	}

	private void applyWhitelistAdd(Player p, Pending action, String message) {
		if (message.equalsIgnoreCase("cancel")) {
			p.sendMessage("§cCancelled.");
			return;
		}
		if (!PendingActions.stillCurrent(action)) {
			p.sendMessage(SESSION_CHANGED);
			return;
		}
		ActiveVehicle v = action.vehicle();
		String name = message.trim();
		if (OwnershipMode.characters()) {
			Optional<CharacterInfo> found = single(name);
			if (found.isEmpty()) {
				p.sendMessage(String.format(NO_SINGLE_MATCH, name));
				return;
			}
			addCharacter(p, v, found.get().ref(), found.get().name());
			return;
		}
		String entry = VehicleOwners.LEGACY_PREFIX + name;
		if (v.getOwnerData().getWhiteList().contains(entry)) {
			p.sendMessage("§c" + name + " is already on the whitelist.");
			return;
		}
		if (change(p, v, OwnershipReason.WHITELIST_ADD, null, entry, data -> data.addToWhiteList(entry))) {
			p.sendMessage("§aAdded §f" + name + "§a to the whitelist.");
		}
	}

	private boolean addCharacter(Player p, ActiveVehicle v, CharacterRef character, String name) {
		for (String existing : v.getOwnerData().getWhiteList()) {
			Optional<WhitelistEntry> parsed = VehicleOwners.parseWhitelistEntry(existing);
			if (parsed.isPresent() && parsed.get().character().characterId().equals(character.characterId())) {
				p.sendMessage("§c" + name + " is already on the whitelist.");
				return false;
			}
		}
		String entry = VehicleOwners.whitelistEntry(character, name);
		if (change(p, v, OwnershipReason.WHITELIST_ADD, character, entry, data -> data.addToWhiteList(entry))) {
			p.sendMessage("§aAdded §f" + name + "§a to the whitelist.");
			return true;
		}
		return false;
	}

	private void applyRename(Player p, Pending action, String message) {
		if (!PendingActions.stillCurrent(action)) {
			p.sendMessage(SESSION_CHANGED);
			return;
		}
		ActiveVehicle v = action.vehicle();
		if (vehicles.get(v.getEntity()) == null) return;
		v.setName(StringFormatter.formatHex(message.replace("_", " ")));
		p.sendMessage("§aRenamed the vehicle to " + v.getName());
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/** Players with a current character within 16 blocks of the vehicle or aboard it, not yet listed. */
	List<WhitelistPickerHolder.Candidate> candidates(ActiveVehicle v) {
		Location centre = v.getEntity().getLocation();
		Set<UUID> listed = new HashSet<>();
		VehicleOwners.characterId(v.getOwnerData().getOwner()).ifPresent(listed::add);
		for (String entry : v.getOwnerData().getWhiteList()) {
			VehicleOwners.parseWhitelistEntry(entry).ifPresent(parsed -> listed.add(parsed.character().characterId()));
		}
		List<WhitelistPickerHolder.Candidate> found = new ArrayList<>();
		for (Player other : Bukkit.getOnlinePlayers()) {
			boolean near = centre.getWorld() != null && centre.getWorld().equals(other.getWorld())
					&& other.getLocation().distanceSquared(centre) <= PICKER_RADIUS * PICKER_RADIUS;
			if (!near && !v.isPassenger(other, true)) continue;
			Optional<CharacterSession> session = CharacterLookup.session(other);
			if (session.isEmpty() || !listed.add(session.get().ref().characterId())) continue;
			found.add(new WhitelistPickerHolder.Candidate(session.get(), CharacterLookup.displayName(other)));
		}
		return found;
	}

	private static Optional<CharacterInfo> single(String name) {
		List<CharacterInfo> found = CharacterLookup.findLiving(name);
		return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
	}

	private static boolean mayView(Player p, ActiveVehicle v) {
		Outcome<Actor> actor = OwnershipActions.actor(p);
		if (actor.refusal() != null) {
			p.sendMessage(actor.refusal());
			return false;
		}
		if (!OwnershipActions.controls(actor.value(), v)) {
			p.sendMessage(OwnershipActions.NOT_OWNER);
			return false;
		}
		return true;
	}

	private static String entryName(String entry) {
		return VehicleOwners.parseWhitelistEntry(entry).map(WhitelistEntry::name).orElseGet(() -> OwnerNames.legacy(entry));
	}

	/** The owner a claim or takeover writes; characters mode also empties the whitelist and turns it off. */
	private static Consumer<OwnerData> owned(String owner) {
		return data -> {
			data.setOwner(owner);
			if (OwnershipMode.characters()) {
				data.setWhiteList(new ArrayList<>());
				data.setWhiteListed(false);
			}
		};
	}

	/** Re-checks the player, then runs the change through the events and the writer. */
	private boolean change(Player p, ActiveVehicle target, OwnershipReason reason, CharacterRef character,
			String entry, Consumer<OwnerData> mutation) {
		Optional<String> refused = OwnershipActions.refusal(p, target, reason);
		if (refused.isPresent()) {
			p.sendMessage(refused.get());
			return false;
		}
		Actor actor = OwnershipActions.actor(p).value();
		return write(actor, target, reason, character, entry, mutation) == Result.OK;
	}

	private static Result write(Actor actor, ActiveVehicle v, OwnershipReason reason, CharacterRef character,
			String entry, Consumer<OwnerData> mutation) {
		Optional<Result> result = OwnershipActions.perform(
				new OwnershipActions.Change(actor, v, reason, character, entry, mutation));
		if (result.isPresent() && result.get() == Result.SAVE_FAILED) {
			actor.player().sendMessage(OwnershipActions.SAVE_FAILED);
		}
		return result.orElse(null);
	}

	private static ActiveVehicle menuVehicle(InventoryClickEvent e, VFGUI type) {
		if (!(e.getView().getTopInventory().getHolder() instanceof VFInventoryHolder h)) return null;
		if (!h.getType().equals(type)) return null;
		e.setCancelled(true);
		if (h.getVehicle().isEmpty()) return null;
		ActiveVehicle v = h.getVehicle().get();
		if (v.isDestroyed()) {
			e.getWhoClicked().closeInventory();
			return null;
		}
		ItemStack item = e.getCurrentItem();
		if (item == null || item.getType().equals(Material.GRAY_STAINED_GLASS_PANE)) return null;
		return v;
	}
}
