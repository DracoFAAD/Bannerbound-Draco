package com.bannerbound.core.api.job;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Public extension point for adding a citizen <b>job</b> from any mod — Core or an expansion —
 * without editing Core's hardcoded job sites. A single {@link #register(JobDef)} call wires the
 * whole job: its work-goal AI, anarchy participation/order, research-unlock unit, and Job-tab icon.
 *
 * <p>The five places Core used to hardcode each job ({@code CitizenEntity.registerGoals},
 * {@link com.bannerbound.core.entity.AnarchyJobs}, {@link com.bannerbound.core.api.settlement.WorkstationUnlocks},
 * {@link com.bannerbound.core.social.JobIcons}, and {@code ServerPayloadHandler}'s job list) now
 * consult this registry as {@code built-ins + registry}, so a new job needs no Core change.
 *
 * <p>Crucially, {@link JobDef#goalFactory()} is a {@code (citizen, speed) -> Goal} lambda, so Core
 * never references the consumer's goal class — an expansion's goal can live entirely in the
 * expansion while still being attached to Core's {@link CitizenEntity}. The Antiquity spear fisher
 * is the first consumer; see {@code SpearFisherWorkGoal}.
 *
 * <p>Register during common setup (e.g. {@code FMLCommonSetupEvent.enqueueWork}); registration is
 * idempotent (a duplicate job id is ignored) and thread-safe.
 */
public final class CitizenJobRegistry {
    private static final List<JobDef> DEFS = new ArrayList<>();

    private CitizenJobRegistry() {
    }

    /** Adds a job. No-op if a job with the same {@link JobDef#jobTypeId()} is already registered. */
    public static synchronized void register(JobDef def) {
        if (def == null || def.jobTypeId() == null) return;
        if (byId(def.jobTypeId()) != null) return;   // idempotent — survive a double commonSetup
        DEFS.add(def);
    }

    /** An immutable snapshot of every registered job. */
    public static synchronized List<JobDef> all() {
        return List.copyOf(DEFS);
    }

    /** The job registered under {@code jobTypeId}, or {@code null}. */
    @Nullable
    public static synchronized JobDef byId(@Nullable String jobTypeId) {
        if (jobTypeId == null) return null;
        for (JobDef d : DEFS) {
            if (d.jobTypeId().equals(jobTypeId)) return d;
        }
        return null;
    }

    /** The research-unlock unit name for a registered job ({@code "spear_fisher"}), or {@code null}. */
    @Nullable
    public static String unitFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d == null ? null : d.unitName();
    }

    /** The icon tool-role for a registered job ({@code "spear"}), or {@code null}. */
    @Nullable
    public static String iconRoleFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d == null ? null : d.iconRole();
    }

    /** True when a registered job binds to a Workshop instead of a normal drop-off/work area. */
    public static boolean isWorkshopBound(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d != null && d.workshopBound();
    }

    /** The station/workshop type a workshop-bound job is restricted to, or {@code null} for any. */
    @Nullable
    public static String workshopTypeFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d != null && d.workshopBound() ? d.workshopTypeId() : null;
    }

    /** First registered workshop-bound job that specifically works {@code workshopTypeId}. */
    @Nullable
    public static String workshopJobForType(String workshopTypeId) {
        if (workshopTypeId == null) return null;
        for (JobDef d : all()) {
            if (d.workshopBound() && workshopTypeId.equals(d.workshopTypeId())) {
                return d.jobTypeId();
            }
        }
        return null;
    }

    /** The Core baseline icon item declared by some registered job for {@code role}, or {@code null}
     *  when no registered job uses that role. Backs {@code JobIcons.defaultFor} for registry roles. */
    @Nullable
    public static Item baselineForRole(String role) {
        if (role == null) return null;
        for (JobDef d : all()) {
            if (role.equals(d.iconRole())) return d.iconBaseline();
        }
        return null;
    }

    /**
     * Everything Core needs to wire one job. Build with {@link #builder(String)}.
     *
     * @param jobTypeId      stable job id (e.g. {@code "spear_fishers_post"}); also the citizen's job tag
     * @param gatherer       participates in anarchy self-employment / auto-assignment
     * @param anarchyOrder   ordering among gatherers (lower = earlier); ignored when {@code !gatherer}
     * @param unitName       research-unlock unit → flag {@code bannerbound.unlock.<unitName>}; nullable = ungated
     * @param iconRole       JobIcons tool-role for the bubble/glyph (e.g. {@code "spear"}); nullable
     * @param iconBaseline   {@code minecraft:} fallback item for the role (the real item comes from tool_ages)
     * @param toolRequired   whether non-anarchy readiness needs a job tool (false = forager-style tool-free)
     * @param obsoletedByUnit when the settlement researches {@code bannerbound.unlock.<obsoletedByUnit>}, this
     *                        job is retired: hidden from the Job tab and its holders migrate to that unit's
     *                        job. Nullable = never obsoleted. (Spear fisher → fisher when the rod unlocks.)
     * @param goalFactory    builds the work goal: {@code (citizen, speedModifier) -> Goal}
     */
    public record JobDef(
        String jobTypeId,
        boolean gatherer,
        int anarchyOrder,
        @Nullable String unitName,
        @Nullable String iconRole,
        Item iconBaseline,
        boolean toolRequired,
        boolean workshopBound,
        @Nullable String workshopTypeId,
        boolean jobPickerVisible,
        @Nullable String obsoletedByUnit,
        BiFunction<CitizenEntity, Double, Goal> goalFactory) {

        public static Builder builder(String jobTypeId) {
            return new Builder(jobTypeId);
        }

        public static final class Builder {
            private final String jobTypeId;
            private boolean gatherer = true;
            private int anarchyOrder = 100;
            private String unitName;
            private String iconRole;
            private Item iconBaseline = Items.AIR;
            private boolean toolRequired = true;
            private boolean workshopBound;
            private String workshopTypeId;
            private boolean jobPickerVisible = true;
            private String obsoletedByUnit;
            private BiFunction<CitizenEntity, Double, Goal> goalFactory;

            private Builder(String jobTypeId) {
                this.jobTypeId = jobTypeId;
            }

            public Builder gatherer(boolean v) { this.gatherer = v; return this; }
            public Builder anarchyOrder(int v) { this.anarchyOrder = v; return this; }
            public Builder unit(String v) { this.unitName = v; return this; }
            public Builder icon(String role, Item baseline) { this.iconRole = role; this.iconBaseline = baseline; return this; }
            public Builder toolRequired(boolean v) { this.toolRequired = v; return this; }
            public Builder workshopBound(@Nullable String typeId) {
                this.workshopBound = true;
                this.workshopTypeId = typeId;
                return this;
            }
            public Builder jobPickerVisible(boolean v) { this.jobPickerVisible = v; return this; }
            public Builder obsoletedBy(String unit) { this.obsoletedByUnit = unit; return this; }
            public Builder goal(BiFunction<CitizenEntity, Double, Goal> f) { this.goalFactory = f; return this; }

            public JobDef build() {
                return new JobDef(jobTypeId, gatherer, anarchyOrder, unitName, iconRole,
                    iconBaseline, toolRequired, workshopBound, workshopTypeId, jobPickerVisible,
                    obsoletedByUnit, goalFactory);
            }
        }
    }
}
