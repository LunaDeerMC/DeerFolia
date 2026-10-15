package cn.lunadeer.mc.deerfolia.lithium;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;

/**
 * A necessary-condition filter for one synchronous, read-only block search.
 * Only owned, already loaded sections are cached; callers still run their full
 * target check for every position that this filter cannot rule out.
 */
public final class BlockSearchSectionCache {
    private final LevelReader level;
    private final Predicate<BlockPos> ownsPosition;
    private final Predicate<BlockState> targetState;
    private @Nullable Long2ByteOpenHashMap sections;
    private long lastSectionKey;
    private byte lastSectionResult;

    public BlockSearchSectionCache(final LevelReader level, final Block target, final Predicate<BlockPos> ownsPosition) {
        this.level = level;
        this.ownsPosition = ownsPosition;
        this.targetState = state -> state.is(target);
    }

    public boolean mayContain(final BlockPos pos) {
        final long sectionKey = SectionPos.asLong(pos);
        // Nearby candidates usually stay in the same section. A search ending in
        // its first section also avoids allocating the backing map altogether.
        if (this.lastSectionResult != 0 && sectionKey == this.lastSectionKey) {
            return this.lastSectionResult == 2;
        }
        if (this.sections != null) {
            final byte cached = this.sections.get(sectionKey);
            if (cached != 0) {
                this.lastSectionKey = sectionKey;
                this.lastSectionResult = cached;
                return cached == 2;
            }
        }

        // Do not inspect or remember another region's mutable section palette.
        if (!this.ownsPosition.test(pos)) {
            return true;
        }
        final ChunkAccess chunk = this.level.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || chunk.isOutsideBuildHeight(pos)) {
            // Retain the original loaded-state and out-of-height behavior. In
            // particular, a missing chunk is not cached across later candidates.
            return true;
        }

        final boolean mayContain = chunk.getSection(chunk.getSectionIndex(pos.getY())).maybeHas(this.targetState);
        final byte result = mayContain ? (byte)2 : (byte)1;
        if (this.sections == null && this.lastSectionResult != 0) {
            this.sections = new Long2ByteOpenHashMap(16);
            this.sections.put(this.lastSectionKey, this.lastSectionResult);
        }
        if (this.sections != null) {
            this.sections.put(sectionKey, result);
        }
        this.lastSectionKey = sectionKey;
        this.lastSectionResult = result;
        return mayContain;
    }
}
