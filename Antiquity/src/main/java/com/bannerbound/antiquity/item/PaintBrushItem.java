package com.bannerbound.antiquity.item;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.TrimShape;
import com.bannerbound.antiquity.deco.FaceDeco;
import com.bannerbound.antiquity.deco.FaceDecorations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Paint brush — the trim tool. It carries a selected trim shape (the {@link
 * BannerboundAntiquity#TRIM_BRUSH_SHAPE} component): index {@code 0} = "None" (remove), {@code 1..9} =
 * the nine {@link TrimShape}s.
 *
 * <ul>
 *   <li><b>Sneak + right-click</b> (block or air) cycles the selected shape, incl. "None"; shown on
 *       the action bar.</li>
 *   <li><b>Right-click a block face</b> stamps the selected shape onto that face as the upper
 *       decoration layer (above any plaster) — coloured by the dye held in the <b>off-hand</b> (white
 *       = unpainted greyscale). On "None" it strips the trim from that face (plaster, if any, stays).
 *       The dye is a palette selector, not consumed.</li>
 * </ul>
 * The trim is per-face decoration data (see {@link FaceDecorations}), so the adjacent cell stays free.
 */
public class PaintBrushItem extends Item {
    private static final int NONE = 0;

    public PaintBrushItem(Properties properties) {
        super(properties);
    }

    private static int selection(ItemStack stack) {
        return stack.getOrDefault(BannerboundAntiquity.TRIM_BRUSH_SHAPE.get(), 1); // default = first shape
    }

    private static Component selectionName(int sel) {
        return sel == NONE
            ? Component.translatable("message.bannerboundantiquity.paint_brush.shape.none")
            : Component.translatable("message.bannerboundantiquity.paint_brush.shape."
                + TrimShape.ALL[sel - 1].getSerializedName());
    }

    private InteractionResult cycle(Level level, Player player, ItemStack stack) {
        if (!level.isClientSide) {
            int next = (selection(stack) + 1) % (TrimShape.ALL.length + 1);
            stack.set(BannerboundAntiquity.TRIM_BRUSH_SHAPE.get(), next);
            player.displayClientMessage(Component.translatable(
                "message.bannerboundantiquity.paint_brush.selected", selectionName(next)), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) { // sneak-click in air cycles the shape
            return new InteractionResultHolder<>(cycle(level, player, stack), stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (player != null && player.isSecondaryUseActive()) {
            return cycle(level, player, stack); // sneak on a block also cycles (don't paint)
        }

        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockState clicked = level.getBlockState(pos);
        if (!clicked.isFaceSturdy(level, pos, face)) {
            return InteractionResult.PASS; // only full, solid faces take trim
        }
        int sel = selection(stack);

        if (level instanceof ServerLevel server) {
            FaceDeco cur = FaceDecorations.get(server, pos, face);
            if (sel == NONE) {
                if (!cur.hasTrim()) {
                    return InteractionResult.PASS;
                }
                FaceDecorations.set(server, pos, face, cur.withoutTrim());
                server.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 0.7F, 0.9F);
            } else {
                DyeColor color = dyeInOffhand(player);
                FaceDeco next = cur.withTrim(TrimShape.ALL[sel - 1], color);
                if (next.equals(cur)) {
                    return InteractionResult.PASS;
                }
                FaceDecorations.set(server, pos, face, next);
                server.playSound(null, pos, SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS, 0.8F, 1.1F);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static DyeColor dyeInOffhand(Player player) {
        if (player != null && player.getOffhandItem().getItem() instanceof DyeItem dye) {
            return dye.getDyeColor();
        }
        return DyeColor.WHITE; // unpainted greyscale base
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(
            "message.bannerboundantiquity.paint_brush.selected", selectionName(selection(stack)))
            .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
