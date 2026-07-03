package com.bannerbound.antiquity.entity;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import javax.annotation.Nullable;

/**
 * Shared server-side state for immersive hunting — the "spine" the flee/herd/charge goals and the
 * combat events all read and write. Backed by the transient attachments on {@link BannerboundAntiquity}
 * ({@code SCARED_UNTIL}, {@code BOAR_CHARGE_CLAIM}, {@code HUNT_STAMINA}, {@code BLEED_TICKS}).
 *
 * <p>Fear propagation: when an animal first becomes scared (sees the player, or is hurt), the caller
 * fires {@link #alarmHerd} once to spook nearby same-species animals — so a whole herd scatters.
 */
public final class HuntingFear {
    private HuntingFear() {}

    // ── Tamed / domesticated ─────────────────────────────────────────────────────────────────
    /** True if this animal should behave like vanilla livestock (no flee, footprints, or hunting fear):
     *  either it's been fed its favourite food ({@code TAMED_LIVESTOCK}), or it's a vanilla-tamed
     *  pet/mount (wolf, cat, horse). */
    public static boolean isTamed(LivingEntity mob) {
        if (mob.getData(BannerboundAntiquity.TAMED_LIVESTOCK.get())) {
            return true;
        }
        // Core's Herder domesticates the animals it corrals/raises; it can't reference this Antiquity
        // attachment, so it stamps a shared persistent-data flag we honour here.
        if (mob.getPersistentData().getBoolean(DOMESTICATED_TAG)) {
            return true;
        }
        if (mob instanceof TamableAnimal t && t.isTame()) {
            return true;
        }
        return mob instanceof AbstractHorse h && h.isTamed();
    }

    /** Shared persistent-data key Core's Herder sets to domesticate an animal (no flee/footprints). */
    public static final String DOMESTICATED_TAG = "BannerboundDomesticated";

    /** Mark an animal tamed (fed its favourite food) — persists across save/reload. */
    public static void setTamed(LivingEntity mob) {
        mob.setData(BannerboundAntiquity.TAMED_LIVESTOCK.get(), true);
    }

    // ── Scared state ─────────────────────────────────────────────────────────────────────────
    public static boolean isScared(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.SCARED_UNTIL.get()) >= mob.level().getGameTime();
    }

    public static void scare(LivingEntity mob, int ticks) {
        long until = mob.level().getGameTime() + ticks;
        if (mob.getData(BannerboundAntiquity.SCARED_UNTIL.get()) < until) {
            mob.setData(BannerboundAntiquity.SCARED_UNTIL.get(), until);
        }
    }

    /** Spook every same-species animal within {@code radius} (the herd). Call once on the
     *  not-scared→scared edge to avoid re-cascading. */
    public static void alarmHerd(Mob source, int radius, int ticks) {
        if (radius <= 0) {
            return;
        }
        long until = source.level().getGameTime() + ticks;
        List<? extends Mob> herd = source.level().getEntitiesOfClass(source.getClass(),
            source.getBoundingBox().inflate(radius), o -> o != source && o.isAlive());
        for (Mob mate : herd) {
            if (isTamed(mate)) {
                continue; // a domesticated animal stays calm even when its herd panics
            }
            if (mate.getData(BannerboundAntiquity.SCARED_UNTIL.get()) < until) {
                mate.setData(BannerboundAntiquity.SCARED_UNTIL.get(), until);
            }
        }
    }

    // ── Boar single-charger election ─────────────────────────────────────────────────────────
    /** On the first-scared edge for a pig: roll the charge chance; if it "charges" and no nearby
     *  pig already holds a live claim, claim the charge for this pig. Otherwise the pig will flee. */
    public static void electBoarCharger(Pig source, int radius, int claimTicks, double chance, RandomSource rng) {
        if (rng.nextDouble() >= chance) {
            return; // this reaction is to flee — no charger claimed
        }
        long now = source.level().getGameTime();
        List<Pig> pigs = source.level().getEntitiesOfClass(Pig.class,
            source.getBoundingBox().inflate(radius), p -> p != source && p.isAlive());
        for (Pig other : pigs) {
            if (other.getData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get()) >= now) {
                return; // a herd-mate already holds the charge
            }
        }
        source.setData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get(), now + claimTicks);
    }

    public static boolean hasChargeClaim(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get()) >= mob.level().getGameTime();
    }

    public static void clearChargeClaim(LivingEntity mob) {
        mob.setData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get(), 0L);
    }

    // ── Stamina (persistence hunting) ────────────────────────────────────────────────────────
    public static float getStamina(LivingEntity mob) {
        float s = mob.getData(BannerboundAntiquity.HUNT_STAMINA.get());
        return s < 0.0F ? (float) (double) Config.STAMINA_MAX.get() : s;
    }

    public static void setStamina(LivingEntity mob, float value) {
        float max = (float) (double) Config.STAMINA_MAX.get();
        mob.setData(BannerboundAntiquity.HUNT_STAMINA.get(), Math.max(0.0F, Math.min(max, value)));
    }

    public static boolean isTired(LivingEntity mob) {
        return getStamina(mob) <= (float) (double) Config.STAMINA_TIRED_THRESHOLD.get();
    }

    // ── Bleeding ─────────────────────────────────────────────────────────────────────────────
    public static boolean isBleeding(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.BLEED_TICKS.get()) > 0;
    }

    public static void applyBleed(LivingEntity mob, int ticks) {
        int current = mob.getData(BannerboundAntiquity.BLEED_TICKS.get());
        mob.setData(BannerboundAntiquity.BLEED_TICKS.get(), Math.max(current, ticks));
    }

    public static void applyBleed(LivingEntity mob, int ticks, @Nullable Entity causedBy) {
        applyBleed(mob, ticks);

        if (causedBy != null)
            mob.setData(BannerboundAntiquity.BLEED_BY.get(), causedBy.getStringUUID());
    }
}
