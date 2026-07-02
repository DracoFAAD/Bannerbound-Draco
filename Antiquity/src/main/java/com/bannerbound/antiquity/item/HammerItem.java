package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;

/**
 * Smithing hammers — the tool that drives the cold-hammer minigame at the stone anvil
 * (METALWORKING_PLAN.md Part 3). A hammer is not a digging tool; it has durability and a
 * <b>material rank</b> that gates how good a casting it can finish: a workpiece can only reach its
 * top quality when the hammer's rank is at least one step below the workpiece's own rank (a stone
 * hammer works copper/tin fully, but bronze needs a copper/tin hammer or it caps at Standard).
 *
 * <p>The minigame trigger ({@code useOn} the anvil) and the rank gate live with the minigame; this
 * class only carries the identity, durability, and a light blunt-weapon profile.
 *
 * @see #rank()
 */
public class HammerItem extends Item {
    private final Tier tier;
    private final String metalId;

    public HammerItem(Properties properties, Tier tier, String metalId) {
        super(properties.durability(tier.getUses()).attributes(hammerAttributes(tier)));
        this.tier = tier;
        this.metalId = metalId;
    }

    /** Heavy and slow — a hammer hits hard but is no sword. */
    private static ItemAttributeModifiers hammerAttributes(Tier tier) {
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 3.0 + tier.getAttackDamageBonus(),
                    AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID, -3.2, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    /** Right-click a stone block with a hammer to forge a Stone Anvil in place (oriented to the player). */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState clicked = level.getBlockState(pos);
        if (!clicked.is(Blocks.STONE)) {
            return InteractionResult.PASS;
        }
        Player player = ctx.getPlayer();
        if (!level.isClientSide) {
            Direction facing = player != null ? player.getDirection().getOpposite() : Direction.NORTH;
            level.setBlock(pos, BannerboundAntiquity.STONE_ANVIL.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.7F, 1.1F);
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, clicked),
                    pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 14, 0.25, 0.15, 0.25, 0.02);
            }
            if (player != null) {
                ctx.getItemInHand().hurtAndBreak(2, player, EquipmentSlot.MAINHAND);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Material rank used by the quality gate (data-driven): stone 0, copper/tin 1, bronze 2, …. */
    public int rank() {
        return com.bannerbound.antiquity.metalworking.MetalworkingData.rank(metalId);
    }

    public Tier tier() {
        return tier;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }
}
