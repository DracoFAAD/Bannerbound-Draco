package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Detects and forms the Kiln. Whenever a clayed cobblestone is created (by claying — see
 * {@code AntiquityEvents} — or placed directly), this checks the up-to-eight 2×2×2 cubes that
 * include that block; the first cube made entirely of clayed cobblestone is replaced with a Kiln.
 * The min-corner of the cube becomes the controller ({@code PART == 0}); the other seven cells
 * encode their offset in {@link KilnBlock#PART}.
 */
@ApiStatus.Internal
public final class KilnFormation {
    private KilnFormation() {
    }

    /**
     * Tries to form a kiln using {@code changedPos} as one of its cells. Returns true (and forms it)
     * on the first all-clayed 2×2×2 cube found. Server-side only.
     */
    public static boolean tryForm(Level level, BlockPos changedPos, @Nullable Player player) {
        if (level.isClientSide) {
            return false;
        }
        Block clayed = BannerboundAntiquity.CLAYED_COBBLESTONE.get();
        if (!level.getBlockState(changedPos).is(clayed)) {
            return false;
        }
        // changedPos can sit at any of the 8 corners of a candidate cube — try each min-corner.
        for (int ox = 0; ox <= 1; ox++) {
            for (int oy = 0; oy <= 1; oy++) {
                for (int oz = 0; oz <= 1; oz++) {
                    BlockPos corner = changedPos.offset(-ox, -oy, -oz);
                    if (isFullCube(level, corner, clayed)) {
                        form(level, corner, player);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFullCube(Level level, BlockPos corner, Block clayed) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    if (!level.getBlockState(corner.offset(dx, dy, dz)).is(clayed)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void form(Level level, BlockPos corner, @Nullable Player player) {
        Direction facing = player != null ? player.getDirection().getOpposite() : Direction.NORTH;
        BlockState base = BannerboundAntiquity.KILN.get().defaultBlockState()
            .setValue(KilnBlock.FACING, facing);
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    int part = dx * 4 + dy * 2 + dz;
                    level.setBlock(corner.offset(dx, dy, dz),
                        base.setValue(KilnBlock.PART, part), Block.UPDATE_ALL);
                }
            }
        }
        // A settling "thunk" plus a brief poof of dust + smoke as the structure knits together.
        level.playSound(null, corner, SoundType.STONE.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        level.playSound(null, corner, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.9F, 0.7F);
        if (level instanceof ServerLevel server) {
            double cx = corner.getX() + 1.0, cy = corner.getY(), cz = corner.getZ() + 1.0;
            server.sendParticles(ParticleTypes.POOF, cx, cy + 0.8, cz, 24, 0.7, 0.7, 0.7, 0.02);
            server.sendParticles(ParticleTypes.LARGE_SMOKE, cx, cy + 1.6, cz, 12, 0.5, 0.4, 0.5, 0.02);
        }
    }
}
