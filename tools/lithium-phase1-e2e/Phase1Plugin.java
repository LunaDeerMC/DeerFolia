package phase1;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.InsideBlockEffectApplier.StepBasedCollector;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import cn.lunadeer.mc.deerfolia.async.path.AsyncPath;
import cn.lunadeer.mc.deerfolia.async.path.AsyncPathProcessor;

/** External, ignored integration harness. Every world access runs on the owner's region. */
public final class Phase1Plugin extends JavaPlugin implements CommandExecutor {
    private static final int WARMUP_ROUNDS=Integer.getInteger("phase1.warmupRounds",20);
    private static final int SAMPLE_ROUNDS=Integer.getInteger("phase1.sampleRounds",20);
    private static final String[] FLAGS={"blockEffects","heightmaps","gameEvents","brainTasks","brainMemory","blockSearch","blockEntitySleeping"};
    private static final Heightmap.Types[] TYPES={Heightmap.Types.MOTION_BLOCKING,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,Heightmap.Types.OCEAN_FLOOR,Heightmap.Types.WORLD_SURFACE};
    private final Map<String,Context> contexts=new ConcurrentHashMap<>();
    private final ThreadMXBean allocation=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    private boolean optimized;
    private boolean baseline;
    private volatile long blackhole;
    record Context(Player player,ServerLevel level,BlockPos base) {
        BlockPos pos(int x,int y,int z) { return base.offset(x,y,z); }
        org.bukkit.block.Block block(int x,int y,int z) { BlockPos p=pos(x,y,z); return player.getWorld().getBlockAt(p.getX(),p.getY(),p.getZ()); }
    }
    private void pass(String name) { getLogger().info("PHASE1_PASS "+name); }
    private void check(boolean ok,String name) { if(!ok) throw new AssertionError(name); pass(name); }
    private void guarded(Runnable action) { try { action.run(); } catch(Throwable t) { getLogger().log(java.util.logging.Level.SEVERE,"PHASE1_FAIL",t); } }
    private void later(Player p,long ticks,Runnable action) { p.getScheduler().runDelayed(this,t -> guarded(action),() -> getLogger().severe("PHASE1_FAIL retired-player"),ticks); }
    private static Object field(Object target,String name) {
        try { Class<?> type=target.getClass(); while(type!=null) { try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(NoSuchFieldException e) {type=type.getSuperclass();} } throw new NoSuchFieldException(name); }
        catch(Exception e) {throw new RuntimeException(e);}
    }
    private static void setField(Object target,String name,Object value) {
        try { Class<?> type=target.getClass(); while(type!=null) { try { Field f=type.getDeclaredField(name); f.setAccessible(true); f.set(target,value); return; } catch(NoSuchFieldException e) {type=type.getSuperclass();} } throw new NoSuchFieldException(name); }
        catch(Exception e) {throw new RuntimeException(e);}
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args) {
        try { Class<?> type=target.getClass(); while(type!=null) { try { Method m=type.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(target,args); } catch(NoSuchMethodException e) {type=type.getSuperclass();} } throw new NoSuchMethodException(name); }
        catch(Exception e) {throw new RuntimeException(e);}
    }
    @Override public void onEnable() {
        getCommand("phase1").setExecutor(this);
        if(allocation.isThreadAllocatedMemorySupported()) allocation.setThreadAllocatedMemoryEnabled(true);
        if(allocation.isThreadCpuTimeSupported()) allocation.setThreadCpuTimeEnabled(true);
        optimized=Boolean.getBoolean("phase1.optimized");baseline=Boolean.getBoolean("phase1.baseline");
        guarded(() -> {
            if(!baseline)for(String flag:FLAGS) check((boolean)field(DeerFoliaConfiguration.lithiumOptimize,flag)==optimized,"config-"+flag+"-"+optimized);
            check(DeerFoliaConfiguration.lithiumOptimize.enabled,"existing-lithium-master-enabled");
            pass("plugin-enabled");
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(args.length<2) return false;
        Player p=Bukkit.getPlayerExact(args[1]);
        if(p==null) {getLogger().severe("PHASE1_FAIL missing-player-"+args[1]);return true;}
        p.getScheduler().run(this,t -> guarded(() -> {
            switch(args[0]) {
                case "setup" -> setup(p);
                case "functional" -> functional(contexts.get(p.getName()));
                case "bench" -> benchmark(contexts.get(p.getName()),0);
                case "restart" -> restart(p);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }),null);
        return true;
    }
    private void setup(Player p) {
        p.setGameMode(GameMode.CREATIVE);p.setGravity(false);p.setInvulnerable(true);
        int origin=p.getName().equals("TestActive")?2056:8;
        Location target=new Location(p.getWorld(),origin+.5,99,origin+.5);
        p.teleportAsync(target).thenAccept(ok -> later(p,20,() -> {
            check(ok && Bukkit.isOwnedByCurrentRegion(p),"owner-teleport-"+p.getName());
            Context c=new Context(p,((CraftWorld)p.getWorld()).getHandle(),new BlockPos(origin,96,origin));
            contexts.put(p.getName(),c);
            for(int x=-24;x<=24;x++) for(int z=-24;z<=24;z++) c.level.setBlock(c.pos(x,-1,z),Blocks.STONE.defaultBlockState(),18);
            for(int x=-8;x<=12;x++) for(int z=-8;z<=12;z++) for(int y=0;y<4;y++) c.level.setBlock(c.pos(x,y,z),Blocks.AIR.defaultBlockState(),18);
            c.block(10,0,10).setType(Material.DIAMOND_BLOCK,false);
            c.block(5,0,0).setType(Material.CRAFTER);
            c.block(5,0,2).setType(Material.CAMPFIRE);
            c.block(5,0,4).setType(Material.SHULKER_BOX);
            c.block(5,0,6).setType(Material.HOPPER);
            c.block(5,-1,6).setType(Material.CHEST);
            c.block(6,0,6).setType(Material.REDSTONE_BLOCK);
            var hopper=(org.bukkit.block.Hopper)c.block(5,0,6).getState(false);
            hopper.getInventory().setItem(0,new org.bukkit.inventory.ItemStack(Material.DIAMOND,8));
            later(p,25,() -> { sleepInitial(c); pass("setup-complete-"+p.getName()); });
        }));
    }
    private boolean sleeping(BlockEntity be) {if(baseline)return false;return (boolean)invoke(be,"deerfoliaIsSleeping",new Class<?>[0]);}
    private BlockEntity blockEntity(Context c,int z) {return c.level.getBlockEntity(c.pos(5,0,z));}
    private void sleepInitial(Context c) {
        for(int z:new int[]{0,2,4,6}) check(sleeping(blockEntity(c,z))==optimized,"idle-sleep-state-"+z+"-"+c.player.getName());
        var hopper=(org.bukkit.block.Hopper)c.block(5,0,6).getState(false);
        check(hopper.getInventory().getItem(0).getAmount()==8,"locked-hopper-retains-items-"+c.player.getName());
    }
    private void functional(Context c) {
        check(c!=null && Bukkit.isOwnedByCurrentRegion(c.player),"functional-owner-"+c.player.getName());
        collector(c); heightmap(c); gameEvents(c); brains(c); eggs(c); wakeup(c); paths(c); villageWorldIsolation(c);
        pass("functional-sync-complete-"+c.player.getName());
    }
    private void collector(Context c) {
        var nms=((CraftPlayer)c.player).getHandle();
        StepBasedCollector empty=new StepBasedCollector();
        for(int i=0;i<10;i++) empty.advanceStep(i,c.base); empty.applyAndClear(nms);
        if(optimized) for(String f:List.of("effectsInStep","beforeEffectsInStep","afterEffectsInStep","finalEffects")) check(field(empty,f)==null,"empty-collector-unallocated-"+f+"-"+c.player.getName());
        List<Integer> order=new ArrayList<>();StepBasedCollector collect=new StepBasedCollector();
        collect.advanceStep(1,c.base);
        collect.runAfter(InsideBlockEffectType.EXTINGUISH,e -> order.add(4));
        collect.runBefore(InsideBlockEffectType.FREEZE,e -> order.add(1));
        collect.runAfter(InsideBlockEffectType.FREEZE,e -> order.add(2));
        collect.runBefore(InsideBlockEffectType.EXTINGUISH,e -> order.add(3));
        collect.advanceStep(2,c.base.above());collect.runBefore(InsideBlockEffectType.FREEZE,e -> order.add(5));
        collect.applyAndClear(nms);collect.applyAndClear(nms);
        check(order.equals(List.of(1,2,3,4,5)),"collector-order-clear-reuse-"+c.player.getName());
        collect.advanceStep(3,c.base);collect.apply(InsideBlockEffectType.FREEZE);collect.apply(InsideBlockEffectType.CLEAR_FREEZE);collect.applyAndClear(nms);
        check(nms.getTicksFrozen()==0,"collector-real-freeze-clear-"+c.player.getName());
    }
    private void oracle(Context c,BlockPos p) {
        LevelChunk chunk=c.level.getChunkAt(p);
        for(Heightmap.Types type:TYPES) {
            int expected=c.level.getMinY();
            for(int y=c.level.getMaxY();y>=c.level.getMinY();y--) if(type.isOpaque().test(chunk.getBlockState(new BlockPos(p.getX(),y,p.getZ())))) {expected=y+1;break;}
            int actual=chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(p.getX()&15,p.getZ()&15);
            if(actual!=expected) throw new AssertionError("heightmap "+type+" at "+p+" expected="+expected+" actual="+actual);
        }
    }
    private void heightmap(Context c) {
        BlockPos p=c.pos(-5,32,-5);Random random=new Random(719225L);
        BlockState[] states={Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.OAK_LEAVES.defaultBlockState(),Blocks.SHORT_GRASS.defaultBlockState(),Blocks.LAVA.defaultBlockState()};
        for(int i=0;i<160;i++) {BlockPos at=p.offset(i%3,random.nextInt(40),i%2);c.level.setBlock(at,states[random.nextInt(states.length)],18);oracle(c,at);}
        for(int y=72;y>=0;y--) for(int x=0;x<3;x++) for(int z=0;z<2;z++) c.level.setBlock(p.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        oracle(c,p);pass("heightmap-random-four-type-oracle-"+c.player.getName());
    }
    private int registries(Context c) {
        int sum=0;
        for(int x=(c.base.getX()>>4)-2;x<=(c.base.getX()>>4)+2;x++) for(int z=(c.base.getZ()>>4)-2;z<=(c.base.getZ()>>4)+2;z++) {
            var chunk=c.level.getChunkIfLoadedImmediately(x,z);
            if(chunk instanceof LevelChunk lc) sum+=((Map<?,?>)field(lc,"gameEventListenerRegistrySections")).size();
        }
        return sum;
    }
    private GameEventListener listener(BlockPos pos,AtomicInteger counter,GameEventListener.DeliveryMode mode) {
        return new GameEventListener() {
            public PositionSource getListenerSource(){return new BlockPositionSource(pos);}
            public int getListenerRadius(){return 48;}
            public DeliveryMode getDeliveryMode(){return mode;}
            public boolean handleGameEvent(ServerLevel level,Holder<GameEvent> e,GameEvent.Context context,Vec3 source){counter.incrementAndGet();return true;}
        };
    }
    private void gameEvents(Context c) {
        BlockPos source=c.pos(0,48,0);int before=registries(c);
        for(int i=0;i<50;i++) c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        int after=registries(c);
        check(!optimized || before==after,"game-event-empty-no-new-registries-"+c.player.getName());
        AtomicInteger immediate=new AtomicInteger(),distance=new AtomicInteger();
        GameEventListener a=listener(source,immediate,GameEventListener.DeliveryMode.UNSPECIFIED),b=listener(source.above(),distance,GameEventListener.DeliveryMode.BY_DISTANCE);
        var registry=c.level.getChunkAt(source).getListenerRegistry(SectionPos.blockToSectionCoord(source.getY()));
        registry.register(a);registry.register(b);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        check(immediate.get()==1 && distance.get()==1,"game-event-both-delivery-modes-"+c.player.getName());
        var apiListener=new org.bukkit.event.Listener() {};
        Bukkit.getPluginManager().registerEvent(org.bukkit.event.world.GenericGameEvent.class,apiListener,org.bukkit.event.EventPriority.NORMAL,(l,e) -> {
            var ge=(org.bukkit.event.world.GenericGameEvent)e;
            if(ge.getLocation().getWorld().equals(c.player.getWorld()) && ge.getLocation().getBlockX()==source.getX() && ge.getLocation().getBlockY()==source.getY()) ge.setCancelled(true);
        },this);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        org.bukkit.event.HandlerList.unregisterAll(apiListener);
        check(immediate.get()==1 && distance.get()==1,"game-event-bukkit-cancel-"+c.player.getName());
        registry.unregister(a);registry.unregister(b);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        check(immediate.get()==1 && distance.get()==1,"game-event-unregister-"+c.player.getName());
        BlockPos far=source.offset(32,0,0);AtomicInteger distant=new AtomicInteger();
        var remote=listener(far,distant,GameEventListener.DeliveryMode.UNSPECIFIED);
        var remoteRegistry=c.level.getChunkAt(far).getListenerRegistry(far.getY()>>4);remoteRegistry.register(remote);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        check(distant.get()==0,"game-event-default-radius-"+c.player.getName());
        var radiusListener=new org.bukkit.event.Listener(){};
        Bukkit.getPluginManager().registerEvent(org.bukkit.event.world.GenericGameEvent.class,radiusListener,org.bukkit.event.EventPriority.NORMAL,(l,e) -> {
            var ge=(org.bukkit.event.world.GenericGameEvent)e;
            if(ge.getLocation().getWorld().equals(c.player.getWorld()) && ge.getLocation().getBlockX()==source.getX() && ge.getLocation().getBlockY()==source.getY())ge.setRadius(48);
        },this);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
        org.bukkit.event.HandlerList.unregisterAll(radiusListener);remoteRegistry.unregister(remote);
        check(distant.get()==1,"game-event-bukkit-radius-expansion-"+c.player.getName());
        AtomicInteger reentered=new AtomicInteger();
        GameEventListener recursive=new GameEventListener(){
            public PositionSource getListenerSource(){return new BlockPositionSource(source);}
            public int getListenerRadius(){return 16;}
            public boolean handleGameEvent(ServerLevel level,Holder<GameEvent> e,GameEvent.Context context,Vec3 pos){if(reentered.incrementAndGet()==1)level.gameEvent(e,source,context);return true;}
        };
        var recursiveRegistry=c.level.getChunkAt(source).getListenerRegistry(source.getY()>>4);recursiveRegistry.register(recursive);
        c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));recursiveRegistry.unregister(recursive);
        check(reentered.get()==2,"game-event-reentrant-delivery-"+c.player.getName());
    }
    private static final class ProbeBehavior extends Behavior<LivingEntity> {
        long starts,ticks,extra;
        ProbeBehavior(){super(com.google.common.collect.ImmutableMap.of(MemoryModuleType.HOME,MemoryStatus.REGISTERED,MemoryModuleType.JOB_SITE,MemoryStatus.REGISTERED,MemoryModuleType.WALK_TARGET,MemoryStatus.REGISTERED,MemoryModuleType.IS_PANICKING,MemoryStatus.VALUE_PRESENT),1);}
        boolean required(LivingEntity e){return super.hasRequiredMemories(e);}
        protected boolean checkExtraStartConditions(ServerLevel l,LivingEntity e){extra++;return true;}
        protected void start(ServerLevel l,LivingEntity e,long t){starts++;}
        protected void tick(ServerLevel l,LivingEntity e,long t){ticks++;}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private void brains(Context c) {
        var api=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+1.5,c.base.getY(),c.base.getZ()+1.5),org.bukkit.entity.Villager.class);api.setAI(false);
        LivingEntity body=((CraftMob)api).getHandle();Brain brain=body.getBrain();brain.removeAllBehaviors();
        ProbeBehavior probe=new ProbeBehavior();brain.addActivity(Activity.IDLE,ImmutableList.of(Pair.of(0,probe)),Set.of(),Set.of());brain.setDefaultActivity(Activity.IDLE);brain.useDefaultActivity();
        brain.eraseMemory(MemoryModuleType.IS_PANICKING);
        check(!probe.required(body) && !probe.required(body),"brain-missing-memory-"+c.player.getName());
        brain.setMemory(MemoryModuleType.IS_PANICKING,true);
        check(probe.required(body),"brain-memory-insert-invalidates-"+c.player.getName());
        brain.tick(c.level,body);check(probe.starts==1 && probe.extra==1,"brain-start-and-extra-condition-"+c.player.getName());
        brain.eraseMemory(MemoryModuleType.IS_PANICKING);check(!probe.required(body),"brain-erase-invalidates-"+c.player.getName());
        brain.setMemoryWithExpiry(MemoryModuleType.IS_PANICKING,true,1);
        brain.tick(c.level,body);brain.tick(c.level,body);brain.tick(c.level,body);
        check(!probe.required(body),"brain-expiry-invalidates-"+c.player.getName());
        ProbeBehavior late=new ProbeBehavior();brain.addActivity(Activity.IDLE,ImmutableList.of(Pair.of(1,late)),Set.of(),Set.of());
        brain.setMemory(MemoryModuleType.IS_PANICKING,true);brain.tick(c.level,body);
        check(late.starts==1,"brain-topology-invalidation-"+c.player.getName());
        brain.removeAllBehaviors();long old=probe.starts+late.starts;brain.tick(c.level,body);
        check(probe.starts+late.starts==old,"brain-remove-all-invalidates-"+c.player.getName());api.remove();
    }
    private static final Method SEARCH_METHOD=searchMethod();
    private static Method searchMethod() {
        try {Method method=MoveToBlockGoal.class.getDeclaredMethod("findNearestBlock");method.setAccessible(true);return method;}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private Object search(RemoveBlockGoal goal) {
        try {return SEARCH_METHOD.invoke(goal);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    private void eggs(Context c) {
        var api=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+.5,c.base.getY(),c.base.getZ()+.5),org.bukkit.entity.Zombie.class);api.setAI(false);api.setInvulnerable(true);
        var mob=(net.minecraft.world.entity.PathfinderMob)((CraftMob)api).getHandle();
        RemoveBlockGoal goal=new RemoveBlockGoal(Blocks.TURTLE_EGG,mob,1,3);
        check(!(boolean)search(goal),"egg-search-empty-"+c.player.getName());
        BlockPos egg=c.pos(17,0,1);c.level.setBlock(egg,Blocks.TURTLE_EGG.defaultBlockState(),18);
        check((boolean)search(goal) && ((BlockPos)field(goal,"blockPos")).equals(egg),"egg-cross-section-added-"+c.player.getName());
        c.level.setBlock(egg.above(),Blocks.STONE.defaultBlockState(),18);
        check(!(boolean)search(goal),"egg-obstructed-rejected-"+c.player.getName());
        c.level.setBlock(egg.above(),Blocks.AIR.defaultBlockState(),18);c.level.setBlock(egg,Blocks.AIR.defaultBlockState(),18);
        check(!(boolean)search(goal),"egg-removal-not-stale-"+c.player.getName());api.remove();
    }
    private void wakeup(Context c) {
        var crafter=(org.bukkit.block.Crafter)c.block(5,0,0).getState(false);crafter.setCraftingTicks(30);
        var campfire=(org.bukkit.block.Campfire)c.block(5,0,2).getState(false);campfire.setCookTimeTotal(0,20);campfire.setCookTime(0,0);campfire.setItem(0,new org.bukkit.inventory.ItemStack(Material.BEEF));campfire.startCooking(0);
        var shulker=(ShulkerBoxBlockEntity)blockEntity(c,4);shulker.triggerEvent(1,1);
        check(!sleeping(blockEntity(c,0)) && !sleeping(blockEntity(c,2)) && !sleeping(shulker),"live-api-immediate-wake-"+c.player.getName());
        c.block(6,0,6).setType(Material.AIR);
        later(c.player,10,() -> {
            check(crafter.getCraftingTicks()<30 && crafter.getCraftingTicks()>0,"crafter-countdown-progress-"+c.player.getName());
            check(campfire.getCookTime(0)>0,"campfire-live-cook-progress-"+c.player.getName());
            var mirror=campfire.getItem(0);int previousProgress=campfire.getCookTime(0);mirror.setAmount(0);
            later(c.player,3,() -> {
                check(!sleeping(blockEntity(c,2)),"campfire-live-zero-mirror-keeps-wakeability-"+c.player.getName());mirror.setAmount(1);
                later(c.player,3,() -> check(campfire.getCookTime(0)>previousProgress,"campfire-live-mirror-revival-cooks-"+c.player.getName()));
            });
            check(shulker.getProgress(1)>0,"shulker-open-progress-"+c.player.getName());shulker.triggerEvent(1,0);
        });
        later(c.player,45,() -> {
            check(crafter.getCraftingTicks()==0,"crafter-countdown-completes-"+c.player.getName());
            check(campfire.getItem(0)==null,"campfire-cooks-to-output-"+c.player.getName());
            var chest=(org.bukkit.block.Chest)c.block(5,-1,6).getState(false);
            check(chest.getBlockInventory().contains(Material.DIAMOND),"hopper-redstone-unlock-transfers-"+c.player.getName());
            c.block(6,0,6).setType(Material.REDSTONE_BLOCK);
            later(c.player,20,() -> {
                for(int z:new int[]{0,2,4,6}) check(sleeping(blockEntity(c,z))==optimized,"returned-idle-state-"+z+"-"+c.player.getName());
                pass("wakeup-complete-"+c.player.getName());
            });
        });
    }
    private void paths(Context c) {
        var cow=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()-23.5,c.base.getY(),c.base.getZ()-23.5),org.bukkit.entity.Cow.class);cow.setPersistent(true);
        later(c.player,10,() -> {
            var mob=((CraftMob)cow).getHandle();
            BlockPos snapshotPos=c.pos(-23,1,-23);
            var region=new net.minecraft.world.level.PathNavigationRegion(c.level,c.pos(-24,-1,-24),c.pos(-22,2,-22));
            Object snapshot=invoke(region,"deerFoliaCreateSnapshot",new Class<?>[]{boolean.class},true);
            check(snapshot!=null,"path-snapshot-created-"+c.player.getName());
            c.level.setBlock(snapshotPos,Blocks.STONE.defaultBlockState(),18);
            check(((BlockState)invoke(snapshot,"getBlockState",new Class<?>[]{BlockPos.class},snapshotPos)).isAir(),"path-snapshot-stable-after-world-edit-"+c.player.getName());
            Object changed=invoke(region,"deerFoliaCreateSnapshot",new Class<?>[]{boolean.class},true);
            check(((BlockState)invoke(changed,"getBlockState",new Class<?>[]{BlockPos.class},snapshotPos)).is(Blocks.STONE),"path-fresh-snapshot-observes-edit-"+c.player.getName());
            c.level.setBlock(snapshotPos,Blocks.AIR.defaultBlockState(),18);
            var path=mob.getNavigation().createPath(c.pos(-21,0,-23),0,4);
            check(path instanceof AsyncPath,"safe-corridor-async-path-"+c.player.getName());
            AsyncPathProcessor.awaitProcessing(mob,path,processed -> guarded(() -> {
                check(Bukkit.isOwnedByCurrentRegion(cow) && processed!=null && processed.isProcessed() && processed.getNodeCount()>0,"async-completion-on-owner-"+c.player.getName());
                c.level.setBlock(snapshotPos,Blocks.POWDER_SNOW.defaultBlockState(),18);
                check(invoke(region,"deerFoliaCreateSnapshot",new Class<?>[]{boolean.class},true)==null,"path-live-collision-rejects-async-snapshot-"+c.player.getName());
                mob.getNavigation().stop();
                var synchronous=mob.getNavigation().createPath(c.pos(-20,0,-22),0,6);
                check(synchronous!=null && !(synchronous instanceof AsyncPath),"path-live-collision-uses-safe-sync-fallback-"+c.player.getName());
                c.level.setBlock(snapshotPos,Blocks.AIR.defaultBlockState(),18);
                mob.getNavigation().moveTo(processed,1);
                later(c.player,50,() -> {check(cow.isValid() && cow.getLocation().distanceSquared(new Location(c.player.getWorld(),c.base.getX()-23.5,c.base.getY(),c.base.getZ()-23.5))>0.1,"async-navigation-moves-"+c.player.getName());cow.remove();pass("paths-complete-"+c.player.getName());});
            }));
        });
    }
    private void villageWorldIsolation(Context c) {
        BlockPos at=c.pos(-3,0,9);
        var manager=c.level.getPoiManager();
        var type=c.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE).getOrThrow(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME);
        var record=manager.add(at,type);check(record!=null && (boolean)invoke(record,"acquireTicket",new Class<?>[0]),"poi-ticket-acquired-"+c.player.getName());
        int village=manager.sectionsToVillage(SectionPos.of(at));check(village==0,"poi-occupied-world-distance-zero-"+c.player.getName());
        check(Arrays.stream(net.minecraft.world.entity.ai.behavior.GoToClosestVillage.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("villageDistanceCache")),"poi-no-shared-coordinate-cache-"+c.player.getName());
        World other=Bukkit.getWorlds().stream().filter(w -> w.getEnvironment()==World.Environment.THE_END).findFirst().orElseThrow();
        other.getChunkAtAsync(at.getX()>>4,at.getZ()>>4).thenAccept(chunk -> Bukkit.getRegionScheduler().run(this,new Location(other,at.getX(),at.getY(),at.getZ()),task -> guarded(() -> {
            ServerLevel alternate=((CraftWorld)other).getHandle();
            int empty=alternate.getPoiManager().sectionsToVillage(SectionPos.of(at));
            check(empty>village,"poi-same-coordinates-world-isolation-"+c.player.getName());
            later(c.player,2,() -> {manager.remove(at);check(manager.sectionsToVillage(SectionPos.of(at))>0,"poi-removal-invalidates-distance-"+c.player.getName());pass("poi-complete-"+c.player.getName());});
        })));
    }
    private void restart(Player p) {
        int origin=p.getName().equals("TestActive")?2056:8;
        p.teleportAsync(new Location(p.getWorld(),origin+.5,99,origin+.5)).thenAccept(ok -> later(p,35,() -> {
            Context c=new Context(p,((CraftWorld)p.getWorld()).getHandle(),new BlockPos(origin,96,origin));contexts.put(p.getName(),c);
            check(ok && c.block(10,0,10).getType()==Material.DIAMOND_BLOCK,"restart-block-persistence-"+p.getName());
            for(int z:new int[]{0,2,4,6}) check(sleeping(blockEntity(c,z))==optimized,"restart-idle-ticker-"+z+"-"+p.getName());
            var crafter=(org.bukkit.block.Crafter)c.block(5,0,0).getState(false);crafter.setCraftingTicks(8);
            later(p,15,() -> {check(crafter.getCraftingTicks()==0,"restart-live-wakeup-"+p.getName());pass("restart-complete-"+p.getName());});
        }));
    }
    @FunctionalInterface private interface Operation {long run(int count);}
    private void sample(Context c,String name,int round,int count,Operation operation) {
        long thread=Thread.currentThread().threadId();long bytes=allocation.getThreadAllocatedBytes(thread),cpuStart=allocation.getCurrentThreadCpuTime(),start=System.nanoTime();
        long work=operation.run(count);long nanos=System.nanoTime()-start,cpu=allocation.getCurrentThreadCpuTime()-cpuStart,allocated=allocation.getThreadAllocatedBytes(thread)-bytes;blackhole^=work;
        if(round>=WARMUP_ROUNDS) getLogger().info("PHASE1_SAMPLE {\"scenario\":\""+name+"\",\"player\":\""+c.player.getName()+"\",\"optimized\":"+optimized+",\"round\":"+(round-WARMUP_ROUNDS)+",\"operations\":"+count+",\"work\":"+work+",\"nanos\":"+nanos+",\"cpu_nanos\":"+cpu+",\"allocated_bytes\":"+allocated+",\"thread\":"+thread+"}");
    }
    private final Map<String,BenchState> benches=new ConcurrentHashMap<>();
    private static final class BenchState {
        LivingEntity mob,smallMob;org.bukkit.entity.Villager api,smallApi;Brain brain,smallBrain;List<ProbeBehavior> smallProbes=new ArrayList<>();List<ProbeBehavior> probes=new ArrayList<>();RemoveBlockGoal eggGoal,denseEggGoal;org.bukkit.entity.Zombie zombie,denseZombie;List<BlockEntity> blockEntities=new ArrayList<>();List<TickingBlockEntity> tickers=new ArrayList<>();StepBasedCollector emptyCollector=new StepBasedCollector(),activeCollector=new StepBasedCollector();
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void benchmark(Context c,int round) {
        BenchState s=benches.computeIfAbsent(c.player.getName(),k -> {
            BenchState b=new BenchState();b.api=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+.5,c.base.getY(),c.base.getZ()+.5),org.bukkit.entity.Villager.class);b.api.setAI(false);
            b.mob=((CraftMob)b.api).getHandle();b.brain=b.mob.getBrain();b.brain.removeAllBehaviors();
            ImmutableList.Builder<Pair<Integer,Behavior<LivingEntity>>> builder=ImmutableList.builder();
            for(int i=0;i<96;i++){ProbeBehavior p=new ProbeBehavior();b.probes.add(p);builder.add(Pair.of(i/8,p));}
            b.brain.addActivity(Activity.IDLE,builder.build(),Set.of(),Set.of());b.brain.setDefaultActivity(Activity.IDLE);b.brain.useDefaultActivity();
            b.smallApi=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+2.5,c.base.getY(),c.base.getZ()+.5),org.bukkit.entity.Villager.class);b.smallApi.setAI(false);b.smallMob=((CraftMob)b.smallApi).getHandle();b.smallBrain=b.smallMob.getBrain();b.smallBrain.removeAllBehaviors();
            ImmutableList.Builder<Pair<Integer,Behavior<LivingEntity>>> smallBuilder=ImmutableList.builder();for(int i=0;i<4;i++){ProbeBehavior probe=new ProbeBehavior();b.smallProbes.add(probe);smallBuilder.add(Pair.of(i,probe));}
            b.smallBrain.addActivity(Activity.IDLE,smallBuilder.build(),Set.of(),Set.of());b.smallBrain.setDefaultActivity(Activity.IDLE);b.smallBrain.useDefaultActivity();
            b.zombie=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+.5,c.base.getY(),c.base.getZ()+.5),org.bukkit.entity.Zombie.class);b.zombie.setAI(false);b.zombie.setInvulnerable(true);
            b.eggGoal=new RemoveBlockGoal(Blocks.TURTLE_EGG,(net.minecraft.world.entity.PathfinderMob)((CraftMob)b.zombie).getHandle(),1,3);
            b.denseZombie=c.player.getWorld().spawn(new Location(c.player.getWorld(),c.base.getX()+24.5,c.base.getY(),c.base.getZ()+24.5),org.bukkit.entity.Zombie.class);b.denseZombie.setAI(false);b.denseZombie.setInvulnerable(true);
            b.denseEggGoal=new RemoveBlockGoal(Blocks.TURTLE_EGG,(net.minecraft.world.entity.PathfinderMob)((CraftMob)b.denseZombie).getHandle(),1,3);
            for(int type=0;type<4;type++) for(int i=0;i<64;i++) {
                BlockPos at=c.pos(-4+(i&7),32+type*16,-4+(i>>3));
                BlockState state=switch(type){case 0 -> Blocks.CRAFTER.defaultBlockState();case 1 -> Blocks.CAMPFIRE.defaultBlockState();case 2 -> Blocks.SHULKER_BOX.defaultBlockState();default -> Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,false);};
                c.level.setBlock(at,state,18);BlockEntity be=c.level.getBlockEntity(at);b.blockEntities.add(be);
                b.tickers.add((TickingBlockEntity)((Map<?,?>)field(c.level.getChunkAt(at),"tickersInLevel")).get(at));
            }
            return b;
        });
        var entity=((CraftPlayer)c.player).getHandle();
        sample(c,"collector_empty",round,20000,n -> {for(int i=0;i<n;i++){StepBasedCollector v=new StepBasedCollector();v.advanceStep(0,c.base);v.advanceStep(1,c.base);v.applyAndClear(entity);}return n;});
        long[] effects={0};Consumer<net.minecraft.world.entity.Entity> effect=e -> effects[0]++;
        sample(c,"collector_active",round,10000,n -> {for(int i=0;i<n;i++){StepBasedCollector v=new StepBasedCollector();v.advanceStep(0,c.base);v.runBefore(InsideBlockEffectType.FREEZE,effect);v.runAfter(InsideBlockEffectType.FREEZE,effect);v.applyAndClear(entity);}return effects[0];});
        sample(c,"collector_reused_empty",round,200000,n -> {for(int i=0;i<n;i++){s.emptyCollector.advanceStep(0,c.base);s.emptyCollector.advanceStep(1,c.base);s.emptyCollector.applyAndClear(entity);}return n;});
        effects[0]=0;
        sample(c,"collector_reused_active",round,20000,n -> {for(int i=0;i<n;i++){s.activeCollector.advanceStep(0,c.base);s.activeCollector.runBefore(InsideBlockEffectType.FREEZE,effect);s.activeCollector.runAfter(InsideBlockEffectType.FREEZE,effect);s.activeCollector.applyAndClear(entity);}return effects[0];});
        BlockPos top=c.pos(-5,90,-5);
        sample(c,"heightmap_shared_descent",round,1500,n -> {for(int i=0;i<n;i++){c.level.setBlock(top,Blocks.STONE.defaultBlockState(),18);c.level.setBlock(top,Blocks.AIR.defaultBlockState(),18);}return n*2;});
        sample(c,"heightmap_active_near_surface",round,1500,n -> {BlockPos near=c.pos(-5,0,-5);for(int i=0;i<n;i++){c.level.setBlock(near,Blocks.STONE.defaultBlockState(),18);c.level.setBlock(near,Blocks.AIR.defaultBlockState(),18);}return n*2;});
        BlockPos source=c.pos(0,48,0);
        sample(c,"game_event_no_listeners",round,3000,n -> {for(int i=0;i<n;i++) c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));return n;});
        AtomicInteger received=new AtomicInteger();var listener=listener(source,received,GameEventListener.DeliveryMode.BY_DISTANCE);var registry=c.level.getChunkAt(source).getListenerRegistry(source.getY()>>4);registry.register(listener);
        sample(c,"game_event_active_listener",round,3000,n -> {for(int i=0;i<n;i++)c.level.gameEvent(GameEvent.STEP,source,GameEvent.Context.of(Blocks.STONE.defaultBlockState()));return received.get();});registry.unregister(listener);
        if(received.get()!=3000) throw new AssertionError("benchmark listener lost events");
        s.brain.eraseMemory(MemoryModuleType.IS_PANICKING);
        sample(c,"brain_unmet_conditions",round,1200,n -> {long before=s.probes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++)s.brain.tick(c.level,s.mob);return s.probes.stream().mapToLong(p -> p.starts).sum()-before;});
        s.brain.setMemory(MemoryModuleType.IS_PANICKING,true);
        sample(c,"brain_active_conditions",round,1200,n -> {long before=s.probes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++)s.brain.tick(c.level,s.mob);return s.probes.stream().mapToLong(p -> p.starts).sum()-before;});
        sample(c,"brain_memory_churn",round,1200,n -> {long before=s.probes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++){if((i&1)==0)s.brain.eraseMemory(MemoryModuleType.IS_PANICKING);else s.brain.setMemory(MemoryModuleType.IS_PANICKING,true);s.brain.tick(c.level,s.mob);}long work=s.probes.stream().mapToLong(p -> p.starts).sum()-before;if(work!=(n/2L)*96)throw new AssertionError("memory-churn starts "+work);return work;});
        s.smallBrain.eraseMemory(MemoryModuleType.IS_PANICKING);
        sample(c,"brain_small_unmet",round,4000,n -> {long before=s.smallProbes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++)s.smallBrain.tick(c.level,s.smallMob);return s.smallProbes.stream().mapToLong(p -> p.starts).sum()-before;});
        s.smallBrain.setMemory(MemoryModuleType.IS_PANICKING,true);
        sample(c,"brain_small_active",round,4000,n -> {long before=s.smallProbes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++)s.smallBrain.tick(c.level,s.smallMob);long work=s.smallProbes.stream().mapToLong(p -> p.starts).sum()-before;if(work!=n*4L)throw new AssertionError("small-active starts "+work);return work;});
        sample(c,"brain_small_memory_churn",round,4000,n -> {long before=s.smallProbes.stream().mapToLong(p -> p.starts).sum();for(int i=0;i<n;i++){if((i&1)==0)s.smallBrain.eraseMemory(MemoryModuleType.IS_PANICKING);else s.smallBrain.setMemory(MemoryModuleType.IS_PANICKING,true);s.smallBrain.tick(c.level,s.smallMob);}long work=s.smallProbes.stream().mapToLong(p -> p.starts).sum()-before;if(work!=(n/2L)*4)throw new AssertionError("small-memory-churn starts "+work);return work;});
        sample(c,"egg_search_empty",round,30,n -> {long found=0;for(int i=0;i<n;i++)if((boolean)search(s.eggGoal))found++;return found;});
        BlockPos egg=c.pos(1,0,1);c.level.setBlock(egg,Blocks.TURTLE_EGG.defaultBlockState(),18);
        sample(c,"egg_search_active_nearby",round,1000,n -> {long found=0;for(int i=0;i<n;i++)if((boolean)search(s.eggGoal))found++;return found;});c.level.setBlock(egg,Blocks.AIR.defaultBlockState(),18);
        BlockPos firstPlaneEgg=c.pos(25,-1,25);BlockState previousEggBase=c.level.getBlockState(firstPlaneEgg);c.level.setBlock(firstPlaneEgg,Blocks.TURTLE_EGG.defaultBlockState(),18);
        sample(c,"egg_search_first_plane_hit",round,10000,n -> {long found=0;for(int i=0;i<n;i++)if((boolean)search(s.denseEggGoal))found++;if(found!=n)throw new AssertionError("first-plane egg count "+found);return found;});
        c.level.setBlock(firstPlaneEgg,previousEggBase,18);
        for(int z:new int[]{0,2,4,6}) {
            BlockEntity be=blockEntity(c,z);Map<?,?> tickers=(Map<?,?>)field(c.level.getChunkAt(be.getBlockPos()),"tickersInLevel");TickingBlockEntity ticker=(TickingBlockEntity)tickers.get(be.getBlockPos());
            sample(c,"block_entity_idle_"+z,round,20000,n -> {for(int i=0;i<n;i++)ticker.tick();return n;});
        }
        for(int type=0;type<4;type++) {
            final int startIndex=type*64;
            for(int i=startIndex;i<startIndex+64;i++) {
                BlockEntity be=s.blockEntities.get(i);
                if(be instanceof CrafterBlockEntity crafter) crafter.setCraftingTicksRemaining(0);
                else if(be instanceof CampfireBlockEntity camp) {camp.getItems().set(0,net.minecraft.world.item.ItemStack.EMPTY);camp.cookingProgress[0]=0;}
                else if(be instanceof ShulkerBoxBlockEntity box) box.triggerEvent(1,0);
                else c.level.setBlock(be.getBlockPos(),be.getBlockState().setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,false),18);
            }
            for(int settle=0;settle<20;settle++)for(int i=startIndex;i<startIndex+64;i++)s.tickers.get(i).tick();
            sample(c,"block_entity_bulk_idle_"+type,round,200,n -> {for(int t=0;t<n;t++)for(int i=startIndex;i<startIndex+64;i++)s.tickers.get(i).tick();return n*64;});
            for(int i=startIndex;i<startIndex+64;i++) {
                BlockEntity be=s.blockEntities.get(i);
                if(be instanceof CrafterBlockEntity crafter) crafter.setCraftingTicksRemaining(1000000);
                else if(be instanceof CampfireBlockEntity camp) {camp.getItems().set(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BEEF));camp.cookingTime[0]=1000000;camp.cookingProgress[0]=0;if(!baseline)invoke(be,"deerfoliaWakeUp",new Class<?>[0]);}
                else if(be instanceof ShulkerBoxBlockEntity box) box.triggerEvent(1,1);
                else c.level.setBlock(be.getBlockPos(),be.getBlockState().setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,true),18);
            }
            sample(c,"block_entity_bulk_active_"+type,round,200,n -> {for(int t=0;t<n;t++)for(int i=startIndex;i<startIndex+64;i++)s.tickers.get(i).tick();return n*64;});
        }
        if(round<WARMUP_ROUNDS+SAMPLE_ROUNDS-1) later(c.player,2,() -> benchmark(c,round+1));
        else {s.api.remove();s.smallApi.remove();s.zombie.remove();s.denseZombie.remove();for(BlockEntity be:s.blockEntities)c.level.setBlock(be.getBlockPos(),Blocks.AIR.defaultBlockState(),18);benches.remove(c.player.getName());pass("benchmark-complete-"+c.player.getName());}
    }
}
