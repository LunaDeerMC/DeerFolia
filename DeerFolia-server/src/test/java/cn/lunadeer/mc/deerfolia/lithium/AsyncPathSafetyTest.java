package cn.lunadeer.mc.deerfolia.lithium;

import ca.spottedleaf.moonrise.common.util.TickThread;
import cn.lunadeer.mc.deerfolia.async.path.AsyncPath;
import cn.lunadeer.mc.deerfolia.async.path.AsyncPathSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@VanillaFeature
class AsyncPathSafetyTest {
    @Test
    void snapshotIsIsolatedFromLaterBlockFluidAndBorderChanges() {
        Level level = level();
        ProtoChunk chunk = chunk();
        BlockPos pos = new BlockPos(3, 5, 7);
        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState());
        AsyncPathSnapshot snapshot = capture(level, chunk, false);
        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState());
        level.getWorldBorder().setSize(8);
        assertSame(Blocks.WATER.defaultBlockState(), snapshot.getBlockState(pos));
        assertFalse(snapshot.getFluidState(pos).isEmpty());
        assertEquals(100, snapshot.getWorldBorder().getSize());
        assertNull(snapshot.getBlockStateIfLoaded(new BlockPos(16, 5, 7)));
        assertTrue(snapshot.getBlockState(new BlockPos(16, 5, 7)).isAir());
        assertThrows(IllegalStateException.class, () -> snapshot.getBlockEntity(pos));
    }

    @Test
    void independentRequestsDoNotShareCoordinateCacheAcrossWorldViews() {
        Level level = level();
        ProtoChunk chunk = chunk();
        BlockPos pos = new BlockPos(3, 5, 7);
        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState());
        PathfindingContext water = new PathfindingContext(capture(level, chunk, false), mob());
        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState());
        PathfindingContext stone = new PathfindingContext(capture(level, chunk, false), mob());
        for (int i = 0; i < 100; i++) {
            assertEquals(PathType.WATER, water.getPathTypeFromState(3, 5, 7));
            assertEquals(PathType.BLOCKED, stone.getPathTypeFromState(3, 5, 7));
        }
    }

    @Test
    void unsafeOwnershipAndDynamicCollisionInputsRequireFallback() {
        Level level = level();
        ProtoChunk chunk = chunk();
        try (MockedStatic<TickThread> tickThread = mockStatic(TickThread.class)) {
            tickThread.when(() -> TickThread.isTickThreadFor(level, 0, 0)).thenReturn(false);
            assertNull(AsyncPathSnapshot.capture(level, 0, 0, new ChunkAccess[][] {{chunk}}, true));
        }
        for (var block : new net.minecraft.world.level.block.Block[] {
            Blocks.MOVING_PISTON, Blocks.SHULKER_BOX, Blocks.POWDER_SNOW, Blocks.SCAFFOLDING, Blocks.WATER
        }) {
            ProtoChunk dynamicChunk = chunk();
            dynamicChunk.setBlockState(new BlockPos(3, 5, 7), block.defaultBlockState());
            try (MockedStatic<TickThread> tickThread = mockStatic(TickThread.class)) {
                tickThread.when(() -> TickThread.isTickThreadFor(level, 0, 0)).thenReturn(true);
                assertNull(AsyncPathSnapshot.capture(level, 0, 0, new ChunkAccess[][] {{dynamicChunk}}, true), block.toString());
                assertNotNull(AsyncPathSnapshot.capture(level, 0, 0, new ChunkAccess[][] {{dynamicChunk}}, false));
            }
        }
    }

    @Test
    void detachedWalkingSearchAndCleanupNeverReadOrMutateTheLiveMob() throws Exception {
        Level level = level();
        ProtoChunk chunk = chunk();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlockState(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        AsyncPathSnapshot snapshot = capture(level, chunk, true);
        Mob mob = mob();
        PathNavigationRegion region = mock(PathNavigationRegion.class);
        when(region.getBlockState(any())).thenAnswer(call -> snapshot.getBlockState(call.getArgument(0)));
        when(region.getBlockStateIfLoaded(any())).thenAnswer(call -> snapshot.getBlockStateIfLoaded(call.getArgument(0)));
        when(region.getHeight()).thenReturn(16);
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        evaluator.prepare(region, mob);
        Node from = evaluator.getStart();
        evaluator.deerFoliaDetachForAsync(snapshot);
        verify(mob).onPathfindingDone();
        clearInvocations(mob);
        int neighbors = CompletableFuture.supplyAsync(() -> {
            try {
                return evaluator.getNeighbors(new Node[32], from);
            } finally {
                evaluator.done();
            }
        }).get(5, TimeUnit.SECONDS);
        assertTrue(neighbors > 0);
        verifyNoInteractions(mob);
    }

    @Test
    void nullResultCompletesAndInvokesCallbacksInsteadOfRemainingProcessing() {
        BlockPos target = new BlockPos(3, 5, 7);
        AsyncPath path = new AsyncPath(new ArrayList<>(), Set.of(target), () -> null);
        path.process();
        assertTrue(path.isProcessed());
        assertFalse(path.canReach());
        assertEquals(0, path.getNodeCount());
        assertEquals(target, path.getTarget());
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        path.postProcessing(callbacks::incrementAndGet);
        assertEquals(1, callbacks.get());
    }

    @Test
    void detachedFlyingAndSwimmingSearchesDoNotAccessTheLiveMob() throws Exception {
        for (NodeEvaluator evaluator : new NodeEvaluator[] {new FlyNodeEvaluator(), new SwimNodeEvaluator(false)}) {
            Level level = level();
            ProtoChunk chunk = chunk();
            if (evaluator instanceof SwimNodeEvaluator) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 8; y++) {
                        for (int z = 0; z < 16; z++) {
                            chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
                        }
                    }
                }
            }
            AsyncPathSnapshot snapshot = capture(level, chunk, false);
            Mob mob = mob();
            PathNavigationRegion region = mock(PathNavigationRegion.class);
            when(region.getBlockState(any())).thenAnswer(call -> snapshot.getBlockState(call.getArgument(0)));
            when(region.getBlockStateIfLoaded(any())).thenAnswer(call -> snapshot.getBlockStateIfLoaded(call.getArgument(0)));
            evaluator.prepare(region, mob);
            Node from = evaluator.getStart();
            evaluator.deerFoliaDetachForAsync(snapshot);
            clearInvocations(mob);
            int neighbors = CompletableFuture.supplyAsync(() -> {
                try {
                    return evaluator.getNeighbors(new Node[32], from);
                } finally {
                    evaluator.done();
                }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(neighbors > 0, evaluator.getClass().getSimpleName());
            verifyNoInteractions(mob);
        }
    }

    private static Level level() {
        Level level = mock(Level.class);
        when(level.getMinY()).thenReturn(0);
        when(level.getHeight()).thenReturn(16);
        WorldBorder border = new WorldBorder();
        border.setSize(100);
        when(level.getWorldBorder()).thenReturn(border);
        return level;
    }

    private static Mob mob() {
        Mob mob = mock(Mob.class);
        when(mob.blockPosition()).thenReturn(new BlockPos(5, 1, 5));
        when(mob.position()).thenReturn(new Vec3(5.5, 1, 5.5));
        when(mob.getX()).thenReturn(5.5);
        when(mob.getY()).thenReturn(1.0);
        when(mob.getZ()).thenReturn(5.5);
        when(mob.getBlockY()).thenReturn(1);
        when(mob.onGround()).thenReturn(true);
        when(mob.getBbWidth()).thenReturn(0.6F);
        when(mob.getBbHeight()).thenReturn(1.8F);
        when(mob.maxUpStep()).thenReturn(0.6F);
        when(mob.getMaxFallDistance()).thenReturn(3);
        when(mob.getBoundingBox()).thenReturn(new AABB(5.2, 1, 5.2, 5.8, 2.8, 5.8));
        when(mob.getPathfindingMalus(any())).thenAnswer(call -> ((PathType) call.getArgument(0)).getMalus());
        return mob;
    }

    private static ProtoChunk chunk() {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(0, 16),
            PalettedContainerFactory.create(RegistryHelper.registryAccess()), null);
    }

    private static AsyncPathSnapshot capture(Level level, ProtoChunk chunk, boolean collision) {
        try (MockedStatic<TickThread> tickThread = mockStatic(TickThread.class)) {
            tickThread.when(() -> TickThread.isTickThreadFor(level, 0, 0)).thenReturn(true);
            AsyncPathSnapshot snapshot = AsyncPathSnapshot.capture(level, 0, 0, new ChunkAccess[][] {{chunk}}, collision);
            assertNotNull(snapshot);
            return snapshot;
        }
    }
}
