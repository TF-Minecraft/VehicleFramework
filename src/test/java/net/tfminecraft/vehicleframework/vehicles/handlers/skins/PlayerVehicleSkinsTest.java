package net.tfminecraft.vehicleframework.vehicles.handlers.skins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;

import net.tfminecraft.vehicleframework.loaders.PlayerSkinLoader;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.SkinHandler;

class PlayerVehicleSkinsTest {
    private static final Set<String> MISSING = new java.util.HashSet<>();
    private MockedStatic<ModelEngineAPI> models;
    private MockedStatic<Bukkit> bukkit;
    private final Logger logger = mock(Logger.class);
    @TempDir Path temp;

    @BeforeEach void setup() {
        MISSING.clear();
        ModelBlueprint blueprint = mock(ModelBlueprint.class);
        models = mockStatic(ModelEngineAPI.class);
        models.when(() -> ModelEngineAPI.getBlueprint(anyString()))
                .thenAnswer(call -> MISSING.contains(call.getArgument(0, String.class)) ? null : blueprint);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach void cleanup() {
        bukkit.close();
        models.close();
    }

    private static SkinHandler karveSkins() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("karve.model", "karve");
        config.set("karve_gold.model", "karve_gold");
        config.set("karve_gold.permission", "vf.skin.gold");
        return new SkinHandler("karve", config);
    }

    private static VehicleSkin player(String id) {
        return new VehicleSkin(id, "Skin " + id, "vskin_" + id, "armourshop.submission." + id, true);
    }

    @Test void skinsReadTheirPermissionAndOnlyHoldersMayUseThem() {
        SkinHandler skins = karveSkins();
        VehicleSkin open = skins.getSkins().get("karve"), gold = skins.getSkins().get("karve_gold");
        assertNull(open.getPermission()); assertFalse(open.isPlayerSkin());
        assertEquals("vf.skin.gold", gold.getPermission());
        Player owner = mock(Player.class), other = mock(Player.class);
        when(owner.hasPermission("vf.skin.gold")).thenReturn(true);
        assertTrue(open.mayUse(null)); assertTrue(open.mayUse(other));
        assertTrue(gold.mayUse(owner)); assertFalse(gold.mayUse(other)); assertFalse(gold.mayUse(null));
        VehicleSkin unnamed = new VehicleSkin("plain", " ", "plain_model", " ", true);
        assertEquals("plain", unnamed.getName()); assertNull(unnamed.getPermission()); assertTrue(unnamed.isPlayerSkin());
        assertEquals("plain", new VehicleSkin("plain", null, "m", null, false).getName());
        assertEquals("Skin a", player("a").getName()); assertEquals("vskin_a", player("a").getModel());
    }

    @Test void playerSkinsAreAddedAfterConfigSkinsAndRemovedWithoutTouchingThem() {
        SkinHandler skins = karveSkins();
        assertTrue(skins.addPlayerSkin(player("sunrise")));
        assertTrue(skins.addPlayerSkin(player("sunrise")), "a reload may replace the same player skin");
        assertFalse(skins.addPlayerSkin(null));
        assertFalse(skins.addPlayerSkin(new VehicleSkin("x", "X", "x", null, false)), "only website skins go through here");
        assertFalse(skins.addPlayerSkin(new VehicleSkin("karve_gold", "Fake", "vskin_fake", "p", true)), "a config skin id is never overwritten");
        assertEquals(List.of("karve", "karve_gold", "sunrise"), List.copyOf(skins.getSkins().keySet()));
        assertEquals(1, skins.removePlayerSkins());
        assertEquals(List.of("karve", "karve_gold"), List.copyOf(skins.getSkins().keySet()));
        assertEquals(0, skins.removePlayerSkins());
    }

    @Test void spawnSkinFallsBackToTheDefaultWhenAWebsiteSkinIsGone() {
        SkinHandler skins = karveSkins();
        skins.addPlayerSkin(player("sunrise"));
        assertEquals("sunrise", skins.resolveSpawnSkin("sunrise").getId());
        assertEquals("karve_gold", skins.resolveSpawnSkin("karve_gold").getId());
        MISSING.add("vskin_sunrise");
        assertEquals("karve", skins.resolveSpawnSkin("sunrise").getId());
        verify(logger).warning(contains("Vehicle skin 'sunrise' is unavailable; using 'karve' instead"));
        assertEquals("karve", skins.resolveSpawnSkin("revoked").getId());
        assertEquals("karve", skins.resolveSpawnSkin(null).getId());
        // A config skin with a missing model keeps the old behaviour: the spawner reports it.
        MISSING.add("karve_gold");
        assertEquals("karve_gold", skins.resolveSpawnSkin("karve_gold").getId());
        verify(logger, times(2)).warning(anyString());
    }

    @Test void withoutADefaultSkinNothingIsInvented() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("blue.model", "blue_model");
        SkinHandler skins = new SkinHandler("absent", config);
        assertNull(skins.resolveSpawnSkin("revoked"));
        assertEquals("blue", skins.resolveSpawnSkin("blue").getId());
        verify(logger, never()).warning(anyString());
    }

    @Test void spawnedVehiclesStartOnTheResolvedSkinAndShareTheMap() {
        SkinHandler template = karveSkins();
        ActiveVehicle vehicle = mock(ActiveVehicle.class);
        assertEquals("karve", new SkinHandler(vehicle, "revoked", template).getCurrentSkin().getId());
        SkinHandler spawned = new SkinHandler(vehicle, "karve_gold", template);
        assertEquals("karve_gold", spawned.getCurrentSkin().getId());
        template.addPlayerSkin(player("late"));
        assertTrue(spawned.getSkins().containsKey("late"), "a reload reaches vehicles that are already out");
        verify(logger, never()).warning(anyString());
        YamlConfiguration config = new YamlConfiguration();
        config.set("blue.model", "blue_model");
        assertNull(new SkinHandler(vehicle, "revoked", new SkinHandler("absent", config)).getCurrentSkin());
    }

    private Map<String, Vehicle> registry(SkinHandler karve) {
        Vehicle vehicle = mock(Vehicle.class);
        when(vehicle.getSkinHandler()).thenReturn(karve);
        Map<String, Vehicle> map = new HashMap<>();
        map.put("karve", vehicle);
        return map;
    }

    @Test void loaderAddsValidEntriesAndSkipsBrokenOnes() throws Exception {
        SkinHandler karve = karveSkins();
        karve.addPlayerSkin(player("stale"));
        File file = temp.resolve("player-skins.yml").toFile();
        Files.writeString(file.toPath(), String.join("\n",
                "vskin_sunrise: {vehicle: karve, name: Sunrise, model: vskin_sunrise, permission: armourshop.submission.sunrise}",
                "not_a_section: 5",
                "Bad-Id: {vehicle: karve, model: m, permission: p}",
                "no_vehicle: {model: m, permission: p}",
                "unknown_vehicle: {vehicle: zeppelin, model: m, permission: p}",
                "no_model: {vehicle: karve, model: ' ', permission: p}",
                "no_model_at_all: {vehicle: karve, permission: p}",
                "no_permission: {vehicle: karve, model: m}",
                "blank_permission: {vehicle: karve, model: m, permission: ' '}",
                "karve_gold: {vehicle: karve, model: m, permission: p}",
                ""));
        PlayerSkinLoader.Result result = PlayerSkinLoader.apply(file, registry(karve));
        assertEquals(1, result.loaded); assertEquals(9, result.skipped); assertFalse(result.fileError);
        assertEquals(List.of("karve", "karve_gold", "vskin_sunrise"), List.copyOf(karve.getSkins().keySet()));
        VehicleSkin sunrise = karve.getSkins().get("vskin_sunrise");
        assertEquals("Sunrise", sunrise.getName()); assertEquals("armourshop.submission.sunrise", sunrise.getPermission());
        verify(logger).warning(contains("'not_a_section': not a section"));
        verify(logger).warning(contains("'Bad-Id': id must be"));
        verify(logger).warning(contains("'no_vehicle': unknown vehicle 'null'"));
        verify(logger).warning(contains("'unknown_vehicle': unknown vehicle 'zeppelin'"));
        verify(logger).warning(contains("'no_model': no model"));
        verify(logger).warning(contains("'no_model_at_all': no model"));
        verify(logger).warning(contains("'no_permission': no permission"));
        verify(logger).warning(contains("'blank_permission': no permission"));
        verify(logger).warning(contains("'karve_gold': vehicle 'karve' already has a skin with this id"));
    }

    @Test void aMissingFileClearsPlayerSkinsAndABrokenFileKeepsThem() throws Exception {
        SkinHandler karve = karveSkins();
        karve.addPlayerSkin(player("kept"));
        File file = temp.resolve("player-skins.yml").toFile();
        Files.writeString(file.toPath(), "broken: [unterminated\n");
        PlayerSkinLoader.Result broken = PlayerSkinLoader.apply(file, registry(karve));
        assertTrue(broken.fileError); assertTrue(karve.getSkins().containsKey("kept"));
        verify(logger).warning(contains("Failed to read player-skins.yml; keeping the loaded player skins"));
        Files.delete(file.toPath());
        PlayerSkinLoader.Result gone = PlayerSkinLoader.apply(file, registry(karve));
        assertEquals(0, gone.loaded); assertFalse(gone.fileError); assertFalse(karve.getSkins().containsKey("kept"));
        assertEquals(0, PlayerSkinLoader.apply(null, registry(karve)).loaded);
    }
}
