package cn.lunadeer.mc.deerfolia.lithium;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.jspecify.annotations.Nullable;

/** Caches only registered/present/absent checks; never entity, world, or time-dependent start conditions. */
public final class BrainMemoryConditionCache {
    private static final Class<?> JDK_EMPTY_OR_MANY_MAP = Map.of().getClass();
    private static final Class<?> JDK_SINGLE_MAP = Map.of(true, true).getClass();
    private static final ClassValue<Boolean> USES_BASE_MEMORY_CHECK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("checkMemory", MemoryModuleType.class, MemoryStatus.class).getDeclaringClass() == Brain.class;
            } catch (NoSuchMethodException exception) {
                return false;
            }
        }
    };

    private final Map<MemoryModuleType<?>, MemoryStatus> conditions;
    private @Nullable Brain<?> previousBrain;
    private long previousVersion;
    private boolean previousResult;

    public BrainMemoryConditionCache(Map<MemoryModuleType<?>, MemoryStatus> conditions) {
        this.conditions = conditions;
    }

    public static boolean canCache(Map<MemoryModuleType<?>, MemoryStatus> conditions) {
        // Do not freeze or trust mutable maps (including unmodifiable views with mutable backing maps).
        return conditions instanceof ImmutableMap<?, ?>
            || conditions.getClass() == JDK_EMPTY_OR_MANY_MAP
            || conditions.getClass() == JDK_SINGLE_MAP;
    }

    public boolean check(Brain<?> brain) {
        // NMS extensions may compute memory conditions from additional state not represented by our version.
        if (brain.getClass() != Brain.class && !USES_BASE_MEMORY_CHECK.get(brain.getClass())) {
            return this.checkUncached(brain);
        }
        long version = brain.deerFoliaMemoryStatusVersion();
        if (this.previousBrain == brain && this.previousVersion == version) {
            return this.previousResult;
        }
        boolean result = this.checkUncached(brain);
        this.previousBrain = brain;
        this.previousVersion = version;
        this.previousResult = result;
        return result;
    }

    private boolean checkUncached(Brain<?> brain) {
        for (Map.Entry<MemoryModuleType<?>, MemoryStatus> condition : this.conditions.entrySet()) {
            if (!brain.checkMemory(condition.getKey(), condition.getValue())) {
                return false;
            }
        }
        return true;
    }
}
