package com.bannerbound.antiquity.item;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Player drunkenness from grog (GROG_PLAN.md Phase 3.5). Every sip applies the grog's per-sip effects
 * (e.g. berry grog → regeneration) and bumps an intoxication level by the grog's {@code strength};
 * sips within a 30-second window <em>stack</em> (Sea-of-Thieves-style), so chugging gets you hammered.
 * The level decays one step per 30s of abstinence, and drives escalating tiers — the swimming drunk
 * shader, a stumble (slowness), then (client) inverted controls. Drink past {@link #MAX} and you
 * <b>black out</b> (curare-style: out cold + input locked, then you come to groggy). Sleeping it off
 * while still hammered triggers a <b>hangover</b> (see {@link #startHangover}). Server-authoritative;
 * the level + the pass-out/hangover deadlines are synced for the client visuals.
 */
public final class Intoxication {
    /** Hard cap — hitting it blacks you out. */
    public static final int MAX = 8;
    /** Stacking / decay window — a sip within this of the last stacks; 30s of none sobers one level. */
    public static final int WINDOW_TICKS = 600;
    /** How long a black-out keeps you out cold (slower, curare-ish: you slump and lie there a while). */
    public static final int PASS_OUT_TICKS = 220;
    /** The level you come to at after a black-out — still drunk, just off the edge. */
    public static final int RECOVER_LEVEL = 3;
    /** Hangover duration on waking up hammered (30s). */
    public static final int HANGOVER_TICKS = 600;
    /** Wake up at or above this intoxication and you get a hangover instead of a free sober-up. */
    public static final int HANGOVER_THRESHOLD = 4;
    /** Vomiting (hunger loss) starts at this intoxication. */
    public static final int VOMIT_MIN = 5;
    /** How long green vomit goo stays on a face you retched into (10s, fades out). */
    public static final int VOMIT_OVERLAY_TICKS = 200;
    /** Reach + cone for catching a vomit in the face (dot ≥ this within {@link #VOMIT_RANGE}). */
    public static final double VOMIT_RANGE = 3.5;
    public static final double VOMIT_CONE = 0.86;

    private Intoxication() {
    }

    /** A sip: restore food, apply the grog's effects, and add {@code strength} intoxication (stacking
     *  inside the {@link #WINDOW_TICKS} window). Server-side. */
    public static void sip(Player player, List<MobEffectInstance> effects, int strength, int foodValue) {
        Level level = player.level();
        if (level.isClientSide) return;
        if (foodValue > 0) player.getFoodData().eat(foodValue, 0.1F * Math.max(1, strength));
        for (MobEffectInstance e : effects) player.addEffect(new MobEffectInstance(e));

        long now = level.getGameTime();
        int lvl = player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        long last = player.getData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get());
        int add = Math.max(1, strength);
        lvl = (now - last <= WINDOW_TICKS) ? lvl + add : add; // stack within the window, else fresh start
        player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), Math.min(lvl, MAX));
        player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
    }

    /** Per-player server tick (call throttled): runs the hangover, then the black-out, then ordinary
     *  intoxication decay + tier effects. */
    public static void serverTick(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;
        // Preserve the buzz through sleep — otherwise a night-skip would sober you below the hangover
        // threshold before you wake, and you'd dodge the morning after. (You can't be passed out asleep.)
        if (player.isSleeping()) return;
        long now = level.getGameTime();

        // ── Hangover: groggy slowness while it lasts (visuals + crude-craft are elsewhere); clears itself. ──
        long hangover = player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        if (hangover > 0) {
            if (now < hangover) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, false, false));
            } else {
                player.setData(BannerboundAntiquity.HANGOVER_UNTIL.get(), 0L);
            }
        }

        // ── Black-out: out cold, then come to groggy. ──
        long passOut = player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOut > 0) {
            if (now < passOut) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 9, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, 30, 128, false, false, false)); // -jump
                return; // out cold — no decay, no other effects
            }
            // Coming to: drop off the edge but still drunk.
            player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), 0L);
            player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), RECOVER_LEVEL);
            player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
        }

        int lvl = player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        if (lvl <= 0) return;

        // Drink past the cap → black out.
        if (lvl >= MAX) {
            player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), now + PASS_OUT_TICKS);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 0.5F); // a woozy down-pitched thud
            return;
        }

        if (now - player.getData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get()) >= WINDOW_TICKS) {
            lvl = Math.max(0, lvl - 1); // 30s without a sip → sober one level
            player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), lvl);
            player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
            if (lvl <= 0) return;
        }
        // Escalating tiers. Visuals (the swimming drunk shader) + the high-tier control inversion are
        // CLIENT-side off the synced level — no vanilla Nausea. The server only applies the stumble.
        if (lvl >= 4) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, false, false, false));
        }
        if (lvl >= 6) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false, false));
        }

        // Very drunk → you randomly throw up: green bile + a retch, and it costs HUNGER (not health).
        // Chance per second climbs with the level (~6% at L5 → ~18% at L7).
        if (lvl >= VOMIT_MIN && player instanceof ServerPlayer sp && level instanceof ServerLevel sl
                && sp.getRandom().nextFloat() < 0.06F * (lvl - (VOMIT_MIN - 1))) {
            vomit(sp, sl);
        }
    }

    /** A heave of green bile from the mouth + a retch — drains hunger, never health. Server-side. */
    private static void vomit(ServerPlayer player, ServerLevel level) {
        Vec3 look = player.getLookAngle();
        Vec3 mouth = player.getEyePosition().add(look.scale(0.3)).subtract(0.0, 0.15, 0.0);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SLIME_BALL)),
            mouth.x, mouth.y, mouth.z, 16, look.x * 0.14, 0.02, look.z * 0.14, 0.2);

        FoodData food = player.getFoodData();
        food.setFoodLevel(Math.max(0, food.getFoodLevel() - 3));
        food.setSaturation(Math.min(food.getSaturationLevel(), food.getFoodLevel()));

        SoundEvent belch = BannerboundAntiquity.WOLFSBANE_BELCH.get();
        player.playNotifySound(belch, SoundSource.PLAYERS, 0.9F, 1.1F);        // first-person, just them
        level.playSound(player, player.getX(), player.getY(), player.getZ(),  // everyone else nearby
            belch, SoundSource.PLAYERS, 0.7F, 1.1F);

        // Vomit in someone's face → green goo all over their screen (Sea-of-Thieves style).
        Vec3 eye = player.getEyePosition();
        for (Player other : level.players()) {
            if (other == player || !other.isAlive()) {
                continue;
            }
            Vec3 to = other.getEyePosition().subtract(eye);
            if (to.length() <= VOMIT_RANGE && look.dot(to.normalize()) >= VOMIT_CONE) {
                splatter(other);
            }
        }
    }

    /** Splatter green vomit goo on {@code target}'s screen for {@link #VOMIT_OVERLAY_TICKS} (fades out).
     *  Server-side; reused by the {@code /bannerbound vomit_overlay} test command. */
    public static void splatter(Player target) {
        Level level = target.level();
        if (level.isClientSide) return;
        target.setData(BannerboundAntiquity.VOMIT_OVERLAY_UNTIL.get(),
            level.getGameTime() + VOMIT_OVERLAY_TICKS);
    }

    /** Sleeping it off while still hammered: clear the drink instantly, but pay with a {@link
     *  #HANGOVER_TICKS} hangover (groggy slowness + a pounding vignette + muffled sound + crude crafts).
     *  Called from the wake-up event; no-op if you went to bed only mildly tipsy. Server-side. */
    public static void startHangover(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;
        if (player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get()) < HANGOVER_THRESHOLD) return;
        player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), 0);   // the drink is gone…
        player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), 0L);
        player.setData(BannerboundAntiquity.HANGOVER_UNTIL.get(),            // …but now you suffer
            level.getGameTime() + HANGOVER_TICKS);
    }

    /** Current intoxication level (0 = sober), for the client drunk visuals / inverted controls. */
    public static int level(Player player) {
        return player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
    }

    /** True while {@code player} is hungover (drives the crude-craft penalty + client vignette/muffle). */
    public static boolean isHungover(Player player) {
        return player.level().getGameTime() < player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
    }

    /** Drink-impaired craftsmanship: drunk (or hungover) hands botch the work. Wrap the player's rolled
     *  hand-craft quality with this at each site (knapping / fletching / hammer). Hungover → always
     *  {@link QualityTier#CRUDE}; otherwise the rolled tier drops by intoxication — steady when tipsy,
     *  −1 tier when drunk (L3–4), −2 when very drunk (L5–6), bottoming out at CRUDE once hammered (L7+). */
    public static QualityTier craftQuality(Player player, QualityTier rolled) {
        if (isHungover(player)) {
            return QualityTier.CRUDE;
        }
        int lvl = level(player);
        if (lvl < 3) {
            return rolled; // sober / tipsy: steady enough
        }
        int drop = lvl >= 7 ? QualityTier.values().length : (lvl >= 5 ? 2 : 1);
        int idx = Math.max(QualityTier.CRUDE.ordinal(), rolled.ordinal() - drop);
        return QualityTier.values()[idx];
    }
}
