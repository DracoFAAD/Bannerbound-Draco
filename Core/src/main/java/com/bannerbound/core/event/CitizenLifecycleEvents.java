package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Server-side reactions to citizen lifecycle: when a {@link CitizenEntity} dies, prune it from
 * its settlement's roster and rebroadcast population state so the next-citizen cost recomputes
 * from the lower population.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenLifecycleEvents {
    private CitizenLifecycleEvents() {
    }

    @SubscribeEvent
    public static void onCitizenDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity citizen)) {
            return;
        }
        if (!(citizen.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // A trade courier can die ANYWHERE (mid-wilderness, another settlement's land) — spill its
        // cargo + fail the deal leg before the roster guards below can bail out.
        if (citizen.isOnTradeJourney()) {
            com.bannerbound.core.trade.TradeCourierManager.onCourierDied(serverLevel, citizen);
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null || citizen.getSettlementId() == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getById(citizen.getSettlementId());
        if (settlement == null) {
            return;
        }
        if (settlement.removeCitizen(citizen.getUUID())) {
            // Death thoughts MUST run before forgetDeadCitizenInRelationships — the tier lookup
            // reads each survivor's relationship-map entry for the deceased, which the forget
            // call about to follow would wipe. Order matters.
            applyDeathThoughtsToSurvivors(serverLevel, settlement, citizen);
            forgetDeadCitizenInRelationships(serverLevel, settlement, citizen.getUUID());
            releaseBedIfSleeping(serverLevel, citizen);
            evictDeadCitizenFromHome(settlement, citizen.getUUID());
            data.setDirty();
            ImmigrationManager.broadcastState(server, settlement);
            broadcastDeathMessage(server, settlement, citizen, event.getSource());
        }
    }

    /** Every still-living citizen who had a relationship with the deceased gets a death thought
     *  matching their tier: family / friend-for-life / close-friend / friend / generic. Negative-
     *  tier survivors (rivals / enemies) get nothing — they wouldn't mourn. The dead citizen's
     *  bare name is captured into the thought's {@code savedPartnerName} since the entity is
     *  about to be discarded and UUID-resolution at screen-build time would return null. */
    private static void applyDeathThoughtsToSurvivors(ServerLevel sl, Settlement settlement,
                                                       CitizenEntity dead) {
        long now = sl.getGameTime();
        UUID deadId = dead.getUUID();
        // Use the raw citizenName (no gender/pregnancy glyph) — getCustomName().getString() flattens
        // the styled component and the PUA glyph codepoint comes out with no font association,
        // rendering as tofu/square when the death-thought label later wraps the string back into a
        // Component.literal. The death-thought label is plain text only; the gender icon belongs
        // on the live entity's nametag, not in the survivors' Thoughts list.
        String deadName = dead.getCitizenName() != null
            ? dead.getCitizenName()
            : "Someone";
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (c.entityId().equals(deadId)) continue;
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity survivor)) continue;
            com.bannerbound.core.social.Relationship rel = survivor.getRelationships().get(deadId);
            // STRANGERS.tier() returns STRANGERS too — the dispatch table maps STRANGERS → the
            // generic "died_recently" so this still fires for citizens who'd at least met the
            // deceased once (Relationship entry exists in the map). Citizens with no entry at
            // all (never interacted) get the STRANGERS default → still mourn lightly. That
            // matches the spec's basic "<X> died recently" applying broadly.
            com.bannerbound.core.social.ThoughtKind kind =
                com.bannerbound.core.social.ThoughtKind.deathThoughtFor(rel.tier());
            if (kind == null) continue;
            survivor.getThoughts().add(kind, deadId, deadName, now, sl.random);
            survivor.recomputeHappiness();
        }
    }

    /** If the citizen died mid-sleep, clear the bed's OCCUPIED flag so the next homeless citizen
     *  can claim it. SleepGoal.stop normally handles this, but dying short-circuits the goal
     *  lifecycle (entity removal happens before the next goal tick), so without this the bed
     *  stays "occupied" until something else writes that block state. */
    private static void releaseBedIfSleeping(ServerLevel sl, CitizenEntity citizen) {
        if (!citizen.isSleeping()) return;
        citizen.getSleepingPos().ifPresent(pos -> {
            net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(pos);
            if (bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                sl.setBlock(pos,
                    bs.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            // Also release the SleepGoal's in-memory reservation. Death short-circuits the
            // goal lifecycle so its stop() may never run — without this, the bed stays
            // permanently "taken" in the reservation set even though OCCUPIED was cleared.
            com.bannerbound.core.entity.SleepGoal.releaseReservation(pos);
        });
    }

    /** Drops the dead UUID from any {@code Home.residents} list. The freed bed is picked up by
     *  the next homeless citizen's auto-assignment poll. Mirrors {@link
     *  #forgetDeadCitizenInRelationships}'s eager cleanup pattern. */
    private static void evictDeadCitizenFromHome(Settlement settlement, UUID dead) {
        com.bannerbound.core.api.settlement.Home home = settlement.getHomeFor(dead);
        if (home != null) home.removeResident(dead);
    }

    /** Iterates every still-living citizen in {@code settlement} and drops the dead UUID from
     *  their {@code Relationships} map. Without this, stale entries accumulate forever (and
     *  matter the moment Lover / Best Friend overflow bars exist — a stale UUID in a Lover
     *  slot would be a silent soft bug). Eager cleanup is cheap (O(N) per death). */
    private static void forgetDeadCitizenInRelationships(ServerLevel sl, Settlement settlement, UUID dead) {
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity other)) continue;
            other.getRelationships().forget(dead);
        }
    }

    /**
     * Sends the vanilla "X was killed by Y" style death message to every settlement member.
     * Uses {@link DamageSource#getLocalizedDeathMessage(net.minecraft.world.entity.LivingEntity)}
     * which already resolves attacker name, weapon, and translation key — so e.g. "Magnus was
     * slain by Zombie" or "Brom drowned" come out naturally per damage type.
     */
    private static void broadcastDeathMessage(MinecraftServer server, Settlement settlement,
                                              CitizenEntity citizen, DamageSource source) {
        Component vanilla = source.getLocalizedDeathMessage(citizen);
        MutableComponent line = Component.empty()
            .append(vanilla)
            .withStyle(ChatFormatting.GRAY);
        for (UUID memberId : settlement.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                m.sendSystemMessage(line);
            }
        }
    }
}
