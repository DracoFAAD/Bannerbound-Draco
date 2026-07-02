package com.bannerbound.core.social;

import net.minecraft.util.RandomSource;

/**
 * One of the conversation topics citizens chat about. Each maps onto an icon that gets shown in
 * the bubble overhead. Two flavours:
 * <ul>
 *   <li><b>Static</b> ({@link #CULTURE}, {@link #FOOD}, {@link #SCIENCE}) — the icon is fixed per
 *       era; the same glyph renders for every citizen who picked the topic.</li>
 *   <li><b>Dynamic</b> ({@link #HAPPINESS}, {@link #JOB}) — the icon reflects per-citizen state
 *       at the moment the bubble is shown. Happiness shows a face matching the citizen's mood
 *       bucket; Job shows the workstation's hotbar block icon (or no icon if unemployed). Both
 *       citizens may pick the same topic and still display different icons — the topic is what
 *       they "talk about", the icon is what they personally have to say about it.</li>
 * </ul>
 *
 * <p>The {@code DATA_BUBBLE} synched-data slot on the citizen carries a <b>packed int</b>:
 * <pre>
 *   packed = bubbleId (low 8 bits) | (subType &lt;&lt; 8) (high 24 bits)
 * </pre>
 * {@code bubbleId} encodes which topic was rolled (0 = no bubble). {@code subType} carries the
 * per-citizen state the dynamic icons need — happiness bucket for HAPPINESS, workstation type
 * ordinal for JOB. Static topics always pack subType = 0. Keep the bubbleId integers stable;
 * clients decode by id, not enum ordinal.
 */
public enum ConversationTopic {
    CULTURE(1),
    FOOD(2),
    SCIENCE(3),
    /** Per-citizen happiness bucket. Both citizens rolling HAPPINESS counts as a match (they
     *  share the topic) even if their happiness levels differ — the bubble icons will differ
     *  visually but the match count still increments. */
    HAPPINESS(4),
    /** Citizen's current job. Match works the same way: any two citizens rolling JOB match,
     *  whether they share the same workstation, have different jobs, or are both unemployed. */
    JOB(5);

    private static final ConversationTopic[] BY_ID = new ConversationTopic[]{
        null, CULTURE, FOOD, SCIENCE, HAPPINESS, JOB
    };
    private final int bubbleId;

    ConversationTopic(int bubbleId) {
        this.bubbleId = bubbleId;
    }

    public int bubbleId() {
        return bubbleId;
    }

    /** Pack this topic + a per-citizen subType into the integer that ships on {@code DATA_BUBBLE}.
     *  Static topics should pass {@code subType = 0}. The encoding leaves room for {@code 0xFFFFFF}
     *  distinct subType values, far more than any topic will ever need. */
    public int packBubbleId(int subType) {
        return bubbleId | ((subType & 0xFFFFFF) << 8);
    }

    /** Reverse lookup. Returns {@code null} for 0 (the "no bubble" sentinel) or out-of-range ids. */
    public static ConversationTopic fromBubbleId(int id) {
        int low = id & 0xFF;
        if (low < 1 || low >= BY_ID.length) return null;
        return BY_ID[low];
    }

    /** Extracts the per-citizen subType from a packed bubble id. Always 0 for static topics. */
    public static int subTypeFromPackedId(int packed) {
        return (packed >>> 8) & 0xFFFFFF;
    }

    /** Uniform random pick — three topics per conversation per citizen, rolled at construction. */
    public static ConversationTopic random(RandomSource rng) {
        return random(rng, true);
    }

    /** Uniform random pick, optionally excluding the work topic ({@link #JOB}). During the
     *  Hearth stage citizens don't have organised jobs yet, so work talk shouldn't come up —
     *  callers pass {@code allowWork = settlement.isTribe()}. */
    public static ConversationTopic random(RandomSource rng, boolean allowWork) {
        if (allowWork) {
            ConversationTopic[] values = values();
            return values[rng.nextInt(values.length)];
        }
        // Pool without JOB: CULTURE, FOOD, SCIENCE, HAPPINESS.
        ConversationTopic[] nonWork = { CULTURE, FOOD, SCIENCE, HAPPINESS };
        return nonWork[rng.nextInt(nonWork.length)];
    }
}
