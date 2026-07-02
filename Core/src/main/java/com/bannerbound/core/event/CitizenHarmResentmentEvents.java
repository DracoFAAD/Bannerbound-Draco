package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Step 11 — resentment-from-harm wiring. Two hooks:
 * <ul>
 *   <li>When a {@link Player} damages a {@link CitizenEntity}, that citizen's resentment
 *       toward the attacker climbs by {@link #RESENTMENT_PER_HIT}. Independent of leader
 *       status — a Council member who slaps a citizen accrues resentment just as fast as
 *       a passing wanderer would.</li>
 *   <li>When a {@link Player} kills a {@link CitizenEntity}, every <i>other</i> citizen in
 *       the same settlement gains {@link #RESENTMENT_PER_WITNESS_KILL} resentment toward
 *       the killer. The dying citizen's own resentment is moot (entity removed).</li>
 * </ul>
 *
 * <p>This sits separately from {@link CitizenLifecycleEvents} so the death-roster cleanup
 * and the resentment broadcast stay independently testable — neither blocks the other.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenHarmResentmentEvents {
    /** Per-hit resentment gain on the struck citizen. Tuned for "a few hits cross the
     *  compliance-drop threshold (20) but a single bump doesn't." */
    public static final int RESENTMENT_PER_HIT = 10;
    /** Per-witness gain on every other settlement citizen when one of theirs is killed.
     *  Larger than per-hit because a death is the kind of event a tribe remembers; a single
     *  killing reliably pushes witnesses past the compliance-drop threshold. */
    public static final int RESENTMENT_PER_WITNESS_KILL = 40;

    private CitizenHarmResentmentEvents() {
    }

    @SubscribeEvent
    public static void onCitizenHit(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity citizen)) return;
        Entity attackerEnt = event.getSource() == null ? null : event.getSource().getEntity();
        if (!(attackerEnt instanceof Player attacker)) return;
        // Don't penalise the player's own citizens for fall damage / mob attacks etc. — only
        // direct player → citizen swings. Per design: hitting a citizen is a social act, not
        // an "the world hurt them" situation.
        citizen.addResentment(attacker.getUUID(), RESENTMENT_PER_HIT);
    }

    @SubscribeEvent
    public static void onCitizenKilled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity killed)) return;
        if (!(killed.level() instanceof ServerLevel level)) return;
        Entity killerEnt = event.getSource() == null ? null : event.getSource().getEntity();
        if (!(killerEnt instanceof Player killer)) return;
        if (level.getServer() == null) return;
        if (killed.getSettlementId() == null) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement settlement = data.getById(killed.getSettlementId());
        if (settlement == null) return;
        // Bump resentment on every still-living settlement citizen toward the killer.
        // Settlement's Citizen roster is a snapshot of UUID/name records, not live entities;
        // the actual resentment map lives on the CitizenEntity, so iterate the loaded
        // entities through the shared SettlementManager.allCitizensOf helper.
        for (CitizenEntity other
                : com.bannerbound.core.api.settlement.SettlementManager.allCitizensOf(level, settlement)) {
            if (other == killed) continue;
            other.addResentment(killer.getUUID(), RESENTMENT_PER_WITNESS_KILL);
        }
    }
}
