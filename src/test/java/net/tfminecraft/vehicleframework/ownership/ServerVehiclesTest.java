package net.tfminecraft.vehicleframework.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.events.ServerVehicleUseEvent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class ServerVehiclesTest {
	@Test void permissionsEventsAndTicketSources() {
		Player player = mock(Player.class);
		ActiveVehicle car = mock(ActiveVehicle.class), loco = mock(ActiveVehicle.class);
		OwnerData data = new OwnerData(); data.setOwner("server");
		when(car.getOwnerData()).thenReturn(data); when(car.ticketSource()).thenReturn(car);
		assertFalse(ServerVehicles.isServer(null));
		assertFalse(ServerVehicles.isServer(loco));
		PluginManager plugins = mock(PluginManager.class);
		try (var bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
			assertFalse(OwnershipAccess.admits(car, AccessKey.name("Alex")));
			assertFalse(ServerVehicles.check(player, car));
			verify(player).sendMessage("§cThis is a server vehicle.");
			when(player.hasPermission("vf.server.use")).thenReturn(true);
			assertTrue(ServerVehicles.check(player, car));
			when(player.hasPermission("vf.server.use")).thenReturn(false);
			when(player.hasPermission("vf.admin")).thenReturn(true);
			assertTrue(ServerVehicles.mayUse(player, car));
			when(player.hasPermission("vf.admin")).thenReturn(false);
			doAnswer(call -> {
				ServerVehicleUseEvent event = call.getArgument(0);
				assertSame(player, event.getPlayer()); assertSame(car, event.getVehicle());
				assertFalse(event.isAllowed()); assertFalse(event.isAsynchronous());
				assertSame(ServerVehicleUseEvent.getHandlerList(), event.getHandlers());
				event.setAllowed(true); return null;
			}).when(plugins).callEvent(any(ServerVehicleUseEvent.class));
			assertTrue(ServerVehicles.mayUse(player, car));
			reset(plugins);
			when(car.ticketSource()).thenReturn(loco);
			when(loco.getOwnerData()).thenReturn(data);
			OwnerData personal = new OwnerData(); when(car.getOwnerData()).thenReturn(personal);
			assertFalse(ServerVehicles.mayAccess(player, car));
			when(player.hasPermission("vf.server.use")).thenReturn(true);
			assertTrue(ServerVehicles.mayAccess(player, car));
			when(loco.getOwnerData()).thenReturn(personal);
			assertTrue(ServerVehicles.mayAccess(player, car));
		}
	}
}
