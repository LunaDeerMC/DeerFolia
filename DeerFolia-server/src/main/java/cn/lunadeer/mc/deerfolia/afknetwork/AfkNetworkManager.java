package cn.lunadeer.mc.deerfolia.afknetwork;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class AfkNetworkManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentMap<UUID, RuntimeState> RUNTIME_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, PlayerStats> PLAYER_STATS = new ConcurrentHashMap<>();
    private static final EnumMap<AfkSuppressibleCategory, CategoryStats> CATEGORY_STATS = new EnumMap<>(AfkSuppressibleCategory.class);

    static {
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            CATEGORY_STATS.put(category, new CategoryStats());
        }
    }

    private AfkNetworkManager() {
    }

    public static void handleBaseActivity(final ServerPlayer player) {
        markActivity(player, runtimeState(player));
    }

    public static void handleLookOrMoveActivity(final ServerPlayer player, final boolean moved, final boolean looked) {
        if (!moved && !looked) {
            return;
        }

        if (moved || (looked && DeerFoliaConfiguration.afkNetworkOptimization.countLookChangesAsActivity)) {
            markActivity(player, runtimeState(player));
        }
    }

    public static void onDisconnect(final ServerPlayer player) {
        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state != null) {
            state.afk = false;
            state.bypassDepth = 0;
            resetSuppressedSinceRefresh(state);
        }
    }

    public static boolean shouldSuppress(final ServerPlayer player, final Packet<?> packet, final Connection connection) {
        final DeerFoliaConfiguration.AfkNetworkOptimization config = DeerFoliaConfiguration.afkNetworkOptimization;
        if (!config.enabled || player.hasDisconnected()) {
            return false;
        }

        final RuntimeState state = runtimeState(player);
        if (state.bypassDepth > 0) {
            return false;
        }

        final AfkSuppressibleCategory category = classify(packet, player);
        if (category == null || whitelistedCategories().contains(category)) {
            return false;
        }

        final long now = System.currentTimeMillis();
        if (!isAfk(player, state, now, config.afkThresholdTicks)) {
            state.afk = false;
            return false;
        }
        state.afk = true;

        final int estimatedBytes = Math.max(estimatePacketBytes(connection, packet), 0);
        final long refreshThreshold = config.maxSuppressedBytesBeforeCategoryResync;
        if (refreshThreshold >= 0L && state.suppressedSinceRefresh.get(category) + estimatedBytes >= refreshThreshold) {
            state.suppressedSinceRefresh.put(category, 0L);
            refreshCategory(player, state, category);
            if (category == AfkSuppressibleCategory.WORLD_EFFECTS || category == AfkSuppressibleCategory.UI_STREAM) {
                return false;
            }
            recordSaved(player, category, estimatedBytes, now);
            return true;
        }

        state.suppressedSinceRefresh.put(category, state.suppressedSinceRefresh.get(category) + estimatedBytes);
        recordSaved(player, category, estimatedBytes, now);
        return true;
    }

    public static List<Component> buildSummaryLines() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("[AFKNet] total saved: " + formatBytes(totalSavedBytes())));
        lines.add(Component.literal("[AFKNet] peak saved bandwidth: " + formatRate(peakSavedBytesPerSecond())));
        lines.add(Component.literal("[AFKNet] categories:"));
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            final CategoryStats stats = CATEGORY_STATS.get(category);
            lines.add(Component.literal(" - " + category.configKey() + ": " + formatBytes(stats.totalBytes()) + ", peak " + formatRate(stats.peakBytesPerSecond())));
        }

        final List<PlayerStats> topPlayers = PLAYER_STATS.values().stream()
            .sorted(Comparator.comparingLong(PlayerStats::totalBytes).reversed())
            .limit(5)
            .toList();
        lines.add(Component.literal("[AFKNet] top players:"));
        if (topPlayers.isEmpty()) {
            lines.add(Component.literal(" - none"));
        } else {
            for (final PlayerStats stats : topPlayers) {
                lines.add(Component.literal(" - " + stats.lastKnownName + ": " + formatBytes(stats.totalBytes()) + ", peak " + formatRate(stats.peakBytesPerSecond())));
            }
        }
        return lines;
    }

    public static List<Component> buildPlayerLines(final UUID playerId, final String fallbackName) {
        final PlayerStats stats = PLAYER_STATS.computeIfAbsent(playerId, ignored -> new PlayerStats(fallbackName));
        stats.lastKnownName = fallbackName;

        final List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("[AFKNet] player: " + stats.lastKnownName));
        lines.add(Component.literal("[AFKNet] total saved: " + formatBytes(stats.totalBytes())));
        lines.add(Component.literal("[AFKNet] peak saved bandwidth: " + formatRate(stats.peakBytesPerSecond())));
        lines.add(Component.literal("[AFKNet] categories:"));
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            final TrafficCounter counter = stats.byCategory.get(category);
            lines.add(Component.literal(" - " + category.configKey() + ": " + formatBytes(counter.totalBytes()) + ", peak " + formatRate(counter.peakBytesPerSecond())));
        }
        return lines;
    }

    private static EnumSet<AfkSuppressibleCategory> whitelistedCategories() {
        final EnumSet<AfkSuppressibleCategory> categories = EnumSet.noneOf(AfkSuppressibleCategory.class);
        for (final String entry : DeerFoliaConfiguration.afkNetworkOptimization.suppressionWhitelistCategories) {
            AfkSuppressibleCategory.fromConfigKey(entry).ifPresent(categories::add);
        }
        return categories;
    }

    private static boolean isAfk(final ServerPlayer player, final RuntimeState state, final long now, final int thresholdTicks) {
        final long thresholdMillis = thresholdTicks * 50L;
        final long lastActivity = Math.max(player.getLastActionTime(), state.lastActivityMillis);
        return now - lastActivity >= thresholdMillis;
    }

    private static RuntimeState runtimeState(final ServerPlayer player) {
        return RUNTIME_STATES.computeIfAbsent(player.getUUID(), ignored -> new RuntimeState());
    }

    private static void recordSaved(final ServerPlayer player, final AfkSuppressibleCategory category, final int bytes, final long now) {
        if (!DeerFoliaConfiguration.afkNetworkOptimization.statsEnabled || bytes <= 0) {
            return;
        }

        final PlayerStats playerStats = PLAYER_STATS.computeIfAbsent(player.getUUID(), ignored -> new PlayerStats(player.getGameProfile().name()));
        playerStats.lastKnownName = player.getGameProfile().name();
        playerStats.totalCounter.record(bytes, now);
        playerStats.byCategory.get(category).record(bytes, now);
        CATEGORY_STATS.get(category).counter.record(bytes, now);
    }

    private static long totalSavedBytes() {
        long total = 0L;
        for (final PlayerStats stats : PLAYER_STATS.values()) {
            total += stats.totalBytes();
        }
        return total;
    }

    private static long peakSavedBytesPerSecond() {
        long peak = 0L;
        for (final PlayerStats stats : PLAYER_STATS.values()) {
            peak = Math.max(peak, stats.peakBytesPerSecond());
        }
        return peak;
    }

    private static int estimatePacketBytes(final Connection connection, final Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundlePacket) {
            int total = 0;
            for (final Packet<?> subPacket : bundlePacket.subPackets()) {
                total += estimatePacketBytes(connection, subPacket);
            }
            return total;
        }

        final ProtocolInfo<?> outboundProtocol = connection.getDeerFoliaOutboundProtocol();
        if (outboundProtocol == null) {
            return 0;
        }

        final EmbeddedChannel channel;
        final int compressionThreshold = connection.getDeerFoliaCompressionThreshold();
        if (compressionThreshold >= 0) {
            channel = new EmbeddedChannel(
                new Varint21LengthFieldPrepender(),
                new CompressionEncoder(compressionThreshold),
                new PacketEncoder<>((ProtocolInfo) outboundProtocol)
            );
        } else {
            channel = new EmbeddedChannel(
                new Varint21LengthFieldPrepender(),
                new PacketEncoder<>((ProtocolInfo) outboundProtocol)
            );
        }

        try {
            if (!channel.writeOutbound(packet)) {
                return 0;
            }
            final Object encoded = channel.readOutbound();
            if (!(encoded instanceof ByteBuf byteBuf)) {
                return 0;
            }
            try {
                return byteBuf.readableBytes();
            } finally {
                byteBuf.release();
            }
        } catch (final Exception exception) {
            LOGGER.debug("Failed to estimate packet size for {}", packet.getClass().getName(), exception);
            return 0;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static @Nullable AfkSuppressibleCategory classify(final Packet<?> packet, final ServerPlayer player) {
        if (packet instanceof BundlePacket<?> bundlePacket) {
            AfkSuppressibleCategory category = null;
            for (final Packet<?> subPacket : bundlePacket.subPackets()) {
                final AfkSuppressibleCategory subCategory = classify(subPacket, player);
                if (subCategory == null) {
                    return null;
                }
                if (category == null) {
                    category = subCategory;
                } else if (category != subCategory) {
                    return null;
                }
            }
            return category;
        }

        if (isSelfEntityPacket(player, packet)) {
            return null;
        }

        if (packet instanceof ClientboundLevelChunkWithLightPacket
            || packet instanceof ClientboundForgetLevelChunkPacket
            || packet instanceof ClientboundLightUpdatePacket
            || packet instanceof ClientboundChunksBiomesPacket
            || packet instanceof ClientboundChunkBatchStartPacket
            || packet instanceof ClientboundChunkBatchFinishedPacket) {
            return AfkSuppressibleCategory.CHUNK_STREAM;
        }

        if (packet instanceof ClientboundBlockUpdatePacket
            || packet instanceof ClientboundSectionBlocksUpdatePacket
            || packet instanceof ClientboundBlockEntityDataPacket
            || packet instanceof ClientboundBlockDestructionPacket
            || packet instanceof ClientboundBlockEventPacket) {
            return AfkSuppressibleCategory.BLOCK_UPDATES;
        }

        if (packet instanceof ClientboundAddEntityPacket
            || packet instanceof ClientboundRemoveEntitiesPacket
            || packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundTeleportEntityPacket
            || packet instanceof ClientboundEntityPositionSyncPacket
            || packet instanceof ClientboundRotateHeadPacket
            || packet instanceof ClientboundSetEntityDataPacket
            || packet instanceof ClientboundSetEntityMotionPacket
            || packet instanceof ClientboundSetEquipmentPacket
            || packet instanceof ClientboundSetPassengersPacket
            || packet instanceof ClientboundSetEntityLinkPacket
            || packet instanceof ClientboundUpdateAttributesPacket
            || packet instanceof ClientboundProjectilePowerPacket
            || packet instanceof ClientboundMoveMinecartPacket
            || packet instanceof ClientboundAnimatePacket
            || packet instanceof ClientboundEntityEventPacket
            || packet instanceof ClientboundDamageEventPacket) {
            return AfkSuppressibleCategory.ENTITY_STREAM;
        }

        if (packet instanceof ClientboundSoundPacket
            || packet instanceof ClientboundSoundEntityPacket
            || packet instanceof ClientboundStopSoundPacket
            || packet instanceof ClientboundLevelParticlesPacket
            || packet instanceof ClientboundLevelEventPacket
            || packet instanceof ClientboundExplodePacket) {
            return AfkSuppressibleCategory.WORLD_EFFECTS;
        }

        if (packet instanceof ClientboundBossEventPacket
            || packet instanceof ClientboundSetTitleTextPacket
            || packet instanceof ClientboundSetSubtitleTextPacket
            || packet instanceof ClientboundSetActionBarTextPacket
            || packet instanceof ClientboundSetTitlesAnimationPacket
            || packet instanceof ClientboundClearTitlesPacket
            || packet instanceof ClientboundTabListPacket
            || packet instanceof ClientboundSetPlayerTeamPacket
            || packet instanceof ClientboundSetObjectivePacket
            || packet instanceof ClientboundSetDisplayObjectivePacket
            || packet instanceof ClientboundSetScorePacket
            || packet instanceof ClientboundResetScorePacket
            || packet instanceof ClientboundMapItemDataPacket
            || packet instanceof ClientboundUpdateAdvancementsPacket
            || packet instanceof ClientboundPlayerInfoUpdatePacket
            || packet instanceof ClientboundPlayerInfoRemovePacket) {
            return AfkSuppressibleCategory.UI_STREAM;
        }

        return null;
    }

    private static void markActivity(final ServerPlayer player, final RuntimeState state) {
        state.lastActivityMillis = System.currentTimeMillis();
        if (state.afk) {
            state.afk = false;
            resetSuppressedSinceRefresh(state);
            if (DeerFoliaConfiguration.afkNetworkOptimization.resyncOnResume) {
                resyncAll(player, state);
            }
        }
    }

    private static boolean isSelfEntityPacket(final ServerPlayer player, final Packet<?> packet) {
        final int playerId = player.getId();
        return packet instanceof ClientboundSetEntityDataPacket entityData && entityData.id() == playerId
            || packet instanceof ClientboundMoveEntityPacket moveEntity && moveEntity.getEntity(player.level()) == player
            || packet instanceof ClientboundTeleportEntityPacket teleportEntity && teleportEntity.id() == playerId
            || packet instanceof ClientboundEntityPositionSyncPacket entityPositionSync && entityPositionSync.id() == playerId
            || packet instanceof ClientboundSetEntityMotionPacket entityMotion && entityMotion.id() == playerId
            || packet instanceof ClientboundSetEquipmentPacket setEquipment && setEquipment.getEntity() == playerId
            || packet instanceof ClientboundUpdateAttributesPacket updateAttributes && updateAttributes.getEntityId() == playerId;
    }

    private static void refreshCategory(final ServerPlayer player, final RuntimeState state, final AfkSuppressibleCategory category) {
        switch (category) {
            case CHUNK_STREAM, BLOCK_UPDATES -> resendVisibleChunks(player, state);
            case ENTITY_STREAM -> resendVisibleEntities(player, state);
            case WORLD_EFFECTS, UI_STREAM -> {
            }
        }
    }

    private static void resyncAll(final ServerPlayer player, final RuntimeState state) {
        resendVisibleChunks(player, state);
        resendVisibleEntities(player, state);
    }

    private static void resendVisibleChunks(final ServerPlayer player, final RuntimeState state) {
        final ServerLevel level = (ServerLevel) player.level();
        final ChunkMap chunkMap = level.getChunkSource().chunkMap;
        final List<LevelChunk> chunks = new ArrayList<>();
        player.getChunkTrackingView().forEach(chunkPos -> {
            final LevelChunk chunk = chunkMap.getChunkToSend(chunkPos.pack());
            if (chunk != null) {
                chunks.add(chunk);
            }
        });

        if (chunks.isEmpty()) {
            return;
        }

        withBypass(state, () -> {
            player.connection.send(ClientboundChunkBatchStartPacket.INSTANCE);
            for (final LevelChunk chunk : chunks) {
                PlayerChunkSender.sendChunk(player.connection, level, chunk);
            }
            player.connection.send(new ClientboundChunkBatchFinishedPacket(chunks.size()));
        });
    }

    private static void resendVisibleEntities(final ServerPlayer player, final RuntimeState state) {
        final ServerLevel level = (ServerLevel) player.level();
        withBypass(state, () -> {
            for (final Entity entity : level.getAllEntities()) {
                final ChunkMap.TrackedEntity tracker = entity.moonrise$getTrackedEntity();
                if (tracker != null) {
                    tracker.removePlayer(player);
                }
            }
            for (final Entity entity : level.getAllEntities()) {
                final ChunkMap.TrackedEntity tracker = entity.moonrise$getTrackedEntity();
                if (tracker != null) {
                    tracker.updatePlayer(player);
                }
            }
        });
    }

    private static void withBypass(final RuntimeState state, final Runnable action) {
        state.bypassDepth++;
        try {
            action.run();
        } finally {
            state.bypassDepth--;
        }
    }

    private static void resetSuppressedSinceRefresh(final RuntimeState state) {
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            state.suppressedSinceRefresh.put(category, 0L);
        }
    }

    private static String formatBytes(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final String[] units = {"KiB", "MiB", "GiB"};
        double value = bytes;
        int unitIndex = -1;
        do {
            value /= 1024.0D;
            unitIndex++;
        } while (value >= 1024.0D && unitIndex < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private static String formatRate(final long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }

    private static final class RuntimeState {
        private volatile long lastActivityMillis = System.currentTimeMillis();
        private volatile boolean afk;
        private volatile int bypassDepth;
        private final EnumMap<AfkSuppressibleCategory, Long> suppressedSinceRefresh = new EnumMap<>(AfkSuppressibleCategory.class);

        private RuntimeState() {
            resetSuppressedSinceRefresh(this);
        }
    }

    private static final class PlayerStats {
        private volatile String lastKnownName;
        private final TrafficCounter totalCounter = new TrafficCounter();
        private final EnumMap<AfkSuppressibleCategory, TrafficCounter> byCategory = new EnumMap<>(AfkSuppressibleCategory.class);

        private PlayerStats(final String lastKnownName) {
            this.lastKnownName = lastKnownName;
            for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
                this.byCategory.put(category, new TrafficCounter());
            }
        }

        private long totalBytes() {
            return this.totalCounter.totalBytes();
        }

        private long peakBytesPerSecond() {
            return this.totalCounter.peakBytesPerSecond();
        }
    }

    private static final class CategoryStats {
        private final TrafficCounter counter = new TrafficCounter();

        private long totalBytes() {
            return this.counter.totalBytes();
        }

        private long peakBytesPerSecond() {
            return this.counter.peakBytesPerSecond();
        }
    }

    private static final class TrafficCounter {
        private long totalBytes;
        private long peakBytesPerSecond;
        private long currentWindowBytes;
        private long currentWindowStartMillis = System.currentTimeMillis();

        private synchronized void record(final long bytes, final long now) {
            this.totalBytes += bytes;
            if (now - this.currentWindowStartMillis >= 1000L) {
                this.peakBytesPerSecond = Math.max(this.peakBytesPerSecond, this.currentWindowBytes);
                this.currentWindowBytes = 0L;
                this.currentWindowStartMillis = now;
            }
            this.currentWindowBytes += bytes;
            this.peakBytesPerSecond = Math.max(this.peakBytesPerSecond, this.currentWindowBytes);
        }

        private synchronized long totalBytes() {
            return this.totalBytes;
        }

        private synchronized long peakBytesPerSecond() {
            return this.peakBytesPerSecond;
        }
    }
}
