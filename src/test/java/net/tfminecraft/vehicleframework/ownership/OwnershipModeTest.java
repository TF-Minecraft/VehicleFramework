package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.VehicleFrameworkApi;

class OwnershipModeTest {
	@AfterEach
	void reset() {
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	@Test
	void theKeyChoosesCharactersOrNamesWhateverItsCase() {
		try (MockedStatic<VFLogger> log = mockStatic(VFLogger.class)) {
			assertEquals(OwnershipIdentity.CHARACTERS, OwnershipMode.configure(" Characters ", true));
			assertTrue(OwnershipMode.characters());
			assertEquals(OwnershipIdentity.CHARACTERS, VehicleFrameworkApi.ownershipIdentity());
			log.verify(() -> VFLogger.info("Vehicle ownership identity: characters"));
			assertEquals(OwnershipIdentity.NAMES, OwnershipMode.configure("names", true));
			assertFalse(OwnershipMode.characters());
		}
	}

	@Test
	void aMissingKeyMeansNamesOnlyWithoutRpCharacters() {
		try (MockedStatic<VFLogger> log = mockStatic(VFLogger.class)) {
			assertEquals(OwnershipIdentity.NAMES, OwnershipMode.configure(null, false));
			assertEquals(OwnershipIdentity.NAMES, OwnershipMode.configure(" ", false));
			log.verify(() -> VFLogger.severe(anyString()), never());
			assertEquals(OwnershipIdentity.DISABLED, OwnershipMode.configure(null, true));
			log.verify(() -> VFLogger.severe(contains("ownership-identity is not set and RPCharacters is installed")));
			log.verify(() -> VFLogger.severe(contains("Set ownership-identity")));
		}
	}

	@Test
	void anUnknownValueRefusesOwnershipLoudly() {
		try (MockedStatic<VFLogger> log = mockStatic(VFLogger.class)) {
			assertEquals(OwnershipIdentity.DISABLED, OwnershipMode.configure("accounts", false));
			assertEquals(OwnershipIdentity.DISABLED, OwnershipMode.identity());
			log.verify(() -> VFLogger.severe(contains("unknown value 'accounts'")));
			log.verify(() -> VFLogger.severe(contains("VEHICLE OWNERSHIP IS DISABLED")));
		}
	}
}
