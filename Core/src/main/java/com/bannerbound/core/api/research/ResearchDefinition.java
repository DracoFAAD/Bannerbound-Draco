package com.bannerbound.core.api.research;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A node in the research tree. ID is the full ResourceLocation string (e.g. "bannerboundantiquity:knapping")
 * derived from the JSON file path. Cost is in science points; sciencePerSecond fills the bar.
 * {@code minAge} is the minimum settlement era required to research this — if the civ regresses
 * below it, the research becomes un-completed.
 *
 * Unlocks are split into three orthogonal lists:
 *   - items: ItemStack IDs the settlement now recognizes (additive to starting_items)
 *   - features: one-shot effects triggered on completion (e.g. bannerbound.advance_age:medieval,
 *     bannerbound.science_per_second_delta:0.5)
 *   - flags: persistent capability gates, true as long as the research is completed
 *     (e.g. bannerbound.allow_animal_breeding)
 *
 * {@code ponderScene} is an optional id of a Create-style Ponder scene that an expansion mod
 * (or a future Create-aware bridge) may register for this node. Core itself never resolves the
 * id — it just carries it to the client, which asks {@link ResearchPonderBridge} to open it.
 * Empty string = no ponder.
 *
 * {@code governmentType} is an optional visibility gate: when non-null, the node only renders
 * (and is only researchable) in a settlement whose current government matches. Null = visible
 * under any government. Drives government-exclusive policy unlocks — a Council can't see a
 * Chiefdom-only policy node and vice versa.
 */
public record ResearchDefinition(
    String id,
    String name,
    String description,
    double cost,
    int x,
    int y,
    boolean autoUnlock,
    Era minAge,
    List<String> prerequisites,
    List<String> unlocksItems,
    List<String> unlocksFeatures,
    List<String> unlocksFlags,
    String ponderScene,
    @Nullable Settlement.Government governmentType,
    boolean requiresTribe,
    /** Heraldry points granted while this research is completed ("heraldry_points" in the
     *  node JSON, default 0). Spent on banner-design pattern layers — see FactionBanner. */
    int heraldryPoints,
    /** Milestone marker ("important": true in the node JSON, default false): the research
     *  screen renders this node with an ornate frame so era-defining choices (Code of Laws
     *  analogs, Spiritualism, age advances) read as bigger than ordinary nodes. */
    boolean important,
    /** Faith-path visibility gate ("faith_path" in faith-tree JSON, e.g. "ASTROLOGY"):
     *  the node only renders/researches for faiths on the matching path. Null = shared
     *  trunk (and always null for science/culture nodes). The faith-tree analog of
     *  {@code governmentType}. */
    @Nullable com.bannerbound.core.api.faith.FaithPath faithPath,
    @Nullable InsightDefinition insight
) {
    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, ResearchDefinition> STREAM_CODEC = StreamCodec.of(
        (buf, def) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, def.id());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.name());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.description());
            buf.writeDouble(def.cost());
            ByteBufCodecs.VAR_INT.encode(buf, def.x());
            ByteBufCodecs.VAR_INT.encode(buf, def.y());
            buf.writeBoolean(def.autoUnlock());
            ByteBufCodecs.VAR_INT.encode(buf, def.minAge().ordinal());
            STRING_LIST.encode(buf, def.prerequisites());
            STRING_LIST.encode(buf, def.unlocksItems());
            STRING_LIST.encode(buf, def.unlocksFeatures());
            STRING_LIST.encode(buf, def.unlocksFlags());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.ponderScene());
            // Government gate: 0 = no restriction (general), else ordinal+1. Avoids negative
            // varints for the null case.
            ByteBufCodecs.VAR_INT.encode(buf,
                def.governmentType() == null ? 0 : def.governmentType().ordinal() + 1);
            buf.writeBoolean(def.requiresTribe());
            ByteBufCodecs.VAR_INT.encode(buf, def.heraldryPoints());
            buf.writeBoolean(def.important());
            // Faith-path gate: 0 = none, else ordinal+1 (same null-safe scheme as government).
            ByteBufCodecs.VAR_INT.encode(buf,
                def.faithPath() == null ? 0 : def.faithPath().ordinal() + 1);
            buf.writeBoolean(def.insight() != null);
            if (def.insight() != null) InsightDefinition.STREAM_CODEC.encode(buf, def.insight());
        },
        buf -> new ResearchDefinition(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            buf.readDouble(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readBoolean(),
            Era.fromOrdinalOrDefault(ByteBufCodecs.VAR_INT.decode(buf)),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            decodeGovernment(ByteBufCodecs.VAR_INT.decode(buf)),
            buf.readBoolean(),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readBoolean(),
            decodeFaithPath(ByteBufCodecs.VAR_INT.decode(buf)),
            buf.readBoolean() ? InsightDefinition.STREAM_CODEC.decode(buf) : null
        )
    );

    @Nullable
    private static com.bannerbound.core.api.faith.FaithPath decodeFaithPath(int wire) {
        if (wire <= 0) return null;
        com.bannerbound.core.api.faith.FaithPath[] vals = com.bannerbound.core.api.faith.FaithPath.values();
        int idx = wire - 1;
        return idx < vals.length ? vals[idx] : null;
    }

    /** Inverse of the encode side: 0 → null (general), else ordinal-1 into Government values.
     *  Out-of-range guards against a malformed packet selecting a bad ordinal. */
    @Nullable
    private static Settlement.Government decodeGovernment(int wire) {
        if (wire <= 0) return null;
        Settlement.Government[] vals = Settlement.Government.values();
        int idx = wire - 1;
        return idx < vals.length ? vals[idx] : null;
    }
}
