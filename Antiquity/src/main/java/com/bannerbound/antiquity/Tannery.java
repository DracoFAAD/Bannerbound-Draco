package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.TanningRackBlock;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.bannerbound.antiquity.item.HideQuality;
import com.bannerbound.antiquity.network.OpenTanningPayload;
import com.bannerbound.antiquity.network.TanningActionPayload;
import com.bannerbound.core.api.workshop.WorkBlockLocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-authoritative driver for the tanning-rack scrape minigame (mirrors {@code Pottery}). */
@ApiStatus.Internal
public final class Tannery {
    private Tannery() {
    }

    private static final Map<UUID, BlockPos> SESSIONS = new HashMap<>();

    /** Open the scrape minigame for a raw hide on the rack. */
    public static void startSession(ServerPlayer player, BlockPos pos) {
        if (!(player.serverLevel().getBlockEntity(pos) instanceof TanningRackBlockEntity be) || !be.isRaw()) {
            return;
        }
        SESSIONS.put(player.getUUID(), pos.immutable());
        WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenTanningPayload(pos, TanningRackBlock.SCRAPE_SWIPES));
    }

    public static void handleAction(ServerPlayer player, TanningActionPayload payload) {
        BlockPos pos = SESSIONS.get(player.getUUID());
        if (pos == null || !pos.equals(payload.pos())) return;
        ServerLevel level = player.serverLevel();
        if (payload.action() == TanningActionPayload.COMPLETE) {
            complete(level, pos);
        }
        WorkBlockLocks.unlock(pos, player.getUUID());
        SESSIONS.remove(player.getUUID());
    }

    private static void complete(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof TanningRackBlockEntity be) || !be.isRaw()) return;
        HideQuality quality = HideQuality.of(be.getRawHide());
        be.clear();
        ItemStack scraped = new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get(), quality.scrapedYield());
        Block.popResource(level, pos.above(), scraped);
        level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        BlockPos pos = SESSIONS.remove(player.getUUID());
        if (pos != null) {
            WorkBlockLocks.unlock(pos, player.getUUID());
        }
    }

    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(p -> p.equals(pos));
        WorkBlockLocks.forceUnlock(pos);
    }
}
