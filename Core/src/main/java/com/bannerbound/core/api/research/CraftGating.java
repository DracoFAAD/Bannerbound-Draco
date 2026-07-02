package com.bannerbound.core.api.research;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Gates production at placed workstations (crafting stone, mortar &amp; pestle, bloomery): a station
 * can only produce an item its owning civ recognizes. The owner is the settlement that has the
 * station's chunk claimed — a block entity has no inherent player, and passive stations like the
 * bloomery run with nobody present, so territory ownership is the natural "whose knowledge applies"
 * answer. (The portable vanilla crafting grid is gated against the <i>player's</i> settlement
 * instead — see {@code CraftingMenuMixin}.)
 * <p>
 * Mirrors the drop side: knowledge = global starting items + the owning settlement's research
 * unlocks; no owner (unclaimed land) → starting items only. Always permissive on the client and
 * when there's no server context — the server is authoritative.
 */
public final class CraftGating {
    private CraftGating() {
    }

    /** True if the civ that owns {@code pos}'s chunk may produce {@code item} there. */
    public static boolean canProduceAt(@Nullable Level level, BlockPos pos, Item item) {
        if (level == null || level.isClientSide()) {
            return true;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return true;
        }
        Settlement owner;
        try {
            owner = SettlementData.get(server.overworld()).getByChunk(new ChunkPos(pos).toLong());
        } catch (Exception ex) {
            owner = null;
        }
        return ItemKnowledge.isKnown(owner, item);
    }
}
