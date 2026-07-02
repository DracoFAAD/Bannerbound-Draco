package com.bannerbound.antiquity.deco;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The Antiquity appeal contributor: plaster/trim face coatings add beauty even though they are not
 * blocks. Registered into Core's {@code AppealContributors} at setup, so a home/workshop's appeal
 * scan adds these per decorated position. Values are per face (a wall plastered + trimmed on its one
 * visible face adds {@code PLASTER + TRIM}).
 */
public final class DecoAppeal {
    public static final double PLASTER_FACE_APPEAL = 0.12;
    public static final double TRIM_FACE_APPEAL = 0.10;

    private DecoAppeal() {}

    public static double contribute(ServerLevel level, BlockPos pos) {
        ChunkDecorations cd = FaceDecorations.of(level.getChunkAt(pos));
        return cd.isEmpty() ? 0.0 : cd.appealAt(pos, PLASTER_FACE_APPEAL, TRIM_FACE_APPEAL);
    }
}
