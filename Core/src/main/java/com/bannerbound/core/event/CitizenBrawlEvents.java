package com.bannerbound.core.event;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Citizen-on-citizen brawl loop. When citizen A hits citizen B (typically via
 * {@code AnarchyWorkGoal}'s occasional punch), this listener rolls B's retaliation chance
 * and, on a pass, schedules B's swing-back. Each subsequent hit in the same exchange uses a
 * lower "keep going" chance so brawls naturally peter out instead of dragging on forever.
 *
 * <p><b>Roll structure</b> (per user spec: "75% that the recipient will hit back, then loop
 * until one rolls 50% out of hitting or one dies"):
 * <ul>
 *   <li><b>First retaliation</b> (B has no recent brawl record with A): {@code 75%} to swing
 *       back. 25% to walk it off — fight over after one punch.</li>
 *   <li><b>Subsequent retaliation</b> (B was already in an active brawl with A inside the
 *       {@link CitizenEntity#BRAWL_ONGOING_WINDOW_TICKS} window): {@code 50%} to keep going.
 *       50% to disengage — fight ends on that side, no further retaliation.</li>
 * </ul>
 *
 * <p>A retaliation pass calls {@link CitizenEntity#schedulePendingRetaliation} with a small
 * delay so the brawl reads as alternating swings rather than a same-tick mash; the actual
 * swing fires in {@link CitizenEntity#aiStep} when the scheduled tick arrives, which itself
 * triggers this listener on the new victim, which (maybe) schedules the next return swing —
 * and so on, until one side fails its roll or somebody dies.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenBrawlEvents {
    /** Maximum distance from victim to attacker before retaliation is suppressed. If the
     *  attacker has already wandered off (anarchy punches are quick walk-ups), there's
     *  nothing meaningful to swing back AT — the brawl just ends. */
    private static final double RETALIATE_RANGE_SQ = 16.0;
    /** First-hit retaliate chance. The recipient might just take it once and walk off. */
    private static final float FIRST_RETALIATE_CHANCE = 0.75f;
    /** Subsequent-hit continue chance. Each side gets a 50/50 to keep swinging or disengage. */
    private static final float ONGOING_CONTINUE_CHANCE = 0.50f;

    private CitizenBrawlEvents() {
    }

    @SubscribeEvent
    public static void onCitizenHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity victim)) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        Entity raw = event.getSource().getEntity();
        // Attacker eligible if it's another citizen OR a player — citizens defend themselves
        // against player aggression too, with the same 75% / 50% rolls. Spectators self-
        // filter (no damage); creative players DO trigger retaliation so the chain is
        // testable in creative mode. Self-hits dropped.
        if (!(raw instanceof LivingEntity attackerLiving)) return;
        if (attackerLiving == victim || !attackerLiving.isAlive()) return;
        if (!(attackerLiving instanceof CitizenEntity) && !(attackerLiving instanceof Player)) {
            return;
        }
        // A guard hit by an OUTSIDER goes to real combat (GuardCombatEvents retaliation), not a
        // one-punch brawl swing. Same-settlement scuffles still brawl like anyone else's.
        if (victim.isGuard() && GuardCombatEvents.isRetaliationWorthy(victim, attackerLiving)) {
            return;
        }
        // If the attacker wandered too far before our hurt-handler fires, skip.
        if (victim.distanceToSqr(attackerLiving) > RETALIATE_RANGE_SQ) return;
        // If victim already has a pending retaliation queued, don't stack another — the chain
        // is already in motion. The next BrawlRetaliationGoal tick will resolve it.
        if (victim.getPendingRetaliationTargetId() != null) return;

        long now = sl.getGameTime();
        UUID attackerId = attackerLiving.getUUID();
        boolean ongoing = attackerId.equals(victim.getLastBrawlOpponentId())
            && (now - victim.getLastBrawlTick()) < CitizenEntity.BRAWL_ONGOING_WINDOW_TICKS;
        float chance = ongoing ? ONGOING_CONTINUE_CHANCE : FIRST_RETALIATE_CHANCE;
        if (victim.getRandom().nextFloat() >= chance) return;

        // Pass — schedule the return swing. noteBrawlExchange happens AFTER the swing
        // lands (in BrawlRetaliationGoal.tick) so the brawl window reflects the actual
        // exchange chain, not just the intent.
        victim.schedulePendingRetaliation(attackerId,
            now + CitizenEntity.BRAWL_RETALIATION_DELAY_TICKS);
    }
}
