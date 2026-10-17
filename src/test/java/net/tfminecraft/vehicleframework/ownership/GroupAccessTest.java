package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.AccessGroup;
import net.tfminecraft.vehicleframework.api.OwnershipIdentity;
import net.tfminecraft.vehicleframework.api.VehicleAccessGroups;
import net.tfminecraft.vehicleframework.api.VehicleOwners;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class GroupAccessTest {
	private final ServicesManager services = mock(ServicesManager.class);
	private final VehicleAccessGroups groups = mock(VehicleAccessGroups.class);
	private final ActiveVehicle vehicle = mock(ActiveVehicle.class);
	private final OwnerData data = new OwnerData();
	private final UUID id = UUID.randomUUID();
	private final UUID kira = OwnershipFixture.uuid("kira");
	private final UUID rider = OwnershipFixture.uuid("rider");
	private final String owner = VehicleOwners.character(kira);
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<VFLogger> logger;

	@BeforeEach
	void setup() {
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getServicesManager).thenReturn(services);
		logger = mockStatic(VFLogger.class);
		when(services.load(VehicleAccessGroups.class)).thenReturn(groups);
		when(groups.ready()).thenReturn(true);
		when(groups.alwaysOpen(any(), any())).thenReturn(Set.of());
		data.setOwner(owner);
		when(vehicle.getOwnerData()).thenReturn(data);
		when(vehicle.getUUID()).thenReturn(id.toString());
		OwnershipFixture.mode(OwnershipIdentity.CHARACTERS);
	}

	@AfterEach
	void cleanup() {
		logger.close();
		bukkit.close();
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
	}

	@Test
	void aSwitchAdmitsOnlyRidersTheServiceCountsInThatGroup() {
		AccessKey key = AccessKey.character(rider);
		when(groups.admits(AccessGroup.GUILD, id, owner, rider)).thenReturn(true);
		assertFalse(GroupAccess.admits(vehicle, key), "no switch is on");
		data.toggleAccessGroup(AccessGroup.FACTION);
		assertFalse(GroupAccess.admits(vehicle, key));
		data.toggleAccessGroup(AccessGroup.GUILD);
		assertTrue(GroupAccess.admits(vehicle, key));
		assertTrue(OwnershipAccess.admits(vehicle, key));
		assertFalse(GroupAccess.admits(vehicle, null));
		assertFalse(GroupAccess.admits(vehicle, AccessKey.name("Alex")), "account names have no groups");
	}

	@Test
	void groupsTheServiceAlwaysOpensCountWithoutTheirSwitch() {
		when(groups.alwaysOpen(id, owner)).thenReturn(Set.of(AccessGroup.FACTION));
		when(groups.admits(AccessGroup.FACTION, id, owner, rider)).thenReturn(true);
		assertEquals(Set.of(AccessGroup.FACTION), GroupAccess.alwaysOpen(vehicle));
		assertTrue(GroupAccess.admits(vehicle, AccessKey.character(rider)));
		when(groups.alwaysOpen(id, owner)).thenReturn(null);
		assertEquals(Set.of(), GroupAccess.alwaysOpen(vehicle));
	}

	@Test
	void ownersStillPassAndStrangersFailWithoutAnyGroup() {
		assertTrue(OwnershipAccess.admits(vehicle, AccessKey.character(kira)));
		assertFalse(OwnershipAccess.admits(vehicle, AccessKey.character(rider)));
	}

	@Test
	void unownedVehiclesUnreadableIdsAndOtherModesHaveNoGroups() {
		data.toggleAccessGroup(AccessGroup.FACTION);
		when(groups.admits(any(), any(), any(), any())).thenReturn(true);
		when(groups.describe(any(), any(), any())).thenReturn(Optional.of("The Bog"));
		AccessKey key = AccessKey.character(rider);
		data.setOwner(VehicleOwners.NONE);
		assertFalse(GroupAccess.admits(vehicle, key));
		assertTrue(GroupAccess.alwaysOpen(vehicle).isEmpty());
		assertTrue(GroupAccess.describe(vehicle, AccessGroup.FACTION).isEmpty());
		data.setOwner(owner);
		when(vehicle.getUUID()).thenReturn("not-a-uuid");
		assertFalse(GroupAccess.admits(vehicle, key));
		when(vehicle.getUUID()).thenReturn(id.toString());
		assertTrue(GroupAccess.admits(vehicle, key));
		assertTrue(GroupAccess.alwaysOpen(null).isEmpty());
		when(vehicle.getOwnerData()).thenReturn(null);
		assertTrue(GroupAccess.alwaysOpen(vehicle).isEmpty());
		when(vehicle.getOwnerData()).thenReturn(data);
		OwnershipFixture.mode(OwnershipIdentity.NAMES);
		assertTrue(GroupAccess.service().isEmpty());
		assertFalse(GroupAccess.admits(vehicle, key));
	}

	@Test
	void aMissingUnreadyOrBrokenServiceAdmitsNobody() {
		data.toggleAccessGroup(AccessGroup.FACTION);
		AccessKey key = AccessKey.character(rider);
		when(groups.admits(any(), any(), any(), any())).thenReturn(true);
		when(groups.ready()).thenReturn(false);
		assertTrue(GroupAccess.service().isEmpty());
		assertFalse(GroupAccess.admits(vehicle, key));
		assertTrue(GroupAccess.alwaysOpen(vehicle).isEmpty());
		assertTrue(GroupAccess.describe(vehicle, AccessGroup.FACTION).isEmpty());
		when(services.load(VehicleAccessGroups.class)).thenReturn(null);
		assertTrue(GroupAccess.service().isEmpty());
		when(services.load(VehicleAccessGroups.class)).thenThrow(new NoClassDefFoundError("old plugin"));
		assertTrue(GroupAccess.service().isEmpty());
		logger.verify(() -> VFLogger.log(contains("unavailable")));
	}

	@Test
	void serviceErrorsAreLoggedAndCountAsNo() {
		data.toggleAccessGroup(AccessGroup.FACTION);
		data.toggleAccessGroup(AccessGroup.ALLY);
		when(groups.admits(eq(AccessGroup.FACTION), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
		when(groups.admits(eq(AccessGroup.ALLY), any(), any(), any())).thenReturn(true);
		assertTrue(GroupAccess.admits(vehicle, AccessKey.character(rider)), "one failing group does not hide another");
		when(groups.admits(eq(AccessGroup.ALLY), any(), any(), any())).thenThrow(new LinkageError("gone"));
		assertFalse(GroupAccess.admits(vehicle, AccessKey.character(rider)));
		when(groups.alwaysOpen(any(), any())).thenThrow(new IllegalStateException("boom"));
		assertTrue(GroupAccess.alwaysOpen(vehicle).isEmpty());
		when(groups.describe(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
		assertTrue(GroupAccess.describe(vehicle, AccessGroup.GUILD).isEmpty());
		logger.verify(() -> VFLogger.log(contains("Could not check FACTION access")), times(2));
		logger.verify(() -> VFLogger.log(contains("Could not read the fixed groups")));
		logger.verify(() -> VFLogger.log(contains("Could not name the GUILD")));
	}

	@Test
	void namesAreTrimmedToNonBlankText() {
		when(groups.describe(AccessGroup.GUILD, id, owner)).thenReturn(Optional.of("Smiths"));
		assertEquals("Smiths", GroupAccess.describe(vehicle, AccessGroup.GUILD).orElseThrow());
		when(groups.describe(AccessGroup.GUILD, id, owner)).thenReturn(Optional.of(" "));
		assertTrue(GroupAccess.describe(vehicle, AccessGroup.GUILD).isEmpty());
		when(groups.describe(AccessGroup.GUILD, id, owner)).thenReturn(null);
		assertTrue(GroupAccess.describe(vehicle, AccessGroup.GUILD).isEmpty());
	}

	@Test
	void theInterfaceDefaultsAreReadyWithNoFixedGroupsOrNames() {
		VehicleAccessGroups bare = (group, vehicleId, owner, rider) -> false;
		assertTrue(bare.ready());
		assertEquals(Set.of(), bare.alwaysOpen(id, owner));
		assertTrue(bare.describe(AccessGroup.FACTION, id, owner).isEmpty());
	}
}
