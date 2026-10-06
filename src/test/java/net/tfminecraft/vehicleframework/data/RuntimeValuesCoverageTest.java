package net.tfminecraft.vehicleframework.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import net.tfminecraft.vehicleframework.VFLogger;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.enums.*;
import net.tfminecraft.vehicleframework.events.*;
import net.tfminecraft.vehicleframework.projectiles.ProjectilesCoverageTest.Rig;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;

class RuntimeValuesCoverageTest {
    Rig r;
    @BeforeEach void setup(){r=new Rig();}
    @AfterEach void cleanup(){if(r!=null)r.close();}

    @Test void colocatedSoundSourceKeepsItsConfiguredPitch()throws Exception {
        Location source=new Location(r.world,0,64,0);Player player=r.entity(Player.class,source);
        SoundData sound=new SoundData(Rig.yaml("sound: engine\ndoppler: true\npitch: 1.2\n"));sound.playSound(List.of(player),source,new Vector(),1);
        verify(player).playSound(source,"engine",1f,1.2f);
    }

    @Test void damageCauseIdsUseRootLocaleForBothConfigurationForms() {
        Locale previous=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertTrue(new DamageData(List.of("fire(0.5)")).hasModifier("FIRE"));assertTrue(new DamageData(Map.of("fire",.5)).hasModifier("FIRE"));}finally{Locale.setDefault(previous);}
    }

    @Test void particleIdsUseRootLocaleRatherThanSilentlyChangingTheEffect()throws Exception {
        Locale previous=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(Particle.CRIT,new ParticleData(Rig.yaml("particle: crit\n")).getParticle());}finally{Locale.setDefault(previous);}
    }

    @Test void deathOverrideIdsUseRootLocale()throws Exception {
        Locale previous=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(VehicleDeath.SINK,new DeathOverride(Rig.yaml("type: sink\n")).getDeath());}finally{Locale.setDefault(previous);}
    }

    @Test void damageModifiersReadNumbersStringsNullMapsAndNullEntries() {
        DamageData list=new DamageData(List.of("EXPLOSION(0.5)","FIRE(2)"));assertEquals(.5,list.getModifier("EXPLOSION"));assertTrue(list.hasModifier("FIRE"));assertFalse(list.hasModifier("OTHER"));assertEquals(2,list.getModifiers().size());assertTrue(new DamageData((List<String>)null).getModifiers().isEmpty());assertTrue(new DamageData((Map<String,Object>)null).getModifiers().isEmpty());Map<String,Object> values=new LinkedHashMap<>();values.put(null,1);values.put("skip",null);values.put("projectile",2);values.put("fire","0.25");DamageData mapped=new DamageData(values);assertEquals(Map.of("PROJECTILE",2d,"FIRE",.25d),mapped.getModifiers());assertThrows(NumberFormatException.class,()->new DamageData(Map.of("fire","invalid")));
    }

    @Test void repairCountsDownClampsDamageAndReportsHealthBands() {
        HealthData health=new HealthData(100,5,2);assertEquals(100,health.getHealth());assertEquals(5,health.getDamage());assertEquals(2,health.getBaseRepairTime());assertEquals(-1,health.getRepairTime());assertFalse(health.isUnderRepair());health.tick();health.startRepair();assertTrue(health.isUnderRepair());assertEquals("0.1s",health.getRepairString());health.tick();assertEquals(1,health.getRepairTime());health.tick();assertFalse(health.isUnderRepair());assertEquals(0,health.getDamage(),"Every configured repair restores at least seven health");health.startRepair();health.stopRepair();assertFalse(health.isUnderRepair());health.setDamage(120);assertEquals(100,health.getDamage());health.setDamage(95);health.damage(20);assertEquals(100,health.getDamage());
        int[] percentages={90,70,50,30,10};String[] colors={"§2","§a","§e","§c","§4"};for(int i=0;i<percentages.length;i++){health.setDamage(100-percentages[i]);assertEquals(percentages[i],health.getHealthPercentage());assertEquals("§fHealth: "+colors[i]+percentages[i]+"%",health.getHealthPercentageString());}
    }

    @Test void ownerSettingsSupportWhitelistAndStableTicketIdentity() {
        boolean allow=Cache.allowWhitelist,defaults=Cache.whitelistedByDefault;try{Cache.allowWhitelist=true;Cache.whitelistedByDefault=true;OwnerData owner=new OwnerData();assertEquals("none",owner.getOwner());assertTrue(owner.isWhiteListed());owner.setOwner("player_alex");assertEquals("player_alex",owner.getOwner());owner.setWhiteList(new ArrayList<>(List.of("alex")));owner.addToWhiteList("sam");owner.removeFromWhiteList("alex");assertEquals(List.of("sam"),owner.getWhiteList());owner.setWhiteListed(false);assertFalse(owner.isWhiteListed());assertFalse(owner.isTicketsEnabled());assertNull(owner.getTicketId());owner.setTicketId(" ");assertNull(owner.getTicketId());owner.setTicketsEnabled(true);String generated=owner.getTicketId();assertNotNull(UUID.fromString(generated));owner.toggleTickets();assertFalse(owner.isTicketsEnabled());owner.toggleTickets();assertEquals(generated,owner.getTicketId());owner.setTicketId("ticket-1");assertEquals("ticket-1",owner.getTicketId());owner.setTicketId(null);assertNull(owner.getTicketId());Cache.allowWhitelist=false;assertFalse(new OwnerData().isWhiteListed());Cache.allowWhitelist=true;Cache.whitelistedByDefault=false;assertFalse(new OwnerData().isWhiteListed());}finally{Cache.allowWhitelist=allow;Cache.whitelistedByDefault=defaults;}
    }

    @Test void namingTimeoutAndStoredSummariesRetainTheirPublicValues() {
        ActiveVehicle vehicle=r.vehicle();NamingData naming=new NamingData(vehicle);assertSame(vehicle,naming.getVehicle());for(int i=0;i<30;i++)assertFalse(naming.tick());assertTrue(naming.tick());assertTrue(naming.tick());StoredVehicleMeta stored=new StoredVehicleMeta("uuid","Wagon","cart","player_alex");assertEquals("uuid",stored.getUuid());assertEquals("Wagon",stored.getName());assertEquals("cart",stored.getTypeId());assertEquals("player_alex",stored.getOwner());for(String owner:Arrays.asList(null,""," ","none","player_","player_none","company_acme"))assertFalse(StoredVehicleMeta.isPlayerOwner(owner));assertTrue(StoredVehicleMeta.isPlayerOwner("PLAYER_alex"));Location location=new Location(r.world,1,64,2);OwnedVehicleSummary summary=new OwnedVehicleSummary("uuid","Wagon","cart",Optional.of(location),true,"player_alex");assertEquals("uuid",summary.getUuid());assertEquals("Wagon",summary.getName());assertEquals("cart",summary.getTypeId());assertEquals(Optional.of(location),summary.getLocation());assertTrue(summary.isSpawned());assertEquals("player_alex",summary.getOwner());OwnedVehicleSummary unloaded=new OwnedVehicleSummary("id","Stored","cart",null,false);assertFalse(unloaded.isSpawned());assertTrue(unloaded.getLocation().isEmpty());assertNull(unloaded.getOwner());
    }

    @Test void deathConfigurationExposesSoundsFragmentsAndConditionalOverrides()throws Exception {
        DeathData empty=new DeathData(VehicleDeath.DIE,Rig.yaml(""));assertEquals(VehicleDeath.DIE,empty.getType());assertEquals(0,empty.getDuration());assertEquals(0,empty.getFragments());assertTrue(empty.getSfx().isEmpty());assertFalse(empty.hasOverrides());DeathData configured=new DeathData(VehicleDeath.EXPLODE,Rig.yaml("duration: 20\nfragments: 3\nsounds: {bang: {sound: custom.bang}}\noverrides:\n  water:\n    type: sink\n    conditions: [state(floating)]\n"));assertEquals(20,configured.getDuration());assertEquals(3,configured.getFragments());assertEquals("custom.bang",configured.getSfx().getFirst().getSound());assertTrue(configured.hasOverrides());assertEquals(VehicleDeath.SINK,configured.getOverrides().getFirst().getDeath());assertEquals(List.of("state(floating)"),configured.getOverrides().getFirst().getConditions());
    }

    @Test void removalPayloadsDistinguishDeathFromUnloadAndRequireDeathCause() {
        assertThrows(IllegalArgumentException.class,()->VehicleRemovePayload.death(null));VehicleRemovePayload death=VehicleRemovePayload.death(VehicleDeath.CRASH);assertEquals(VehicleRemoveType.DEATH,death.getType());assertTrue(death.isDeath());assertFalse(death.isRemove());assertEquals(Optional.of(VehicleDeath.CRASH),death.getDeathCause());assertTrue(death.getRemoveReason().isEmpty());VehicleRemovePayload remove=VehicleRemovePayload.remove(VehicleRemoveReason.UNLOAD);assertEquals(VehicleRemoveType.REMOVE,remove.getType());assertTrue(remove.isRemove());assertFalse(remove.isDeath());assertEquals(Optional.of(VehicleRemoveReason.UNLOAD),remove.getRemoveReason());assertTrue(remove.getDeathCause().isEmpty());assertEquals(Optional.of(VehicleRemoveReason.GENERIC),VehicleRemovePayload.remove(null).getRemoveReason());
    }

    @Test void soundConfigurationControlsVolumePitchClampsAndDoppler()throws Exception {
        Location source=new Location(r.world,0,64,0);Player player=r.entity(Player.class,new Location(r.world,10,64,0));SoundData regular=new SoundData(Rig.yaml("sound: custom.motor\nvolume: 2\npitch: 1.2\ndelay: 3\n"));assertEquals("custom.motor",regular.getSound());assertEquals(2,regular.getVolume());assertEquals(1.2f,regular.getPitch());assertEquals(3,regular.getDelay());assertFalse(regular.isPitched());assertFalse(regular.isDoppler());regular.playSound(source);regular.playSound(List.of(player),source,2f);regular.playSound(List.of(player),source,new Vector(),2f);verify(r.world,times(2)).playSound(source,"custom.motor",2f,1.2f);verify(player).playSound(source,"custom.motor",2f,1.2f);
        SoundData pitched=new SoundData(Rig.yaml("sound: custom.pitched\npitched: true\nmin-pitch: 0.8\nmax-pitch: 1.5\n"));assertTrue(pitched.isPitched());pitched.playSound(List.of(player),source,new Vector(),.1f);pitched.playSound(List.of(player),source,new Vector(),3f);verify(player).playSound(source,"custom.pitched",1f,.8f);verify(player).playSound(source,"custom.pitched",1f,1.5f);pitched.playSound(List.of(player),source,.1f);pitched.playSound(List.of(player),source,3f);verify(r.world).playSound(source,"custom.pitched",1f,.8f);verify(r.world).playSound(source,"custom.pitched",1f,1.5f);
        SoundData doppler=new SoundData(Rig.yaml("sound: custom.doppler\npitched: true\ndoppler: true\n"));assertTrue(doppler.isDoppler());doppler.playSound(List.of(player),source,new Vector(-1000,0,0),1);doppler.playSound(List.of(player),source,new Vector(300,0,0),1);verify(player).playSound(source,"custom.doppler",1f,.5f);verify(player).playSound(source,"custom.doppler",1f,2f);
    }

    @Test void particleConfigurationUsesOffsetsAndLimitsVisibleDelivery()throws Exception {
        ParticleData fallback=new ParticleData(Rig.yaml("particle: missing\n"));assertEquals(Particle.FLAME,fallback.getParticle());ParticleData data=new ParticleData(Rig.yaml("particle: crit\namount: 2\nspread: 0\nspeed: 0.25\nx: 1\ny: 1\nz: 1\n"));assertEquals(Particle.CRIT,data.getParticle());assertEquals(2,data.getAmount());assertEquals(0,data.getSpread());assertEquals(.25,data.getSpeed());Location source=new Location(r.world,0,64,0);data.spawnParticle(null,new Vector(0,0,1));data.spawnParticle(new Location(null,0,0,0),new Vector(0,0,1));data.spawnParticle(source,new Vector(0,0,1));Vector expected=new Vector(1,1,2).normalize();verify(r.world,times(2)).spawnParticle(Particle.CRIT,source,0,expected.getX(),expected.getY(),expected.getZ(),.25,null,true);Player viewer=r.entity(Player.class,source);when(r.world.getPlayers()).thenReturn(List.of(viewer));data.spawnParticleVisible(source,new Vector(0,0,1));verify(viewer,times(2)).spawnParticle(Particle.CRIT,source,0,expected.getX(),expected.getY(),expected.getZ(),.25);
        ParticleData negative=new ParticleData(Rig.yaml("amount: 1\nspread: 0\nx: -1\ny: -1\nz: -1\n"));negative.spawnParticle(source,new Vector());Vector backwards=new Vector(-1,-1,-1).normalize();verify(r.world).spawnParticle(Particle.FLAME,source,0,backwards.getX(),backwards.getY(),backwards.getZ(),.4,null,true);
    }

    @Test void timedSoundsPreventOverlapAndCopiesStartIdle()throws Exception {
        TimedSound sound=new TimedSound(Rig.yaml("sound: timer\nduration: 3\n"));assertFalse(sound.isPlaying());assertEquals(3,sound.getDuration());Location location=new Location(r.world,0,64,0);sound.playSound(location);sound.playSound(location);assertTrue(sound.isPlaying());verify(r.world).playSound(location,"timer",1f,1f);TimedSound copy=new TimedSound(sound);assertSame(sound.getSound(),copy.getSound());assertEquals(3,copy.getDuration());assertFalse(copy.isPlaying());r.ticks(2);assertTrue(sound.isPlaying());r.tick();assertFalse(sound.isPlaying());sound.playSound(location);verify(r.world,times(2)).playSound(location,"timer",1f,1f);
    }

    @Test void eventsPreservePayloadsCancellationAndStableHandlerLists() {
        ActiveVehicle vehicle=r.vehicle();Player player=r.entity(Player.class,new Location(r.world,0,64,0));VehicleSpawnEvent spawn=new VehicleSpawnEvent(vehicle);assertSame(vehicle,spawn.getVehicle());assertSame(VehicleSpawnEvent.getHandlerList(),spawn.getHandlers());VehicleRemovePayload payload=VehicleRemovePayload.death(VehicleDeath.DIE);VehicleRemoveEvent remove=new VehicleRemoveEvent(vehicle,payload);assertSame(vehicle,remove.getVehicle());assertSame(payload,remove.getPayload());assertSame(VehicleRemoveEvent.getHandlerList(),remove.getHandlers());assertTrue(new VehicleRemoveEvent(vehicle,null).getPayload().isRemove());VehicleOwnerClaimedEvent owner=new VehicleOwnerClaimedEvent(player,vehicle,"none","player_alex");assertSame(player,owner.getPlayer());assertSame(vehicle,owner.getVehicle());assertEquals("none",owner.getPreviousOwner());assertEquals("player_alex",owner.getNewOwner());assertFalse(owner.isCancelled());owner.setCancelled(true);assertTrue(owner.isCancelled());assertSame(VehicleOwnerClaimedEvent.getHandlerList(),owner.getHandlers());VehiclePreInteractEvent interact=new VehiclePreInteractEvent(player,vehicle);assertSame(vehicle,interact.getVehicle());assertFalse(interact.isCancelled());interact.setCancelled(true);assertTrue(interact.isCancelled());assertSame(VehiclePreInteractEvent.getHandlerList(),interact.getHandlers());VehicleRepairStartEvent repair=new VehicleRepairStartEvent(player,vehicle);assertSame(vehicle,repair.getVehicle());assertFalse(repair.isCancelled());repair.setCancelled(true);assertTrue(repair.isCancelled());assertSame(VehicleRepairStartEvent.getHandlerList(),repair.getHandlers());VFEntityDamageEvent damage=new VFEntityDamageEvent(vehicle.getEntity(),player,"FIRE",10);assertSame(vehicle.getEntity(),damage.getEntity());assertSame(player,damage.getDamager());assertEquals("FIRE",damage.getCause());assertEquals(10,damage.getDamage());damage.setDamage(5);assertEquals(5,damage.getDamage());assertFalse(damage.isCancelled());damage.setCancelled(true);assertTrue(damage.isCancelled());assertSame(VFEntityDamageEvent.getHandlerList(),damage.getHandlers());boolean before=Cache.blockDamage;try{Cache.blockDamage=true;Location center=player.getLocation();VFExplosionEvent explosion=new VFExplosionEvent(center);assertSame(center,explosion.getLocation());assertTrue(explosion.doesBlockDamage());explosion.setBlockDamage(false);assertFalse(explosion.doesBlockDamage());assertFalse(explosion.isCancelled());explosion.setCancelled(true);assertTrue(explosion.isCancelled());assertSame(VFExplosionEvent.getHandlerList(),explosion.getHandlers());}finally{Cache.blockDamage=before;}
    }

    @Test void cacheCleanupRemovesProjectilesAndOnlyTemporaryLights() {
        Set<Location> old=new HashSet<>(Cache.lightLocations);try{new Cache();Entity projectile=r.entity(Entity.class,new Location(r.world,0,64,0));Cache.projectiles.add(null);Cache.projectiles.add(projectile);Cache.removeProjectiles();verify(projectile).remove();Location light=new Location(r.world,0,64,0),stone=new Location(r.world,1,64,0);r.block(0,64,0,Material.LIGHT);r.block(1,64,0,Material.STONE);Cache.lightLocations.clear();Cache.lightLocations.addAll(List.of(light,stone));Cache.removeLights();assertEquals(Material.AIR,light.getBlock().getType());assertEquals(Material.STONE,stone.getBlock().getType());}finally{Cache.lightLocations.clear();Cache.lightLocations.addAll(old);}
    }

    @Test void applyingTrackStylePublishesTheConfiguredModelsAndOffset() {
        String small=Cache.trackItemSmall,medium=Cache.trackItemMedium,large=Cache.trackItemLarge,appliedSmall=Cache.appliedTrackItemSmall,appliedMedium=Cache.appliedTrackItemMedium,appliedLarge=Cache.appliedTrackItemLarge;double offset=Cache.trackDisplayYOffset,appliedOffset=Cache.appliedTrackDisplayYOffset;
        try{Cache.trackItemSmall="small";Cache.trackItemMedium="medium";Cache.trackItemLarge="large";Cache.trackDisplayYOffset=.75;Cache.applyTrackDisplayStyle();assertEquals("small",Cache.appliedTrackItemSmall);assertEquals("medium",Cache.appliedTrackItemMedium);assertEquals("large",Cache.appliedTrackItemLarge);assertEquals(.75,Cache.appliedTrackDisplayYOffset);}finally{Cache.trackItemSmall=small;Cache.trackItemMedium=medium;Cache.trackItemLarge=large;Cache.appliedTrackItemSmall=appliedSmall;Cache.appliedTrackItemMedium=appliedMedium;Cache.appliedTrackItemLarge=appliedLarge;Cache.trackDisplayYOffset=offset;Cache.appliedTrackDisplayYOffset=appliedOffset;}
    }

    @Test void loggerRoutesSeverityPluginNamesAndOnlineCreatorMessages() {
        r.close();r=null;try(var bukkit=mockStatic(Bukkit.class)){
            Logger logger=mock(Logger.class);bukkit.when(Bukkit::getLogger).thenReturn(logger);JavaPlugin plugin=mock(JavaPlugin.class);when(plugin.getName()).thenReturn("TestPlugin");Player player=mock(Player.class);new VFLogger();VFLogger.log("broken");VFLogger.info("ready");VFLogger.log(plugin,"bad config");VFLogger.info(plugin,"loaded");verify(logger).warning("[VehicleFramework] Error! broken");verify(logger).info("[VehicleFramework] ready");verify(logger).warning("[TestPlugin] Error! bad config");verify(logger).info("[TestPlugin] loaded");VFLogger.message(player,"hello");verify(player).sendMessage("§a[VehicleFramework] §ehello");VFLogger.creatorLog("first");bukkit.when(()->Bukkit.getPlayerExact("drefvelin")).thenReturn(player);VFLogger.creatorLog("offline");verify(player,never()).sendMessage(contains("offline"));when(player.isOnline()).thenReturn(true);VFLogger.creatorLog("trace");verify(player).sendMessage("§a[VFLogger]§e: §btrace");
        }
    }
}
