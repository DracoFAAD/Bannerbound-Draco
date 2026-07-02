package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.RopeAnchor;
import com.bannerbound.antiquity.RopeTieHost;
import com.bannerbound.antiquity.RopeTies;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for a Rope Fence Gate — two rope tie points: slot 0 = left upright, slot 1 = right
 * upright. Each upright keeps its own set of {@link RopeAnchor}s; ropes the gate owns keep their
 * collision-filler cells. {@link com.bannerbound.antiquity.RopeTies} drives the linking/breaking.
 */
@ApiStatus.Internal
public class RopeFenceGateBlockEntity extends BlockEntity implements RopeTieHost {
    @SuppressWarnings("unchecked")
    private final Set<RopeAnchor>[] connections = new Set[] { new LinkedHashSet<>(), new LinkedHashSet<>() };
    private final Map<RopeAnchor, List<BlockPos>> fillers = new HashMap<>();

    public RopeFenceGateBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.ROPE_FENCE_GATE_BE.get(), pos, state);
    }

    @Override
    public int slotCount() {
        return 2;
    }

    @Override
    public Set<RopeAnchor> connections(int slot) {
        return connections[slot];
    }

    @Override
    public boolean addConnection(int slot, RopeAnchor other) {
        boolean added = connections[slot].add(other.immutable());
        if (added) {
            setChanged();
        }
        return added;
    }

    @Override
    public boolean removeConnection(int slot, RopeAnchor other) {
        boolean removed = connections[slot].remove(other);
        if (removed) {
            setChanged();
        }
        return removed;
    }

    @Override
    public List<BlockPos> getFillers(RopeAnchor other) {
        return fillers.getOrDefault(other, List.of());
    }

    @Override
    public void putFillers(RopeAnchor other, List<BlockPos> cells) {
        if (cells.isEmpty()) {
            fillers.remove(other);
        } else {
            fillers.put(other.immutable(), List.copyOf(cells));
        }
        setChanged();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null) {
            RopeTies.onHostLoad(level, new ChunkPos(getBlockPos()));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) {
            RopeTies.onHostUnload(level, new ChunkPos(getBlockPos()));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        for (int slot = 0; slot < 2; slot++) {
            ListTag list = new ListTag();
            for (RopeAnchor a : connections[slot]) {
                list.add(a.toTag());
            }
            tag.put(slot == 0 ? "Left" : "Right", list);
        }
        ListTag fillerList = new ListTag();
        for (Map.Entry<RopeAnchor, List<BlockPos>> e : fillers.entrySet()) {
            CompoundTag entry = e.getKey().toTag();
            long[] cells = new long[e.getValue().size()];
            int j = 0;
            for (BlockPos c : e.getValue()) {
                cells[j++] = c.asLong();
            }
            entry.putLongArray("Cells", cells);
            fillerList.add(entry);
        }
        tag.put("Fillers", fillerList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        for (int slot = 0; slot < 2; slot++) {
            connections[slot].clear();
            ListTag list = tag.getList(slot == 0 ? "Left" : "Right", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                connections[slot].add(RopeAnchor.fromTag(list.getCompound(i)));
            }
        }
        fillers.clear();
        ListTag fillerList = tag.getList("Fillers", Tag.TAG_COMPOUND);
        for (int i = 0; i < fillerList.size(); i++) {
            CompoundTag entry = fillerList.getCompound(i);
            List<BlockPos> cells = new ArrayList<>();
            for (long l : entry.getLongArray("Cells")) {
                cells.add(BlockPos.of(l));
            }
            fillers.put(RopeAnchor.fromTag(entry), cells);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
