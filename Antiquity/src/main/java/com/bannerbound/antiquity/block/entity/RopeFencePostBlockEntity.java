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
 * Block entity for a Rope Fence Post — a single rope tie point (slot 0). Stores the {@link RopeAnchor}s
 * it's roped to and the collision-filler cells for the ropes it owns; {@link RopeTies} drives the rest.
 */
@ApiStatus.Internal
public class RopeFencePostBlockEntity extends BlockEntity implements RopeTieHost {
    /** Far ends this post is roped to (insertion-ordered for stable rendering / most-recent breaking). */
    private final Set<RopeAnchor> connections = new LinkedHashSet<>();
    /** Filler cells for the ropes this post owns, keyed by the far anchor. */
    private final Map<RopeAnchor, List<BlockPos>> fillers = new HashMap<>();

    public RopeFencePostBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.ROPE_FENCE_POST_BE.get(), pos, state);
    }

    @Override
    public int slotCount() {
        return 1;
    }

    @Override
    public Set<RopeAnchor> connections(int slot) {
        return connections;
    }

    @Override
    public boolean addConnection(int slot, RopeAnchor other) {
        boolean added = connections.add(other.immutable());
        if (added) {
            setChanged();
        }
        return added;
    }

    @Override
    public boolean removeConnection(int slot, RopeAnchor other) {
        boolean removed = connections.remove(other);
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
        ListTag conns = new ListTag();
        for (RopeAnchor a : connections) {
            conns.add(a.toTag());
        }
        tag.put("Anchors", conns);

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
        connections.clear();
        if (tag.contains("Anchors", Tag.TAG_LIST)) {
            ListTag conns = tag.getList("Anchors", Tag.TAG_COMPOUND);
            for (int i = 0; i < conns.size(); i++) {
                connections.add(RopeAnchor.fromTag(conns.getCompound(i)));
            }
        } else if (tag.contains("Connections", Tag.TAG_LONG_ARRAY)) {
            // Pre-anchor save: connections were bare post positions (all slot 0).
            for (long l : tag.getLongArray("Connections")) {
                connections.add(new RopeAnchor(BlockPos.of(l), 0));
            }
        }

        fillers.clear();
        ListTag fillerList = tag.getList("Fillers", Tag.TAG_COMPOUND);
        for (int i = 0; i < fillerList.size(); i++) {
            CompoundTag entry = fillerList.getCompound(i);
            RopeAnchor key = entry.contains("P") ? RopeAnchor.fromTag(entry)
                : new RopeAnchor(BlockPos.of(entry.getLong("Rope")), 0); // pre-anchor filler key
            List<BlockPos> cells = new ArrayList<>();
            for (long l : entry.getLongArray("Cells")) {
                cells.add(BlockPos.of(l));
            }
            fillers.put(key, cells);
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
