package com.bannerbound.core.civpm.data;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.civpm.CivPM;
import com.bannerbound.core.civpm.utils.CPMMathUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

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
                BannerboundCore.LOGGER.error("Failed to read region save file: {}", e.getMessage());
                return null;
            }
        }
    }

    private long pos;
    private final HashMap<UUID, Object> wanderers;
    private boolean changed;

    public CPMRegion(long pos, HashMap<UUID, Object> wanderers) {
        this.pos = pos;
        this.wanderers = wanderers;
    }

    public CPMRegion(long pos) {
        this(pos, new HashMap<>());
    }

    public CPMRegion(long pos, String wanderersString) {
        this(pos, new HashMap<>());
        deserializeWanderers(wanderersString);
    }

    public int getX() { return CPMMathUtils.CPM2DUtils.unpackX(pos); }
    public int getY() { return CPMMathUtils.CPM2DUtils.unpackY(pos); }
    public long getPos() { return pos; }

    public void changed() {CivPM.getRegionManager().regionChanged(this);}

    public HashMap<UUID, Object> getWanderers() { return wanderers; }

    public void removeWanderer(UUID wanderer) {
        wanderers.remove(wanderer);
        changed();
    }

    public void addWanderer(UUID wanderer) {
        if (wanderers.size() > 3000) {
            throw new IllegalArgumentException("There can only be max 3000 Wanderers in a region");
        }

        wanderers.put(wanderer, true);
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

        for (UUID wanderer : wanderers.keySet()) {
            joined.append(wanderer.toString()).append(",");
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
                    wanderers.put(UUID.fromString(wanderersString.substring(s, e)), true);
                }
                start = i + 1;
            }
        }
    }
}
