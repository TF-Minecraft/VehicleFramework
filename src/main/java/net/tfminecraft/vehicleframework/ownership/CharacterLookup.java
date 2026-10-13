package net.tfminecraft.vehicleframework.ownership;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.vehicleframework.VFLogger;

/** TLibs character calls, with the typed-name lookup probed once (TLibs 2.5.0). */
public final class CharacterLookup {
	private static Optional<Method> findByName;

	private CharacterLookup() {
	}

	/** The player's session, if it is current. */
	public static Optional<CharacterSession> session(Player player) {
		if (player == null) {
			return Optional.empty();
		}
		return Characters.currentSession(player).filter(Characters::isCurrent);
	}

	public static boolean isCurrent(CharacterSession session) {
		return session != null && Characters.isCurrent(session);
	}

	public static Optional<CharacterInfo> info(UUID characterId) {
		return characterId == null ? Optional.empty() : Characters.info(characterId);
	}

	public static String displayName(Player player) {
		return Characters.displayName(player);
	}

	/** Whether typed character names can be looked up. */
	public static boolean typedNamesAvailable() {
		return probe().isPresent();
	}

	/** Living characters whose real name is {@code name}; empty without the lookup. */
	public static List<CharacterInfo> findLiving(String name) {
		Optional<Method> method = probe();
		if (method.isEmpty() || name == null || name.isBlank()) {
			return List.of();
		}
		List<CharacterInfo> living = new ArrayList<>();
		try {
			Object result = method.get().invoke(null, name.trim());
			if (result instanceof List<?> list) {
				for (Object entry : list) {
					if (entry instanceof CharacterInfo info && info.alive()) {
						living.add(info);
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ex) {
			VFLogger.log("Character name lookup failed: " + ex);
			return List.of();
		}
		return living;
	}

	/** Probes once, logging the result. */
	public static synchronized Optional<Method> probe() {
		if (findByName == null) {
			findByName = probe(Characters.class);
			if (findByName.isEmpty()) {
				VFLogger.info("TLibs has no Characters.findByName (needs 2.5.0): typed whitelist names are off; "
						+ "the nearby picker still works.");
			}
		}
		return findByName;
	}

	static Optional<Method> probe(Class<?> facade) {
		try {
			Method method = facade.getMethod("findByName", String.class);
			return List.class.isAssignableFrom(method.getReturnType()) ? Optional.of(method) : Optional.empty();
		} catch (ReflectiveOperationException | LinkageError ex) {
			return Optional.empty();
		}
	}

	/** Replaces the probed lookup; for tests. */
	static synchronized void useLookup(Class<?> facade) {
		findByName = facade == null ? null : probe(facade);
	}
}
