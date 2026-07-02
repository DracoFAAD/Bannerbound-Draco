package com.bannerbound.antiquity.poison;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;

/**
 * The five biome poisons. Each attacks a different game system (mobility / control / healing /
 * contagion / senses) rather than being a different number — see POISON_PLAN. A poison carries a
 * stable string {@code id} (serialized, never the ordinal, so adding poisons can't corrupt saves),
 * a {@code lethalAtMaxStage} flag (whether its final stage can actually kill), and an overridable
 * per-tick {@link #tick} signature effect.
 *
 * <p>Only {@link #WOLFSBANE} is implemented so far (the end-to-end proof). The other four are
 * declared with their flags and a no-op {@code tick} so the framework compiles, serializes and is
 * ready to flesh out one constant + one callback at a time.
 */
public enum PoisonType {
    /** Mountain. Paralytic: a constant movement slow plus periodic ~0.5s "root pulses" that pin the
     *  victim where it stands (stops fleeing prey). Lethal only at the final stage if never cured. */
    WOLFSBANE("wolfsbane", true, 4, false, 0xFF9A40D0) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.wolfsbaneTick(victim, stage, level);
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearParalysis(victim);
        }

        @Override
        public SoundEvent hitSound() {
            return BannerboundAntiquity.WOLFSBANE_HIT_CLIENT.get();
        }

        @Override
        public SoundEvent healSound() {
            return BannerboundAntiquity.WOLFSBANE_HEAL_CLIENT.get();
        }

        @Override
        public SoundEvent belchSound() {
            return BannerboundAntiquity.WOLFSBANE_BELCH.get();
        }
    },
    /** Jungle. A two-phase KIDNAP poison (control): a brief heavy-slow STUN (eyelids going heavy),
     *  then the victim PASSES OUT — fully immobilised, rendered lying down, for a fixed time (players
     *  15s / animals 30s) — during which it can be DRAGGED with a fiber rope. Non-lethal; re-darting
     *  just refreshes the timeline. Single stage; the two phases are driven by synced deadlines. */
    CURARE("curare", false, 1, true, 0xFF2E7D32) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.curareTick(victim, level);
        }

        @Override
        public boolean escalates() {
            return false; // single application with a fixed stun→unconscious→recover timeline
        }

        @Override
        public boolean dealsDamageOverTime() {
            return false; // pure control/kidnap — never chips HP
        }

        @Override
        public void onApplied(LivingEntity victim, boolean freshInfection) {
            if (freshInfection) {
                Poisons.startCurareClocks(victim); // first dose: stun → pass out
            } else {
                Poisons.refreshCurare(victim);     // re-dose: extend the passed-out state (don't wake them)
            }
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearCurare(victim);
        }
    },
    /** Desert. Attacks the HEALING system: blocks ALL regeneration the whole time, and runs a FIXED
     *  cardiac countdown from infection — when it expires the heart gives out (guaranteed-lethal),
     *  regardless of stage. The race is to the antidote (cinchona), not to survive chip damage. */
    OLEANDER("oleander", true, 1, true, 0xFFFF5ECF) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.oleanderTick(victim, stage, level);
        }

        @Override
        public boolean escalates() {
            return false; // single stage — the fixed cardiac clock is the threat, not stage escalation
        }

        @Override
        public void onApplied(LivingEntity victim, boolean freshInfection) {
            if (freshInfection) {
                Poisons.startOleanderClock(victim);       // first dose: arm the full cardiac clock
            } else {
                Poisons.accelerateOleanderClock(victim);  // each further dose drags the deadline closer
            }
        }

        @Override
        public int maxDose() {
            return 5; // no stages — extra coatings just speed up the clock (vs staged poisons capping at maxStage)
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearOleanderClock(victim);
        }
    },
    /** Swamp. Convulsions + contagion (town outbreaks). Not yet implemented. */
    WATER_HEMLOCK("water_hemlock", true, 4, true, 0xFFB5C334),
    /** Forest. Deliriant — hallucinations + visual warp + false sounds, plus a slow drain that can
     *  kill (lethal final stage). The signature sensory chaos is client-side (driven by the synced
     *  poison type); server-side the generic DoT/stage-5 timeout supplies the lethality. */
    BELLADONNA("belladonna", true, 4, true, 0xFF3A1556) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.belladonnaTick(victim, stage, level); // mobs act erratically so others can see the madness
        }

        @Override
        public SoundEvent hitSound() {
            return BannerboundAntiquity.BELLADONNA_HIT_CLIENT.get();
        }

        @Override
        public SoundEvent healSound() {
            return BannerboundAntiquity.BELLADONNA_HEAL_CLIENT.get();
        }
    };

    private final String id;
    private final boolean lethalAtMaxStage;
    private final int maxStage;
    private final boolean toxicToAnimals;
    private final int tintColor;

    PoisonType(String id, boolean lethalAtMaxStage, int maxStage, boolean toxicToAnimals, int tintColor) {
        this.id = id;
        this.lethalAtMaxStage = lethalAtMaxStage;
        this.maxStage = maxStage;
        this.toxicToAnimals = toxicToAnimals;
        this.tintColor = tintColor;
    }

    public String id() {
        return id;
    }

    /** ARGB tint for this poison's dart coating layer (the {@code dart_poison_layer} overlay). */
    public int tintColor() {
        return tintColor;
    }

    /** Highest escalation stage (1-based) this poison climbs to if untreated. */
    public int maxStage() {
        return maxStage;
    }

    /** Whether this poison climbs stages on a clock (the default). When false the stage clock never
     *  advances or times out — the poison sits at stage 1 until cured or until its own {@link #tick}
     *  ends it (oleander: a single stage, killed only by its fixed cardiac countdown). */
    public boolean escalates() {
        return true;
    }

    /** How many times this poison can be coated onto one food item. Staged poisons cap at their final
     *  stage (the dose = the opening stage); oleander allows more, since each extra dose just speeds up
     *  its cardiac clock rather than raising a (non-existent) stage. */
    public int maxDose() {
        return maxStage();
    }

    /** Whether the shared damage-over-time runs for this poison (the default). Pure control poisons
     *  (curare) return false so they never chip the victim's health. */
    public boolean dealsDamageOverTime() {
        return true;
    }

    /** Whether the toxin actually harms non-player creatures. Wolfsbane is a pure HUNTING paralytic:
     *  it only slows/roots animals so they can't flee — it deals them no damage and never kills them
     *  (the hunter makes the kill; an un-killed animal's dose simply wears off). */
    public boolean toxicToAnimals() {
        return toxicToAnimals;
    }

    /** Cue played ONLY to the afflicted player the moment the poison lands (a dart impact). Null = none. */
    public SoundEvent hitSound() {
        return null;
    }

    /** Cue played to the player when this poison is cured. Null = none. */
    public SoundEvent healSound() {
        return null;
    }

    /** Retch/vomit cue for the lethal final stage. Null = none. */
    public SoundEvent belchSound() {
        return null;
    }

    /** Whether the final stage's damage-over-time is allowed to drop the victim below the
     *  non-lethal health floor (i.e. actually kill). Below max stage every poison is non-lethal. */
    public boolean lethalAtMaxStage() {
        return lethalAtMaxStage;
    }

    /** Per-poison signature effect, run server-side every tick the victim is poisoned. Default no-op. */
    public void tick(LivingEntity victim, int stage, ServerLevel level) {
    }

    /** Hook fired the moment this poison is applied. {@code freshInfection} is true when the victim
     *  was NOT already suffering this poison (a brand-new stage-1 infection), false for a re-dose that
     *  only escalated the stage — lets a poison start a one-shot clock without resetting it on re-hits.
     *  Default no-op. */
    public void onApplied(LivingEntity victim, boolean freshInfection) {
    }

    /** Cleanup when this poison is cured or replaced (e.g. strip attribute modifiers). Default no-op. */
    public void onCleared(LivingEntity victim) {
    }

    /** The poison with this id, or {@code null} for {@code ""}/unknown (malformed save → not poisoned). */
    @Nullable
    public static PoisonType fromId(String id) {
        if (id != null && !id.isEmpty()) {
            for (PoisonType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String toId(@Nullable PoisonType t) {
        return t == null ? "" : t.id;
    }

    /** Serialized by string id, NOT ordinal — adding a poison never shifts existing saves.
     *  ({@link PoisonState} hand-writes the same id over the wire, so no StreamCodec is needed here.) */
    public static final Codec<PoisonType> CODEC = Codec.STRING.xmap(PoisonType::fromId, PoisonType::toId);
}
