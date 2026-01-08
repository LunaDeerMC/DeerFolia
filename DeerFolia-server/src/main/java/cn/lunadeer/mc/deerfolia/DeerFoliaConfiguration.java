package cn.lunadeer.mc.deerfolia;

import cn.lunadeer.mc.deerfolia.utils.configuration.*;
import com.mojang.logging.LogUtils;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import io.papermc.paper.configuration.PaperConfigurations;
import org.slf4j.Logger;

import java.io.File;

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

}
