package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the settlement's gatherer-labor state for the Town Hall "Labor" tab — the unlocked gatherer
 * jobs in priority order, with per-job enabled flag + current/target worker counts, and the global
 * auto-assign flag. Sent when the town hall opens and after any labor edit.
 *
 * @param jobIds    unlocked gatherer job ids in priority order
 * @param enabled   parallel to {@code jobIds}: false = the job is switched off (0 target)
 * @param current   parallel: how many citizens currently hold each job
 * @param caps      parallel: the player-set worker cap for each job ({@code -1} = no limit)
 * @param autoAssign whether the settlement auto-distributes labor (always on in anarchy)
 * @param workloadShareActive whether the Workload Share policy is active (delegates labor editing to
 *                  every member in a chiefdom) — the client folds this into its can-edit gate
 * @param preferredStorage packed {@link net.minecraft.core.BlockPos#asLong} of the settlement's
 *                  default depot, or {@link Long#MIN_VALUE} if none is set
 */
@ApiStatus.Internal
public record LaborStatePayload(
    List<String> jobIds,
    List<Boolean> enabled,
    List<Integer> current,
    List<Integer> caps,
    boolean autoAssign,
    boolean workloadShareActive,
    long preferredStorage
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LaborStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "labor_state"));

    public static final StreamCodec<ByteBuf, LaborStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.jobIds().size());
            for (String s : p.jobIds()) ByteBufCodecs.STRING_UTF8.encode(buf, s);
            for (boolean b : p.enabled()) ByteBufCodecs.BOOL.encode(buf, b);
            for (int v : p.current()) ByteBufCodecs.VAR_INT.encode(buf, v);
            for (int v : p.caps()) ByteBufCodecs.VAR_INT.encode(buf, v);
            ByteBufCodecs.BOOL.encode(buf, p.autoAssign());
            ByteBufCodecs.BOOL.encode(buf, p.workloadShareActive());
            ByteBufCodecs.VAR_LONG.encode(buf, p.preferredStorage());
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            List<Boolean> en = new ArrayList<>(n);
            for (int i = 0; i < n; i++) en.add(ByteBufCodecs.BOOL.decode(buf));
            List<Integer> cur = new ArrayList<>(n);
            for (int i = 0; i < n; i++) cur.add(ByteBufCodecs.VAR_INT.decode(buf));
            List<Integer> tgt = new ArrayList<>(n);
            for (int i = 0; i < n; i++) tgt.add(ByteBufCodecs.VAR_INT.decode(buf));
            boolean auto = ByteBufCodecs.BOOL.decode(buf);
            boolean workloadShare = ByteBufCodecs.BOOL.decode(buf);
            long preferred = ByteBufCodecs.VAR_LONG.decode(buf);
            return new LaborStatePayload(ids, en, cur, tgt, auto, workloadShare, preferred);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
