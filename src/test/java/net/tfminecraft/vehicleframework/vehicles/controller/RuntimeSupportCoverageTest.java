package net.tfminecraft.vehicleframework.vehicles.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.ticxo.modelengine.api.model.ActiveModel;
import net.tfminecraft.vehicleframework.bones.BoneRotator;
import net.tfminecraft.vehicleframework.data.OwnerData;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.managers.inventory.VFInventoryHolder;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.component.Engine;
import net.tfminecraft.vehicleframework.vehicles.component.GearedEngine;
import net.tfminecraft.vehicleframework.vehicles.component.propulsion.Throttle;
import net.tfminecraft.vehicleframework.vehicles.handlers.*;
import net.tfminecraft.vehicleframework.vehicles.handlers.state.InputHandler;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.vehicleframework.vehicles.util.AccessPanel;
import net.tfminecraft.vehicleframework.weapons.ActiveWeapon;
import net.tfminecraft.vehicleframework.weapons.Weapon;

class RuntimeSupportCoverageTest {
    @TempDir Path folder;

    @BeforeAll static void initialize() {
        RegistryFixture.initialize();
        // A disabled logger can be bootstrapped before a plugin data folder is available.
        assertDoesNotThrow(() -> GroundEngineLog.configure(false, true, null));
        assertFalse(GroundEngineLog.isEnabled());
    }

    @AfterEach void resetLog() { GroundEngineLog.configure(false, false, folder.toFile()); }

    @Test void inputMappingsAreCompleteAndCopiesDoNotShareMutableBindings() {
        InputHandler defaults = new InputHandler();
        assertEquals(Keybind.values().length, defaults.getMappings().size());
        assertEquals(Input.SEAT_SELECTION, defaults.getInput(Keybind.SHIFT));
        assertTrue(defaults.containsInput(Input.SEAT_SELECTION)); assertFalse(defaults.containsInput(Input.LIGHTS));
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("W", "throttle_up"); yaml.set("RIGHT_CLICK", "weapon_shoot");
        InputHandler configured = new InputHandler(yaml); InputHandler copied = new InputHandler(configured);
        configured.getMappings().put(Keybind.W, Input.NONE);
        assertEquals(Input.THROTTLE_UP, copied.getInput(Keybind.W));
        assertEquals(Input.WEAPON_SHOOT, copied.getInput(Keybind.RIGHT_CLICK)); assertEquals(Input.NONE, copied.getInput(Keybind.S));
        assertEquals(Keybind.values().length, copied.getMappings().size());
    }

    @Test void configuredInputNamesAreIndependentOfTheServersDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            YamlConfiguration yaml = new YamlConfiguration(); yaml.set("RIGHT_CLICK", "lights");
            assertEquals(Input.LIGHTS, new InputHandler(yaml).getInput(Keybind.RIGHT_CLICK));
        } finally { Locale.setDefault(previous); }
    }

    @Test void accessPanelConnectsLiveSeatsAndOnlyTurnsWhileMoving() {
        AccessPanel panel = new AccessPanel(); ActiveVehicle vehicle = mock(ActiveVehicle.class); when(vehicle.getAccessPanel()).thenReturn(panel);
        Seat driver = new Seat(vehicle, new Seat(SeatType.CAPTAIN, "captain"));
        Seat passenger = new Seat(vehicle, new Seat(SeatType.PASSENGER, "bench"));
        BoneRotator front = mock(BoneRotator.class), rear = mock(BoneRotator.class);
        when(front.getId()).thenReturn("front"); when(rear.getId()).thenReturn("rear"); panel.addRotator(front); panel.addRotator(rear);
        assertEquals(List.of(driver, passenger), panel.getSeats()); assertSame(passenger, panel.getSeat("BENCH")); assertNull(panel.getSeat("missing"));
        assertEquals(List.of(front, rear), panel.getRotators()); assertSame(rear, panel.getRotator("REAR")); assertNull(panel.getRotator("missing"));
        panel.setTurnRate(3); assertEquals(0, panel.getTurnRate()); panel.setSpeed(.4); assertEquals(.4, panel.getSpeed()); assertEquals(3, panel.getTurnRate());
        panel.setReverse(true); assertTrue(panel.isReverse()); panel.setSpeed(0); assertEquals(0, panel.getTurnRate());
    }

    @Test void weaponRoutingConnectsMatchingSeatsAndOnlyDispatchesTheCurrentPlayersInputs() {
        ActiveModel model = mock(ActiveModel.class), replacement = mock(ActiveModel.class); ActiveVehicle vehicle = mock(ActiveVehicle.class);
        Weapon cannon = mock(Weapon.class), rearGun = mock(Weapon.class), idleGun = mock(Weapon.class);
        Seat driver = new Seat(SeatType.CAPTAIN, "captain"), rear = new Seat(SeatType.GUNNER, "rear"), passenger = new Seat(SeatType.PASSENGER, "bench");
        SeatHandler seats = mock(SeatHandler.class); when(seats.getSeats()).thenReturn(List.of(driver, rear, passenger));
        Player operator = mock(Player.class), other = mock(Player.class); List<Player> nearby = List.of(operator, other);
        List<List<?>> arguments = new ArrayList<>();
        try (var constructed = mockConstruction(ActiveWeapon.class, (weapon, context) -> {
            arguments.add(new ArrayList<>(context.arguments())); int index = context.getCount();
            when(weapon.getId()).thenReturn(index == 1 ? "cannon" : index == 2 ? "rear_gun" : "unused");
            when(weapon.getSeat()).thenReturn(index == 1 ? "CAPTAIN" : index == 2 ? "rear" : "unassigned");
            when(weapon.isControlled()).thenReturn(index != 3); when(weapon.getController()).thenReturn(index == 1 ? operator : other);
        })) {
            WeaponHandler handler = new WeaponHandler(model, vehicle, List.of(cannon, rearGun, idleGun), seats);
            ActiveWeapon first = constructed.constructed().get(0), second = constructed.constructed().get(1), third = constructed.constructed().get(2);
            assertEquals(Arrays.asList(model, vehicle, cannon, null), arguments.get(0));
            assertSame(first, driver.getWeapon()); assertSame(second, rear.getWeapon()); assertFalse(passenger.hasWeapon());
            assertEquals(constructed.constructed(), handler.getWeapons()); assertSame(second, handler.getWeapon("REAR_GUN")); assertNull(handler.getWeapon("missing"));
            handler.input(nearby, Keybind.LEFT_CLICK, operator);
            verify(first).input(nearby, Keybind.LEFT_CLICK); verify(second, never()).input(anyList(), any()); verify(third, never()).input(anyList(), any());
            handler.updateModel(replacement); handler.damage("explosion", 4.5); handler.tick();
            for (ActiveWeapon weapon : constructed.constructed()) { verify(weapon).updateModel(replacement); verify(weapon).damage("explosion", 4.5); verify(weapon).tick(); }
        }
    }

    @Test void accessRulesHandleMissingLegacyWhitelistEntriesWithoutGrantingStrangersAccess() {
        assertFalse(VehicleTicketRules.ownerOrWhitelisted(null, "Guest"));
        OwnerData access = new OwnerData(); access.setOwner("player_Owner"); access.setWhiteListed(true);
        assertFalse(VehicleTicketRules.ownerOrWhitelisted(access, null)); assertTrue(VehicleTicketRules.ownerOrWhitelisted(access, "OWNER"));
        access.setWhiteList(null); assertFalse(VehicleTicketRules.ownerOrWhitelisted(access, "Guest"));
        access.setWhiteList(Arrays.asList(null, "player_Alice", "Bob"));
        assertTrue(VehicleTicketRules.ownerOrWhitelisted(access, "ALICE")); assertTrue(VehicleTicketRules.ownerOrWhitelisted(access, "bob"));
        assertFalse(VehicleTicketRules.ownerOrWhitelisted(access, "Guest"));
        assertTrue(VehicleTicketRules.mayOpenSeatMenu(null, "Guest", false));
        assertFalse(VehicleTicketRules.mayOpenSeatMenu(access, null, "Guest", true));
        OwnerData locomotive = new OwnerData(); locomotive.setTicketsEnabled(true);
        assertTrue(VehicleTicketRules.mayOpenSeatMenu(access, locomotive, "Guest", true));
        assertFalse(VehicleTicketRules.mayEnter(access, locomotive, "Guest", SeatType.PASSENGER, false));
        assertTrue(VehicleTicketRules.mayEnter(access, locomotive, "Guest", null, false));
        access.setOwner(null); assertTrue(VehicleTicketRules.mayOpenSeatMenu(access, "Guest", false));
        access.setOwner("none"); assertTrue(VehicleTicketRules.mayOpenSeatMenu(access, "Guest", false));
    }

    @Test void inventoryHoldersCarryRoutingContextWithoutOwningTheBukkitInventory() {
        VFInventoryHolder selection = new VFInventoryHolder("select", VFGUI.SEAT_SELECTION);
        assertEquals("select", selection.getId()); assertEquals(VFGUI.SEAT_SELECTION, selection.getType()); assertTrue(selection.getVehicle().isEmpty());
        ActiveVehicle vehicle = mock(ActiveVehicle.class); VFInventoryHolder repair = new VFInventoryHolder("repair", VFGUI.REPAIR, vehicle);
        assertSame(vehicle, repair.getVehicle().orElseThrow()); assertEquals(VFGUI.REPAIR, repair.getType()); assertNull(repair.getInventory());
    }

    @Test void groundLogAppendsImmediatelyAndCanWipeOrRetainTheConfiguredDestination() throws Exception {
        GroundEngineLog.configure(true, false, folder.toFile()); assertTrue(GroundEngineLog.isEnabled());
        GroundEngineLog.append("first"); GroundEngineLog.append(null); GroundEngineLog.append("second");
        Path log = folder.resolve("logs/ground_engine.log"); List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size()); assertTrue(lines.get(0).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} first")); assertTrue(lines.get(1).endsWith(" second"));
        GroundEngineLog.configure(false, false, null); GroundEngineLog.append("disabled"); assertEquals(lines, Files.readAllLines(log));
        GroundEngineLog.configure(true, true, null); assertFalse(Files.exists(log)); GroundEngineLog.append("fresh"); assertEquals(1, Files.readAllLines(log).size());
    }

    @Test void groundLogReportsUnwritableOutputAndFailedWipesWithoutEscapingToTheTickLoop() throws Exception {
        Logger logger = Logger.getLogger(GroundEngineLog.class.getName()); List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() { public void publish(LogRecord record) { records.add(record); } public void flush() {} public void close() {} };
        logger.addHandler(capture);
        try {
            Path log = folder.resolve("logs/ground_engine.log"); Files.createDirectories(log); Path original = Files.writeString(log.resolve("keep"), "data");
            GroundEngineLog.configure(true, true, folder.toFile()); assertEquals("data", Files.readString(original));
            GroundEngineLog.append("cannot write to directory");
            assertTrue(records.stream().anyMatch(record -> record.getMessage().startsWith("Failed to wipe ground_engine.log:")));
            assertTrue(records.stream().anyMatch(record -> record.getMessage().startsWith("Failed to write ground_engine.log:")));
        } finally { logger.removeHandler(capture); }
    }

    @Test void groundLogFormatsActualEngineThrottleGearAndStateTransitions() throws Exception {
        assertEquals("", GroundEngineLog.formatThrottle(null)); assertEquals("", GroundEngineLog.formatGearedEngine(null)); assertEquals("", GroundEngineLog.formatEngineFragment(null));
        Throttle independent = new Throttle("Throttle", 100, -30, null); independent.setThrottle(-10);
        assertEquals("thr=-10/-30/100", GroundEngineLog.formatThrottle(independent));
        YamlConfiguration config = new YamlConfiguration(); config.loadFromString("max: 100\nmin: -30\nhealth: 100\ngears:\n  first:\n    name: First\n    engine-sound: {}\n    accelerate-sound: {}\n    max: 100\n    min: -30\n");
        Engine engine = new Engine(config); engine.getThrottle().setThrottle(20);
        GearedEngine geared = new GearedEngine(config); geared.getGear().getThrottle().setThrottle(40); geared.setStarted(true);
        String expected = "gear=0 gearName=First thr=40/-30/100 shifting=false started=true";
        assertEquals(expected, GroundEngineLog.formatGearedEngine(geared));
        ActiveVehicle vehicle = mock(ActiveVehicle.class); assertEquals("", GroundEngineLog.formatEngineFragment(vehicle));
        when(vehicle.hasComponent(Component.ENGINE)).thenReturn(true); assertEquals("", GroundEngineLog.formatEngineFragment(vehicle));
        when(vehicle.getComponent(Component.ENGINE)).thenReturn(engine); assertEquals("thr=20/-30/100", GroundEngineLog.formatEngineFragment(vehicle));
        when(vehicle.hasComponent(Component.GEARED_ENGINE)).thenReturn(true); when(vehicle.getComponent(Component.GEARED_ENGINE)).thenReturn(geared);
        assertEquals(expected, GroundEngineLog.formatEngineFragment(vehicle));
        assertEquals("id=null state=none->null reason=unknown isDefault=false", GroundEngineLog.formatStateSwap(null, null, null, null, false));
        assertEquals("id=cart state=GROUND->FLYING reason=input isDefault=true", GroundEngineLog.formatStateSwap("cart", "GROUND", "FLYING", "input", true));
    }

    @Test void levelObstacleAllowsASafeBackoffWithoutTryingToRaiseTheVehicle() {
        var move = TerrainFollowMath.raiseThenSlide(.4, 0, 64, 64, 64, 64, .25,
                (x, z, y) -> x > 0);
        assertTrue(move.offsetX <= 1e-9); assertEquals(0, move.offsetZ); assertEquals(64, move.y);
        assertEquals("back+slidePartial", move.path); assertTrue(move.aabbBlocked);
    }

    @Test void shortMovementBesideABlockCornerCanBackOffThenSlideAlongOneAxis() {
        TerrainFollowMath.OffsetYBlocked corners = (x, z, y) ->
                (x > 0 && z > 0) || (x < -.062 && x > -1.062 && z > -.067 && z < .933);
        var move = TerrainFollowMath.raiseThenSlide(.007, .007, 64, 64, 64, 64, .25, corners);
        assertEquals(-.1 / Math.sqrt(2) + .007, move.offsetX, 1e-9);
        assertEquals(-.1 / Math.sqrt(2), move.offsetZ, 1e-9);
        assertFalse(corners.test(move.offsetX, move.offsetZ, move.y));
        assertEquals("back+slideX", move.path); assertTrue(move.aabbBlocked);
    }
}
