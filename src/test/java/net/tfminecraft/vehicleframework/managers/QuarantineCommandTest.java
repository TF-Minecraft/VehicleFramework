package net.tfminecraft.vehicleframework.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.api.OwnershipMeta;
import net.tfminecraft.vehicleframework.api.QuarantinedVehicle;
import net.tfminecraft.vehicleframework.api.VehicleQuarantineSource;
import net.tfminecraft.vehicleframework.ownership.OwnershipStore;

class QuarantineCommandTest {
	private static final UUID STORED = UUID.fromString("11111111-2222-4333-8444-555555555555");
	private static final UUID MISSING = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
	private final ServicesManager services = mock(ServicesManager.class);
	private final CommandSender staff = mock(CommandSender.class);
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<VFLogger> log;
	private MockedStatic<OwnershipStore> store;

	@BeforeEach
	void setup() {
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getServicesManager).thenReturn(services);
		log = mockStatic(VFLogger.class);
		store = mockStatic(OwnershipStore.class);
	}

	@AfterEach
	void cleanup() {
		store.close(); log.close(); bukkit.close();
	}

	private RegisteredServiceProvider<VehicleQuarantineSource> source(String plugin, VehicleQuarantineSource provider) {
		Plugin owner = mock(Plugin.class);
		when(owner.getName()).thenReturn(plugin);
		return new RegisteredServiceProvider<>(VehicleQuarantineSource.class, provider, ServicePriority.Normal, owner);
	}

	@Test
	void everySourceIsListedWithTheVehicleItNamesWhereKnown() {
		VehicleQuarantineSource factions = () -> List.of(
				new QuarantinedVehicle(STORED, "player_Gone", "name does not round-trip"),
				new QuarantinedVehicle(MISSING, "player_Lost", "no heir"));
		VehicleQuarantineSource broken = () -> { throw new IllegalStateException("plan unreadable"); };
		List<RegisteredServiceProvider<VehicleQuarantineSource>> sources = List.of(source("SimpleFactions", factions), source("Other", broken));
		when(services.getRegistrations(VehicleQuarantineSource.class)).thenReturn(sources);
		store.when(OwnershipStore::listOwnershipMeta).thenReturn(List.of(
				new OwnershipMeta(STORED, "cart", "Old cart", "player_Gone", Optional.empty(), Optional.empty())));
		QuarantineCommand.list(staff);
		verify(staff).sendMessage("§cOther could not list its quarantine; see the console.");
		log.verify(() -> VFLogger.log(contains("Quarantine list from Other failed")));
		verify(staff).sendMessage("§eQuarantined vehicles: §f2");
		verify(staff).sendMessage("§7" + STORED + " §8cart 'Old cart' §fplayer_Gone §7- name does not round-trip");
		verify(staff).sendMessage("§7" + MISSING + " §8(not stored) §fplayer_Lost §7- no heir");
		verify(staff, never()).sendMessage(contains("metadata unavailable"));
	}

	@Test
	void aVehicleReadFailureListsTheQuarantineAsMetadataUnavailable() {
		VehicleQuarantineSource factions = () -> List.of(new QuarantinedVehicle(STORED, "player_Gone", "no heir"));
		List<RegisteredServiceProvider<VehicleQuarantineSource>> sources = List.of(source("SimpleFactions", factions));
		when(services.getRegistrations(VehicleQuarantineSource.class)).thenReturn(sources);
		store.when(OwnershipStore::listOwnershipMeta).thenThrow(new IllegalStateException("SQLite is not open"));
		QuarantineCommand.list(staff);
		verify(staff).sendMessage("§7" + STORED + " §8(metadata unavailable) §fplayer_Gone §7- no heir");
		verify(staff, never()).sendMessage(contains("(not stored)"));
		log.verify(() -> VFLogger.log(contains("Could not read vehicles for the quarantine list")));
	}
}
