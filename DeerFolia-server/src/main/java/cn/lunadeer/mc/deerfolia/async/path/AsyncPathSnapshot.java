package cn.lunadeer.mc.deerfolia.async.path;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A task-owned copy of loaded block palettes. No Level, ChunkAccess, block entity,
 * or entity is retained. Capture must run on the owner of every included chunk.
 * Collision-dependent evaluators conservatively fall back when a palette contains
 * a dynamic or entity-dependent shape; this is not a block-entity snapshot.
 */
public final class AsyncPathSnapshot implements CollisionGetter {
    private static final int MAX_CHUNKS = 256;
    private final int centerX;
    private final int centerZ;
    private final int minY;
    private final int height;
    private final PalettedContainer<BlockState>[][][] blocks;
    private final WorldBorder border;

    private AsyncPathSnapshot(int centerX, int centerZ, int minY, int height,
                              PalettedContainer<BlockState>[][][] blocks, WorldBorder border) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.minY = minY;
        this.height = height;
        this.blocks = blocks;
        this.border = border;
    }

    @SuppressWarnings("unchecked")
    public static @Nullable AsyncPathSnapshot capture(Level level, int centerX, int centerZ,
                                                       ChunkAccess[][] chunks, boolean needsCollisionShapes) {
        if (chunks.length == 0 || chunks[0].length == 0
                || (long) chunks.length * chunks[0].length > MAX_CHUNKS) {
            return null;
        }
        // Validate the entire input before copying any mutable palette.
        for (int x = 0; x < chunks.length; x++) {
            for (int z = 0; z < chunks[x].length; z++) {
                if (chunks[x][z] != null && !TickThread.isTickThreadFor(level, centerX + x, centerZ + z)) {
                    return null;
                }
                if (needsCollisionShapes && chunks[x][z] != null) {
                    for (LevelChunkSection section : chunks[x][z].getSections()) {
                        if (section.getStates().maybeHas(AsyncPathSnapshot::requiresLiveCollision)) {
                            return null;
                        }
                    }
                }
            }
        }
        PalettedContainer<BlockState>[][][] copies = new PalettedContainer[chunks.length][chunks[0].length][];
        for (int x = 0; x < chunks.length; x++) {
            for (int z = 0; z < chunks[x].length; z++) {
                ChunkAccess chunk = chunks[x][z];
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                copies[x][z] = new PalettedContainer[sections.length];
                for (int y = 0; y < sections.length; y++) {
                    PalettedContainer<BlockState> states = sections[y].getStates();
                    copies[x][z][y] = states.copy();
                }
            }
        }
        WorldBorder sourceBorder = level.getWorldBorder();
        WorldBorder border = new WorldBorder();
        border.setCenter(sourceBorder.getCenterX(), sourceBorder.getCenterZ());
        border.setSize(sourceBorder.getSize());
        border.setAbsoluteMaxSize(sourceBorder.getAbsoluteMaxSize());
        return new AsyncPathSnapshot(centerX, centerZ, level.getMinY(), level.getHeight(), copies, border);
    }

    private static boolean requiresLiveCollision(BlockState state) {
        // Dynamic shapes include shulkers, moving pistons, powder snow and scaffolding.
        // Liquid collision also depends on the living entity (e.g. striders).
        return state.getBlock().hasDynamicShape() || state.getBlock() instanceof LiquidBlock;
    }

    private boolean hasChunk(int chunkX, int chunkZ) {
        int x = chunkX - this.centerX;
        int z = chunkZ - this.centerZ;
        return x >= 0 && x < this.blocks.length && z >= 0 && z < this.blocks[x].length && this.blocks[x][z] != null;
    }

    @Override
    public @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        if (!this.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        if (this.isOutsideBuildHeight(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return this.blocks[(pos.getX() >> 4) - this.centerX][(pos.getZ() >> 4) - this.centerZ]
                [(pos.getY() - this.minY) >> 4].get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = this.getBlockStateIfLoaded(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        BlockState state = this.getBlockStateIfLoaded(pos);
        return state == null ? null : state.getFluidState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        throw new IllegalStateException("Block entities are not part of an asynchronous path snapshot");
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        // PathNavigationRegion presents absent chunks as empty to collision queries.
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
        return List.of();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.border;
    }

    @Override
    public int getMinY() {
        return this.minY;
    }

    @Override
    public int getHeight() {
        return this.height;
    }
}
