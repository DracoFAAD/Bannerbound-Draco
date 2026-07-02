package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Settlement-aware respawn fallback: if a player respawns with no bed/anchor set
 * <i>and</i> they belong to a settlement with a valid town hall (the lit campfire), warp them
 * to that campfire instead of dropping them at world spawn.
 *
 * <p>Priority chain the user wants:
 * <ol>
 *   <li>Vanilla world spawn (default)</li>
 *   <li>Settlement's town hall campfire (this listener — when no bed exists)</li>
 *   <li>Bed / respawn anchor (vanilla, already overrides everything above)</li>
 * </ol>
 *
 * <p>Vanilla's bed/anchor logic runs first; this listener only fires after the player has been
 * placed and only intervenes when {@link ServerPlayer#getRespawnPosition()} is {@code null}
 * (i.e. no bed). End-return respawns are left alone — the player was sent to world spawn
 * intentionally by the End portal flow, not by a death.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class PlayerSpawnEvents {
    private PlayerSpawnEvents() {
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getRespawnPosition() != null) return; // has a bed/anchor — vanilla wins
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return;
        BlockPos thp = s.townHallPos();
        if (thp == null) return;

        // Land one block above the campfire so we don't suffocate the player inside the block.
        // The campfire's own walk-through volume + the +1 Y offset keep them safely on top.
        player.teleportTo(overworld, thp.getX() + 0.5, thp.getY() + 1.0, thp.getZ() + 0.5,
            java.util.Set.of(), player.getYRot(), player.getXRot());
    }
}
