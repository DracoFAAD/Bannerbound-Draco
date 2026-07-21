package com.bannerbound.core.civpm.data;

import com.bannerbound.core.civpm.CivPM;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

public class CPMRegion {
    public static class Serialization {
        public static final Codec<CPMRegion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("pos", 0L).forGetter(CPMRegion::getPos),
            Codec.STRING.optionalFieldOf("wanderers", "").forGetter(CPMRegion::serializeWanderers)
        ).apply(instance, CPMRegion::new));

        public static void saveToFile(CPMRegion data, Path path) {
            try {
                CompoundTag tag = (CompoundTag) Serialization.CODEC.encodeStart(NbtOps.INSTANCE, data)
                        .getOrThrow(IllegalStateException::new);

                NbtIo.writeCompressed(tag, path);
            } catch (IOException | IllegalStateException e) {
                System.err.println("Failed to write region save file: " + e.getMessage());
            }
        }

        public static CPMRegion loadFromFile(Path path) {
            try {
                CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());

                return Serialization.CODEC.parse(NbtOps.INSTANCE, tag)
                        .getOrThrow(IllegalStateException::new);
            } catch (IOException | IllegalStateException e) {
                System.err.println("Failed to read region save file: " + e.getMessage());
                return null;
            }
        }
    }

    private long pos;
    private final HashSet<String> wanderers;
    private boolean changed; // to not save an unchanged region

    public CPMRegion(long pos, HashSet<String> wanderers) {
        this.pos = pos;
        this.wanderers = wanderers;
    }

    public CPMRegion() {
        this(0L, new HashSet<>());
    }

    public CPMRegion(int x, int y) {
        this(pack(x, y), new HashSet<>());
    }

    public CPMRegion(long pos) {
        this(pos, new HashSet<>());
    }

    public CPMRegion(int x, int y, HashSet<String> wanderers) {
        this(pack(x, y), wanderers);
    }

    public CPMRegion(int x, int y, String wanderersString) {
        this(pack(x, y), new HashSet<>());
        deserializeWanderers(wanderersString);
    }

    public CPMRegion(long pos, String wanderersString) {
        this(pos, new HashSet<>());
        deserializeWanderers(wanderersString);
    }

    public int getX() { return unpackX(pos); }
    public void setX(int x) { pos = packX(pos, x); }

    public int getY() { return unpackY(pos); }
    public void setY(int y) { pos = packY(pos, y); }

    public long getPos() { return pos; }
    public void setPos(long pos) { this.pos = pos; }

    public void changed() {CivPM.getInstance().regionChanged(this);}

    public HashSet<String> getWanderers() { return wanderers; }

    public void removeWanderer(String wanderer) {
        wanderers.remove(wanderer);
        changed();
    }

    public void addWanderer(String wanderer) {
        if (wanderer.length() > 4000) {
            throw new IllegalArgumentException("There can only be max 4000 Wanderers in a region");
        }

        wanderers.add(wanderer);
        changed();
    }

    @Override
    public String toString() {
        return String.format("Region(%d, %d)", getX(), getY());
    }

    public boolean isChanged() { return changed; }
    public void setChanged(boolean changed) { this.changed = changed; }

    public String serializeWanderers() {
        StringBuilder joined = new StringBuilder();

        for (String wanderer : wanderers) {
            joined.append(wanderer).append(",");
        }

        if (!joined.isEmpty()) {
            joined.deleteCharAt(joined.length() - 1);
        }

        return joined.toString();
    }

    public void deserializeWanderers(String wanderersString) {
        wanderers.clear();

        int len = wanderersString.length();
        int start = 0;

        // I AINT USING SPLIT!!!!
        for (int i = 0; i <= len; i++) {
            if (i == len || wanderersString.charAt(i) == ',') {
                int s = start;
                while (s < i && wanderersString.charAt(s) <= ' ') {
                    s++;
                }
                int e = i;
                while (e > s && wanderersString.charAt(e - 1) <= ' ') {
                    e--;
                }

                if (s < e) {
                    wanderers.add(wanderersString.substring(s, e));
                }
                start = i + 1;
            }
        }
    }

    public static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackY(long packed) {
        return (int) packed;
    }

    public static long packX(long pos, int newX) {
        return ((long) newX << 32) | (pos & 0xFFFFFFFFL);
    }

    public static long packY(long pos, int newY) {
        return (pos & 0xFFFFFFFF00000000L) | (newY & 0xFFFFFFFFL);
    }
}
