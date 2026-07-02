package com.bannerbound.core.api.research;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Gates a player <i>using</i> a placed block (opening its terminal/menu, operating it) behind the
 * owning civ's research. A block can be built or left over from before its tech was learned — this
 * check is the run-time counterpart that keeps it inert until the settlement that owns its chunk has
 * actually researched it.
 * <p>
 * "Researched" here means the same thing as everywhere else: the owning settlement recognizes the
 * block's item ({@link ItemKnowledge#isKnown}), so adding the block to a research node's
 * {@code unlocks.items} is all it takes to gate both its production <i>and</i> its use. The
 * knowledge lookup is shared with {@link CraftGating}, so the inventory question-mark, the craft
 * gate and the use gate never disagree.
 * <p>
 * Always permissive on the client and when there's no server context — the server is authoritative.
 */
public final class BlockUseGate {
    private BlockUseGate() {
    }

    /** True if the civ that owns {@code pos}'s chunk has researched (recognizes) {@code blockItem}. */
    public static boolean isUnlocked(Level level, BlockPos pos, Item blockItem) {
        return CraftGating.canProduceAt(level, pos, blockItem);
    }

    /**
     * Server-side gate for {@code player} interacting with a research-locked block. Returns
     * {@code true} when use is allowed; otherwise flashes {@code lockedMsgKey} on the action bar
     * (red) and returns {@code false}, so callers can simply {@code if (!checkUse(...)) return;}.
     */
    public static boolean checkUse(ServerPlayer player, Level level, BlockPos pos, Item blockItem,
                                   String lockedMsgKey) {
        if (isUnlocked(level, pos, blockItem)) {
            return true;
        }
        player.displayClientMessage(Component.translatable(lockedMsgKey).withStyle(ChatFormatting.RED), true);
        return false;
    }
}
