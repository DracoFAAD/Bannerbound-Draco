package com.bannerbound.core.api.settlement;

import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * A timed effect attached to a {@link Settlement}. Drives both gameplay (e.g. summed food
 * bonuses from fishing) and UI (the Statuses tab on the town hall screen). Mutable so the tick
 * loop can decrement {@link #remainingTicks} without allocating; everything else is final.
 * <p>
 * Sourcing: anyone (work goals, research, events) creates a {@code StatusEffect} via the
 * constructor and adds it via {@link Settlement#addStatusEffect}. Lifetime is bounded by
 * {@link #totalDurationTicks} — no manual cleanup required.
 *
 * <h2>Translation</h2>
 * The {@link #translationKey} resolves to a chat-component on the client (e.g.
 * {@code bannerbound.status.fish_caught} → "Fisher caught {0}"); {@link #args} are positional
 * substitutions. Keep args plain strings — the screen wraps them as
 * {@link net.minecraft.network.chat.Component#literal} on render.
 */
public final class StatusEffect {
    private final UUID instanceId;
    private final String translationKey;
    private final List<String> args;
    private final StatusEffectIcon icon;
    private final double iconValue;
    private final int totalDurationTicks;
    private int remainingTicks;

    public StatusEffect(UUID instanceId, String translationKey, List<String> args,
                         StatusEffectIcon icon, double iconValue, int totalDurationTicks) {
        this.instanceId = instanceId;
        this.translationKey = translationKey;
        this.args = List.copyOf(args);
        this.icon = icon;
        this.iconValue = iconValue;
        this.totalDurationTicks = totalDurationTicks;
        this.remainingTicks = totalDurationTicks;
    }

    /** Constructor used by NBT/network loaders that need to restore a partially-elapsed effect. */
    public StatusEffect(UUID instanceId, String translationKey, List<String> args,
                         StatusEffectIcon icon, double iconValue,
                         int totalDurationTicks, int remainingTicks) {
        this.instanceId = instanceId;
        this.translationKey = translationKey;
        this.args = List.copyOf(args);
        this.icon = icon;
        this.iconValue = iconValue;
        this.totalDurationTicks = totalDurationTicks;
        this.remainingTicks = remainingTicks;
    }

    public UUID instanceId() { return instanceId; }
    public String translationKey() { return translationKey; }
    public List<String> args() { return args; }
    public StatusEffectIcon icon() { return icon; }
    public double iconValue() { return iconValue; }
    public int totalDurationTicks() { return totalDurationTicks; }
    public int remainingTicks() { return remainingTicks; }

    /** Decrement the remaining-time counter. Returns true if the effect has expired (caller is
     *  expected to remove it from the parent settlement's list). */
    public boolean tickDown() {
        remainingTicks--;
        return remainingTicks <= 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", instanceId);
        tag.putString("Key", translationKey);
        ListTag argsTag = new ListTag();
        for (String a : args) argsTag.add(StringTag.valueOf(a));
        tag.put("Args", argsTag);
        tag.putInt("Icon", icon.ordinal());
        tag.putDouble("Value", iconValue);
        tag.putInt("Total", totalDurationTicks);
        tag.putInt("Rem", remainingTicks);
        return tag;
    }

    public static StatusEffect load(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        String key = tag.getString("Key");
        ListTag argsTag = tag.getList("Args", Tag.TAG_STRING);
        java.util.ArrayList<String> args = new java.util.ArrayList<>(argsTag.size());
        for (int i = 0; i < argsTag.size(); i++) args.add(argsTag.getString(i));
        StatusEffectIcon icon = StatusEffectIcon.fromOrdinalOrFood(tag.getInt("Icon"));
        double value = tag.getDouble("Value");
        int total = tag.getInt("Total");
        int rem = tag.contains("Rem") ? tag.getInt("Rem") : total;
        return new StatusEffect(id, key, args, icon, value, total, rem);
    }
}
