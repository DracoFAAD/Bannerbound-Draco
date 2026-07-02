package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.RopeFenceInteractions;
import com.bannerbound.antiquity.SpearFishing;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Antiquity's payload registration (mirrors Core's {@code BannerboundNetwork}). Currently just the
 * empty-hand reel-in signal — the server resolves and reels the sender's tethered spear/catch.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class AntiquityNetwork {
    private AntiquityNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
            ReelTetherPayload.TYPE,
            ReelTetherPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    SpearFishing.startReel(player);
                }
            }));
        // Left-click on a rope post's rope part: cancel the player's tie, or break one of the post's ropes.
        registrar.playToServer(
            RopeFenceActionPayload.TYPE,
            RopeFenceActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    RopeFenceInteractions.serverHandle(player, payload.pos());
                }
            }));
        // Fletching minigame: client reports each step; the server owns the session + quality roll.
        registrar.playToServer(
            FletchingActionPayload.TYPE,
            FletchingActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Fletching.handleAction(player, payload);
                }
            }));
        // Pottery wheel minigame: non-scored spin loop; the server owns the session and output.
        registrar.playToServer(
            PotteryActionPayload.TYPE,
            PotteryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Pottery.handleAction(player, payload);
                }
            }));

        // Mortar press-and-grind minigame: non-scored; the server owns the loaded batch and output.
        registrar.playToServer(
            MortarGrindActionPayload.TYPE,
            MortarGrindActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.MortarGrind.handleAction(player, payload);
                }
            }));

        // Knapping minigame: client reports each step; the server owns the session, the rock
        // consumption, and the quality roll (no station — a pure two-rocks hand-craft).
        registrar.playToServer(
            KnappingActionPayload.TYPE,
            KnappingActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Knapping.handleAction(player, payload);
                }
            }));

        // Ghost-preview clicks (browse arrows / ghost result) on the crafting stone + fletching
        // station + carpenter's table — the targets are client-side billboards, so the click arrives
        // as a payload.
        registrar.playToServer(
            GhostActionPayload.TYPE,
            GhostActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.GhostWorkstationActions.serverHandle(
                        player, payload.pos(), payload.action());
                }
            }));

        // In-world carve commit: the previewed block is hidden (air) on the client and can't be
        // ray-clicked, so the use-key press arrives here and the server replays the carve at the
        // anchor (Carves.commit re-fires the real RightClickBlock handler).
        registrar.playToServer(
            CarveCommitPayload.TYPE,
            CarveCommitPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Carves.commit(player, payload.anchor());
                }
            }));

        // Carpentry saw minigame: client reports completion/cancel; the server owns the session and
        // outputs the build list.
        registrar.playToServer(
            CarpentryActionPayload.TYPE,
            CarpentryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Carpentry.handleAction(player, payload);
                }
            }));

        // Tannery scrape minigame: client reports completion/cancel; the server owns the session and
        // emits the scraped hides scaled by the input hide's quality.
        registrar.playToServer(
            TanningActionPayload.TYPE,
            TanningActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Tannery.handleAction(player, payload);
                }
            }));

        // Masonry chisel-strike minigame: client reports completion/cancel; the server owns the
        // session and outputs the build list.
        registrar.playToServer(
            MasonryActionPayload.TYPE,
            MasonryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Masonry.handleAction(player, payload);
                }
            }));

        // Cold-hammer minigame at the stone anvil: client reports each strike; the server owns the
        // session, the rank-gated quality roll, and the finished tool.
        registrar.playToServer(
            HammerActionPayload.TYPE,
            HammerActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Hammer.handleAction(player, payload);
                }
            }));
        // Client-bound: the datapack arrow-part registry, so clients render modular arrows + tooltips.
        // The handler only touches the COMMON ArrowPartRegistry, so no dist guard is needed (the server
        // registers it solely to encode the payload; the handler runs client-side).
        registrar.playToClient(
            ArrowPartsSyncPayload.TYPE,
            ArrowPartsSyncPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() ->
                com.bannerbound.antiquity.recipe.ArrowPartRegistry.replace(payload.parts())));
        // Client-bound: open the fletching minigame. The real handler touches client-only classes
        // (FletchingScreen), so it's dist-guarded with a no-op on the server (which still must
        // register TYPE + CODEC to encode the payload). See the haschannel-false-negative lesson.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registrar.playToClient(
                OpenFletchingPayload.TYPE,
                OpenFletchingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.FletchingClientHandler.open(payload)));
            registrar.playToClient(
                OpenPotteryPayload.TYPE,
                OpenPotteryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.PotteryClientHandler.open(payload)));
            registrar.playToClient(
                OpenCarpentrySawPayload.TYPE,
                OpenCarpentrySawPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.CarpentryClientHandler.open(payload)));
            registrar.playToClient(
                OpenTanningPayload.TYPE,
                OpenTanningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.TanningClientHandler.open(payload)));
            registrar.playToClient(
                OpenKnappingPayload.TYPE,
                OpenKnappingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.KnappingClientHandler.open(payload)));
            registrar.playToClient(
                OpenMortarGrindPayload.TYPE,
                OpenMortarGrindPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.MortarGrindClientHandler.open(payload)));
            registrar.playToClient(
                OpenMasonChiselPayload.TYPE,
                OpenMasonChiselPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.MasonryClientHandler.open(payload)));
            registrar.playToClient(
                OpenHammerPayload.TYPE,
                OpenHammerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.HammerClientHandler.open(payload)));
            // Cold-hammer third-person arm raise: flag the player so the model lifts the hammer arm.
            registrar.playToClient(
                HammerArmPayload.TYPE,
                HammerArmPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.HammerArmState.setActive(payload.player(), payload.active())));
            // Face-decoration sync: full chunk on watch, single-face deltas on edit.
            registrar.playToClient(
                DecoChunkSyncPayload.TYPE,
                DecoChunkSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.DecoClientHandler.applyChunk(payload)));
            registrar.playToClient(
                DecoUpdatePayload.TYPE,
                DecoUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.DecoClientHandler.applyUpdate(payload)));
            // Armorer's Workbench: open the player-designed-armor screen (ARMOR_PLAN.md).
            registrar.playToClient(
                OpenArmorerPayload.TYPE,
                OpenArmorerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.ArmorerClientHandler.open(payload)));
        } else {
            registrar.playToClient(
                OpenFletchingPayload.TYPE,
                OpenFletchingPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenPotteryPayload.TYPE,
                OpenPotteryPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenCarpentrySawPayload.TYPE,
                OpenCarpentrySawPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenTanningPayload.TYPE,
                OpenTanningPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenKnappingPayload.TYPE,
                OpenKnappingPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenMortarGrindPayload.TYPE,
                OpenMortarGrindPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenMasonChiselPayload.TYPE,
                OpenMasonChiselPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenHammerPayload.TYPE,
                OpenHammerPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                HammerArmPayload.TYPE,
                HammerArmPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                DecoChunkSyncPayload.TYPE,
                DecoChunkSyncPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                DecoUpdatePayload.TYPE,
                DecoUpdatePayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenArmorerPayload.TYPE,
                OpenArmorerPayload.STREAM_CODEC,
                (payload, context) -> { });
        }
    }
}
