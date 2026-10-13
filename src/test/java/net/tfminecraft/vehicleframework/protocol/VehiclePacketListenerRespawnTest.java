package net.tfminecraft.vehicleframework.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import java.util.*;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.*;

class VehiclePacketListenerRespawnTest {
    private Rig rig;
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private PacketListener listener;
    private PacketEvent event;
    private Player rider;

    @BeforeEach
    void setup() {
        rig = new Rig();
        Server server = mock(Server.class);
        when(server.getVersion()).thenReturn("Paper (MC: 1.21.10)");
        when(server.getBukkitVersion()).thenReturn("1.21.10-R0.1-SNAPSHOT");
        rig.bukkit.when(Bukkit::getServer).thenReturn(server);
        rig.bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
        rig.bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
        rig.bukkit.when(Bukkit::getLogger).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var library = mockStatic(ProtocolLibrary.class); scopes.add(library);
        var reflection = mockStatic(com.comphenix.protocol.utility.MinecraftReflection.class); scopes.add(reflection);
        reflection.when(com.comphenix.protocol.utility.MinecraftReflection::getItemStackClass).thenReturn(org.bukkit.inventory.ItemStack.class);
        reflection.when(com.comphenix.protocol.utility.MinecraftReflection::getBlockClass).thenReturn(org.bukkit.block.Block.class);
        ProtocolManager protocol = mock(ProtocolManager.class);
        library.when(ProtocolLibrary::getProtocolManager).thenReturn(protocol);
        new VehiclePacketListener(rig.manager).register();
        var capture = ArgumentCaptor.forClass(PacketListener.class);
        verify(protocol, times(2)).addPacketListener(capture.capture());
        listener = capture.getAllValues().get(1);
        assertTrue(listener.getSendingWhitelist().getTypes().contains(PacketType.Play.Server.RESPAWN));
        assertEquals(ListenerPriority.MONITOR, listener.getSendingWhitelist().getPriority());
        rider = rig.entity(Player.class, new Location(rig.world, 0, 120, 0));
        rig.bukkit.when(() -> Bukkit.getPlayer(rider.getUniqueId())).thenReturn(rider);
        event = mock(PacketEvent.class);
        when(event.getPlayer()).thenReturn(rider);
    }

    @AfterEach
    void cleanup() throws Exception {
        Collections.reverse(scopes);
        for (var scope : scopes) scope.close();
        rig.close();
    }

    @Test
    void respawnRestoresTheSeatOnTheNextTick() {
        listener.onPacketSending(event);
        verify(rig.manager, never()).refreshPlayerMount(any());
        rig.tick();
        verify(rig.manager).refreshPlayerMount(rider);
    }

    @Test
    void cancelledRespawnDoesNotRefreshTheMount() {
        when(event.isCancelled()).thenReturn(true);
        listener.onPacketSending(event);
        rig.tick();
        verify(rig.manager, never()).refreshPlayerMount(any());
    }

    @Test
    void disconnectOrNewLoginDoesNotRestoreAnOldPlayersSeat() {
        listener.onPacketSending(event);
        rig.bukkit.when(() -> Bukkit.getPlayer(rider.getUniqueId())).thenReturn(null);
        rig.tick();
        listener.onPacketSending(event);
        rig.bukkit.when(() -> Bukkit.getPlayer(rider.getUniqueId())).thenReturn(mock(Player.class));
        rig.tick();
        verify(rig.manager, never()).refreshPlayerMount(any());
    }
}
