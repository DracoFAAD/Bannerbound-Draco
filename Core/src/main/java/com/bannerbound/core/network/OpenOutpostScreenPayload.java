package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Opens (or refreshes) the Outpost Banner screen — the outpost's own management
 * panel. The BANNER owns the deposit marker (no Foreman's Rod involved): the player assigns a
 * specific miner here, the server creates/binds the chunk's miner marker to them, and the screen
 * names exactly who holds the post. Re-sent after every assign/unassign so the screen live-updates.
 *
 * @param bannerPos      the banner block (round-tripped by {@link AssignOutpostWorkerPayload})
 * @param resourceName   lower-case {@code ChunkResource} name ({@code ""} = no deposit) — client
 *                       renders {@code bannerbound.resource.<name>}
 * @param storageSet     a drop-off container exists inside the outpost chunk
 * @param roofedBeds     roofed bed HEADs in the chunk (lodging; one bed houses the one worker)
 * @param veinReady      ore faces mineable RIGHT NOW (0 = vein worked out, waiting for the next
 *                       refresh wave; -1 = unknown/no deposit → row hidden)
 * @param markerOpen     a legacy rod-made marker open to ALL miners exists (pre-banner-UI worlds);
 *                       assigning a specific miner replaces it
 * @param assignedName   display name of the bound miner ({@code ""} = nobody assigned)
 * @param candidateIds   UUID strings of assignable miners (settlement citizens with the Miner job)
 * @param candidateNames parallel display names
 * @param outpostCount   the settlement's current outpost count
 * @param outpostMax     the outpost cap
 * @param established    true = an existing outpost (full management UI); false = a valid but
 *                       not-yet-claimed site (the "Establish outpost here" confirm UI)
 */
@ApiStatus.Internal
public record OpenOutpostScreenPayload(BlockPos bannerPos, String resourceName, boolean storageSet,
                                       int roofedBeds, int veinReady, int veinTotal, int richness,
                                       boolean markerOpen, String assignedName,
                                       List<String> candidateIds, List<String> candidateNames,
                                       int outpostCount, int outpostMax, boolean established)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenOutpostScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "open_outpost_screen"));

    public static final StreamCodec<ByteBuf, OpenOutpostScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            BlockPos.STREAM_CODEC.encode(buf, p.bannerPos());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.resourceName());
            ByteBufCodecs.BOOL.encode(buf, p.storageSet());
            ByteBufCodecs.VAR_INT.encode(buf, p.roofedBeds());
            ByteBufCodecs.VAR_INT.encode(buf, p.veinReady() + 1); // VAR_INT is unsigned-friendly; shift -1..n to 0..n+1
            ByteBufCodecs.VAR_INT.encode(buf, p.veinTotal());
            ByteBufCodecs.VAR_INT.encode(buf, p.richness() + 1); // -1..2 shifted to 0..3
            ByteBufCodecs.BOOL.encode(buf, p.markerOpen());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.assignedName());
            ByteBufCodecs.VAR_INT.encode(buf, p.candidateIds().size());
            for (String s : p.candidateIds()) ByteBufCodecs.STRING_UTF8.encode(buf, s);
            for (String s : p.candidateNames()) ByteBufCodecs.STRING_UTF8.encode(buf, s);
            ByteBufCodecs.VAR_INT.encode(buf, p.outpostCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.outpostMax());
            ByteBufCodecs.BOOL.encode(buf, p.established());
        },
        buf -> {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            String resource = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean storage = ByteBufCodecs.BOOL.decode(buf);
            int beds = ByteBufCodecs.VAR_INT.decode(buf);
            int veinReady = ByteBufCodecs.VAR_INT.decode(buf) - 1;
            int veinTotal = ByteBufCodecs.VAR_INT.decode(buf);
            int richness = ByteBufCodecs.VAR_INT.decode(buf) - 1;
            boolean open = ByteBufCodecs.BOOL.decode(buf);
            String assigned = ByteBufCodecs.STRING_UTF8.decode(buf);
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) names.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            int max = ByteBufCodecs.VAR_INT.decode(buf);
            boolean established = ByteBufCodecs.BOOL.decode(buf);
            return new OpenOutpostScreenPayload(pos, resource, storage, beds, veinReady, veinTotal,
                richness, open, assigned, ids, names, count, max, established);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
