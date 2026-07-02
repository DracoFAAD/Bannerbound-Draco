package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
import com.bannerbound.antiquity.network.FletchingActionPayload;
import com.bannerbound.antiquity.network.OpenFletchingPayload;
import com.bannerbound.antiquity.recipe.FletchingRecipe;
import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for the fletching stretch minigame. Holds one in-flight session per
 * player (station pos + matched recipe + committed flag), opens the client minigame, and on
 * completion rolls the {@link QualityTier} via the shared {@link QualityMath} and pops the finished
 * item with the {@code TOOL_QUALITY} component. The station's pile is consumed at the COMMIT step
 * (first stretch) so cancelling before any stretch is free and cancelling after forfeits the inputs.
 */
@ApiStatus.Internal
public final class Fletching {
    private Fletching() {
    }

    private static final class Session {
        final BlockPos pos;
        /** Base output (no quality yet), snapshotted at start so a synthetic modular-arrow recipe —
         *  which has no registry id to re-fetch — completes the same as a data-driven one. */
        final ItemStack result;
        /** Display-only "work in progress" sprite for this recipe, or null. */
        final net.minecraft.world.item.Item inProgress;
        final int stretches;
        final long startTime;
        boolean committed;

        Session(BlockPos pos, ItemStack result, net.minecraft.world.item.Item inProgress,
                int stretches, long startTime) {
            this.pos = pos;
            this.result = result;
            this.inProgress = inProgress;
            this.stretches = stretches;
            this.startTime = startTime;
        }
    }

    /** Active sessions keyed by player UUID (server thread only). */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Opens the minigame for {@code player} on the station at {@code pos} (recipe already validated). */
    public static void startSession(ServerPlayer player, BlockPos pos, FletchingStationBlockEntity be) {
        FletchingRecipe recipe = be.matchedRecipe();
        if (recipe == null) return;
        SESSIONS.put(player.getUUID(), new Session(pos.immutable(),
            recipe.result().copy(), recipe.inProgress().orElse(null),
            recipe.stretches(), player.serverLevel().getGameTime()));
        // Claim the station for the player so a crafter citizen skips it mid-minigame (the
        // mirror of the NPC's own craft lock). Released wherever the session ends.
        com.bannerbound.core.api.workshop.WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenFletchingPayload(
            pos, recipe.stretches(), recipe.baseZonePct(), recipe.zoneDecay(),
            recipe.minZonePct(), recipe.yellowPadPct()));
    }

    /** Handles a client minigame step (COMMIT / COMPLETE / CANCEL). */
    public static void handleAction(ServerPlayer player, FletchingActionPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos.equals(payload.pos())) return;
        ServerLevel level = player.serverLevel();

        switch (payload.action()) {
            case FletchingActionPayload.COMMIT -> {
                if (!session.committed
                        && level.getBlockEntity(session.pos) instanceof FletchingStationBlockEntity be) {
                    be.consumePile();
                    session.committed = true;
                    // Show the recipe's work-in-progress sprite lying on the table (if it has one).
                    if (session.inProgress != null) {
                        be.setInProgress(new ItemStack(session.inProgress));
                    }
                }
            }
            case FletchingActionPayload.COMPLETE -> {
                if (session.committed
                        && MinigameGuard.stationInReach(player, session.pos)
                        && payload.scores().size() == session.stretches
                        && MinigameGuard.elapsedOk(player, session.startTime, session.stretches, 8)) {
                    complete(player, level, session, payload.scores());
                }
                clearInProgress(level, session.pos);
                com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
            }
            case FletchingActionPayload.CANCEL -> {
                clearInProgress(level, session.pos);
                com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
            }
            default -> { }
        }
    }

    private static void clearInProgress(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof FletchingStationBlockEntity be) {
            be.setInProgress(ItemStack.EMPTY);
        }
    }

    /** Drops a disconnecting player's session and clears the table's in-progress sprite (the
     *  committed inputs are forfeit, same as a cancel). */
    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            clearInProgress(player.serverLevel(), session.pos);
            com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
        }
    }

    private static void complete(ServerPlayer player, ServerLevel level, Session session, List<Integer> scores) {
        int[] arr = new int[scores.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = MinigameGuard.clampScore(scores.get(i));
        QualityTier tier = com.bannerbound.antiquity.item.Intoxication.craftQuality(
            player, QualityTier.fromScore(QualityMath.aggregate(arr)));

        ItemStack out = applyQuality(session.result.copy(), tier);
        Block.popResource(level, session.pos.above(), out);
        level.playSound(null, session.pos, SoundEvents.VILLAGER_WORK_FLETCHER,
            SoundSource.BLOCKS, 0.8F, 1.0F);
        // Completion burst, visible to everyone nearby (the per-stretch puffs are client-side on the
        // crafter only). Fine+ work earns an extra enchanted flourish.
        double x = session.pos.getX() + 0.5;
        double y = session.pos.getY() + 1.1;
        double z = session.pos.getZ() + 0.5;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
            x, y, z, 12, 0.3, 0.2, 0.3, 0.0);
        if (tier.ordinal() >= QualityTier.FINE.ordinal()) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT,
                x, y, z, 10, 0.25, 0.2, 0.25, 0.1);
        }
    }

    /** Stamps the rolled craftsmanship tier on a fletched output: the TOOL_QUALITY component plus
     *  quality-scaled durability via the MAX_DAMAGE stack component (works for ANY damageable
     *  output — the primitive bow and the vanilla fishing rod alike). Shared by the player
     *  minigame path and the Fletcher NPC's simulated path so quality is computed identically. */
    public static ItemStack applyQuality(ItemStack out, QualityTier tier) {
        out.set(BannerboundCore.TOOL_QUALITY.get(), tier);
        float m = tier.statMultiplier();
        // Durability.
        Integer baseMax = out.get(net.minecraft.core.component.DataComponents.MAX_DAMAGE);
        if (baseMax != null && baseMax > 0) {
            out.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, Math.max(1, Math.round(baseMax * m)));
        }
        if (m == 1.0F) return out;

        // Attack damage — scale the item's ATTACK_DAMAGE modifier so quality shows in the tooltip
        // AND lands in a player's melee hit. Citizen hunters scale damage off the settlement tool-age
        // value (HunterWorkGoal#qualityScaledDamage), not the item's attributes, so this never
        // double-counts for NPCs.
        net.minecraft.world.item.component.ItemAttributeModifiers mods =
            out.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods != null && !mods.modifiers().isEmpty()) {
            net.minecraft.world.item.component.ItemAttributeModifiers.Builder b =
                net.minecraft.world.item.component.ItemAttributeModifiers.builder();
            for (net.minecraft.world.item.component.ItemAttributeModifiers.Entry e : mods.modifiers()) {
                net.minecraft.world.entity.ai.attributes.AttributeModifier mod = e.modifier();
                if (e.attribute().value() == net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value()
                        && mod.operation()
                            == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                    mod = new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        mod.id(), mod.amount() * m, mod.operation());
                }
                b.add(e.attribute(), mod, e.slot());
            }
            out.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, b.build());
        }

        // Mining — scale the tool component's speeds, AMPLIFIED so a fine tool is clearly felt while
        // digging (a +10% stat tier becomes ~+30% mining speed). Not a tooltip number, but real.
        net.minecraft.world.item.component.Tool tool =
            out.get(net.minecraft.core.component.DataComponents.TOOL);
        if (tool != null) {
            float mining = 1.0F + (m - 1.0F) * 3.0F;
            java.util.List<net.minecraft.world.item.component.Tool.Rule> rules = new java.util.ArrayList<>();
            for (net.minecraft.world.item.component.Tool.Rule r : tool.rules()) {
                rules.add(new net.minecraft.world.item.component.Tool.Rule(
                    r.blocks(), r.speed().map(s -> s * mining), r.correctForDrops()));
            }
            out.set(net.minecraft.core.component.DataComponents.TOOL,
                new net.minecraft.world.item.component.Tool(
                    rules, tool.defaultMiningSpeed() * mining, tool.damagePerBlock()));
        }
        return out;
    }

    /** Drops any session anchored at {@code pos} (e.g. the station was broken) and clears the
     *  station's work lock so a rebuilt block doesn't inherit a stale claim. */
    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos.equals(pos));
        com.bannerbound.core.api.workshop.WorkBlockLocks.forceUnlock(pos);
    }
}
