package cn.lunadeer.mc.deerfolia;

import cn.lunadeer.mc.deerfolia.afknetwork.AfkSuppressibleCategory;
import cn.lunadeer.mc.deerfolia.utils.configuration.*;
import com.mojang.logging.LogUtils;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import io.papermc.paper.configuration.PaperConfigurations;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DeerFoliaConfiguration extends ConfigurationFile {

    @HandleManually
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void load() {
        try {
            ConfigurationManager.load(DeerFoliaConfiguration.class, new File(PaperConfigurations.CONFIG_DIR, "deer-folia.yml"));
        } catch (Exception e) {
            LOGGER.error("Failed to load DeerFolia configuration: {}", e.getMessage());
        }
    }

    @Comments("Pufferfish Dynamic Activation Brain")
    public static DynamicActivationBrain dynamicActivationBrain = new DynamicActivationBrain();

    public static class DynamicActivationBrain extends ConfigurationPart {
        public boolean enabled = true;
        public int startDistance = 12;
        public int activationDistanceMod = 8;
        public int maximumActivationPrio = 20;
    }

    @Comments("Pufferfish Async Pathfinding")
    public static AsyncPathfinding asyncPathfinding = new AsyncPathfinding();

    public static class AsyncPathfinding extends ConfigurationPart {
        public boolean enabled = true;
        public int asyncPathfindingKeepAlive = 60;
        public int asyncPathfindingMaxThreads = 20;
    }

    @Comments("Kaiiju Entity Throttling (details in kaiiju-entity-throttling.yml)")
    public static boolean kaiijuEntityThrottling = true;

    @PostProcess
    public static void loadEntityThrottling() {
        if (!kaiijuEntityThrottling) return;
        LOGGER.info("Loading Entity Throttling (By KaiijuMC) configuration");
        KaiijuEntityLimits.init(new File(PaperConfigurations.CONFIG_DIR, "kaiiju-entity-throttling.yml"));
    }

    @Comments("Network Optimizations - Reduce CPU and bandwidth usage")
    public static NetworkOptimizations networkOptimizations = new NetworkOptimizations();

    public static class NetworkOptimizations extends ConfigurationPart {
        @Comments("Enable optimized VarInt read/write operations")
        public boolean optimizedVarInt = true;
        @Comments("Enable optimized frame encoding for better throughput")
        public boolean optimizedFrameEncoding = true;
    }

    @Comments("POI (Point of Interest) Optimizations - Reduce CPU usage with many villagers")
    public static PoiOptimizations poiOptimizations = new PoiOptimizations();

    public static class PoiOptimizations extends ConfigurationPart {
        @Comments("Enable POI optimizations")
        public boolean enabled = true;
        @Comments("Minimum interval (ticks) between golem spawn checks per villager")
        public int golemSpawnCheckInterval = 200;
        @Comments("Minimum interval (ticks) between secondary POI sensor scans")
        public int secondaryPoiSensorInterval = 80;
        @Comments("Cache duration (ticks) for village distance calculations")
        public int villageDistanceCacheDuration = 100;
        @Comments("Skip golem spawn check if villager is more than this distance from nearest player")
        public int golemSpawnPlayerDistanceLimit = 128;
    }

    @Comments("AFK Network Optimization - Suppress non-essential packets for inactive players")
    public static AfkNetworkOptimization afkNetworkOptimization = new AfkNetworkOptimization();

    public static class AfkNetworkOptimization extends ConfigurationPart {
        @Comments("Enable AFK network optimization")
        public boolean enabled = true;
        @Comments("Ticks without activity before a player is considered AFK")
        public int afkThresholdTicks = 20 * 60;
        @Comments({"Message sent once when a player first becomes AFK. Empty string disables it", "Placeholders: {player}", "Supports legacy colors like &7, &a, and hex colors like &#55FFFF"})
        public String afkEnterMessage = "&7[AFKNet] &eYou are now AFK. &7DeerFolia will temporarily suppress non-essential packets to save bandwidth.";
        @Comments({"Message sent when a player becomes active again. Empty string disables it", "Placeholders: {player}, {saved_traffic}, {saved_bandwidth}", "Supports legacy colors like &7, &a, and hex colors like &#55FFFF"})
        public String afkExitMessage = "&7[AFKNet] &aWelcome back! &7During this AFK session, you saved &b{saved_traffic} &7of traffic and up to &b{saved_bandwidth} &7of bandwidth. &aThanks for helping the server.";
        @Comments("Whether look-only rotation changes count as activity")
        public boolean countLookChangesAsActivity = true;
        @Comments("Resend nearby chunks and tracked entities when a player becomes active again")
        public boolean resyncOnResume = true;
        @Comments("Collect bandwidth saving statistics for commands")
        public boolean statsEnabled = true;
        @Comments("Suppressible categories that should continue to pass through while AFK. Valid values: chunk-stream, block-updates, entity-stream, world-effects, ui-stream")
        public List<String> suppressionWhitelistCategories = new ArrayList<>();
        @Comments("Maximum suppressed bytes per player and category before a category-specific refresh is forced. Set to -1 for unlimited suppression")
        public long maxSuppressedBytesBeforeCategoryResync = -1L;
    }

    @PostProcess
    public static void normalizeAfkNetworkOptimization() {
        if (afkNetworkOptimization.afkThresholdTicks < 1) {
            afkNetworkOptimization.afkThresholdTicks = 1;
        }
        afkNetworkOptimization.afkEnterMessage = afkNetworkOptimization.afkEnterMessage == null ? "" : afkNetworkOptimization.afkEnterMessage.trim();
        afkNetworkOptimization.afkExitMessage = afkNetworkOptimization.afkExitMessage == null ? "" : afkNetworkOptimization.afkExitMessage.trim();
        if (afkNetworkOptimization.maxSuppressedBytesBeforeCategoryResync < -1L) {
            afkNetworkOptimization.maxSuppressedBytesBeforeCategoryResync = -1L;
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String categoryName : afkNetworkOptimization.suppressionWhitelistCategories) {
            if (categoryName == null) {
                continue;
            }
            String normalizedName = categoryName.toLowerCase(Locale.ROOT).trim();
            if (normalizedName.isEmpty()) {
                continue;
            }
            if (AfkSuppressibleCategory.fromConfigKey(normalizedName).isPresent()) {
                normalized.add(normalizedName);
            } else {
                LOGGER.warn("Ignoring unknown AFK suppression whitelist category: {}", categoryName);
            }
        }
        afkNetworkOptimization.suppressionWhitelistCategories = new ArrayList<>(normalized);
    }

}
