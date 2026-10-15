package cn.lunadeer.mc.deerfolia.lithium;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

/** Per-Brain traversal index. Behavior status remains owned by each BehaviorControl. */
public final class BrainTaskIndex<E extends LivingEntity> {
    private final List<Group<E>> groups = new ArrayList<>();
    private final List<BehaviorControl<? super E>> allBehaviors = new ArrayList<>();
    private Set<Activity> indexedActivities = Set.of();
    private List<Group<E>> possibleGroups = List.of();

    public BrainTaskIndex(Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> priorities) {
        // Preserve the original map/set iteration order and duplicate registrations across groups.
        for (Map<Activity, Set<BehaviorControl<? super E>>> activities : priorities.values()) {
            for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : activities.entrySet()) {
                List<BehaviorControl<? super E>> behaviors = List.copyOf(entry.getValue());
                this.groups.add(new Group<>(this.groups.size(), entry.getKey(), behaviors));
                this.allBehaviors.addAll(behaviors);
            }
        }
    }

    private List<Group<E>> possibleGroups(Set<Activity> activeActivities) {
        // getActiveActivities exposes the live set, so a version changed only by Brain setters is insufficient.
        if (!this.indexedActivities.equals(activeActivities)) {
            this.indexedActivities = Set.copyOf(activeActivities);
            List<Group<E>> possible = new ArrayList<>();
            for (Group<E> group : this.groups) {
                if (activeActivities.contains(group.activity())) {
                    possible.add(group);
                }
            }
            this.possibleGroups = possible;
        }
        return this.possibleGroups;
    }

    public void startNonRunning(ServerLevel level, E body, long timestamp, Set<Activity> activeActivities) {
        List<Group<E>> possible = this.possibleGroups(activeActivities);
        int next = 0;
        while (next < possible.size()) {
            Group<E> group = possible.get(next++);
            List<BehaviorControl<? super E>> behaviors = group.behaviors();
            // Activity eligibility is tested once per group, exactly as in the nested original loop.
            for (int i = 0; i < behaviors.size(); ++i) {
                BehaviorControl<? super E> behavior = behaviors.get(i);
                if (behavior.getStatus() == Behavior.Status.STOPPED) {
                    behavior.tryStart(level, body, timestamp);
                }
            }
            List<Group<E>> updated = this.possibleGroups(activeActivities);
            if (updated != possible) {
                possible = updated;
                next = 0;
                // A callback may activate a later group. Never revisit a group already passed this tick.
                while (next < possible.size() && possible.get(next).ordinal() <= group.ordinal()) {
                    ++next;
                }
            }
        }
    }

    public void collectRunning(List<BehaviorControl<? super E>> destination) {
        // One flat status scan deliberately accommodates custom controls and out-of-band start/stop.
        // The caller owns the snapshot: callbacks cannot remove entries from the batch being ticked.
        for (int i = 0; i < this.allBehaviors.size(); ++i) {
            BehaviorControl<? super E> behavior = this.allBehaviors.get(i);
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                destination.add(behavior);
            }
        }
    }

    private record Group<E extends LivingEntity>(int ordinal, Activity activity, List<BehaviorControl<? super E>> behaviors) {
    }
}
