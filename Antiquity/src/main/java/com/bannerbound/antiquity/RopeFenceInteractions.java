package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.RopeFenceActionPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Left-click QOL for rope ties (posts and gates alike). A tie host is only "locked" while it has ropes
 * (or is the host you're tying from): left-click then breaks one rope / cancels the tie; otherwise it
 * mines normally (an axe is faster but not required). The action is predicted on the client (cancel
 * the left-click, send {@link RopeFenceActionPayload}) and performed authoritatively in
 * {@link #serverHandle}. Creative is build mode and exempt — posts/gates delete freely there.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class RopeFenceInteractions {
    /** Max distance² the server will honour a rope action from (anti-cheat / sanity). */
    private static final double MAX_REACH_SQR = 64.0;
    /** Min ticks between honoured rope actions per player — stops creative hold-to-break re-firing. */
    private static final long ACTION_COOLDOWN = 5L;
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();

    private RopeFenceInteractions() {}

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        Player player = event.getEntity();
        if (player.getAbilities().instabuild) {
            return; // creative deletes posts/gates freely
        }
        BlockPos pos = event.getPos();
        RopeTieHost host = RopeTies.hostAt(level, pos);
        if (host == null) {
            return;
        }
        RopeAnchor pend = RopeTieState.get();
        boolean pendingHere = pend != null && pend.pos().equals(pos);
        if (!pendingHere && !RopeTies.isConnectedAnySlot(host)) {
            return; // bare, un-tying → let normal mining break it
        }
        if (pendingHere) {
            RopeTieState.clear(); // responsive preview removal; server confirms
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new RopeFenceActionPayload(pos.immutable()));
    }

    /** Block destroying a tie host while it still has ropes — also covers creative insta-break. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player breaker = event.getPlayer();
        if (breaker != null && !breaker.getAbilities().instabuild
                && breaker.level().getBlockEntity(event.getPos()) instanceof RopeTieHost host
                && RopeTies.isConnectedAnySlot(host)) {
            event.setCanceled(true); // peel its ropes off first
        }
    }

    /** A player who logs out mid-tie shouldn't leave their anchor stuck showing the roped model. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RopeTies.clearPending(player);
        }
    }

    /** Server-authoritative rope action for a left-click relayed by {@link RopeFenceActionPayload}. */
    public static void serverHandle(ServerPlayer player, BlockPos pos) {
        Level level = player.level();
        if (player.blockPosition().distSqr(pos) > MAX_REACH_SQR) {
            return;
        }
        RopeTieHost host = RopeTies.hostAt(level, pos);
        if (host == null) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_ACTION.get(player.getUUID());
        if (last != null && now - last < ACTION_COOLDOWN) {
            return;
        }
        LAST_ACTION.put(player.getUUID(), now);
        if (RopeTies.isPendingAnchorAt(player, pos)) {
            RopeTies.clearPending(player);
            player.displayClientMessage(Component.translatable("message.bannerboundantiquity.rope_fence.cancel"), true);
        } else if (RopeTies.isConnectedAnySlot(host)) {
            RopeTies.breakOne(level, pos, host);
        }
    }
}
