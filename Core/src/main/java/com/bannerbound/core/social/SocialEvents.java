package com.bannerbound.core.social;

import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * One-stop helper for symmetric relationship mutations.
 * <p>
 * The invariant the social system depends on is: <b>citizen A's view of B and citizen B's view
 * of A always have the same score.</b> Without a single chokepoint, every caller would have to
 * remember to update both sides and asymmetry would creep in. Every relationship change in
 * Core should go through {@link #applyMutual(CitizenEntity, CitizenEntity, int)}.
 */
public final class SocialEvents {
    private SocialEvents() {}

    /** Applies {@code delta} to BOTH directions of the pair's relationship, stamps the current
     *  game tick on each entry, and marks the owning settlement dirty so the change persists.
     *  No-op if either citizen is null or they have no settlement. */
    public static void applyMutual(CitizenEntity a, CitizenEntity b, int delta) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        a.getRelationships().applyDelta(b.getUUID(), delta, now);
        b.getRelationships().applyDelta(a.getUUID(), delta, now);
        // Mark dirty so the per-citizen NBT (saved with the entity) and any settlement-level
        // bookkeeping land on disk on the next save tick.
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    /** Symmetric hard-overwrite of both sides of a pair's score to a specific value. Wipes any
     *  prior FAMILY flag (the family entry's no-op score path would otherwise swallow the set).
     *  Debug-only chokepoint for {@code /bannerbound set_relationship}; gameplay should still
     *  use {@link #applyMutual} so the family/lover guard rails apply. */
    public static void setMutualScore(CitizenEntity a, CitizenEntity b, int value) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        a.getRelationships().setScore(b.getUUID(), value, now);
        b.getRelationships().setScore(a.getUUID(), value, now);
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    /** Symmetric installer for the permanent parent ↔ child bond. Overwrites whatever
     *  score-based entry the pair previously had with the canonical {@link Relationship#FAMILY}
     *  record on both sides, and marks the settlement dirty so the new bond survives a reload.
     *  No-op if either citizen is null or off-server. */
    public static void linkMutualFamily(CitizenEntity a, CitizenEntity b) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        a.getRelationships().linkFamily(b.getUUID());
        b.getRelationships().linkFamily(a.getUUID());
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    /** Spawns the outcome-flavoured particle burst above both citizens once a conversation
     *  resolves: 0 matches = angry villager, 1 = smoke, 2 = firework, 3 = happy villager.
     *  Server-side {@code ServerLevel.sendParticles} broadcasts to every viewing client. */
    public static void spawnOutcomeParticles(CitizenEntity a, CitizenEntity b, int matches) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        ParticleOptions type = switch (matches) {
            case 0 -> ParticleTypes.ANGRY_VILLAGER;
            case 1 -> ParticleTypes.SMOKE;
            case 2 -> ParticleTypes.FIREWORK;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
        burstAt(sl, a, type);
        burstAt(sl, b, type);
    }

    /** Public hearts-above-the-head burst — used by {@code BabyMakingManager} for the
     *  lovemaking-session pulses. Same 12-particle cloud shape as the conversation-outcome
     *  bursts so the visual language stays consistent. */
    public static void spawnHearts(ServerLevel sl, CitizenEntity c) {
        burstAt(sl, c, ParticleTypes.HEART);
    }

    private static void burstAt(ServerLevel sl, CitizenEntity c, ParticleOptions type) {
        // 12 particles in a small cloud just above the head, with a touch of upward velocity.
        sl.sendParticles(type,
            c.getX(), c.getY() + c.getBbHeight() + 0.3, c.getZ(),
            12, 0.3, 0.3, 0.3, 0.02);
    }
}
