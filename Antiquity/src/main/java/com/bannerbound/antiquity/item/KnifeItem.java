package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.CraftingStoneBlock;
import com.bannerbound.core.codex.CodexManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Base for primitive knives — a durable cutting edge that doubles as a light weapon. Knives are
 * in {@code #bannerboundantiquity:cutting_tools}, so they harvest plant fibers from grass and
 * sticks from leaves (see {@code AntiquityEvents.onCuttingHarvest}); that path damages them.
 * They're plain {@link Item}s (not tiered diggers), so durability loss on attack comes from the
 * {@link #hurtEnemy} override here.
 *
 * <p>Every knife (flint, bone, …) can carve a Crafting Stone out of cobblestone / sandstone /
 * red_sandstone via {@link #useOn} — the resulting stone keeps the source rock's look.
 */
public class KnifeItem extends Item {
    public KnifeItem(Properties properties, int durability, double attackDamage, double attackSpeed) {
        super(properties.durability(durability).attributes(knifeAttributes(attackDamage, attackSpeed)));
    }

    /** Sword-style attack attributes. {@code damage}/{@code speed} are the TOTAL displayed values;
     *  the player's base (1 dmg, 4 speed) is subtracted to get the modifier. */
    public static ItemAttributeModifiers knifeAttributes(double damage, double speed) {
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage - 1.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID, speed - 4.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    /** Carve a Crafting Stone out of cobblestone / sandstone / red_sandstone — replaced in place,
     *  oriented to face the player, skinned to match the source rock, with a burst of that rock's
     *  particles. The knife takes 1 durability. */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        CraftingStoneBlock.Material material = materialFor(state);
        if (material == null) {
            return InteractionResult.PASS;
        }
        Player player = ctx.getPlayer();
        if (!level.isClientSide) {
            Direction facing = player != null ? player.getDirection().getOpposite() : Direction.NORTH;
            level.setBlock(pos, BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing)
                .setValue(CraftingStoneBlock.MATERIAL, material), Block.UPDATE_ALL);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 14, 0.25, 0.15, 0.25, 0.02);
            }
            if (player != null) {
                ctx.getItemInHand().hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            if (player instanceof ServerPlayer serverPlayer) {
                CodexManager.onBlockFormed(serverPlayer, BannerboundAntiquity.MODID + ":crafting_stone");
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The crafting-stone variant a given rock carves into, or {@code null} if it isn't carveable. */
    public static CraftingStoneBlock.Material materialFor(BlockState state) {
        if (state.is(Blocks.COBBLESTONE)) return CraftingStoneBlock.Material.STONE;
        if (state.is(Blocks.SANDSTONE)) return CraftingStoneBlock.Material.SANDSTONE;
        if (state.is(Blocks.RED_SANDSTONE)) return CraftingStoneBlock.Material.RED_SANDSTONE;
        return null;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }
}
