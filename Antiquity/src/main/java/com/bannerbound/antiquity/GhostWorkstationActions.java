package com.bannerbound.antiquity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Server side of the ghost-preview clicks ({@link GhostActionPayload}): browse arrows cycle the
 * candidate recipe, clicking the ghost result pulls the missing ingredients straight from the
 * player's inventory. On the crafting stone a completed pull also crafts immediately (mirroring
 * {@code CraftingStoneBlock.tryCraft}); the fletching station only fills — the stretch minigame
 * stays mandatory, so the player still shift-clicks to start it.
 */
@ApiStatus.Internal
public final class GhostWorkstationActions {
    private GhostWorkstationActions() {}

    public static void serverHandle(ServerPlayer player, BlockPos pos, int action) {
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
        if (!(level.getBlockEntity(pos) instanceof GhostRecipeWorkstation ws)) return;
        // A citizen mid-craft owns the station (same rule as the block's own interactions).
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            player.displayClientMessage(Component.translatable("bannerbound.workshop.station_busy")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        // The pile may have changed since the client ray-tested. Act on either a live ghost preview
        // OR a solid exact-match result floating above the crafting stone (clicking it crafts).
        boolean hasGhost = !ws.getGhostResult().isEmpty();
        boolean hasResult = !ws.getResult().isEmpty();
        if (!hasGhost && !hasResult) return;
        switch (action) {
            case GhostActionPayload.CYCLE_LEFT, GhostActionPayload.CYCLE_RIGHT -> {
                if (!hasGhost) break;   // browse arrows only exist while a ghost preview is showing
                ws.cycleGhost(action == GhostActionPayload.CYCLE_LEFT ? -1 : 1);
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            case GhostActionPayload.FILL -> {
                // The carpenter's table reuses the ghost picker but FILL means "queue one unit of the
                // selection into the build list" (it has no missing-ingredient pull).
                if (ws instanceof com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity table) {
                    if (table.addSelected()) {
                        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.BLOCKS, 0.5F, 1.2F);
                    } else {
                        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(),
                            SoundSource.BLOCKS, 0.4F, 0.7F);
                    }
                } else if (ws instanceof com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity bench) {
                    // The mason's bench reuses the ghost picker: FILL queues one unit of the selection.
                    if (bench.addSelected()) {
                        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.BLOCKS, 0.5F, 1.2F);
                    } else {
                        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(),
                            SoundSource.BLOCKS, 0.4F, 0.7F);
                    }
                } else if (ws instanceof com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity pottery
                        && !pottery.getResult().isEmpty()) {
                    pottery.lockGhost();
                    level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.BLOCKS, 0.5F, 1.2F);
                } else if (hasGhost) {
                    // Partial pile with a ghost preview → pull the missing ingredients (and, on the
                    // crafting stone, craft if that completes the chosen recipe).
                    fill(player, level, pos, ws);
                } else if (ws instanceof CraftingStoneBlockEntity be && !be.getResult().isEmpty()) {
                    // Exact recipe already on the stone, no ghost — clicking the floating result
                    // crafts it (the no-shift craft the player expects from the preview).
                    ItemStack out = be.craft();
                    Block.popResource(level, pos.above(), out);
                    level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                        SoundSource.BLOCKS, 0.8F, 1.2F);
                }
            }
            default -> { }
        }
    }

    /** Pulls as many of the missing ingredients as the player has into the station, then — on the
     *  crafting stone — crafts immediately if that completed the pile. */
    private static void fill(ServerPlayer player, Level level, BlockPos pos, GhostRecipeWorkstation ws) {
        // Clicking the recipe is an explicit choice — lock it so later inserts can't switch it.
        ws.lockGhost();
        // Remember WHICH recipe is being filled: a partial pull can complete a different, smaller
        // recipe by accident (2 sticks = fire sticks while building a bone axe) and that must not
        // be auto-crafted.
        ItemStack target = ws.getGhostResult().copy();
        Direction from = player.getDirection().getOpposite();
        boolean any = false;
        // Snapshot — each insertOne() recomputes the ghost and shrinks this list under us. The
        // sticky selection keeps the same recipe chosen while its ingredients land.
        for (ItemStack miss : List.copyOf(ws.getGhostIngredients())) {
            int want = miss.getCount();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && want > 0; slot++) {
                ItemStack s = player.getInventory().getItem(slot);
                while (want > 0 && !s.isEmpty() && s.is(miss.getItem())) {
                    if (!ws.insertOne(s, from)) {
                        want = 0;
                        break;
                    }
                    if (!player.hasInfiniteMaterials()) s.shrink(1);
                    want--;
                    any = true;
                }
            }
        }
        if (!any) {
            player.displayClientMessage(Component.translatable("bannerboundantiquity.ghost_fill.missing")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.1F);
        if (ws instanceof CraftingStoneBlockEntity be && !be.getResult().isEmpty()
                && be.getResult().is(target.getItem())) {
            ItemStack out = be.craft();
            Block.popResource(level, pos.above(), out);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                SoundSource.BLOCKS, 0.8F, 1.2F);
        }
    }
}
