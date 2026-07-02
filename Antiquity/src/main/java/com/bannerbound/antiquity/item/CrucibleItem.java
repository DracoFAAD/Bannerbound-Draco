package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The crucible item — a {@link BlockItem} (places the crucible block) that also <b>pours</b> when you
 * hold right-click on a stone anvil: the anvil starts the use, and each tick {@link #onUseTick} drains
 * a little molten metal into the mold the player is looking at, so you watch it fill gradually.
 */
public class CrucibleItem extends BlockItem {
    /** Millibuckets transferred per tick while pouring (≈ a 200mB mould in ~1.5s). */
    private static final int POUR_RATE = 8;

    public CrucibleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000; // effectively "until released"
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /** How often (ticks) a bare-handed carry burns / a gripped carry wears the tongs — once a second. */
    private static final int BURN_INTERVAL = 20;
    /** Contact damage per interval when carrying a molten crucible bare-handed. */
    private static final float BURN_DAMAGE = 3.0F;

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity,
                              int slot, boolean selected) {
        CrucibleContents c = stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        boolean molten = c != null && c.molten() && c.totalMb() > 0;

        // Client: a held, molten crucible radiates a faint wisp of heat so you can tell it's still liquid.
        if (level.isClientSide) {
            if (!selected || !molten || level.random.nextInt(5) != 0) return;
            double x = entity.getX() + (level.random.nextDouble() - 0.5) * 0.3;
            double y = entity.getY() + entity.getBbHeight() * 0.55;
            double z = entity.getZ() + (level.random.nextDouble() - 0.5) * 0.3;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, x, y, z, 0.0, 0.02, 0.0);
            return;
        }

        // Server: a molten crucible is a ~1000°C clay pot. Carry one in a bare hand and it sets you
        // alight and burns you each second; with tongs in the OTHER hand the heat wears the tongs
        // instead (METALWORKING_PLAN.md). Only an actually-held crucible burns — one stowed away doesn't.
        if (!molten || !(entity instanceof net.minecraft.world.entity.player.Player player)) return;
        boolean inMain = player.getMainHandItem() == stack;
        boolean inOff = player.getOffhandItem() == stack;
        if (!inMain && !inOff) return;
        if (level.getGameTime() % BURN_INTERVAL != 0) return;

        ItemStack other = inMain ? player.getOffhandItem() : player.getMainHandItem();
        if (other.getItem() instanceof com.bannerbound.antiquity.item.TongsItem) {
            // Gripped safely — the tongs take the heat instead of the carrier.
            net.minecraft.world.entity.EquipmentSlot otherSlot =
                inMain ? net.minecraft.world.entity.EquipmentSlot.OFFHAND
                       : net.minecraft.world.entity.EquipmentSlot.MAINHAND;
            other.hurtAndBreak(1, player, otherSlot);
        } else if (!player.isCreative()) {
            // Bare-handed: catch fire and take a contact burn.
            player.igniteForSeconds(3.0F);
            player.hurt(level.damageSources().hotFloor(), BURN_DAMAGE);
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    player.getX(), player.getY() + player.getBbHeight() * 0.6, player.getZ(),
                    6, 0.15, 0.15, 0.15, 0.01);
            }
        }
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (level.isClientSide) return;
        CrucibleContents c = stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        if (c == null || !c.molten() || c.totalMb() <= 0) {
            entity.stopUsingItem();
            return;
        }
        HitResult hit = entity.pick(5.0, 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK
                || !(level.getBlockEntity(bhr.getBlockPos()) instanceof StoneAnvilBlockEntity anvil)
                || !anvil.hasMold()) {
            entity.stopUsingItem();
            return;
        }
        int amount = anvil.pourInto(c.dominantMetal(), c.tintColor(), Math.min(POUR_RATE, c.totalMb()));
        if (amount <= 0) {
            entity.stopUsingItem();
            return;
        }
        CrucibleContents left = c.drain(amount);
        if (left.isEmpty()) {
            stack.remove(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        } else {
            stack.set(BannerboundAntiquity.CRUCIBLE_CONTENTS.get(), left);
        }
        spawnPourStream(level, bhr.getBlockPos(), c.tintColor());
        if (level.getGameTime() % 4 == 0) {
            level.playSound(null, bhr.getBlockPos(), SoundEvents.BUCKET_EMPTY_LAVA,
                SoundSource.BLOCKS, 0.3F, 1.6F);
        }
    }

    /** A glowing molten stream splashing into the mold — metal-tinted sparks + lava drips, broadcast
     *  to everyone nearby (server-spawned) so the pour reads as hot liquid metal. */
    private static void spawnPourStream(Level level, net.minecraft.core.BlockPos moldPos, int tint) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        double x = moldPos.getX() + 0.5, y = moldPos.getY() + 0.95, z = moldPos.getZ() + 0.5;
        var dust = new net.minecraft.core.particles.DustParticleOptions(
            new org.joml.Vector3f(((tint >> 16) & 0xFF) / 255f,
                ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f), 1.0F);
        sl.sendParticles(dust, x, y + 0.35, z, 3, 0.05, 0.12, 0.05, 0.0);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, x, y + 0.45, z,
            1, 0.04, 0.0, 0.04, 0.0);
        if (level.getGameTime() % 3 == 0) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,
                x, y + 0.1, z, 1, 0.1, 0.02, 0.1, 0.0);
        }
    }
}
