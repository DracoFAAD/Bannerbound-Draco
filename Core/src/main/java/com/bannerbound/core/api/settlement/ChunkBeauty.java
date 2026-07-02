package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

/**
 * The nine beauty tiers a chunk can fall into, derived from its collective block-appeal score.
 * Each tier owns an inclusive score band, a culture-per-second contribution applied while the
 * chunk is <i>claimed</i>, and a translation key.
 *
 * <p>This enum is deliberately free of client-only imports so it can be used on both the server
 * (culture math) and the client (expand-territory tooltip), the latter reached via
 * {@link #byNetworkId(int)} from a packet.
 *
 * <p>Score bands (the design spec's +2 overlap is resolved as bland 0–1 / pleasant 2–5):
 * {@code ≤-15} atrocious · {@code -14..-8} repulsive · {@code -7..-4} disgusting ·
 * {@code -3..-1} unappealing · {@code 0..1} bland · {@code 2..5} pleasant · {@code 6..9}
 * attractive · {@code 10..14} stunning · {@code ≥15} breathtaking.
 */
@ApiStatus.Internal
public enum ChunkBeauty {
    ATROCIOUS(Integer.MIN_VALUE, -15, -1.00, "bannerbound.beauty.atrocious"),
    REPULSIVE(-14, -8, -0.50, "bannerbound.beauty.repulsive"),
    DISGUSTING(-7, -4, -0.25, "bannerbound.beauty.disgusting"),
    UNAPPEALING(-3, -1, -0.10, "bannerbound.beauty.unappealing"),
    BLAND(0, 1, 0.00, "bannerbound.beauty.bland"),
    PLEASANT(2, 5, 0.10, "bannerbound.beauty.pleasant"),
    ATTRACTIVE(6, 9, 0.25, "bannerbound.beauty.attractive"),
    STUNNING(10, 14, 0.50, "bannerbound.beauty.stunning"),
    BREATHTAKING(15, Integer.MAX_VALUE, 1.00, "bannerbound.beauty.breathtaking");

    private final int minScore;
    private final int maxScore;
    private final double culturePerSecond;
    private final String langKey;

    ChunkBeauty(int minScore, int maxScore, double culturePerSecond, String langKey) {
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.culturePerSecond = culturePerSecond;
        this.langKey = langKey;
    }

    /** Maps a collective appeal score onto its tier. The score is rounded to the nearest int
     *  before banding so a chunk at 1.6 reads as pleasant, not bland. */
    public static ChunkBeauty fromScore(double score) {
        int s = (int) Math.round(score);
        for (ChunkBeauty b : values()) {
            if (s >= b.minScore && s <= b.maxScore) return b;
        }
        return BLAND; // unreachable — the bands cover the whole int range
    }

    /** Culture per second a claimed chunk of this tier adds to (or subtracts from) its settlement. */
    public double culturePerSecond() { return culturePerSecond; }

    /** Tier index on the −4..+4 scale: ATROCIOUS = −4, BLAND = 0, BREATHTAKING = +4. Via the
     *  adjacency layer each chunk lends this many score points to each of its neighbours. */
    public int tierIndex() { return ordinal() - 4; }

    public String langKey() { return langKey; }

    /** Compact id for packets. */
    public byte networkId() { return (byte) ordinal(); }

    public static ChunkBeauty byNetworkId(int id) {
        ChunkBeauty[] v = values();
        return (id >= 0 && id < v.length) ? v[id] : BLAND;
    }
}
