package com.bannerbound.core.celestial;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Exact pure-Java reproduction of vanilla's star field — {@code LevelRenderer.drawStars}
 * (1.21.1, NeoForge 21.1.230 sources): {@code RandomSource.create(10842L)}, 1500 samples,
 * cube-rejection to the unit shell. Vanilla's {@code LegacyRandomSource} is bit-identical
 * to {@link java.util.Random}, so these directions are EXACTLY where vanilla renders its
 * stars — which makes every vanilla star a pickable "common" for constellation drawing
 * (FAITH_PLAN Part 3) without touching vanilla rendering.
 * <p>
 * CRITICAL replication detail: the per-star rotation {@code nextDouble()} is consumed
 * ONLY for ACCEPTED samples (vanilla rolls it after the length check passes), so the RNG
 * stream — and therefore every later star — depends on the accept/reject sequence.
 * A star's stable identity is its accepted-order index (~780 survive of 1500).
 * <p>
 * PINNED to the targeted MC version: if a future MC update changes drawStars, this copy
 * must be re-verified against the new decompile before upgrading.
 */
public final class VanillaStars {

    /** One vanilla star: unit direction (celestial frame) + vanilla's quad half-size. */
    public static final class CommonStar {
        public final float dx, dy, dz;
        public final float size;

        CommonStar(float dx, float dy, float dz, float size) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.size = size;
        }
    }

    private static final List<CommonStar> STARS = generate();

    private VanillaStars() {
    }

    public static List<CommonStar> all() {
        return STARS;
    }

    public static int count() {
        return STARS.size();
    }

    public static CommonStar get(int index) {
        return STARS.get(index);
    }

    private static List<CommonStar> generate() {
        List<CommonStar> out = new ArrayList<>(800);
        Random random = new Random(10842L);
        for (int j = 0; j < 1500; j++) {
            float f1 = random.nextFloat() * 2.0F - 1.0F;
            float f2 = random.nextFloat() * 2.0F - 1.0F;
            float f3 = random.nextFloat() * 2.0F - 1.0F;
            float f4 = 0.15F + random.nextFloat() * 0.1F;
            float f5 = f1 * f1 + f2 * f2 + f3 * f3; // Mth.lengthSquared, float math
            if (!(f5 <= 0.010000001F) && !(f5 >= 1.0F)) {
                random.nextDouble(); // vanilla's quad rotation — consumed only on accept
                float len = (float) Math.sqrt(f5);
                out.add(new CommonStar(f1 / len, f2 / len, f3 / len, f4));
            }
        }
        return List.copyOf(out);
    }
}
