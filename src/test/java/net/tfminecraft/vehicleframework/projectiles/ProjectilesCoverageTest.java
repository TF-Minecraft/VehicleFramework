package net.tfminecraft.vehicleframework.projectiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.mockito.*;
import net.tfminecraft.vehicleframework.*;
import net.tfminecraft.vehicleframework.cache.Cache;
import net.tfminecraft.vehicleframework.managers.VehicleManager;
import net.tfminecraft.vehicleframework.test.RegistryFixture;
import net.tfminecraft.vehicleframework.util.ExplosionCreator;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.handlers.train.DeckBody;
import net.tfminecraft.vehicleframework.weapons.*;
import net.tfminecraft.vehicleframework.weapons.ammunition.Bullet;

public class ProjectilesCoverageTest {
    @BeforeAll static void registries() { RegistryFixture.initialize(); assertTrue(Material.AIR.isAir()); assertTrue(Material.STONE.isSolid()); }
    Rig r;
    @BeforeEach void setup() { r = new Rig(); }
    @AfterEach void cleanup() throws Exception { r.close(); }

    @Test void solidWallStopsBulletBeforeLivingTarget() {
        r.block(0,64,2,Material.STONE);
        LivingEntity target=r.entity(LivingEntity.class,new Location(r.world,0.5,64.5,4.5));
        r.rayTargets.add(target);
        try(var explosions=mockStatic(ExplosionCreator.class)) {
            assertTrue(segment(r.bullet(false),null,5));
            explosions.verifyNoInteractions();
            verify(r.world).playSound(any(Location.class),eq(Sound.BLOCK_STONE_BREAK),eq(1f),eq(2f));
        }
    }

    @Test void weaponCanOverrideBulletExplosionPolicyAndBlastSize() throws Exception {
        Bullet bullet=r.bullet(false);
        YamlConfiguration config=Rig.yaml("fixed: true\nprojectile-explosive: true\nprojectile-yield: 4\nprojectile-radius: 9\nprojectile-damage: 25\nprojectile-damage-type: FIRE\n");
        ActiveWeapon weapon=new ActiveWeapon(mock(com.ticxo.modelengine.api.model.ActiveModel.class),r.vehicle(),new Weapon("gun",config),null);
        try(var explosions=mockStatic(ExplosionCreator.class)) {
            BulletRaycast.triggerExplosion(new Location(r.world,0,64,0),bullet,weapon);
            explosions.verify(()->ExplosionCreator.triggerExplosion(any(Location.class),eq(4d),eq(9d),eq(25d),eq("FIRE")));
        }
    }

    @Test void hitCheckerChecksEachNeighbourAndIgnoresLightAndWater() {
        HitChecker checker=new HitChecker(); Entity projectile=r.entity(Entity.class,new Location(r.world,0,64,0));
        int[][] offsets={{0,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
        for(int[] d:offsets) {
            Block block=r.block(d[0],64+d[1],d[2],Material.STONE);
            assertTrue(checker.hasHitLocation(projectile.getLocation())); assertTrue(checker.hasHit(projectile,List.of())); assertTrue(checker.hasHitIgnoreWater(projectile,List.of()));
            block.setType(Material.LIGHT); assertFalse(checker.hasHitLocation(projectile.getLocation())); assertFalse(checker.hasHit(projectile,List.of())); assertFalse(checker.hasHitIgnoreWater(projectile,List.of())); block.setType(Material.AIR);
        }
        for(Material water:List.of(Material.WATER,Material.KELP,Material.KELP_PLANT,Material.SEAGRASS,Material.TALL_SEAGRASS)) { r.block(0,64,0,water); assertFalse(checker.hasHitIgnoreWater(projectile,List.of())); }
        Block logged=r.block(0,64,0,Material.OAK_SLAB); Waterlogged data=mock(Waterlogged.class); when(data.isWaterlogged()).thenReturn(true);when(logged.getBlockData()).thenReturn(data);assertFalse(checker.hasHitIgnoreWater(projectile,List.of()));when(data.isWaterlogged()).thenReturn(false);assertTrue(checker.hasHitIgnoreWater(projectile,List.of()));
    }

    @Test void hitCheckerUsesBoundsAndIgnoresSelfPassengersAndMarkers() {
        HitChecker checker=new HitChecker();Entity projectile=r.entity(Entity.class,new Location(r.world,0,64,0));Entity ignored=r.entity(Entity.class,new Location(r.world,0,64,0));ArmorStand marker=r.entity(ArmorStand.class,new Location(r.world,0,64,0));when(marker.isMarker()).thenReturn(true);
        Entity miss=r.entity(Entity.class,new Location(r.world,.45,64,0));Entity far=r.entity(Entity.class,new Location(r.world,6,64,0));when(far.getBoundingBox()).thenReturn(new BoundingBox(-.1,63.9,-.1,6.2,64.2,.2));Entity hit=r.entity(Entity.class,new Location(r.world,0.1,64,0));
        r.nearby.addAll(List.of(projectile,ignored,marker,miss,far));assertFalse(checker.hasHit(projectile,List.of(ignored)));r.nearby.remove(marker);assertFalse(checker.hasHitIgnoreWater(projectile,List.of(ignored)));assertTrue(checker.getHitEntities(projectile,List.of(ignored),0.1).isEmpty());
        r.nearby.add(hit);assertTrue(checker.hasHit(projectile,List.of(ignored)));assertTrue(checker.hasHitIgnoreWater(projectile,List.of(ignored)));assertEquals(List.of(hit),checker.getHitEntities(projectile,List.of(ignored),0.1));
    }

    @Test void collisionShapesRespectPassabilityLocalCoordinatesAndEmptyShapes() {
        Block b=r.block(4,64,7,Material.STONE);assertTrue(BulletRaycast.intersectsCollision(b,new Location(r.world,4.5,64.5,7.5)));assertFalse(BulletRaycast.intersectsCollision(b,new Location(r.world,3.5,64.5,7.5)));
        when(b.isPassable()).thenReturn(true);assertFalse(BulletRaycast.intersectsCollision(b,new Location(r.world,4.5,64.5,7.5)));when(b.isPassable()).thenReturn(false);VoxelShape empty=mock(VoxelShape.class);when(empty.getBoundingBoxes()).thenReturn(List.of());when(b.getCollisionShape()).thenReturn(empty);assertFalse(BulletRaycast.intersectsCollision(b,new Location(r.world,4.5,64.5,7.5)));
    }

    @Test void bulletsFilterShooterDeckDeadAndNonVehicleEntities() {
        Bullet bullet=r.bullet(false);Entity ignored=r.entity(Entity.class,new Location(r.world,0.5,64.5,1));Entity deck=r.entity(Entity.class,new Location(r.world,0.5,64.5,2));LivingEntity dead=r.entity(LivingEntity.class,new Location(r.world,0.5,64.5,3));when(dead.isDead()).thenReturn(true);Entity decoration=r.entity(Entity.class,new Location(r.world,0.5,64.5,4));r.rayTargets.addAll(List.of(ignored,deck,dead,decoration));r.decks.when(()->DeckBody.isPart(deck)).thenReturn(true);
        assertFalse(BulletRaycast.handleSegment(new Location(r.world,0.5,64.5,0),new Location(r.world,0.5,64.5,5),Set.of(ignored),null,bullet.getData(),bullet,null,List.of()));
        assertFalse(BulletRaycast.handleSegment(new Location(r.world,0,0,0),new Location(r.world,0,0,0),Set.of(),null,bullet.getData(),bullet,null,List.of()));
    }

    @Test void fragmentsBurnVehiclesTrailAndStopOnImpactOrDeath() {
        ActiveVehicle source=r.vehicle();Player observer=r.entity(Player.class,new Location(r.world,0,64,0));when(source.getNearbyPlayers()).thenReturn(List.of(observer));List<Entity> projectiles=new ArrayList<>();new Fragment(source,r.world,new Location(r.world,0,64,0),projectiles);ArmorStand fragment=(ArmorStand)projectiles.getFirst();
        Vector initial=fragment.getVelocity();assertTrue(initial.getY()>=1.5&&initial.getY()<2);assertTrue(Math.abs(initial.getX())<=3&&Math.abs(initial.getZ())<=3);verify(fragment).setInvisible(true);verify(fragment).setSmall(true);
        Entity target=r.entity(Entity.class,new Location(r.world,0.1,64,0));ActiveVehicle victim=r.vehicle();when(source.getVehicleManager().get(target)).thenReturn(victim);r.nearby.add(target);r.tick();verify(victim).randomFire();verify(observer).spawnParticle(eq(Particle.FLAME),any(Location.class),eq(0),eq(0d),eq(0d),eq(0d),eq(0.2d));
        r.nearby.clear();r.ticks(10);r.block(0,64,0,Material.STONE);r.tick();verify(fragment).remove();assertEquals(0,r.pending());
        new Fragment(source,r.world,new Location(r.world,5,64,0),new ArrayList<>());ArmorStand another=(ArmorStand)r.spawned.getLast();when(another.isDead()).thenReturn(true);r.tick();verify(another).remove();
    }

    @Test void livingTargetsReceiveDirectDamageOrConfiguredExplosions() {
        LivingEntity target=r.entity(LivingEntity.class,new Location(r.world,.5,64.5,2));r.rayTargets.add(target);
        try(var explosions=mockStatic(ExplosionCreator.class)){assertTrue(segment(r.bullet(false),null,5));explosions.verify(()->ExplosionCreator.applyDamage(target,8d,"PROJECTILE"));assertTrue(segment(r.bullet(true),null,5));explosions.verify(()->ExplosionCreator.triggerExplosion(any(Location.class),eq(1d),eq(5d),eq(8d),eq("PROJECTILE")));}
    }

    @Test void vehicleTargetsReceiveDamageExceptForTheShooterItself() {
        Entity target=r.entity(Entity.class,new Location(r.world,.5,64.5,2));ActiveVehicle victim=r.vehicle(),shooter=r.vehicle();when(r.manager.get(target)).thenReturn(victim);r.rayTargets.add(target);
        try(var explosions=mockStatic(ExplosionCreator.class)){assertTrue(segment(r.bullet(false),null,5));verify(victim).damage("PROJECTILE",8);Bullet explosive=r.bullet(true);assertTrue(BulletRaycast.handleSegment(new Location(r.world,.5,64.5,0),new Location(r.world,.5,64.5,5),Set.of(),shooter,explosive.getData(),explosive,null,List.of()));explosions.verify(()->ExplosionCreator.triggerExplosion(any(Location.class),eq(1d),eq(5d),eq(8d),eq("PROJECTILE")));Bullet regular=r.bullet(false);String victimId=victim.getUUID().toUpperCase(Locale.ROOT);when(shooter.getUUID()).thenReturn(victimId);assertFalse(BulletRaycast.handleSegment(new Location(r.world,.5,64.5,0),new Location(r.world,.5,64.5,5),Set.of(),shooter,regular.getData(),regular,null,List.of()));verify(victim,times(2)).damage("PROJECTILE",8);}
    }

    @Test void leavesAndGlassAllowBulletsThroughWhileSolidBlocksStopThem() {
        r.block(0,64,1,Material.OAK_LEAVES);r.block(0,64,2,Material.GLASS);Block hollow=r.block(0,64,3,Material.STONE);when(hollow.isPassable()).thenReturn(true);assertFalse(segment(r.bullet(false),null,5));verify(r.world,atLeastOnce()).playSound(any(Location.class),eq(Sound.BLOCK_GLASS_BREAK),eq(.8f),eq(1.2f));
        r.block(0,64,4,Material.STONE);try(var explosions=mockStatic(ExplosionCreator.class)){assertTrue(segment(r.bullet(true),null,5));explosions.verify(()->ExplosionCreator.triggerExplosion(any(Location.class),eq(1d),eq(5d),eq(8d),eq("PROJECTILE")));}
        assertTrue(BulletRaycast.isBulletPassable(Material.GLASS_PANE));assertFalse(BulletRaycast.isBulletPassable(Material.STONE));
    }

    @Test void blockSamplingStopsAtTheActualSegmentEndpoint() {
        r.block(0,64,1,Material.STONE);Bullet bullet=r.bullet(false);assertFalse(BulletRaycast.handleSegment(new Location(r.world,.5,64.5,.6),new Location(r.world,.5,64.5,.95),Set.of(),null,bullet.getData(),bullet,null,List.of()));
    }

    private boolean segment(Bullet bullet,ActiveWeapon weapon,double length) {return BulletRaycast.handleSegment(new Location(r.world,0.5,64.5,0),new Location(r.world,0.5,64.5,length),Set.of(),null,bullet.getData(),bullet,weapon,List.of());}

    /** Stateful external server boundary; production movement, collisions and timers run unchanged. */
    public static final class Rig implements AutoCloseable {
        public final World world=mock(World.class); public final BukkitScheduler scheduler=mock(BukkitScheduler.class);
        public final VehicleManager manager=mock(VehicleManager.class); public final List<Entity> nearby=new ArrayList<>(),rayTargets=new ArrayList<>(),spawned=new ArrayList<>();
        public final MockedStatic<Bukkit> bukkit; public final MockedStatic<VehicleFramework> framework; public final MockedStatic<DeckBody> decks;
        private final MockedStatic<VFLogger> logger; private final VehicleFramework oldPlugin; private final List<Entity> oldProjectiles;
        private final Map<String,Block> blocks=new HashMap<>(); private final List<Scheduled> scheduled=new ArrayList<>();private long tick;private int nextId=1;
        public Rig() {
            RegistryFixture.initialize();bukkit=mockStatic(Bukkit.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);logger=mockStatic(VFLogger.class);oldPlugin=VehicleFramework.plugin;VehicleFramework.plugin=mock(VehicleFramework.class);framework=mockStatic(VehicleFramework.class);framework.when(VehicleFramework::getVehicleManager).thenReturn(manager);decks=mockStatic(DeckBody.class);
            oldProjectiles=new ArrayList<>(Cache.projectiles);Cache.projectiles.clear();when(world.getName()).thenReturn("weapons");when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(c->block(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
            when(world.getBlockAt(any(Location.class))).thenAnswer(c->{Location l=c.getArgument(0);return block(l.getBlockX(),l.getBlockY(),l.getBlockZ());});
            when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble())).thenAnswer(c->{Location center=c.getArgument(0);double x=c.getArgument(1),y=c.getArgument(2),z=c.getArgument(3);BoundingBox query=new BoundingBox(center.getX()-x,center.getY()-y,center.getZ()-z,center.getX()+x,center.getY()+y,center.getZ()+z);return nearby.stream().filter(e->e.getBoundingBox().overlaps(query)).toList();});
            when(world.rayTraceEntities(any(Location.class),any(Vector.class),anyDouble(),anyDouble(),any())).thenAnswer(c->{Location from=c.getArgument(0);double range=c.getArgument(2);Predicate<Entity> filter=c.getArgument(4);return rayTargets.stream().filter(filter).filter(e->e.getLocation().distance(from)<=range).min(Comparator.comparingDouble(e->e.getLocation().distanceSquared(from))).map(e->new RayTraceResult(e.getLocation().toVector(),e)).orElse(null);});
            when(world.spawn(any(Location.class),any(Class.class))).thenAnswer(c->{Entity e=entity(c.getArgument(1),c.getArgument(0));spawned.add(e);return e;});
            when(world.spawn(any(Location.class),any(Class.class),any(Consumer.class))).thenAnswer(c->{Entity e=entity(c.getArgument(1),c.getArgument(0));((Consumer<Entity>)c.getArgument(2)).accept(e);spawned.add(e);return e;});
            when(scheduler.runTaskTimer(any(),any(Runnable.class),anyLong(),anyLong())).thenAnswer(c->schedule(c.getArgument(1),c.getArgument(2),c.getArgument(3)));
            when(scheduler.runTaskLater(any(),any(Runnable.class),anyLong())).thenAnswer(c->schedule(c.getArgument(1),c.getArgument(2),0));
            doAnswer(c->{int id=c.getArgument(0);scheduled.stream().filter(s->s.id==id).forEach(s->s.cancelled=true);return null;}).when(scheduler).cancelTask(anyInt());
        }
        private BukkitTask schedule(Runnable run,long delay,long period) {Scheduled s=new Scheduled(nextId++,run,tick+Math.max(1,delay),period);scheduled.add(s);BukkitTask task=mock(BukkitTask.class);when(task.getTaskId()).thenReturn(s.id);return task;}
        public void tick(){tick++;for(Scheduled s:new ArrayList<>(scheduled))if(!s.cancelled&&s.due<=tick){s.run.run();if(s.period==0)s.cancelled=true;else s.due=tick+s.period;}}
        public void ticks(int count){for(int i=0;i<count;i++)tick();}public int pending(){return(int)scheduled.stream().filter(s->!s.cancelled).count();}
        public Block block(int x,int y,int z,Material type){Block b=block(x,y,z);b.setType(type);return b;}
        public Block block(int x,int y,int z){return blocks.computeIfAbsent(x+":"+y+":"+z,key->{Block b=mock(Block.class);AtomicReference<Material> type=new AtomicReference<>(Material.AIR);AtomicReference<BlockData> data=new AtomicReference<>(mock(BlockData.class));when(b.getType()).thenAnswer(c->type.get());doAnswer(c->{type.set(c.getArgument(0));if(type.get()==Material.LIGHT)data.set(mock(Levelled.class));return null;}).when(b).setType(any());when(b.getBlockData()).thenAnswer(c->data.get());doAnswer(c->{data.set(c.getArgument(0));return null;}).when(b).setBlockData(any(),anyBoolean());when(b.getLocation()).thenAnswer(c->new Location(world,x,y,z));when(b.getWorld()).thenReturn(world);when(b.isPassable()).thenAnswer(c->Set.of(Material.AIR,Material.LIGHT,Material.WATER,Material.KELP,Material.KELP_PLANT,Material.SEAGRASS,Material.TALL_SEAGRASS).contains(type.get()));VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0,0,0,1,1,1)));when(b.getCollisionShape()).thenReturn(shape);return b;});}
        public <T extends Entity>T entity(Class<T> type,Location start){T e=mock(type,RETURNS_DEEP_STUBS);AtomicReference<Location> location=new AtomicReference<>(start.clone());AtomicReference<Vector> velocity=new AtomicReference<>(new Vector());when(e.getLocation()).thenAnswer(c->location.get().clone());when(e.getWorld()).thenAnswer(c->location.get().getWorld());when(e.getUniqueId()).thenReturn(UUID.randomUUID());when(e.getBoundingBox()).thenAnswer(c->{Location l=location.get();return new BoundingBox(l.getX()-.2,l.getY()-.2,l.getZ()-.2,l.getX()+.2,l.getY()+.2,l.getZ()+.2);});when(e.getVelocity()).thenAnswer(c->velocity.get().clone());doAnswer(c->{velocity.set(((Vector)c.getArgument(0)).clone());return null;}).when(e).setVelocity(any());when(e.teleport(any(Location.class))).thenAnswer(c->{location.set(((Location)c.getArgument(0)).clone());return true;});when(e.isDead()).thenReturn(false);when(e.isOnGround()).thenReturn(false);when(e.isValid()).thenReturn(true);if(e instanceof Player p){when(p.getEyeLocation()).thenAnswer(c->location.get().clone());when(p.isOnline()).thenReturn(true);}return e;}
        public ActiveVehicle vehicle(){ActiveVehicle v=mock(ActiveVehicle.class,RETURNS_DEEP_STUBS);Entity root=entity(Entity.class,new Location(world,0,64,0));when(v.getUUID()).thenReturn(UUID.randomUUID().toString());when(v.getEntity()).thenReturn(root);when(v.getSeatHandler().getPassengers()).thenReturn(List.of());when(v.getAccessPanel().getRotator(anyString())).thenReturn(null);return v;}
        public Bullet bullet(boolean explosive){YamlConfiguration yaml=new YamlConfiguration();yaml.set("type","BULLET");yaml.set("explosive",explosive);return new Bullet("bullet",yaml);}
        public static YamlConfiguration yaml(String text)throws Exception{YamlConfiguration y=new YamlConfiguration();y.loadFromString(text);return y;}
        @Override public void close(){Cache.projectiles.clear();Cache.projectiles.addAll(oldProjectiles);decks.close();framework.close();VehicleFramework.plugin=oldPlugin;logger.close();bukkit.close();}
        private static final class Scheduled {final int id;final Runnable run;long due;final long period;boolean cancelled;Scheduled(int id,Runnable run,long due,long period){this.id=id;this.run=run;this.due=due;this.period=period;}}
    }
}
