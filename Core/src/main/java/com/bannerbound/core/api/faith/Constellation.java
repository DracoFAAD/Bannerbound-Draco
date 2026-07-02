package com.bannerbound.core.api.faith;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * One drawn god (FAITH_PLAN Part 3): a player-authored constellation on the shared sky.
 * Star ids are the stable identities from {@code SkyField} (commons = vanilla
 * accepted-order index, typed = TYPED_ID_BASE + generation index); the line path is the
 * id ORDER (consecutive ids connect). Domain profile per the hybrid rules: primary =
 * dominant typed star type, secondary = second type with ≥2 stars ({@code null} = pure
 * god → purity bonus when passives land).
 */
public final class Constellation {
    private final UUID id;
    private final String name;
    private final String deityName;
    private final int[] starIds;
    private final DeityDomain primaryDomain;
    @Nullable
    private final DeityDomain secondaryDomain;

    public Constellation(UUID id, String name, String deityName, int[] starIds,
                         DeityDomain primaryDomain, @Nullable DeityDomain secondaryDomain) {
        this.id = id;
        this.name = name;
        this.deityName = deityName;
        this.starIds = starIds.clone();
        this.primaryDomain = primaryDomain;
        this.secondaryDomain = secondaryDomain;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String deityName() {
        return deityName;
    }

    public int[] starIds() {
        return starIds.clone();
    }

    public boolean usesStar(int starId) {
        for (int id : starIds) {
            if (id == starId) return true;
        }
        return false;
    }

    public DeityDomain primaryDomain() {
        return primaryDomain;
    }

    @Nullable
    public DeityDomain secondaryDomain() {
        return secondaryDomain;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Deity", deityName);
        tag.putIntArray("Stars", starIds);
        tag.putInt("Domain", primaryDomain.ordinal());
        tag.putInt("Secondary", secondaryDomain == null ? -1 : secondaryDomain.ordinal());
        return tag;
    }

    public static Constellation load(CompoundTag tag) {
        int secondary = tag.getInt("Secondary");
        return new Constellation(
            tag.getUUID("Id"),
            tag.getString("Name"),
            tag.getString("Deity"),
            tag.getIntArray("Stars"),
            DeityDomain.fromOrdinal(tag.getInt("Domain")),
            secondary < 0 ? null : DeityDomain.fromOrdinal(secondary));
    }
}
