package com.bannerbound.antiquity.deco;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * All face decorations within one chunk: {@code BlockPos → (Direction → FaceDeco)}. Used both as the
 * server-side chunk {@linkplain net.neoforged.neoforge.attachment.AttachmentType attachment} value
 * (persisted via {@link #CODEC}) and as the client-side per-chunk cache. Mutable; empty faces are not
 * stored.
 */
public class ChunkDecorations {
    private final Map<BlockPos, EnumMap<Direction, FaceDeco>> faces = new HashMap<>();

    /** Serializes as a flat list of {@link FaceDecoEntry} (chunk save data). */
    public static final Codec<ChunkDecorations> CODEC =
        FaceDecoEntry.CODEC.listOf().xmap(ChunkDecorations::fromEntries, ChunkDecorations::toEntries);

    public FaceDeco get(BlockPos pos, Direction dir) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        FaceDeco d = m == null ? null : m.get(dir);
        return d == null ? FaceDeco.EMPTY : d;
    }

    /** Set (or clear, when {@code deco.isEmpty()}) the decoration on one face. Returns true if changed. */
    public boolean set(BlockPos pos, Direction dir, FaceDeco deco) {
        BlockPos key = pos.immutable();
        if (deco == null || deco.isEmpty()) {
            EnumMap<Direction, FaceDeco> m = faces.get(key);
            if (m == null) {
                return false;
            }
            boolean changed = m.remove(dir) != null;
            if (m.isEmpty()) {
                faces.remove(key);
            }
            return changed;
        }
        EnumMap<Direction, FaceDeco> m = faces.computeIfAbsent(key, k -> new EnumMap<>(Direction.class));
        return !deco.equals(m.put(dir, deco));
    }

    public boolean isEmpty() {
        return faces.isEmpty();
    }

    /** Remove and return every face decoration at {@code pos} (null if it had none). */
    public EnumMap<Direction, FaceDeco> removeAll(BlockPos pos) {
        return faces.remove(pos);
    }

    /** Summed appeal at one position: {@code plasterEach} per plastered face + {@code trimEach} per
     *  trimmed face. 0 if the position has no decorations. */
    public double appealAt(BlockPos pos, double plasterEach, double trimEach) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        if (m == null) {
            return 0.0;
        }
        double s = 0.0;
        for (FaceDeco d : m.values()) {
            if (d.plaster()) {
                s += plasterEach;
            }
            if (d.hasTrim()) {
                s += trimEach;
            }
        }
        return s;
    }

    /** True if any face of {@code pos} is plastered (drives the blast/break "sturdier" effects). */
    public boolean anyPlaster(BlockPos pos) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        if (m == null) {
            return false;
        }
        for (FaceDeco d : m.values()) {
            if (d.plaster()) {
                return true;
            }
        }
        return false;
    }

    public void forEach(Consumer<FaceDecoEntry> out) {
        faces.forEach((pos, m) -> m.forEach((dir, deco) -> out.accept(new FaceDecoEntry(pos, dir, deco))));
    }

    /** Entries whose block Y is within {@code [minY, maxY)} — for per-section rendering. */
    public void forEachInYRange(int minY, int maxY, Consumer<FaceDecoEntry> out) {
        faces.forEach((pos, m) -> {
            if (pos.getY() >= minY && pos.getY() < maxY) {
                m.forEach((dir, deco) -> out.accept(new FaceDecoEntry(pos, dir, deco)));
            }
        });
    }

    public List<FaceDecoEntry> toEntries() {
        List<FaceDecoEntry> list = new ArrayList<>();
        forEach(list::add);
        return list;
    }

    public static ChunkDecorations fromEntries(List<FaceDecoEntry> entries) {
        ChunkDecorations cd = new ChunkDecorations();
        for (FaceDecoEntry e : entries) {
            cd.set(e.pos(), e.dir(), e.deco());
        }
        return cd;
    }
}
