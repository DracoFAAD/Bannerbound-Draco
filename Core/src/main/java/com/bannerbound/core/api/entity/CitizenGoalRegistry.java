package com.bannerbound.core.api.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Public extension point for adding an <b>auxiliary</b> (non-job) AI goal to every citizen from any
 * mod — Core or an expansion — without editing {@code CitizenEntity.registerGoals}. The sibling
 * {@link com.bannerbound.core.api.job.CitizenJobRegistry} wires whole jobs (work goals + anarchy +
 * research + icons); this one is for plain leisure/behaviour goals that need none of that — a citizen
 * wandering to a refreshment block, a seasonal idle, and so on.
 *
 * <p>As with the job registry, the factory is a {@code (citizen, speedModifier) -> Goal} lambda, so
 * Core never references the consumer's goal class — an expansion's goal lives entirely in the
 * expansion while still being attached to Core's {@link CitizenEntity}. Register during common setup;
 * registration is order-preserving and thread-safe.
 */
public final class CitizenGoalRegistry {
    /** One registered goal: the {@code goalSelector} priority and the factory that builds it. */
    public record Entry(int priority, BiFunction<CitizenEntity, Double, Goal> factory) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private CitizenGoalRegistry() {}

    /** Add an auxiliary goal at {@code priority} (same scale as Core's built-in goals: lower wins;
     *  work is 3, patrol/anarchy 4, idle look 5+). The factory is {@code (citizen, speed) -> Goal}. */
    public static synchronized void register(int priority, BiFunction<CitizenEntity, Double, Goal> factory) {
        if (factory != null) ENTRIES.add(new Entry(priority, factory));
    }

    /** An immutable snapshot of every registered auxiliary goal. */
    public static synchronized List<Entry> all() {
        return List.copyOf(ENTRIES);
    }
}
