package cn.lunadeer.mc.deerfolia.lithium;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
class BrainOptimizationTest {
    private boolean oldEnabled;
    private boolean oldAi;
    private boolean oldTasks;
    private boolean oldMemory;

    @BeforeEach
    void enable() {
        oldEnabled = DeerFoliaConfiguration.lithiumOptimize.enabled;
        oldAi = DeerFoliaConfiguration.lithiumOptimize.ai;
        oldTasks = DeerFoliaConfiguration.lithiumOptimize.brainTasks;
        oldMemory = DeerFoliaConfiguration.lithiumOptimize.brainMemory;
        DeerFoliaConfiguration.lithiumOptimize.enabled = true;
        DeerFoliaConfiguration.lithiumOptimize.ai = true;
        DeerFoliaConfiguration.lithiumOptimize.brainTasks = true;
        DeerFoliaConfiguration.lithiumOptimize.brainMemory = true;
    }

    @AfterEach
    void restore() {
        DeerFoliaConfiguration.lithiumOptimize.enabled = oldEnabled;
        DeerFoliaConfiguration.lithiumOptimize.ai = oldAi;
        DeerFoliaConfiguration.lithiumOptimize.brainTasks = oldTasks;
        DeerFoliaConfiguration.lithiumOptimize.brainMemory = oldMemory;
    }

    @Test
    void memoryCacheCoversAllStatusesAndInvalidations() {
        TestBrain brain = spy(new TestBrain(List.of(MemoryModuleType.IS_PANICKING)));
        for (MemoryStatus status : MemoryStatus.values()) {
            BrainMemoryConditionCache cache = new BrainMemoryConditionCache(Map.of(MemoryModuleType.IS_PANICKING, status));
            assertEquals(brain.checkMemory(MemoryModuleType.IS_PANICKING, status), cache.check(brain));
            clearInvocations(brain);
            for (int i = 0; i < 100; ++i) cache.check(brain);
            verify(brain, never()).checkMemory(any(), any());
            brain.setMemory(MemoryModuleType.IS_PANICKING, true);
            assertEquals(brain.checkMemory(MemoryModuleType.IS_PANICKING, status), cache.check(brain));
            long version = brain.deerFoliaMemoryStatusVersion();
            brain.setMemory(MemoryModuleType.IS_PANICKING, false);
            assertEquals(version, brain.deerFoliaMemoryStatusVersion(), "present-to-present is not a presence change");
            brain.eraseMemory(MemoryModuleType.IS_PANICKING);
            assertEquals(brain.checkMemory(MemoryModuleType.IS_PANICKING, status), cache.check(brain));
            brain.setMemory(MemoryModuleType.IS_PANICKING, true);
            assertEquals(brain.checkMemory(MemoryModuleType.IS_PANICKING, status), cache.check(brain));
            brain.clearMemories();
            assertEquals(brain.checkMemory(MemoryModuleType.IS_PANICKING, status), cache.check(brain));
        }
    }

    @Test
    void unregisteredIsNotAbsentAndRegistrationInvalidates() {
        TestBrain brain = new TestBrain(List.of());
        BrainMemoryConditionCache absent = new BrainMemoryConditionCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT));
        BrainMemoryConditionCache registered = new BrainMemoryConditionCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.REGISTERED));
        assertFalse(absent.check(brain));
        assertFalse(registered.check(brain));
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0,
            new MemoryProbe(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.REGISTERED)))), Set.of(), Set.of());
        assertTrue(absent.check(brain));
        assertTrue(registered.check(brain));
    }

    @Test
    void expiryKeepsTheOriginalBrainTickBoundary() {
        TestBrain brain = spy(new TestBrain(List.of(MemoryModuleType.IS_PANICKING)));
        BrainMemoryConditionCache present = new BrainMemoryConditionCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_PRESENT));
        brain.setMemoryWithExpiry(MemoryModuleType.IS_PANICKING, true, 1);
        assertTrue(present.check(brain));
        ServerLevel level = mock(ServerLevel.class);
        long version = brain.deerFoliaMemoryStatusVersion();
        brain.tick(level, null);
        assertTrue(present.check(brain));
        assertEquals(version, brain.deerFoliaMemoryStatusVersion());
        brain.tick(level, null);
        assertFalse(present.check(brain));
        assertTrue(version < brain.deerFoliaMemoryStatusVersion());
    }

    @Test
    void emptyCollectionsClearAndBrainIdentityIsPartOfTheCacheKey() {
        TestBrain first = new TestBrain(List.of(MemoryModuleType.NEAREST_LIVING_ENTITIES));
        TestBrain second = new TestBrain(List.of(MemoryModuleType.NEAREST_LIVING_ENTITIES));
        BrainMemoryConditionCache present = new BrainMemoryConditionCache(Map.of(MemoryModuleType.NEAREST_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT));
        first.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(mock(LivingEntity.class)));
        assertTrue(present.check(first));
        assertFalse(present.check(second));
        first.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of());
        assertFalse(present.check(first));
        assertEquals(first.deerFoliaMemoryStatusVersion(), second.deerFoliaMemoryStatusVersion() + 2);

        // Two different brains can reach the same numeric version with opposite states.
        first.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(mock(LivingEntity.class)));
        second.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(mock(LivingEntity.class)));
        second.eraseMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        second.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0,
            new MemoryProbe(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.REGISTERED)))), Set.of(), Set.of());
        assertEquals(first.deerFoliaMemoryStatusVersion(), second.deerFoliaMemoryStatusVersion());
        assertTrue(present.check(first));
        assertFalse(present.check(second));
    }

    @Test
    void behaviorPreservesMutableConditionsAndDisabledPath() {
        TestBrain brain = spy(new TestBrain(List.of(MemoryModuleType.IS_PANICKING)));
        LivingEntity body = mock(LivingEntity.class);
        doReturn(brain).when(body).getBrain();
        Map<MemoryModuleType<?>, MemoryStatus> mutable = new HashMap<>();
        mutable.put(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT);
        MemoryProbe probe = new MemoryProbe(Collections.unmodifiableMap(mutable));
        assertTrue(probe.required(body));
        mutable.put(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_PRESENT);
        assertFalse(probe.required(body));

        MemoryProbe immutable = new MemoryProbe(ImmutableMap.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT));
        assertTrue(immutable.required(body));
        clearInvocations(brain);
        assertTrue(immutable.required(body));
        verify(brain, never()).checkMemory(any(), any());
        DeerFoliaConfiguration.lithiumOptimize.brainMemory = false;
        assertTrue(immutable.required(body));
        verify(brain, times(1)).checkMemory(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT);
        brain.setMemory(MemoryModuleType.IS_PANICKING, true);
        DeerFoliaConfiguration.lithiumOptimize.brainMemory = true;
        assertFalse(immutable.required(body));
    }

    @Test
    void recognizesImmutableBuiltinsOnly() {
        assertTrue(BrainMemoryConditionCache.canCache(Map.of()));
        assertTrue(BrainMemoryConditionCache.canCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT)));
        assertTrue(BrainMemoryConditionCache.canCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT,
            MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)));
        assertTrue(BrainMemoryConditionCache.canCache(ImmutableMap.of()));
        assertFalse(BrainMemoryConditionCache.canCache(new HashMap<>()));
        assertFalse(BrainMemoryConditionCache.canCache(Collections.unmodifiableMap(new HashMap<>())));
    }

    @Test
    void overriddenMemorySemanticsAreNeverCached() {
        class DynamicBrain extends Brain<LivingEntity> {
            boolean result;

            @Override
            public boolean checkMemory(MemoryModuleType<?> type, MemoryStatus status) {
                return this.result;
            }
        }
        DynamicBrain brain = new DynamicBrain();
        BrainMemoryConditionCache cache = new BrainMemoryConditionCache(Map.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_PRESENT));
        assertFalse(cache.check(brain));
        brain.result = true;
        assertTrue(cache.check(brain));
    }

    @Test
    void taskOrderAndMidGroupActivitySwitchMatchOriginal() {
        assertEquals(activitySwitchTrace(false), activitySwitchTrace(true));
    }

    private List<String> activitySwitchTrace(boolean optimize) {
        DeerFoliaConfiguration.lithiumOptimize.brainTasks = optimize;
        Brain<LivingEntity> brain = new Brain<>();
        List<String> trace = new ArrayList<>();
        ProbeControl switcher = new ProbeControl("switch", trace);
        switcher.start = () -> brain.setActiveActivityIfPossible(Activity.WORK);
        add(brain, Activity.CORE, 0, switcher);
        add(brain, Activity.CORE, 0, new ProbeControl("same-group", trace));
        add(brain, Activity.IDLE, 1, new ProbeControl("idle", trace));
        add(brain, Activity.WORK, 2, new ProbeControl("work", trace));
        brain.tick(mock(ServerLevel.class), null);
        assertFalse(trace.contains("start:idle"));
        assertTrue(trace.contains("start:same-group"));
        assertTrue(trace.contains("start:work"));
        return trace;
    }

    @Test
    void duplicateRegistrationAndStoppedDuringTickMatchOriginal() {
        assertEquals(duplicateTrace(false), duplicateTrace(true));
    }

    private List<String> duplicateTrace(boolean optimize) {
        DeerFoliaConfiguration.lithiumOptimize.brainTasks = optimize;
        Brain<LivingEntity> brain = new Brain<>();
        List<String> trace = new ArrayList<>();
        ProbeControl shared = new ProbeControl("shared", trace);
        shared.tick = () -> shared.status = Behavior.Status.STOPPED;
        add(brain, Activity.CORE, 0, shared);
        add(brain, Activity.IDLE, 1, shared);
        brain.tick(mock(ServerLevel.class), null);
        assertEquals(List.of("start:shared", "tick:shared", "tick:shared"), trace);
        return trace;
    }

    @Test
    void recursiveTickRetainsOuterSnapshot() {
        assertEquals(recursiveTrace(false), recursiveTrace(true));
    }

    private List<String> recursiveTrace(boolean optimize) {
        DeerFoliaConfiguration.lithiumOptimize.brainTasks = optimize;
        Brain<LivingEntity> brain = new Brain<>();
        ServerLevel level = mock(ServerLevel.class);
        List<String> trace = new ArrayList<>();
        ProbeControl first = new ProbeControl("first", trace);
        boolean[] entered = {false};
        first.tick = () -> {
            if (!entered[0]) {
                entered[0] = true;
                brain.tick(level, null);
            }
        };
        add(brain, Activity.CORE, 0, first);
        add(brain, Activity.CORE, 0, new ProbeControl("second", trace));
        brain.tick(level, null);
        assertEquals(List.of("start:first", "start:second", "tick:first", "tick:first", "tick:second", "tick:second"), trace);
        return trace;
    }

    @Test
    void externalStatusChangesTopologyAndReturnedListsRemainObservable() {
        Brain<LivingEntity> brain = new Brain<>();
        ProbeControl first = new ProbeControl("first", new ArrayList<>());
        add(brain, Activity.IDLE, 0, first);
        assertTrue(brain.getRunningBehaviors().isEmpty());
        first.status = Behavior.Status.RUNNING;
        List<BehaviorControl<? super LivingEntity>> snapshot = brain.getRunningBehaviors();
        assertEquals(List.of(first), snapshot);
        snapshot.clear();
        assertEquals(List.of(first), brain.getRunningBehaviors());
        ProbeControl second = new ProbeControl("second", new ArrayList<>());
        second.status = Behavior.Status.RUNNING;
        add(brain, Activity.IDLE, 1, second);
        assertEquals(List.of(first, second), brain.getRunningBehaviors());
        brain.removeAllBehaviors();
        assertTrue(brain.getRunningBehaviors().isEmpty());
    }

    @Test
    void liveActivitySetChangesInvalidatePossibleGroups() {
        Brain<LivingEntity> brain = new Brain<>();
        List<String> trace = new ArrayList<>();
        add(brain, Activity.IDLE, 0, new ProbeControl("idle", trace));
        add(brain, Activity.WORK, 1, new ProbeControl("work", trace));
        ServerLevel level = mock(ServerLevel.class);
        brain.tick(level, null);
        brain.getActiveActivities().clear();
        brain.getActiveActivities().add(Activity.WORK);
        trace.clear();
        brain.tick(level, null);
        assertTrue(trace.contains("start:work"));
    }

    @Test
    void emptyStopAllDoesNotReadWorldTimeAndExceptionsReleaseSnapshot() {
        Brain<LivingEntity> empty = new Brain<>();
        empty.stopAll(null, null);

        Brain<LivingEntity> brain = new Brain<>();
        List<String> trace = new ArrayList<>();
        ProbeControl first = new ProbeControl("first", trace);
        first.tick = () -> { throw new IllegalStateException("probe"); };
        add(brain, Activity.CORE, 0, first);
        add(brain, Activity.CORE, 0, new ProbeControl("second", trace));
        ServerLevel level = mock(ServerLevel.class);
        assertThrows(IllegalStateException.class, () -> brain.tick(level, null));
        first.tick = () -> {};
        trace.clear();
        brain.tick(level, null);
        assertEquals(List.of("tick:first", "tick:second"), trace);
    }

    private static void add(Brain<LivingEntity> brain, Activity activity, int priority, ProbeControl control) {
        brain.addActivity(activity, ImmutableList.of(Pair.of(priority, control)), Set.of(), Set.of());
    }

    private static final class TestBrain extends Brain<LivingEntity> {
        TestBrain(List<MemoryModuleType<?>> types) {
            super(types, List.of(), List.of(), MemoryMap.EMPTY, RandomSource.create(1L));
        }

    }

    private static final class MemoryProbe extends Behavior<LivingEntity> {
        MemoryProbe(Map<MemoryModuleType<?>, MemoryStatus> conditions) {
            super(conditions);
        }

        boolean required(LivingEntity body) {
            return this.hasRequiredMemories(body);
        }
    }

    private static final class ProbeControl implements BehaviorControl<LivingEntity> {
        private final String name;
        private final List<String> trace;
        Behavior.Status status = Behavior.Status.STOPPED;
        Runnable start = () -> {};
        Runnable tick = () -> {};

        ProbeControl(String name, List<String> trace) {
            this.name = name;
            this.trace = trace;
        }

        @Override
        public Behavior.Status getStatus() { return status; }

        @Override
        public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }

        @Override
        public boolean tryStart(ServerLevel level, LivingEntity body, long timestamp) {
            trace.add("start:" + name);
            status = Behavior.Status.RUNNING;
            start.run();
            return true;
        }

        @Override
        public void tickOrStop(ServerLevel level, LivingEntity body, long timestamp) {
            trace.add("tick:" + name);
            tick.run();
        }

        @Override
        public void doStop(ServerLevel level, LivingEntity body, long timestamp) { status = Behavior.Status.STOPPED; }

        @Override
        public String debugString() { return name; }
    }
}
