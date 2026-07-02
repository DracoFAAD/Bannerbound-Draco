package com.bannerbound.core.entity;

import net.minecraft.server.level.ServerLevel;

/**
 * Step 14 — citizen-on-citizen brawl escalation. Not actually a vanilla {@code Goal} class;
 * this is a static helper called from {@link ConversationGoal#onResolvingEntry()} when a
 * 0-match argument should turn physical (anarchy or low compliance, 25% chance).
 *
 * <p>The actual fight loop is handled by the existing brawl mechanic:
 * {@link CitizenEntity#schedulePendingRetaliation} → {@code BrawlRetaliationGoal} fires the
 * swing → {@code CitizenBrawlEvents} catches the resulting LivingIncomingDamageEvent and
 * schedules the OTHER side's retaliation → fight continues until one runs away, dies, or the
 * brawl-ongoing window expires. We just light the first match here.
 *
 * <p>Death of either citizen flows through {@code CitizenLifecycleEvents.onCitizenDeath}
 * (death broadcast, settlement roster prune) and {@code CitizenHarmResentmentEvents}
 * (witnesses gain resentment toward the killer) for free — Step 11 already wired both.
 */
public final class ConflictGoal {
    /** Tick delay before the first swing — same value the brawl retaliation uses so an
     *  escalated argument feels like a natural continuation of the conversation, not a
     *  scripted cut. */
    private static final int FIRST_SWING_DELAY_TICKS = 5;

    private ConflictGoal() {
    }

    /** Kick off a brawl between {@code a} and {@code b}: schedule each side's first swing.
     *  After the first swings land, the existing brawl-retaliation chain (BrawlRetaliationGoal
     *  + CitizenBrawlEvents) drives the fight on its own. */
    public static void escalate(CitizenEntity a, CitizenEntity b, ServerLevel sl) {
        if (a == null || b == null || sl == null) return;
        if (!a.isAlive() || !b.isAlive()) return;
        long fireAt = sl.getGameTime() + FIRST_SWING_DELAY_TICKS;
        a.schedulePendingRetaliation(b.getUUID(), fireAt);
        b.schedulePendingRetaliation(a.getUUID(), fireAt);
    }
}
