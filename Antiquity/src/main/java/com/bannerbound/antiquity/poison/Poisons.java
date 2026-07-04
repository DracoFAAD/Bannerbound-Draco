package com.bannerbound.antiquity.poison;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The poison "spine" — the {@link HuntingFear}-style helper every poison reads and writes. Backed by
 * the synced {@code POISON_STATE} attachment. The shared lifecycle ({@link #applyPoison}, escalation,
 * damage-over-time, {@link #cure}) lives here; each poison's signature behaviour is the per-constant
 * {@link PoisonType#tick}. Ticked for every poisoned {@link LivingEntity} by {@code PoisonEvents}.
 */
public final class Poisons {
    private Poisons() {}

    /** Shared persistent-data int Core's {@code CitizenEntity} reads to mirror poison into its
     *  thought / stamina / name-tag glyph — Core can't import this Antiquity attachment, so we stamp
     *  the same bridge {@link com.bannerbound.antiquity.entity.HuntingFear#DOMESTICATED_TAG} uses.
     *  {@code 0} = not poisoned; otherwise the current 1-based stage. */
    public static final String POISON_STAGE_TAG = "BannerboundPoisonStage";

    // Wolfsbane paralytic root-pulse cadence (ticks). The freeze window stays ~0.5s; the gap between
    // pulses tightens as the poison deepens, so it locks the victim down more and more.
    private static final int PULSE_LENGTH = 10;       // ~0.5s pinned
    private static final int PULSE_BASE_PERIOD = 60;  // stage 1: a pulse every ~3s
    private static final int PULSE_PERIOD_STEP = 10;  // each stage shortens the gap
    private static final int PULSE_MIN_PERIOD = 30;   // floor (max stage: every ~1.5s)
    /** A leg-buckle window for the unpredictable jump-lock — re-rolled each window per victim. */
    private static final int JUMP_WINDOW_TICKS = 30;

    private static final ResourceLocation PARALYSIS_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "poison_paralysis_speed");
    private static final ResourceLocation PARALYSIS_JUMP_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "poison_paralysis_jump");

    // ── Query ────────────────────────────────────────────────────────────────────────────────
    public static boolean isPoisoned(LivingEntity e) {
        return e.getData(BannerboundAntiquity.POISON_STATE.get()).active();
    }

    public static PoisonState getPoison(LivingEntity e) {
        return e.getData(BannerboundAntiquity.POISON_STATE.get());
    }

    // ── Apply / cure ───────────────────────────────────────────────────────────────────────────
    /** Inflict (or refresh) {@code type} on {@code victim}. Re-applying the same poison keeps the
     *  current stage and restarts that stage's clock; a different poison takes over at stage 1. */
    public static void applyPoison(LivingEntity victim, PoisonType type) {
        applyPoison(victim, type, null);
    }

    /** As {@link #applyPoison(LivingEntity, PoisonType)}, additionally recording WHO administered the
     *  dose (dart/arrow owner) so the eventual poison death still credits their settlement with the
     *  kill — mirrors {@code HuntingFear.applyBleed}'s owner overload. Null = unattributed. */
    public static void applyPoison(LivingEntity victim, PoisonType type, @Nullable Entity causedBy) {
        if (!Config.POISON_ENABLED.get() || type == null) {
            return;
        }
        if (type == PoisonType.CURARE && isCurareImmune(victim)) {
            return; // the arnica antidote left them temporarily resistant — the dose doesn't take
        }
        PoisonState cur = getPoison(victim);
        if (cur.type() != null && cur.type() != type) {
            cur.type().onCleared(victim); // switching poisons — let the old one tear down its effects
        }
        // A second dart of the SAME poison escalates a stage IMMEDIATELY (stacking darts); a fresh
        // poison starts at stage 1. Either way the new stage's clock restarts.
        boolean freshInfection = !(cur.active() && cur.type() == type);
        int stage = freshInfection ? 1 : Math.min(type.maxStage(), cur.stage() + 1);
        // A non-escalating poison (oleander) never times its stage out — death is its own tick's job.
        long stageEndsAt = type.escalates()
            ? victim.level().getGameTime() + Config.POISON_STAGE_ADVANCE_TICKS.get()
            : Long.MAX_VALUE;
        setState(victim, new PoisonState(type, stage, stageEndsAt));
        setPoisonedBy(victim, causedBy);
        type.onApplied(victim, freshInfection); // e.g. oleander arms its fixed cardiac clock
        // Impact cue, heard only by the afflicted player.
        if (victim instanceof ServerPlayer player && type.hitSound() != null) {
            player.playNotifySound(type.hitSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /** Inflict {@code type} starting at an explicit {@code startStage} (clamped 1..maxStage) — used by
     *  poisoned food, where the dose (number of coatings) sets the opening stage. Treated as a fresh
     *  infection so any per-poison clock (oleander/curare) arms from now. */
    public static void applyPoisonAtStage(LivingEntity victim, PoisonType type, int startStage) {
        applyPoisonAtStage(victim, type, startStage, null);
    }

    /** As {@link #applyPoisonAtStage(LivingEntity, PoisonType, int)} with the administering entity
     *  recorded for kill credit (see {@link #applyPoison(LivingEntity, PoisonType, Entity)}). */
    public static void applyPoisonAtStage(LivingEntity victim, PoisonType type, int startStage,
            @Nullable Entity causedBy) {
        if (!Config.POISON_ENABLED.get() || type == null) {
            return;
        }
        if (type == PoisonType.CURARE && isCurareImmune(victim)) {
            return;
        }
        PoisonState cur = getPoison(victim);
        if (cur.type() != null && cur.type() != type) {
            cur.type().onCleared(victim);
        }
        boolean freshInfection = !(cur.active() && cur.type() == type);
        int stage = Math.max(1, Math.min(type.maxStage(), startStage));
        long stageEndsAt = type.escalates()
            ? victim.level().getGameTime() + Config.POISON_STAGE_ADVANCE_TICKS.get()
            : Long.MAX_VALUE;
        setState(victim, new PoisonState(type, stage, stageEndsAt));
        setPoisonedBy(victim, causedBy);
        type.onApplied(victim, freshInfection);
        // Extra food doses beyond the first: a no-op for staged poisons (their stage was already set),
        // but oleander treats each as a re-dose that speeds up its cardiac clock.
        for (int i = 1; i < startStage; i++) {
            type.onApplied(victim, false);
        }
        if (victim instanceof ServerPlayer player && type.hitSound() != null) {
            player.playNotifySound(type.hitSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /** Cure ONLY if the active poison is {@code onlyType} — the cross-biome cure cycle, where each
     *  remedy treats exactly one poison (yarrow→wolfsbane, marshmallow→belladonna, …). A no-op if the
     *  victim isn't suffering that specific poison. */
    public static void cure(LivingEntity victim, PoisonType onlyType) {
        if (getPoison(victim).type() == onlyType) {
            cure(victim);
            // Curing curare specifically (an antidote, not a natural wake-up) grants brief resistance so
            // a kidnapper can't instantly re-dart the freed victim back under.
            if (onlyType == PoisonType.CURARE && Config.POISON_CURARE_IMMUNE_TICKS.get() > 0) {
                victim.setData(BannerboundAntiquity.POISON_CURARE_IMMUNE_UNTIL.get(),
                    victim.level().getGameTime() + Config.POISON_CURARE_IMMUNE_TICKS.get());
            }
        }
    }

    /** Whether the arnica antidote left {@code victim} still resisting new curare doses. */
    private static boolean isCurareImmune(LivingEntity victim) {
        return victim.level().getGameTime() < victim.getData(BannerboundAntiquity.POISON_CURARE_IMMUNE_UNTIL.get());
    }

    /** Clear all poison from {@code victim} (milk, debug, death-cleanup). */
    public static void cure(LivingEntity victim) {
        PoisonState cur = getPoison(victim);
        if (cur.type() != null) {
            cur.type().onCleared(victim);
            if (victim instanceof ServerPlayer player && cur.type().healSound() != null) {
                player.playNotifySound(cur.type().healSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }
        setState(victim, PoisonState.NONE);
        victim.removeData(BannerboundAntiquity.POISON_BY.get());
    }

    /** Record (or clear, when null) who administered the active dose. A null causedBy CLEARS any
     *  previous inflictor rather than leaving it — an unattributed re-dose must not keep crediting
     *  the old attacker. */
    private static void setPoisonedBy(LivingEntity victim, @Nullable Entity causedBy) {
        if (causedBy != null) {
            victim.setData(BannerboundAntiquity.POISON_BY.get(), causedBy.getStringUUID());
        } else {
            victim.removeData(BannerboundAntiquity.POISON_BY.get());
        }
    }

    /** The poison damage source, attributed to whoever administered the dose (if they're still
     *  loaded) — so DropGatingEvents' kill-credit resolution sees the hunter, not a wild death.
     *  Also selects the "%1$s succumbed to poison whilst fighting %2$s" death line. */
    private static DamageSource poisonDamage(LivingEntity victim) {
        Entity owner = null;
        if (victim.level() instanceof ServerLevel sl) {
            String by = victim.getData(BannerboundAntiquity.POISON_BY.get());
            if (!by.isEmpty()) {
                try {
                    owner = sl.getEntity(UUID.fromString(by));
                } catch (IllegalArgumentException ignored) {
                    // malformed attachment (hand-edited NBT) — treat as unattributed
                }
            }
        }
        return victim.damageSources().source(BannerboundAntiquity.POISON_DAMAGE, owner);
    }

    private static void setState(LivingEntity victim, PoisonState state) {
        victim.setData(BannerboundAntiquity.POISON_STATE.get(), state);
        // Bridge for Core (citizens): mirror the stage into shared persistent data. 0 clears it.
        victim.getPersistentData().putInt(POISON_STAGE_TAG, state.active() ? state.stage() : 0);
    }

    // ── Per-tick lifecycle (called only for already-poisoned entities) ──────────────────────────
    public static void tickPoison(LivingEntity victim, ServerLevel level) {
        PoisonState s = getPoison(victim);
        if (!s.active()) {
            return;
        }
        PoisonType type = s.type();
        boolean isPlayer = victim instanceof Player;
        long now = level.getGameTime();
        int stage = s.stage();
        // Escalate when this stage's deadline passes. Past the final stage the deadline becomes the
        // moment of death — "stage 5": the body finally gives out. This is what kills a player
        // (their DoT is negligible); they die by running out the final stage, not by chip damage.
        if (now >= s.stageEndsAt()) {
            if (stage < type.maxStage()) {
                stage++;
                setState(victim, new PoisonState(type, stage, now + Config.POISON_STAGE_ADVANCE_TICKS.get()));
            } else if (type.lethalAtMaxStage() && (isPlayer || type.toxicToAnimals())) {
                victim.hurt(poisonDamage(victim),
                    victim.getMaxHealth() * 10.0F + 1000.0F); // guaranteed lethal, "succumbed to poison"
                return;
            } else {
                // Non-lethal here (e.g. wolfsbane on an animal): the dose simply wears off — it only
                // ever held the creature still for the hunter, it never poisoned it to death.
                cure(victim);
                return;
            }
        }
        // Damage-over-time on the interval, clamped non-lethal below the final stage (pure control
        // poisons like curare skip it entirely).
        if (type.dealsDamageOverTime() && now % Config.POISON_DOT_INTERVAL_TICKS.get() == 0) {
            applyDot(victim, type, stage, isPlayer);
        }
        // Signature effect (wolfsbane root-pulse, etc).
        type.tick(victim, stage, level);
        // Ambient poison haze in the poison's colour — so OTHERS (not just the victim) can see at a
        // glance that this creature is poisoned and with what.
        if (now % 10 == 0) {
            emitPoisonHaze(victim, type, level);
        }
    }

    /** A small puff of the poison's tint colour around the victim, broadcast to all nearby clients. */
    private static void emitPoisonHaze(LivingEntity victim, PoisonType type, ServerLevel level) {
        int c = type.tintColor();
        level.sendParticles(
            ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
                ((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F),
            victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
            2, victim.getBbWidth() * 0.4, victim.getBbHeight() * 0.35, victim.getBbWidth() * 0.4, 0.0);
    }

    private static void applyDot(LivingEntity victim, PoisonType type, int stage, boolean isPlayer) {
        // A pure hunting paralytic (wolfsbane) deals animals NO poison damage — it just immobilizes.
        if (!isPlayer && !type.toxicToAnimals()) {
            return;
        }
        float dmg = (float) (double) Config.POISON_DOT_PER_STAGE.get() * stage;
        if (isPlayer) {
            dmg *= (float) (double) Config.POISON_PLAYER_DOT_MULT.get(); // players barely take chip damage
        }
        // Players are NEVER killed by the DoT — only by the final-stage timeout above. Animals can be
        // finished by the DoT at the lethal final stage.
        boolean lethal = type.lethalAtMaxStage() && stage >= type.maxStage() && !isPlayer;
        if (!lethal) {
            float floor = (float) (double) Config.POISON_NONLETHAL_FLOOR.get();
            dmg = Math.min(dmg, Math.max(0.0F, victim.getHealth() - floor));
        }
        if (dmg <= 0.0F) {
            return;
        }
        victim.hurt(poisonDamage(victim), dmg);
    }

    // ── Oleander signature: anti-heal + a fixed cardiac countdown ───────────────────────────────
    /** Whether {@code victim} is currently suffering oleander — drives the {@code LivingHealEvent}
     *  cancel in {@code PoisonEvents} that blocks ALL healing for oleander's duration. */
    public static boolean blocksHealing(LivingEntity victim) {
        return getPoison(victim).type() == PoisonType.OLEANDER;
    }

    /** Arm the fixed heart-attack clock at infection (a one-shot; re-doses don't reset it). Stored in
     *  the SYNCED {@code POISON_CARDIAC_AT} attachment so the client can drive the countdown visuals. */
    static void startOleanderClock(LivingEntity victim) {
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(),
            victim.level().getGameTime() + Config.POISON_OLEANDER_CLOCK_TICKS.get());
        victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(), 0L); // reschedule coughs fresh
    }

    static void clearOleanderClock(LivingEntity victim) {
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(), 0L);
    }

    private static final long OLEANDER_MIN_TICKS = 300L;     // a re-dosed clock can't drop below 15s
    private static final double OLEANDER_DOSE_KEEP = 0.6;    // each extra dose cuts the REMAINING time by 40%

    /** A second-and-onward oleander dose (more darts/arrows, or a higher-dosed food) doesn't reset the
     *  clock — it drags the heart-attack deadline CLOSER, flooring at {@link #OLEANDER_MIN_TICKS}. */
    static void accelerateOleanderClock(LivingEntity victim) {
        long now = victim.level().getGameTime();
        long deadline = victim.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            startOleanderClock(victim); // no clock yet — arm the full one
            return;
        }
        long remaining = Math.max(0L, deadline - now);
        long shortened = Math.max(OLEANDER_MIN_TICKS, (long) (remaining * OLEANDER_DOSE_KEEP));
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(), now + shortened);
    }

    /** Oleander does no special per-tick movement effect — its threat is the anti-heal (handled by the
     *  heal-event cancel) plus the fixed clock checked here: when the deadline passes, the heart gives
     *  out and the victim dies outright, regardless of stage. Stage only deepens the (un-healable) DoT. */
    static void oleanderTick(LivingEntity victim, int stage, ServerLevel level) {
        long now = level.getGameTime();
        long deadline = victim.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            startOleanderClock(victim); // safety: clock missing (poison predates this code) — arm it now
            return;
        }
        if (now >= deadline) {
            clearOleanderClock(victim);
            // Cardiac arrest: guaranteed lethal (players and animals — oleander is toxicToAnimals).
            victim.hurt(poisonDamage(victim),
                victim.getMaxHealth() * 10.0F + 1000.0F);
            return;
        }
        // Blood coughs (same retch + blood as wolfsbane) that ALSO cost 2 hearts each — with the anti-heal
        // they accumulate. Cadence: every 40–60s early, tightening toward 15s as the heart nears arrest.
        long nextCough = victim.getData(BannerboundAntiquity.POISON_NEXT_VOMIT.get());
        if (now >= nextCough) {
            if (nextCough > 0L) {
                vomit(victim, level, PoisonType.WOLFSBANE.belchSound());
                victim.hurt(poisonDamage(victim), 3.0F);
            }
            double clock = Math.max(1.0, Config.POISON_OLEANDER_CLOCK_TICKS.get());
            double f = Math.max(0.0, Math.min(1.0, 1.0 - (deadline - now) / clock)); // 0 at infection → 1 at arrest
            long slow = 800L + victim.getRandom().nextInt(401);                       // 40–60s
            long interval = Math.round(slow + (300.0 - slow) * f);                    // → 15s near the end
            victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(), now + interval);
        }
    }

    // ── Curare signature: two-phase kidnap (stun → unconscious) ─────────────────────────────────
    /** True while {@code victim} is in curare's UNCONSCIOUS phase (passed out: fully immobilised,
     *  rendered prone, draggable). Usable on both sides (synced deadlines). */
    public static boolean isCurareUnconscious(LivingEntity victim, long now) {
        if (getPoison(victim).type() != PoisonType.CURARE) {
            return false;
        }
        long faintAt = victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        long wakeAt = victim.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        return faintAt > 0L && now >= faintAt && now < wakeAt;
    }

    /** Arm (or refresh) curare's stun→unconscious→recover timeline from now. Re-darting RESETS it
     *  (the opposite of oleander's one-shot clock). Players go under for less time than animals. */
    static void startCurareClocks(LivingEntity victim) {
        long faintAt = victim.level().getGameTime() + Config.POISON_CURARE_STUN_TICKS.get();
        victim.setData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get(), faintAt);
        victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(), faintAt + curareOutTicks(victim));
    }

    /** Re-doses extend unconsciousness, but never beyond this many times the base out-duration from
     *  the faint — caps CONTINUOUS captivity (players: 3×300t = 45s; animals/citizens: 3×600t = 90s at
     *  defaults). Past the cap the victim wakes (fully cured), so holding someone under forever takes
     *  letting them wake and landing a fresh stun-phase dart each cycle, not a dart-spam lock. */
    private static final int CURARE_MAX_OUT_MULT = 3;

    /** A re-dose: if the victim has ALREADY passed out, keep them under and just push the wake time back
     *  (extend the unconsciousness, capped at {@link #CURARE_MAX_OUT_MULT}× the base duration since the
     *  faint) — resetting to the stun phase would briefly WAKE them. If they're still in the stun phase
     *  (or somehow have no clock), restart the timeline from scratch. */
    static void refreshCurare(LivingEntity victim) {
        long now = victim.level().getGameTime();
        long faintAt = victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        if (faintAt > 0L && now >= faintAt) {
            long cap = faintAt + (long) CURARE_MAX_OUT_MULT * curareOutTicks(victim);
            victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(),
                Math.min(now + curareOutTicks(victim), cap));
        } else {
            startCurareClocks(victim);
        }
    }

    private static int curareOutTicks(LivingEntity victim) {
        return (victim instanceof Player)
            ? Config.POISON_CURARE_PLAYER_OUT_TICKS.get()
            : Config.POISON_CURARE_ANIMAL_OUT_TICKS.get();
    }

    /** Cure-cleanup for curare: strip the immobilising modifiers, clear the deadlines, and drop any
     *  kidnap drag (release both ends of the rope link). */
    static void clearCurare(LivingEntity victim) {
        clearParalysis(victim);
        victim.setData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get(), 0L);
        victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(), 0L);
        int dragger = victim.getData(BannerboundAntiquity.DRAGGED_BY.get());
        if (dragger != 0) {
            victim.setData(BannerboundAntiquity.DRAGGED_BY.get(), 0);
            if (victim.level().getEntity(dragger) instanceof LivingEntity d) {
                d.setData(BannerboundAntiquity.DRAGGING.get(), 0);
            }
        }
    }

    /** Phase 1 STUN = heavy slow + sprint/jump lock (the victim staggers, eyelids heavy). Phase 2
     *  UNCONSCIOUS = fully rooted (speed -1, jump -1, zero horizontal velocity each tick) UNLESS being
     *  dragged, in which case the drag controls movement. Ends (non-lethal) at the wake deadline. */
    static void curareTick(LivingEntity victim, ServerLevel level) {
        long now = level.getGameTime();
        long wakeAt = victim.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        if (wakeAt <= 0L) {
            startCurareClocks(victim); // safety: clocks missing (poison predates this code) — arm now
            return;
        }
        if (now >= wakeAt) {
            cure(victim); // wakes up — onCleared() clears clocks, modifiers and any drag
            return;
        }
        boolean unconscious = now >= victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        setParalysisSlow(victim, unconscious ? -1.0 : -0.8);
        applyJumpLock(victim);
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
        }
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop(); // drop any path (stun stagger or full faint)
        }
        if (unconscious && victim.getData(BannerboundAntiquity.DRAGGED_BY.get()) == 0) {
            // Pin in place — but only when NOT being towed (the drag owns the velocity then).
            Vec3 v = victim.getDeltaMovement();
            victim.setDeltaMovement(0.0, v.y, 0.0);
            victim.hurtMarked = true;
        }
    }

    // ── Belladonna signature: deliriant madness (mob erratic behaviour) ─────────────────────────
    /** So the madness is VISIBLE to others, a belladonna-poisoned mob (citizen/animal) acts erratically
     *  — bolting to random spots and flailing/striking at nothing (or, rarely, a random bystander). A
     *  poisoned PLAYER instead gets the client-side hallucinations, so this is a no-op for players. */
    static void belladonnaTick(LivingEntity victim, int stage, ServerLevel level) {
        if (!(victim instanceof PathfinderMob mob)) {
            return;
        }
        var rng = mob.getRandom();
        if (rng.nextInt(Math.max(10, 50 - stage * 9)) == 0) {
            double a = rng.nextDouble() * Math.PI * 2.0;
            double d = 3.0 + rng.nextDouble() * 5.0;
            mob.getNavigation().moveTo(mob.getX() + Math.cos(a) * d, mob.getY(), mob.getZ() + Math.sin(a) * d, 1.4);
        }
        if (rng.nextInt(Math.max(8, 40 - stage * 8)) == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            if (rng.nextInt(3) == 0) {
                List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                    mob.getBoundingBox().inflate(2.0), e -> e != mob && e.isAlive());
                if (!near.isEmpty()) {
                    near.get(rng.nextInt(near.size())).hurt(mob.damageSources().mobAttack(mob), 1.0F);
                }
            }
        }
    }

    // ── Wolfsbane signature: creeping paralytic ─────────────────────────────────────────────────
    static void wolfsbaneTick(LivingEntity victim, int stage, ServerLevel level) {
        applyParalysisSlow(victim, stage);
        // Root pulse: a short full-stop window every `period` ticks; the gap tightens with stage.
        int period = Math.max(PULSE_MIN_PERIOD, PULSE_BASE_PERIOD - (stage - 1) * PULSE_PERIOD_STEP);
        if (level.getGameTime() % period < PULSE_LENGTH) {
            rootPulse(victim);
        }
        // A visible stumble — every so often the legs buckle and the victim lurches off-balance, so it
        // clearly reads as "trying to walk and failing" to anyone watching (not just a slow).
        if (victim.onGround() && victim.getRandom().nextInt(Math.max(15, 45 - stage * 8)) == 0) {
            double a = victim.getRandom().nextDouble() * Math.PI * 2.0;
            victim.setDeltaMovement(Math.cos(a) * 0.18, victim.getDeltaMovement().y, Math.sin(a) * 0.18);
            victim.hurtMarked = true;
        }
        // Players can't be path-stopped — kill their sprint. The jump only buckles from stage 2 on,
        // and even then only in unpredictable windows (sometimes you can leap, sometimes your legs
        // give out) — the uncertainty is scarier than a flat lock.
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
            if (jumpBucklesNow(victim, stage, level.getGameTime())) {
                applyJumpLock(victim);
            } else {
                removeJumpLock(victim);
            }
        }
        // Final stage: the body is failing — an occasional blood-vomit + retch (every 1200–2400t),
        // scheduled per-victim so it isn't a metronome.
        if (stage >= PoisonType.WOLFSBANE.maxStage()) {
            long now = level.getGameTime();
            long next = victim.getData(BannerboundAntiquity.POISON_NEXT_VOMIT.get());
            if (now >= next) {
                if (next > 0L) {
                    vomit(victim, level, PoisonType.WOLFSBANE.belchSound());
                }
                victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(),
                    now + 1200L + victim.getRandom().nextInt(1201)); // 1200–2400 ticks
            }
        }
    }

    /** Unpredictable leg-buckle: stage 2+, a per-victim coin-flip per {@link #JUMP_WINDOW_TICKS}
     *  window whose odds climb with the stage. Deterministic within a window (so it doesn't flicker
     *  every tick) but unpredictable across windows and desynced between victims. */
    private static boolean jumpBucklesNow(LivingEntity victim, int stage, long gameTime) {
        if (stage < 2) {
            return false;
        }
        long window = gameTime / JUMP_WINDOW_TICKS;
        long h = window * 0x9E3779B97F4A7C15L ^ ((long) victim.getId() * 0x2545F4914F6CDD1DL);
        h ^= (h >>> 29);
        int roll = (int) Math.floorMod(h, 100);
        int chance = stage == 2 ? 35 : stage == 3 ? 55 : 75; // % of windows the legs give out
        return roll < chance;
    }

    /** A burst of blood from the mouth (front of the head) + a retch — the "you're really dying" cue.
     *  Visible on poisoned citizens/animals too, not just the player. */
    private static void vomit(LivingEntity victim, ServerLevel level, SoundEvent belch) {
        Vec3 look = victim.getLookAngle();
        Vec3 mouth = victim.getEyePosition().add(look.scale(0.3)).subtract(0.0, 0.15, 0.0);
        level.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
            mouth.x, mouth.y, mouth.z, 18, look.x * 0.12, 0.02, look.z * 0.12, 0.18);
        if (belch == null) {
            return;
        }
        Player except = victim instanceof Player p ? p : null;
        if (victim instanceof ServerPlayer sp) {
            sp.playNotifySound(belch, SoundSource.PLAYERS, 0.9F, 1.0F); // first-person, only them
        }
        level.playSound(except, victim.getX(), victim.getY(), victim.getZ(),
            belch, SoundSource.PLAYERS, 0.7F, 1.0F); // everyone else nearby
    }

    /** Pin the victim where it stands for this tick (zero horizontal velocity + halt pathing). */
    private static void rootPulse(LivingEntity victim) {
        Vec3 v = victim.getDeltaMovement();
        victim.setDeltaMovement(0.0, v.y, 0.0);
        victim.hurtMarked = true; // force a velocity packet so the client actually sees the freeze
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop(); // citizens + wild animals: drop the current path
        }
    }

    private static void applyParalysisSlow(LivingEntity victim, int stage) {
        setParalysisSlow(victim, -Math.min(0.9, Config.POISON_SLOW_PER_STAGE.get() * stage));
    }

    /** Set the shared poison movement-speed modifier to {@code slow} (a negative ADD_MULTIPLIED_BASE
     *  fraction; -1.0 = fully rooted). Idempotent; cleared by {@link #clearParalysis}. */
    private static void setParalysisSlow(LivingEntity victim, double slow) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        AttributeModifier existing = speed.getModifier(PARALYSIS_SPEED_ID);
        if (existing == null || existing.amount() != slow) {
            speed.removeModifier(PARALYSIS_SPEED_ID);
            speed.addTransientModifier(new AttributeModifier(PARALYSIS_SPEED_ID, slow,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void applyJumpLock(LivingEntity victim) {
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(PARALYSIS_JUMP_ID) == null) {
            jump.addTransientModifier(new AttributeModifier(PARALYSIS_JUMP_ID, -1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void removeJumpLock(LivingEntity victim) {
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(PARALYSIS_JUMP_ID);
        }
    }

    /** Remove the paralytic attribute modifiers (cure, or switching off wolfsbane). Transient
     *  modifiers also vanish on their own at death/relog, so this only matters for a live cure. */
    static void clearParalysis(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(PARALYSIS_SPEED_ID);
        }
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(PARALYSIS_JUMP_ID);
        }
    }
}
