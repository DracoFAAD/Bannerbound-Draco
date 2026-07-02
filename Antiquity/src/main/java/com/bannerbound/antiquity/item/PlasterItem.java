package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.deco.FaceDeco;
import com.bannerbound.antiquity.deco.FaceDecorations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plaster — applied as a face <i>layer</i> (not a block): right-click a solid block face to coat it
 * (consumes one plaster), sneak-right-click to strip the coat. The coat is stored per-face in the
 * chunk decoration attachment and drawn flush onto the face, so the adjacent cell stays free. Plaster
 * is the lower layer; trim (paint brush) draws on top and is independent. Gameplay effects (appeal +
 * blast/break resistance) read the same store. See {@link FaceDecorations}.
 */
public class PlasterItem extends Item {
    public PlasterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        Player player = context.getPlayer();

        BlockState clicked = level.getBlockState(pos);
        if (!clicked.isFaceSturdy(level, pos, face)) {
            return InteractionResult.PASS; // only full, solid faces take plaster
        }
        boolean strip = player != null && player.isSecondaryUseActive();

        if (level instanceof ServerLevel server) {
            FaceDeco cur = FaceDecorations.get(server, pos, face);
            if (strip) {
                if (!cur.plaster()) {
                    return InteractionResult.PASS;
                }
                FaceDecorations.set(server, pos, face, cur.withPlaster(false));
                server.playSound(null, pos, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F);
            } else {
                if (cur.plaster()) {
                    return InteractionResult.PASS; // already coated
                }
                FaceDecorations.set(server, pos, face, cur.withPlaster(true));
                if (player == null || !player.getAbilities().instabuild) {
                    context.getItemInHand().shrink(1);
                }
                server.playSound(null, pos, SoundEvents.MUD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
