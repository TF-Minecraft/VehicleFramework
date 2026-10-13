package net.tfminecraft.vehicleframework.ownership;

import java.lang.reflect.Field;
import java.util.UUID;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;

/** Sets the identity mode without the startup log, and builds character sessions. */
public final class OwnershipFixture {
	private OwnershipFixture() {
	}

	public static void mode(OwnershipIdentity identity) {
		try {
			Field field = OwnershipMode.class.getDeclaredField("identity");
			field.setAccessible(true);
			field.set(null, identity);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static CharacterSession session(String account, String character, long id) {
		return new CharacterSession(new CharacterRef(uuid(account), uuid(character)), id);
	}

	public static UUID uuid(String seed) {
		return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** Resets the typed-name probe; a facade without findByName turns typed names off. */
	public static void lookup(Class<?> facade) {
		CharacterLookup.useLookup(facade);
	}
}
