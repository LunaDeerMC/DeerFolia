package cn.lunadeer.mc.deerfolia.lithium;

import ca.spottedleaf.moonrise.common.util.TickThread;
import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@VanillaFeature
class BlockSearchOptimizationTest {
    private static final class SearchGoal extends RemoveBlockGoal {
        private SearchGoal(final PathfinderMob mob) {
            super(Blocks.TURTLE_EGG, mob, 1.0, 1);
        }

        @Override
        protected boolean lithiumCanPruneSearch() {
            return true;
        }

        private boolean search() {
            return this.findNearestBlock();
        }

        private boolean checkTarget(final BlockPos pos) {
            return this.isValidTarget(this.mob.level(), pos);
        }

        private BlockPos target() {
            return this.blockPos;
        }
    }

    private static LevelChunkSection emptySection() {
        return new LevelChunkSection(PalettedContainerFactory.create(RegistryHelper.registryAccess()), null, null, 0);
    }

    private static ChunkAccess chunk(final LevelChunkSection section) {
        final ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getSection(anyInt())).thenReturn(section);
        return chunk;
    }

    private static PathfinderMob mob(final Level level) {
        final PathfinderMob mob = mock(PathfinderMob.class);
        when(mob.level()).thenReturn(level);
        when(mob.blockPosition()).thenReturn(new BlockPos(8, 5, 8));
        when(mob.isWithinHome(any(BlockPos.class))).thenReturn(true);
        return mob;
    }

    private static void withSearchEnabled(final Runnable test) {
        final var config = DeerFoliaConfiguration.lithiumOptimize;
        final boolean enabled = config.enabled;
        final boolean ai = config.ai;
        final boolean blockSearch = config.blockSearch;
        config.enabled = config.ai = config.blockSearch = true;
        try {
            test.run();
        } finally {
            config.enabled = enabled;
            config.ai = ai;
            config.blockSearch = blockSearch;
        }
    }

    @Test
    void firstEightCandidatesAvoidPaletteWorkButNinthCandidateEnablesPruning() {
        withSearchEnabled(() -> {
            // These are the first, eighth and ninth positions in the original ring order.
            final BlockPos[] targets = {new BlockPos(8, 4, 8), new BlockPos(7, 4, 9), new BlockPos(7, 4, 7)};
            for (int i = 0; i < targets.length; ++i) {
                final BlockPos target = targets[i];
                final Level level = mock(Level.class);
                final LevelChunkSection section = emptySection();
                section.setBlockState(target.getX(), target.getY(), target.getZ(), Blocks.TURTLE_EGG.defaultBlockState());
                final ChunkAccess chunk = chunk(section);
                when(level.getChunkIfLoadedImmediately(anyInt(), anyInt())).thenReturn(chunk);
                when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(call ->
                    target.equals(call.getArgument(0)) ? Blocks.TURTLE_EGG.defaultBlockState() : Blocks.AIR.defaultBlockState());
                final PathfinderMob mob = mob(level);
                final SearchGoal goal = new SearchGoal(mob);
                try (MockedStatic<TickThread> tickThread = org.mockito.Mockito.mockStatic(TickThread.class)) {
                    tickThread.when(() -> TickThread.isTickThreadFor(eq(level), any(BlockPos.class))).thenReturn(true);
                    assertTrue(goal.search());
                }
                assertEquals(target, goal.target());
                assertEquals(target, mob.movingTarget);
                verify(chunk, times(i == 2 ? 1 : 0)).getSection(anyInt());
            }
        });
    }

    @Test
    void longEmptySearchPrunesAfterEightChecksAndRestoresNormalTargetChecks() {
        withSearchEnabled(() -> {
            final Level level = mock(Level.class);
            final ChunkAccess chunk = chunk(emptySection());
            when(level.getChunkIfLoadedImmediately(anyInt(), anyInt())).thenReturn(chunk);
            when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
            final SearchGoal goal = new SearchGoal(mob(level));
            try (MockedStatic<TickThread> tickThread = org.mockito.Mockito.mockStatic(TickThread.class)) {
                tickThread.when(() -> TickThread.isTickThreadFor(eq(level), any(BlockPos.class))).thenReturn(true);
                assertFalse(goal.search());
            }
            verify(chunk, times(8)).getBlockState(any(BlockPos.class));
            assertFalse(goal.checkTarget(new BlockPos(8, 4, 8)));
            verify(chunk, times(9)).getBlockState(any(BlockPos.class));
        });
    }

    @Test
    void emptySectionRequiresOnlyOneLoadedChunkLookup() {
        final LevelReader level = mock(LevelReader.class);
        final ChunkAccess chunk = chunk(emptySection());
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(chunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> true);

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                assertFalse(cache.mayContain(new BlockPos(x, 5, z)));
            }
        }

        verify(level, times(1)).getChunkIfLoadedImmediately(0, 0);
        verify(chunk, times(1)).getSection(anyInt());
        verify(chunk, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    void matchingPaletteIsOnlyANecessaryCondition() {
        final LevelReader level = mock(LevelReader.class);
        final LevelChunkSection section = emptySection();
        section.setBlockState(1, 5, 1, Blocks.TURTLE_EGG.defaultBlockState());
        section.setBlockState(1, 6, 1, Blocks.STONE.defaultBlockState());
        final ChunkAccess chunk = chunk(section);
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(chunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> true);

        // Both an empty position and an obstructed egg must still reach the full check.
        assertTrue(cache.mayContain(new BlockPos(0, 5, 0)));
        assertTrue(cache.mayContain(new BlockPos(1, 5, 1)));
    }

    @Test
    void sectionCoordinatesIncludeNegativeCoordinatesAndHeight() {
        final LevelReader level = mock(LevelReader.class);
        final ChunkAccess emptyChunk = chunk(emptySection());
        final LevelChunkSection targetSection = emptySection();
        targetSection.setBlockState(15, 15, 15, Blocks.TURTLE_EGG.defaultBlockState());
        final ChunkAccess negativeChunk = chunk(targetSection);
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(emptyChunk);
        when(level.getChunkIfLoadedImmediately(-1, -1)).thenReturn(negativeChunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> true);

        assertFalse(cache.mayContain(new BlockPos(0, 0, 0)));
        assertTrue(cache.mayContain(new BlockPos(-1, -1, -1)));
        assertFalse(cache.mayContain(new BlockPos(0, 16, 0)));
        assertFalse(cache.mayContain(new BlockPos(15, 15, 15)));
        verify(level, times(2)).getChunkIfLoadedImmediately(0, 0);
    }

    @Test
    void alternatingSectionVisitsRetainBothPositiveAndNegativeResults() {
        final LevelReader level = mock(LevelReader.class);
        final ChunkAccess firstChunk = chunk(emptySection());
        final LevelChunkSection targetSection = emptySection();
        targetSection.setBlockState(1, 5, 1, Blocks.TURTLE_EGG.defaultBlockState());
        final ChunkAccess targetChunk = chunk(targetSection);
        final ChunkAccess thirdChunk = chunk(emptySection());
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(firstChunk);
        when(level.getChunkIfLoadedImmediately(1, 0)).thenReturn(targetChunk);
        when(level.getChunkIfLoadedImmediately(-1, 0)).thenReturn(thirdChunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> true);
        final BlockPos first = new BlockPos(1, 5, 1);
        final BlockPos target = new BlockPos(17, 5, 1);
        final BlockPos third = new BlockPos(-15, 5, 1);

        assertFalse(cache.mayContain(first));
        assertFalse(cache.mayContain(first));
        assertTrue(cache.mayContain(target));
        assertTrue(cache.mayContain(target));
        assertFalse(cache.mayContain(first));
        assertFalse(cache.mayContain(third));
        for (int i = 0; i < 10; ++i) {
            assertTrue(cache.mayContain(target));
            assertFalse(cache.mayContain(first));
            assertFalse(cache.mayContain(third));
        }

        verify(level, times(1)).getChunkIfLoadedImmediately(0, 0);
        verify(level, times(1)).getChunkIfLoadedImmediately(1, 0);
        verify(level, times(1)).getChunkIfLoadedImmediately(-1, 0);
    }

    @Test
    void unownedSectionDoesNotReadOrCacheItsPalette() {
        final LevelReader level = mock(LevelReader.class);
        final AtomicBoolean owned = new AtomicBoolean();
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> owned.get());
        final BlockPos pos = new BlockPos(1, 5, 1);

        assertTrue(cache.mayContain(pos));
        verifyNoInteractions(level);

        final ChunkAccess chunk = chunk(emptySection());
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(chunk);
        owned.set(true);
        assertFalse(cache.mayContain(pos));
    }

    @Test
    void missingChunkIsNotNegativelyCachedOrLoaded() {
        final LevelReader level = mock(LevelReader.class);
        final ChunkAccess chunk = chunk(emptySection());
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(null, chunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, pos -> true);
        final BlockPos pos = new BlockPos(1, 5, 1);

        assertTrue(cache.mayContain(pos));
        assertFalse(cache.mayContain(pos));
        verify(level, times(2)).getChunkIfLoadedImmediately(0, 0);
        verify(level, never()).getChunk(anyInt(), anyInt());
    }

    @Test
    void outsideBuildHeightRetainsTheOriginalCheck() {
        final LevelReader level = mock(LevelReader.class);
        final ChunkAccess chunk = chunk(emptySection());
        when(chunk.isOutsideBuildHeight(any(BlockPos.class))).thenReturn(true);
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(chunk);
        final BlockSearchSectionCache cache = new BlockSearchSectionCache(level, Blocks.AIR, pos -> true);

        assertTrue(cache.mayContain(new BlockPos(0, -1024, 0)));
        verify(chunk, never()).getSection(anyInt());
    }

    @Test
    void aNewSearchSeesAnEggPlacedAfterThePreviousSearch() {
        final LevelReader level = mock(LevelReader.class);
        final LevelChunkSection section = emptySection();
        final ChunkAccess chunk = chunk(section);
        when(level.getChunkIfLoadedImmediately(0, 0)).thenReturn(chunk);
        final BlockPos pos = new BlockPos(1, 5, 1);

        assertFalse(new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, candidate -> true).mayContain(pos));
        section.setBlockState(1, 5, 1, Blocks.TURTLE_EGG.defaultBlockState());
        assertTrue(new BlockSearchSectionCache(level, Blocks.TURTLE_EGG, candidate -> true).mayContain(pos));
    }
}
