package net.tfminecraft.vehicleframework.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;

public class Cache {
	public static List<Material> ignoreExplode = new ArrayList<Material>();
	public static List<Material> ignoreLands = new ArrayList<Material>();
	public static List<Material> ignoreGround = new ArrayList<Material>();

	public static Set<Entity> projectiles = new HashSet<>();
	
	public static HashMap<Material, Material> convertExplode = new HashMap<>();
	
	// Keep the same defaults as an empty config if the first YAML load fails.
	public static int despawnDistance = 64 * 64;

	public static boolean blockDamage = true;
	
	public static Set<Location> lightLocations = new HashSet<>();

	public static String skinItem = "v.bucket";
	public static String repairItem = "v.iron_shovel";
	public static String destroyItem = "v.stone_axe";
	public static String ticketItem = "v.paper";
	public static String trackItemSmall = "ia.tfmc:track_small";
	public static String trackItemMedium = "ia.tfmc:track_medium";
	public static String trackItemLarge = "ia.tfmc:track_large";
	public static String appliedTrackItemSmall;
	public static String appliedTrackItemMedium;
	public static String appliedTrackItemLarge;
	public static double appliedTrackDisplayYOffset = 0.5;
	public static int trackResyncChunksPerTick = 2;
	public static String trackLayerItem = "v.iron_shovel";
	public static String trackRemoverItem = "v.iron_pickaxe";
	public static String trackRecorderItem = "v.clock";
	public static String trackJunctionItem = "v.diamond_shovel";
	public static String trackSwitchItem = "ia.tfmc:railroad_switch";
	public static String trackItem = "ia.tfmc:train_track";
	public static double trackSwitchOffsetAlong = -1.2;
	public static double trackSwitchOffsetOut = 2.4;
	public static double trackSwitchOffsetY = 0;
	public static float trackSwitchYawInward = 90;
	public static float trackSwitchThrowDegrees = 90;
	public static float trackSwitchThrowDegreesPerSecond = 90;
	public static double trackSnapDistance = 3;
	public static double trackDisplayYOffset = 0.5;
	public static double trackVehicleYOffset = 0.5;
	public static double trainClearanceWidth = 3;
	public static double trainClearanceHeight = 2.5;
	// Players on a train's deck move with it up to this speed, in blocks a tick.
	public static double trainDeckCarryMaxSpeed = 1.0;
	// Warn riders this many seconds before their train would run into another.
	public static boolean trainCollisionWarning = true;
	public static double trainCollisionWarningSeconds = 30;
	public static int trainCollisionWarningCheckTicks = 10;
	public static double trainCollisionWarningMargin = 2;
	public static double trainCollisionWarningParkedHalfLength = 5;
	public static String trainCollisionWarningSound = "minecraft:block.bell.use";
	public static float trainCollisionWarningSoundVolume = 1.0f;
	public static float trainCollisionWarningSoundPitch = 1.5f;
	public static int trainCollisionWarningSoundTicks = 40;
	public static long trackLayRetryMs = 3000;
	public static long trackRemoveCooldownMs = 3000;
	public static double trackMaxTurnDegrees = 25;
	public static double trackMinLayDistance = 8;
	public static double trackCurveRadius = 32;
	public static double trackJoinDistance = 1.5;
	public static double trackPlaceKeepoutRadius = 1.5;
	public static double trackMinJunctionSpacing = 16;
	public static double trackMaxJunctionLength = 32;
	public static double trackJunctionArmDistance = 16;
	public static double trackDesiredGradeDegrees = 6;
	public static double trackMaxGradeDegrees = 10;
	public static Particle trackFxParticle = Particle.BLOCK;
	public static Material trackFxBlock = Material.GRAVEL;
	public static int trackFxCount = 3;
	public static int trackFxWidth = 3;
	public static double trackFxExtra = 0.06;
	public static double trackFxYOffset = 0.08;
	public static String trackFxSound = "minecraft:block.stone.break";
	public static float trackFxSoundVolume = 0.35f;
	public static float trackFxSoundPitch = 0.85f;
	public static double trackFxSoundInterval = 2.5;
	public static int trackBuildIntervalTicks = 4;
	public static boolean trackBuildSwing = true;
	public static String trackBuildSound = "minecraft:block.gravel.break";
	public static float trackBuildSoundVolume = 0.55f;
	public static float trackBuildSoundPitch = 0.9f;
	public static String trackBuildSound2 = "minecraft:block.iron.place";
	public static float trackBuildSound2Volume = 1.0f;
	public static float trackBuildSound2Pitch = 2.0f;
	public static Particle trackBuildParticle = Particle.BLOCK;
	public static Material trackBuildBlock = Material.GRAVEL;
	public static int trackBuildCount = 6;
	public static int trackBuildWidth = 1;
	public static double trackBuildExtra = 0.08;
	public static double trackBuildYOffset = 0.08;

	public static boolean enableLogging;
	public static String mythicMob = "none";

	public static boolean allowWhitelist;
	public static boolean whitelistedByDefault;

	public static double weaponDegradedReloadMultiplier = 2.0;
	public static boolean weaponAimDebug = false;
	public static boolean terrainFollowDebug = false;
	public static boolean debugLogging = false;
	public static boolean persistenceLogging = false;
	public static boolean groundEngineLogging = false;
	public static boolean wipeLog = false;

	//Plugins
	public static boolean coreProtect = false;
	
	public static void applyTrackDisplayStyle() {
		appliedTrackItemSmall = trackItemSmall;
		appliedTrackItemMedium = trackItemMedium;
		appliedTrackItemLarge = trackItemLarge;
		appliedTrackDisplayYOffset = trackDisplayYOffset;
	}

	public static void removeProjectiles() {
		for(Entity e : projectiles) {
			if(e == null) continue;
			e.remove();
		}
	}
	
	public static void removeLights() {
		for(Location loc : lightLocations) {
			if(loc.getBlock().getType().equals(Material.LIGHT)) {
				loc.getBlock().setType(Material.AIR);
			}
		}
	}
}
