package cn.lunadeer.mc.deerfolia.lithium;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@VanillaFeature
class InsideBlockEffectsOptimizationTest {
    private DeerFoliaConfiguration.LithiumOptimize previous;

    @BeforeEach
    void saveConfiguration() {
        this.previous = DeerFoliaConfiguration.lithiumOptimize;
        DeerFoliaConfiguration.lithiumOptimize = new DeerFoliaConfiguration.LithiumOptimize();
    }

    @AfterEach
    void restoreConfiguration() {
        DeerFoliaConfiguration.lithiumOptimize = this.previous;
    }

    @Test
    void emptyCollectorDoesNotAllocateCollectionsAcrossStepsAndReuse() throws ReflectiveOperationException {
        InsideBlockEffectApplier.StepBasedCollector collector = collector(true);
        Entity entity = mock(Entity.class);
        for (int step = 0; step < 10_000; step++) {
            collector.advanceStep(step, BlockPos.ZERO);
            collector.applyAndClear(entity);
        }
        for (String name : List.of("effectsInStep", "beforeEffectsInStep", "afterEffectsInStep", "finalEffects")) {
            Field field = collector.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(collector), name);
        }
    }

    @Test
    void effectsRetainOrderLatestSourcePositionAndStepBoundaries() {
        List<String> expected = List.of("before-clear", "clear", "after-clear", "before-lava",
            "lava:4, 5, 6", "after-lava", "before-extinguish", "extinguish", "after-extinguish");
        assertEquals(expected, explicitTranscript(false));
        assertEquals(expected, explicitTranscript(true));
    }

    @Test
    void randomCallbacksAndRepeatedEffectsMatchDisabledPath() {
        assertEquals(randomTranscript(false), randomTranscript(true));
    }

    @Test
    void deathStopsCallbacksAndClearAllowsReuseWithTheSameStep() {
        for (boolean optimized : new boolean[] {false, true}) {
            var collector = collector(optimized);
            AtomicBoolean alive = new AtomicBoolean(true);
            List<String> transcript = new ArrayList<>();
            Entity entity = recordingEntity(transcript, alive);
            collector.advanceStep(0, BlockPos.ZERO);
            collector.runBefore(InsideBlockEffectType.CLEAR_FREEZE, ignored -> {
                transcript.add("death");
                alive.set(false);
            });
            collector.apply(InsideBlockEffectType.CLEAR_FREEZE);
            collector.runAfter(InsideBlockEffectType.CLEAR_FREEZE, ignored -> transcript.add("must-not-run"));
            collector.applyAndClear(entity);
            alive.set(true);
            collector.advanceStep(0, BlockPos.ZERO);
            collector.apply(InsideBlockEffectType.EXTINGUISH);
            collector.applyAndClear(entity);
            assertEquals(List.of("death", "extinguish"), transcript);
        }
    }

    private static InsideBlockEffectApplier.StepBasedCollector collector(boolean optimized) {
        DeerFoliaConfiguration.lithiumOptimize.blockEffects = optimized;
        return new InsideBlockEffectApplier.StepBasedCollector();
    }

    private static Entity recordingEntity(List<String> transcript, AtomicBoolean alive) {
        Entity entity = mock(Entity.class);
        when(entity.isAlive()).thenAnswer(ignored -> alive.get());
        doAnswer(ignored -> { transcript.add("clear"); return null; }).when(entity).clearFreeze();
        doAnswer(ignored -> { transcript.add("extinguish"); return null; }).when(entity).clearFire();
        doAnswer(invocation -> {
            transcript.add("lava:" + invocation.<BlockPos>getArgument(0).toShortString());
            return null;
        }).when(entity).lavaIgnite(any(BlockPos.class));
        return entity;
    }

    private static List<String> explicitTranscript(boolean optimized) {
        var collector = collector(optimized);
        List<String> transcript = new ArrayList<>();
        Entity entity = recordingEntity(transcript, new AtomicBoolean(true));
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(1, 2, 3);
        collector.advanceStep(0, position);
        collector.runBefore(InsideBlockEffectType.CLEAR_FREEZE, ignored -> transcript.add("before-clear"));
        collector.apply(InsideBlockEffectType.CLEAR_FREEZE);
        collector.apply(InsideBlockEffectType.CLEAR_FREEZE);
        collector.runAfter(InsideBlockEffectType.CLEAR_FREEZE, ignored -> transcript.add("after-clear"));
        collector.apply(InsideBlockEffectType.LAVA_IGNITE);
        position.set(4, 5, 6);
        collector.advanceStep(0, position);
        collector.apply(InsideBlockEffectType.LAVA_IGNITE);
        position.set(7, 8, 9); // RecordedEffect must own its immutable position.
        collector.runBefore(InsideBlockEffectType.LAVA_IGNITE, ignored -> transcript.add("before-lava"));
        collector.runAfter(InsideBlockEffectType.LAVA_IGNITE, ignored -> transcript.add("after-lava"));
        collector.advanceStep(1, position);
        collector.runBefore(InsideBlockEffectType.EXTINGUISH, ignored -> transcript.add("before-extinguish"));
        collector.apply(InsideBlockEffectType.EXTINGUISH);
        collector.runAfter(InsideBlockEffectType.EXTINGUISH, ignored -> transcript.add("after-extinguish"));
        collector.applyAndClear(entity);
        collector.applyAndClear(entity);
        return transcript;
    }

    private static List<String> randomTranscript(boolean optimized) {
        var collector = collector(optimized);
        List<String> transcript = new ArrayList<>();
        Entity entity = recordingEntity(transcript, new AtomicBoolean(true));
        Random random = new Random(0xDEEF01A);
        InsideBlockEffectType[] types = {
            InsideBlockEffectType.CLEAR_FREEZE, InsideBlockEffectType.LAVA_IGNITE, InsideBlockEffectType.EXTINGUISH
        };
        int step = 0;
        collector.advanceStep(step, BlockPos.ZERO);
        for (int i = 0; i < 5_000; i++) {
            InsideBlockEffectType type = types[random.nextInt(types.length)];
            String label = i + ":" + type;
            switch (random.nextInt(6)) {
                case 0 -> collector.runBefore(type, ignored -> transcript.add("before:" + label));
                case 1 -> collector.runAfter(type, ignored -> transcript.add("after:" + label));
                case 2, 3 -> collector.apply(type);
                case 4 -> collector.advanceStep(++step, new BlockPos(i, -i, i));
                case 5 -> {
                    collector.applyAndClear(entity);
                    collector.advanceStep(step, new BlockPos(i, -i, i));
                }
            }
        }
        collector.applyAndClear(entity);
        return transcript;
    }
}
