package cn.lunadeer.mc.deerfolia.afknetwork;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public final class AfkNetworkManager {
    private static final ConcurrentMap<UUID, RuntimeState> RUNTIME_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, PlayerStats> PLAYER_STATS = new ConcurrentHashMap<>();
    private static final EnumMap<AfkSuppressibleCategory, CategoryStats> CATEGORY_STATS = new EnumMap<>(AfkSuppressibleCategory.class);
    private static final TrafficCounter GLOBAL_COUNTER = new TrafficCounter();
    private static final ThreadLocal<Integer> SUPPRESSION_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile int suppressionWhitelistMask;

    static {
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            CATEGORY_STATS.put(category, new CategoryStats());
        }
    }

    private AfkNetworkManager() {
    }

    public static void refreshConfiguration() {
        int mask = 0;
        for (final String entry : DeerFoliaConfiguration.afkNetworkOptimization.suppressionWhitelistCategories) {
            final var category = AfkSuppressibleCategory.fromConfigKey(entry);
            if (category.isPresent()) {
                mask |= category.get().bit();
            }
        }
        suppressionWhitelistMask = mask;
    }

    public static void handleBaseActivity(final ServerPlayer player) {
        markActivityFromAnyThread(player);
    }

    public static void handleLookOrMoveActivity(final ServerPlayer player, final boolean moved, final boolean looked) {
        if (!moved && !looked) {
            return;
        }
        if (moved || (looked && DeerFoliaConfiguration.afkNetworkOptimization.countLookChangesAsActivity)) {
            markActivityFromAnyThread(player);
        }
    }

    public static void tickPlayer(final ServerPlayer player) {
        final RuntimeState state = runtimeStateForOwner(player);
        final DeerFoliaConfiguration.AfkNetworkOptimization config = DeerFoliaConfiguration.afkNetworkOptimization;
        final long now = Util.getMillis();

        if (state.activityPending.getAndSet(false) && state.afk) {
            exitAfk(player, state, now);
        }

        if (!config.enabled || player.hasDisconnected()) {
            if (state.afk) {
                exitAfk(player, state, now);
            }
            return;
        }

        if (!state.afk && isAfk(player, state, now, config.afkThresholdTicks)) {
            enterAfk(player, state, now);
        }
    }

    public static void onLogin(final ServerPlayer player) {
        RUNTIME_STATES.put(player.getUUID(), new RuntimeState(player));
    }

    public static void onDisconnect(final ServerPlayer player) {
        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state != null && state.owner == player) {
            RUNTIME_STATES.remove(player.getUUID(), state);
        }
    }

    public static void onEntityTrackingStart(final ServerPlayer player, final ChunkMap.TrackedEntity tracker) {
        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state != null && state.owner == player) {
            state.trackedEntities.add(tracker);
            state.trackedEntitiesById.put(tracker.deerFolia$getEntityId(), tracker);
            if (!state.afk || (suppressionWhitelistMask & AfkSuppressibleCategory.ENTITY_STREAM.bit()) != 0) {
                state.clientTrackedEntities.add(tracker);
            }
        }
    }

    public static void onEntityTrackingStop(final ServerPlayer player, final ChunkMap.TrackedEntity tracker) {
        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state != null && state.owner == player) {
            state.trackedEntities.remove(tracker);
            state.trackedEntitiesById.remove(tracker.deerFolia$getEntityId(), tracker);
            if (!state.afk || (suppressionWhitelistMask & AfkSuppressibleCategory.ENTITY_STREAM.bit()) != 0) {
                state.clientTrackedEntities.remove(tracker);
            }
        }
    }

    public static void withSuppressionBypass(final Runnable action) {
        final int previous = SUPPRESSION_BYPASS_DEPTH.get();
        SUPPRESSION_BYPASS_DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) {
                SUPPRESSION_BYPASS_DEPTH.remove();
            } else {
                SUPPRESSION_BYPASS_DEPTH.set(previous);
            }
        }
    }

    public static boolean shouldSuppress(final ServerPlayer player, final Packet<?> packet) {
        final DeerFoliaConfiguration.AfkNetworkOptimization config = DeerFoliaConfiguration.afkNetworkOptimization;
        if (!config.enabled || player.hasDisconnected() || SUPPRESSION_BYPASS_DEPTH.get() > 0) {
            return false;
        }

        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state == null || state.owner != player || !state.afk) {
            return false;
        }

        final AfkSuppressibleCategory category = classify(packet, player.getId());
        if (category == null || category == AfkSuppressibleCategory.UI_STREAM || (suppressionWhitelistMask & category.bit()) != 0) {
            return false;
        }

        final int estimatedBytes = estimatePacketBytes(packet, category);
        markSuppressedDirtyState(state, packet, category);
        final long threshold = config.maxSuppressedBytesBeforeCategoryResync;
        if (threshold >= 0L && crossedRefreshThreshold(state.suppressedSinceRefresh, category.ordinal(), estimatedBytes, threshold)) {
            if (category == AfkSuppressibleCategory.WORLD_EFFECTS) {
                return false;
            }
            scheduleCategoryRefresh(player, state, category);
        }

        state.suppressedCategoryMask.getAndUpdate(mask -> mask | category.bit());
        recordAfkSessionSaved(state, estimatedBytes, Util.getMillis());
        recordSaved(player, category, estimatedBytes, Util.getMillis());
        return true;
    }

    public static List<Component> buildSummaryLines() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("[AFKNet] estimated total saved: " + formatBytes(GLOBAL_COUNTER.totalBytes())));
        lines.add(Component.literal("[AFKNet] estimated peak saved bandwidth: " + formatRate(GLOBAL_COUNTER.peakBytesPerSecond())));
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
        lines.add(Component.literal("[AFKNet] estimated total saved: " + formatBytes(stats.totalBytes())));
        lines.add(Component.literal("[AFKNet] estimated peak saved bandwidth: " + formatRate(stats.peakBytesPerSecond())));
        lines.add(Component.literal("[AFKNet] categories:"));
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            final TrafficCounter counter = stats.byCategory.get(category);
            lines.add(Component.literal(" - " + category.configKey() + ": " + formatBytes(counter.totalBytes()) + ", peak " + formatRate(counter.peakBytesPerSecond())));
        }
        return lines;
    }

    private static RuntimeState runtimeStateForOwner(final ServerPlayer player) {
        return RUNTIME_STATES.compute(player.getUUID(), (ignored, existing) -> {
            if (existing == null || existing.owner != player) {
                return new RuntimeState(player);
            }
            return existing;
        });
    }

    private static boolean isAfk(final ServerPlayer player, final RuntimeState state, final long now, final int thresholdTicks) {
        final long thresholdMillis = thresholdTicks * 50L;
        return now - state.lastActivityMillis.get() >= thresholdMillis;
    }

    private static void markActivityFromAnyThread(final ServerPlayer player) {
        final RuntimeState state = RUNTIME_STATES.get(player.getUUID());
        if (state == null || state.owner != player) {
            return;
        }

        final long now = Util.getMillis();
        long previous = state.lastActivityMillis.get();
        while (now > previous && !state.lastActivityMillis.compareAndSet(previous, now)) {
            previous = state.lastActivityMillis.get();
        }
        state.activityPending.set(true);
    }

    private static void enterAfk(final ServerPlayer player, final RuntimeState state, final long now) {
        state.afk = true;
        state.afkGeneration++;
        state.afkSessionCounter.reset(now);
        state.suppressedCategoryMask.set(0);
        state.dirtyBlockChunks.clear();
        state.dirtyEntityIds.clear();
        resetSuppressedSinceRefresh(state);

        state.clientTrackedEntities.clear();
        state.clientTrackedEntities.addAll(state.trackedEntities);
        state.clientChunkSnapshot = snapshotSentChunks(player);

        sendConfiguredMessage(player, DeerFoliaConfiguration.afkNetworkOptimization.afkEnterMessage, 0L, 0L);
    }

    private static void exitAfk(final ServerPlayer player, final RuntimeState state, final long now) {
        final long sessionSavedTraffic = state.afkSessionCounter.totalBytes();
        final long sessionSavedBandwidth = state.afkSessionCounter.peakBytesPerSecond();
        final int suppressedCategoryMask = state.suppressedCategoryMask.getAndSet(0);

        state.afk = false;
        state.afkGeneration++;
        resetSuppressedSinceRefresh(state);
        state.afkSessionCounter.reset(now);

        sendConfiguredMessage(
            player,
            DeerFoliaConfiguration.afkNetworkOptimization.afkExitMessage,
            sessionSavedTraffic,
            sessionSavedBandwidth
        );
        resyncAfterAfk(player, state, DeerFoliaConfiguration.afkNetworkOptimization.resyncOnResume, suppressedCategoryMask);
    }

    private static long[] snapshotSentChunks(final ServerPlayer player) {
        final var loader = player.moonrise$getChunkLoader();
        return loader == null ? new long[0] : loader.deerFolia$snapshotSentChunks();
    }

    private static void resyncAfterAfk(final ServerPlayer player, final RuntimeState state, final boolean fullResync, final int suppressedCategoryMask) {
        if (player.hasDisconnected()) {
            return;
        }

        final boolean blockStateDirty = (suppressedCategoryMask & AfkSuppressibleCategory.BLOCK_UPDATES.bit()) != 0;
        final boolean entityStateDirty = (suppressedCategoryMask & AfkSuppressibleCategory.ENTITY_STREAM.bit()) != 0;

        final var loader = player.moonrise$getChunkLoader();
        if (loader != null) {
            final long[] previous = state.clientChunkSnapshot;
            withSuppressionBypass(() -> loader.deerFolia$resyncClientChunks(previous, fullResync));
            state.clientChunkSnapshot = loader.deerFolia$snapshotSentChunks();
            if (fullResync) {
                state.dirtyBlockChunks.clear();
            } else if (blockStateDirty) {
                refreshDirtyBlockChunks(player, state);
            }
        } else {
            state.clientChunkSnapshot = new long[0];
        }

        if (fullResync || entityStateDirty) {
            reconcileTrackedEntities(player, state, fullResync);
        } else {
            state.clientTrackedEntities.clear();
            state.clientTrackedEntities.addAll(state.trackedEntities);
            state.dirtyEntityIds.clear();
        }
    }

    private static void scheduleCategoryRefresh(final ServerPlayer player, final RuntimeState state, final AfkSuppressibleCategory category) {
        final int categoryIndex = category.ordinal();
        state.refreshRequested[categoryIndex].set(true);
        final AtomicBoolean scheduled = state.refreshScheduled[categoryIndex];
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        final long afkGeneration = state.afkGeneration;

        schedulePlayerTask(player, () -> {
            try {
                while (state.afk
                    && state.afkGeneration == afkGeneration
                    && RUNTIME_STATES.get(player.getUUID()) == state
                    && state.refreshRequested[categoryIndex].getAndSet(false)) {
                    state.suppressedSinceRefresh.set(categoryIndex, 0L);
                    switch (category) {
                        case CHUNK_STREAM -> {
                            final var loader = player.moonrise$getChunkLoader();
                            if (loader != null) {
                                final long[] previous = state.clientChunkSnapshot;
                                withSuppressionBypass(() -> loader.deerFolia$resyncClientChunks(previous, false));
                                state.clientChunkSnapshot = loader.deerFolia$snapshotSentChunks();
                            }
                        }
                        case BLOCK_UPDATES -> refreshDirtyBlockChunks(player, state);
                        case ENTITY_STREAM -> reconcileTrackedEntities(player, state, false);
                        case WORLD_EFFECTS, UI_STREAM -> {
                        }
                    }
                }
            } finally {
                scheduled.set(false);
                if (state.afk
                    && state.afkGeneration == afkGeneration
                    && RUNTIME_STATES.get(player.getUUID()) == state
                    && state.refreshRequested[categoryIndex].get()) {
                    scheduleCategoryRefresh(player, state, category);
                }
            }
        });
    }

    private static void refreshDirtyBlockChunks(final ServerPlayer player, final RuntimeState state) {
        final var loader = player.moonrise$getChunkLoader();
        if (loader == null || state.dirtyBlockChunks.isEmpty()) {
            return;
        }

        final Map<Long, Long> dirtySnapshot = Map.copyOf(state.dirtyBlockChunks);
        final long[] chunkKeys = new long[dirtySnapshot.size()];
        int index = 0;
        for (final long chunkKey : dirtySnapshot.keySet()) {
            chunkKeys[index++] = chunkKey;
        }

        withSuppressionBypass(() -> loader.deerFolia$resendClientChunks(chunkKeys));
        dirtySnapshot.forEach((chunkKey, version) -> state.dirtyBlockChunks.remove(chunkKey, version));
    }

    private static void reconcileTrackedEntities(final ServerPlayer player, final RuntimeState state, final boolean resendAllCurrent) {
        final Set<ChunkMap.TrackedEntity> current = new HashSet<>(state.trackedEntities);
        final Set<ChunkMap.TrackedEntity> stale = new HashSet<>(state.clientTrackedEntities);
        stale.removeAll(current);
        final Set<ChunkMap.TrackedEntity> toResync = new HashSet<>();
        final Map<Integer, Long> dirtySnapshot = Map.copyOf(state.dirtyEntityIds);

        if (!stale.isEmpty()) {
            final int[] ids = stale.stream().mapToInt(ChunkMap.TrackedEntity::deerFolia$getEntityId).toArray();
            withSuppressionBypass(() -> player.connection.send(new ClientboundRemoveEntitiesPacket(ids)));
        }

        if (resendAllCurrent) {
            toResync.addAll(current);
        } else {
            final Set<ChunkMap.TrackedEntity> added = new HashSet<>(current);
            added.removeAll(state.clientTrackedEntities);
            toResync.addAll(added);
            for (final int entityId : dirtySnapshot.keySet()) {
                final ChunkMap.TrackedEntity tracker = state.trackedEntitiesById.get(entityId);
                if (tracker != null && current.contains(tracker)) {
                    toResync.add(tracker);
                }
            }
        }

        for (final ChunkMap.TrackedEntity tracker : toResync) {
            tracker.deerFolia$resyncPlayer(player);
        }

        state.clientTrackedEntities.clear();
        state.clientTrackedEntities.addAll(current);
        dirtySnapshot.forEach((entityId, version) -> state.dirtyEntityIds.remove(entityId, version));
    }

    private static void markSuppressedDirtyState(final RuntimeState state, final Packet<?> packet, final AfkSuppressibleCategory category) {
        if (packet instanceof BundlePacket<?> bundlePacket) {
            for (final Packet<?> subPacket : bundlePacket.subPackets()) {
                markSuppressedDirtyState(state, subPacket, category);
            }
            return;
        }

        switch (category) {
            case BLOCK_UPDATES -> markDirtyBlockChunk(state, packet);
            case ENTITY_STREAM -> markDirtyEntity(state, packet);
            case CHUNK_STREAM, WORLD_EFFECTS, UI_STREAM -> {
            }
        }
    }

    private static void markDirtyBlockChunk(final RuntimeState state, final Packet<?> packet) {
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            markDirtyChunk(state, blockUpdate.getPos());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionBlocksUpdate) {
            markDirtyVersion(state.dirtyBlockChunks, sectionBlocksUpdate.deerFolia$getChunkKey());
        } else if (packet instanceof ClientboundBlockEntityDataPacket blockEntityData) {
            markDirtyChunk(state, blockEntityData.getPos());
        } else if (packet instanceof ClientboundBlockDestructionPacket blockDestruction) {
            markDirtyChunk(state, blockDestruction.getPos());
        } else if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            markDirtyChunk(state, blockEvent.getPos());
        }
    }

    private static void markDirtyChunk(final RuntimeState state, final net.minecraft.core.BlockPos pos) {
        markDirtyVersion(state.dirtyBlockChunks, ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private static void markDirtyEntity(final RuntimeState state, final Packet<?> packet) {
        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            markDirtyVersion(state.dirtyEntityIds, addEntity.getId());
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removeEntities) {
            for (final int entityId : removeEntities.getEntityIds()) {
                markDirtyVersion(state.dirtyEntityIds, entityId);
            }
        } else if (packet instanceof ClientboundMoveEntityPacket moveEntity) {
            markDirtyVersion(state.dirtyEntityIds, moveEntity.deerFolia$getEntityId());
        } else if (packet instanceof ClientboundTeleportEntityPacket teleportEntity) {
            markDirtyVersion(state.dirtyEntityIds, teleportEntity.id());
        } else if (packet instanceof ClientboundEntityPositionSyncPacket positionSync) {
            markDirtyVersion(state.dirtyEntityIds, positionSync.id());
        } else if (packet instanceof ClientboundRotateHeadPacket rotateHead) {
            markDirtyVersion(state.dirtyEntityIds, rotateHead.deerFolia$getEntityId());
        } else if (packet instanceof ClientboundSetEntityDataPacket entityData) {
            markDirtyVersion(state.dirtyEntityIds, entityData.id());
        } else if (packet instanceof ClientboundSetEntityMotionPacket entityMotion) {
            markDirtyVersion(state.dirtyEntityIds, entityMotion.id());
        } else if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            markDirtyVersion(state.dirtyEntityIds, equipment.getEntity());
        } else if (packet instanceof ClientboundSetPassengersPacket passengers) {
            markDirtyVersion(state.dirtyEntityIds, passengers.getVehicle());
            for (final int passengerId : passengers.getPassengers()) {
                markDirtyVersion(state.dirtyEntityIds, passengerId);
            }
        } else if (packet instanceof ClientboundSetEntityLinkPacket entityLink) {
            markDirtyVersion(state.dirtyEntityIds, entityLink.getSourceId());
            if (entityLink.getDestId() != 0) {
                markDirtyVersion(state.dirtyEntityIds, entityLink.getDestId());
            }
        } else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            markDirtyVersion(state.dirtyEntityIds, attributes.getEntityId());
        } else if (packet instanceof ClientboundProjectilePowerPacket projectilePower) {
            markDirtyVersion(state.dirtyEntityIds, projectilePower.getId());
        } else if (packet instanceof ClientboundMoveMinecartPacket moveMinecart) {
            markDirtyVersion(state.dirtyEntityIds, moveMinecart.entityId());
        } else if (packet instanceof ClientboundAnimatePacket animate) {
            markDirtyVersion(state.dirtyEntityIds, animate.getId());
        } else if (packet instanceof ClientboundEntityEventPacket entityEvent) {
            markDirtyVersion(state.dirtyEntityIds, entityEvent.deerFolia$getEntityId());
        } else if (packet instanceof ClientboundDamageEventPacket damageEvent) {
            markDirtyVersion(state.dirtyEntityIds, damageEvent.entityId());
        }
    }

    private static <K> void markDirtyVersion(final ConcurrentMap<K, Long> dirtyMap, final K key) {
        dirtyMap.merge(key, 1L, (current, ignored) -> current == Long.MAX_VALUE ? Long.MIN_VALUE : current + 1L);
    }

    private static void schedulePlayerTask(final ServerPlayer player, final Runnable action) {
        if (player.hasDisconnected()) {
            return;
        }
        player.getBukkitEntity().taskScheduler.scheduleOrExecute(ignored -> {
            if (!player.hasDisconnected()) {
                action.run();
            }
        });
    }

    private static boolean crossedRefreshThreshold(
        final AtomicLongArray counters,
        final int index,
        final int estimatedBytes,
        final long threshold
    ) {
        while (true) {
            final long current = counters.get(index);
            final long nextValue = saturatingAdd(current, estimatedBytes);
            final boolean crossed = nextValue >= threshold;
            final long replacement = crossed ? 0L : nextValue;
            if (counters.compareAndSet(index, current, replacement)) {
                return crossed;
            }
        }
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void resetSuppressedSinceRefresh(final RuntimeState state) {
        for (final AfkSuppressibleCategory category : AfkSuppressibleCategory.values()) {
            state.suppressedSinceRefresh.set(category.ordinal(), 0L);
            state.refreshRequested[category.ordinal()].set(false);
        }
    }

    private static void recordAfkSessionSaved(final RuntimeState state, final int bytes, final long now) {
        if (bytes > 0) {
            state.afkSessionCounter.record(bytes, now);
        }
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
        GLOBAL_COUNTER.record(bytes, now);
    }

    private static int estimatePacketBytes(final Packet<?> packet, final AfkSuppressibleCategory category) {
        if (packet instanceof BundlePacket<?> bundlePacket) {
            long total = 0L;
            for (final Packet<?> subPacket : bundlePacket.subPackets()) {
                total += estimatePacketBytes(subPacket, category);
                if (total >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return (int)total;
        }

        if (packet instanceof ClientboundLevelChunkWithLightPacket) return 64 * 1024;
        if (packet instanceof ClientboundChunksBiomesPacket) return 16 * 1024;
        if (packet instanceof ClientboundLightUpdatePacket) return 4 * 1024;
        if (packet instanceof ClientboundSectionBlocksUpdatePacket) return 1024;
        if (packet instanceof ClientboundBlockEntityDataPacket) return 512;
        if (packet instanceof ClientboundSetEntityDataPacket || packet instanceof ClientboundSetEquipmentPacket) return 256;
        if (packet instanceof ClientboundAddEntityPacket) return 128;
        if (packet instanceof ClientboundLevelParticlesPacket) return 96;
        if (packet instanceof ClientboundForgetLevelChunkPacket
            || packet instanceof ClientboundChunkBatchStartPacket
            || packet instanceof ClientboundChunkBatchFinishedPacket) return 8;

        return switch (category) {
            case CHUNK_STREAM -> 512;
            case BLOCK_UPDATES -> 64;
            case ENTITY_STREAM -> 48;
            case WORLD_EFFECTS -> 48;
            case UI_STREAM -> 128;
        };
    }

    private static @Nullable AfkSuppressibleCategory classify(final Packet<?> packet, final int playerId) {
        if (packet instanceof BundlePacket<?> bundlePacket) {
            AfkSuppressibleCategory category = null;
            for (final Packet<?> subPacket : bundlePacket.subPackets()) {
                final AfkSuppressibleCategory subCategory = classify(subPacket, playerId);
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

        if (isSelfEntityPacket(playerId, packet)) {
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

    private static boolean isSelfEntityPacket(final int playerId, final Packet<?> packet) {
        return packet instanceof ClientboundSetEntityDataPacket entityData && entityData.id() == playerId
            || packet instanceof ClientboundMoveEntityPacket moveEntity && moveEntity.deerFolia$getEntityId() == playerId
            || packet instanceof ClientboundTeleportEntityPacket teleportEntity && teleportEntity.id() == playerId
            || packet instanceof ClientboundEntityPositionSyncPacket entityPositionSync && entityPositionSync.id() == playerId
            || packet instanceof ClientboundSetEntityMotionPacket entityMotion && entityMotion.id() == playerId
            || packet instanceof ClientboundSetEquipmentPacket setEquipment && setEquipment.getEntity() == playerId
            || packet instanceof ClientboundUpdateAttributesPacket updateAttributes && updateAttributes.getEntityId() == playerId;
    }

    private static void sendConfiguredMessage(
        final ServerPlayer player,
        final String template,
        final long savedTrafficBytes,
        final long savedBandwidthBytesPerSecond
    ) {
        if (player.hasDisconnected() || template == null || template.isEmpty()) {
            return;
        }

        final String message = template
            .replace("{player}", player.getGameProfile().name())
            .replace("{saved_traffic}", formatBytes(savedTrafficBytes))
            .replace("{saved_bandwidth}", formatRate(savedBandwidthBytesPerSecond));
        if (!message.isEmpty()) {
            withSuppressionBypass(() -> player.sendSystemMessage(parseConfiguredMessage(message)));
        }
    }

    private static Component parseConfiguredMessage(final String message) {
        final MutableComponent component = Component.empty();
        final StringBuilder segment = new StringBuilder(message.length());
        Style style = Style.EMPTY;

        for (int index = 0; index < message.length(); index++) {
            final char character = message.charAt(index);
            if (isColorCodePrefix(character) && index + 1 < message.length()) {
                final Style hexStyle = parseHexColor(message, index);
                if (hexStyle != null) {
                    appendMessageSegment(component, segment, style);
                    style = hexStyle;
                    index += 7;
                    continue;
                }

                final ChatFormatting formatting = ChatFormatting.getByCode(message.charAt(index + 1));
                if (formatting != null) {
                    appendMessageSegment(component, segment, style);
                    style = applyLegacyFormatting(style, formatting);
                    index++;
                    continue;
                }
            }
            segment.append(character);
        }

        appendMessageSegment(component, segment, style);
        return component;
    }

    private static boolean isColorCodePrefix(final char character) {
        return character == '&' || character == '\u00A7';
    }

    private static @Nullable Style parseHexColor(final String message, final int prefixIndex) {
        if (prefixIndex + 7 >= message.length() || message.charAt(prefixIndex + 1) != '#') {
            return null;
        }

        final String hex = message.substring(prefixIndex + 2, prefixIndex + 8);
        for (int index = 0; index < hex.length(); index++) {
            if (Character.digit(hex.charAt(index), 16) < 0) {
                return null;
            }
        }
        return Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
    }

    private static Style applyLegacyFormatting(final Style currentStyle, final ChatFormatting formatting) {
        if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        if (TextColor.fromLegacyFormat(formatting) != null) {
            return Style.EMPTY.withColor(formatting);
        }
        return switch (formatting) {
            case BOLD -> currentStyle.withBold(true);
            case ITALIC -> currentStyle.withItalic(true);
            case UNDERLINE -> currentStyle.withUnderlined(true);
            case STRIKETHROUGH -> currentStyle.withStrikethrough(true);
            case OBFUSCATED -> currentStyle.withObfuscated(true);
            default -> currentStyle;
        };
    }

    private static void appendMessageSegment(final MutableComponent component, final StringBuilder segment, final Style style) {
        if (segment.isEmpty()) {
            return;
        }
        component.append(Component.literal(segment.toString()).setStyle(style));
        segment.setLength(0);
    }

    private static String formatBytes(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final String[] units = {"KiB", "MiB", "GiB", "TiB"};
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
        private final ServerPlayer owner;
        private final AtomicLong lastActivityMillis = new AtomicLong(Util.getMillis());
        private final AtomicBoolean activityPending = new AtomicBoolean();
        private volatile boolean afk;
        private volatile long afkGeneration;
        private final AtomicInteger suppressedCategoryMask = new AtomicInteger();
        private final AtomicLongArray suppressedSinceRefresh = new AtomicLongArray(AfkSuppressibleCategory.values().length);
        private final AtomicBoolean[] refreshScheduled = new AtomicBoolean[AfkSuppressibleCategory.values().length];
        private final AtomicBoolean[] refreshRequested = new AtomicBoolean[AfkSuppressibleCategory.values().length];
        private final TrafficCounter afkSessionCounter = new TrafficCounter();
        private final Set<ChunkMap.TrackedEntity> trackedEntities = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<Integer, ChunkMap.TrackedEntity> trackedEntitiesById = new ConcurrentHashMap<>();
        private final Set<ChunkMap.TrackedEntity> clientTrackedEntities = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<Long, Long> dirtyBlockChunks = new ConcurrentHashMap<>();
        private final ConcurrentMap<Integer, Long> dirtyEntityIds = new ConcurrentHashMap<>();
        private long[] clientChunkSnapshot = new long[0];

        private RuntimeState(final ServerPlayer owner) {
            this.owner = owner;
            for (int i = 0; i < this.refreshScheduled.length; i++) {
                this.refreshScheduled[i] = new AtomicBoolean();
                this.refreshRequested[i] = new AtomicBoolean();
            }
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
        private final LongAdder totalBytes = new LongAdder();
        private final AtomicLong currentWindowSecond = new AtomicLong(Util.getMillis() / 1000L);
        private final AtomicLong currentWindowBytes = new AtomicLong();
        private final AtomicLong peakBytesPerSecond = new AtomicLong();

        private void record(final long bytes, final long now) {
            if (bytes <= 0L) {
                return;
            }
            this.totalBytes.add(bytes);
            final long second = now / 1000L;
            final long observedSecond = this.currentWindowSecond.get();
            if (second != observedSecond && this.currentWindowSecond.compareAndSet(observedSecond, second)) {
                updateMax(this.peakBytesPerSecond, this.currentWindowBytes.getAndSet(0L));
            }
            final long current = this.currentWindowBytes.addAndGet(bytes);
            updateMax(this.peakBytesPerSecond, current);
        }

        private void reset(final long now) {
            this.totalBytes.reset();
            this.currentWindowSecond.set(now / 1000L);
            this.currentWindowBytes.set(0L);
            this.peakBytesPerSecond.set(0L);
        }

        private long totalBytes() {
            return this.totalBytes.sum();
        }

        private long peakBytesPerSecond() {
            return this.peakBytesPerSecond.get();
        }

        private static void updateMax(final AtomicLong target, final long candidate) {
            long current = target.get();
            while (candidate > current && !target.compareAndSet(current, candidate)) {
                current = target.get();
            }
        }
    }
}
