package cn.lunadeer.mc.deerfolia.lithium;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@VanillaFeature
class CombinedHeightmapTest {
    private static final Heightmap.Types[] TYPES = {
        Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE
    };

    @Test
    void randomizedEditsMatchIndependentUpdatesIncludingSerializedData() {
        for (int[] dimension : new int[][] {{-64, 384}, {0, 256}, {-256, 512}, {-2048, 4096}}) {
            CountingChunk chunk = new CountingChunk(dimension[0], dimension[1]);
            Heightmap[] independent = maps(chunk);
            Heightmap[] combined = maps(chunk);
            BlockState[] states = {
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState()
            };
            Random random = new Random(0x2613);
            for (int operation = 0; operation < 2_000; operation++) {
                int x = random.nextInt(16);
                int z = random.nextInt(16);
                int y = dimension[0] + random.nextInt(dimension[1]);
                BlockState state = states[random.nextInt(states.length)];
                if (operation % 4 == 0) {
                    y = Math.max(dimension[0], independent[random.nextInt(4)].getHighestTaken(x, z));
                    state = Blocks.AIR.defaultBlockState();
                } else if (operation % 17 == 0) {
                    y = operation % 34 == 0 ? dimension[0] : dimension[0] + dimension[1] - 1;
                }
                chunk.setBlockState(new BlockPos(x, y, z), state);
                chunk.reads = 0;
                for (Heightmap heightmap : independent) {
                    heightmap.update(x, y, z, state);
                }
                int baselineReads = chunk.reads;
                chunk.reads = 0;
                Heightmap.updateCombined(combined[0], combined[1], combined[2], combined[3], x, y, z, state);
                assertTrue(chunk.reads <= baselineReads, "Combined scan must not read more blocks");
                for (int type = 0; type < TYPES.length; type++) {
                    assertEquals(independent[type].getFirstAvailable(x, z), combined[type].getFirstAvailable(x, z), TYPES[type].name());
                    // This compares all 256 columns after every edit, not just the changed column.
                    assertArrayEquals(independent[type].getRawData(), combined[type].getRawData(), TYPES[type].name());
                }
            }
            for (int type = 0; type < TYPES.length; type++) {
                Heightmap reloaded = new Heightmap(chunk, TYPES[type]);
                reloaded.setRawData(chunk, TYPES[type], combined[type].getRawData().clone());
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        assertEquals(independent[type].getFirstAvailable(x, z), reloaded.getFirstAvailable(x, z));
                    }
                }
            }
        }
    }

    @Test
    void platformRemovalReadsTheColumnOnceAndUndergroundUpdatesReadNothing() {
        CountingChunk chunk = new CountingChunk(-64, 384);
        Heightmap[] independent = maps(chunk);
        Heightmap[] combined = maps(chunk);
        for (int y : new int[] {-64, 128}) {
            chunk.setBlockState(new BlockPos(3, y, 7), Blocks.STONE.defaultBlockState());
            for (Heightmap map : independent) map.update(3, y, 7, Blocks.STONE.defaultBlockState());
            Heightmap.updateCombined(combined[0], combined[1], combined[2], combined[3], 3, y, 7, Blocks.STONE.defaultBlockState());
        }
        chunk.setBlockState(new BlockPos(3, 16, 7), Blocks.AIR.defaultBlockState());
        chunk.reads = 0;
        Heightmap.updateCombined(combined[0], combined[1], combined[2], combined[3], 3, 16, 7, Blocks.AIR.defaultBlockState());
        assertEquals(0, chunk.reads);

        chunk.setBlockState(new BlockPos(3, 128, 7), Blocks.AIR.defaultBlockState());
        chunk.reads = 0;
        for (Heightmap map : independent) map.update(3, 128, 7, Blocks.AIR.defaultBlockState());
        int baselineReads = chunk.reads;
        chunk.reads = 0;
        Heightmap.updateCombined(combined[0], combined[1], combined[2], combined[3], 3, 128, 7, Blocks.AIR.defaultBlockState());
        assertEquals(192, chunk.reads);
        assertEquals(4 * chunk.reads, baselineReads);
        for (int type = 0; type < TYPES.length; type++) {
            assertArrayEquals(independent[type].getRawData(), combined[type].getRawData());
        }
    }

    private static Heightmap[] maps(CountingChunk chunk) {
        Heightmap[] result = new Heightmap[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) result[i] = new Heightmap(chunk, TYPES[i]);
        return result;
    }

    private static final class CountingChunk extends ProtoChunk {
        private int reads;

        private CountingChunk(int minY, int height) {
            super(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(minY, height),
                PalettedContainerFactory.create(RegistryHelper.registryAccess()), null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            this.reads++;
            return super.getBlockState(pos);
        }
    }
}
