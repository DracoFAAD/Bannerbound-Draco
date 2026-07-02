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
 * S→C: the full state the citizen Job tab needs. Sent once right after {@link OpenCitizenScreenPayload}
 * (so the tab has data on open) and again on each live-state poll (so it stays fresh while another
 * player edits the same citizen).
 *
 * @param canManageJobs        whether the viewing player may edit jobs (chief / council / Workload Share)
 * @param jobTypeId            current job type id ({@code ""} = unemployed)
 * @param jobIconItemId        registry id of the icon for the current job (the settlement's current
 *                             tool-age tool for the job's role; {@code 0} = none)
 * @param hasTool              whether a job tool is installed
 * @param toolItemId           registry id of the installed tool item ({@code 0} = none) for the slot icon
 * @param preferredLogId       block id of the forester's preferred log ({@code ""} = none)
 * @param dropOffSet           whether a drop-off has been marked
 * @param unlockedJobTypeIds   research-unlocked job type ids the assign dropdown may offer
 * @param unlockedJobIconItemIds parallel to {@code unlockedJobTypeIds}: each job's icon item id
 * @param allowedToolItemIds   item ids of tools the primary tool slot accepts (current age or lower)
 * @param pickaxeUnlocked      whether the quarryworker's pickaxe slot is available (Quarry researched)
 * @param hasPickaxe           whether a pickaxe is installed in the second slot
 * @param pickaxeItemId        registry id of the installed pickaxe ({@code 0} = none)
 * @param allowedPickaxeItemIds item ids the pickaxe slot accepts (current age or lower)
 * @param seedSourceSet        whether the farmer's seed source has been marked
 * @param forageEnabledBits    forager only: bitmask of categories the player has switched on
 * @param forageUnlockedBits   forager only: bitmask of categories the settlement has researched (the
 *                             rest render LOCKED in the picker)
 * @param hunterPreyOffIds     hunter only: entity-type ids the player has switched OFF in the prey
 *                             picker (the full species list is the {@code #bannerbound:huntable}
 *                             tag, which the client reads locally — only the exclusions travel)
 * @param anarchy              whether the settlement is in anarchy (no government): the Job tab then
 *                             shows the auto-assigned job with a "request switch" control instead of
 *                             free assign/unassign, and offers only gatherer jobs
 * @param switchRefused        whether the citizen recently refused a switch request (an active
 *                             NO_WORK_AS_JOB thought): the "request switch" button stays greyed until
 *                             it lapses so the player can't spam re-requests
 * @param workshopId           crafter only: bound workshop's id (UUID string; {@code ""} = none)
 * @param workshopName         crafter only: bound workshop's custom name ({@code ""} = client shows
 *                             the derived type)
 * @param workshopTypeId       crafter only: bound workshop's derived type id ({@code ""} = none)
 * @param jobXp                whole-number experience for the current profession bucket
 *                             (workshop profession for crafters, job id for other workers)
 * @param stockerTaskItemIds   stocker only: item registry id per board task (queue order)
 * @param stockerTaskCounts    stocker only: haul count per board task
 * @param stockerTaskDests     stocker only: destination display name per task ({@code ""} =
 *                             the stockpile — client renders the translatable label)
 * @param stockerTaskStates    stocker only: 0 = open, 1 = claimed by another stocker, 2 = THIS
 *                             citizen's current haul
 * @param outpostManaged       the citizen's current work site is an OUTPOST working claim: storage
 *                             is decided by the outpost (nearest chest in its chunk, auto-assigned)
 *                             so the Job tab greys its "Set drop location" button
 * @param workStatus           {@link com.bannerbound.core.entity.CitizenWorkStatus} ordinal — the
 *                             glanceable live verdict shown as the Job-tab headline (derived
 *                             server-side, or the goal-published plantation sub-state)
 * @param foresterPlantationUnlocked forester only: whether the Silviculture research is done, so the
 *                             Job tab offers "Select plantation area" (mirrors {@code pickaxeUnlocked})
 */
@ApiStatus.Internal
public record CitizenJobStatePayload(
    int entityId,
    boolean canManageJobs,
    String jobTypeId,
    int jobIconItemId,
    boolean hasTool,
    int toolItemId,
    String preferredLogId,
    boolean dropOffSet,
    List<String> unlockedJobTypeIds,
    List<Integer> unlockedJobIconItemIds,
    List<Integer> allowedToolItemIds,
    boolean pickaxeUnlocked,
    boolean hasPickaxe,
    int pickaxeItemId,
    List<Integer> allowedPickaxeItemIds,
    boolean seedSourceSet,
    int forageEnabledBits,
    int forageUnlockedBits,
    List<String> hunterPreyOffIds,
    List<Integer> seedCacheItemIds,
    List<Integer> seedCacheCounts,
    boolean anarchy,
    boolean foresterKeepExtras,
    boolean jobPinned,
    boolean switchRefused,
    String workshopId,
    String workshopName,
    String workshopTypeId,
    int jobXp,
    List<Integer> stockerTaskItemIds,
    List<Integer> stockerTaskCounts,
    List<String> stockerTaskDests,
    List<Integer> stockerTaskStates,
    boolean outpostManaged,
    int workStatus,
    boolean foresterPlantationUnlocked,
    boolean tradingCourier
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CitizenJobStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "citizen_job_state"));

    public static final StreamCodec<ByteBuf, CitizenJobStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.canManageJobs());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.jobTypeId());
            ByteBufCodecs.VAR_INT.encode(buf, p.jobIconItemId());
            ByteBufCodecs.BOOL.encode(buf, p.hasTool());
            ByteBufCodecs.VAR_INT.encode(buf, p.toolItemId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.preferredLogId());
            ByteBufCodecs.BOOL.encode(buf, p.dropOffSet());
            encodeStrings(buf, p.unlockedJobTypeIds());
            encodeInts(buf, p.unlockedJobIconItemIds());
            encodeInts(buf, p.allowedToolItemIds());
            ByteBufCodecs.BOOL.encode(buf, p.pickaxeUnlocked());
            ByteBufCodecs.BOOL.encode(buf, p.hasPickaxe());
            ByteBufCodecs.VAR_INT.encode(buf, p.pickaxeItemId());
            encodeInts(buf, p.allowedPickaxeItemIds());
            ByteBufCodecs.BOOL.encode(buf, p.seedSourceSet());
            ByteBufCodecs.VAR_INT.encode(buf, p.forageEnabledBits());
            ByteBufCodecs.VAR_INT.encode(buf, p.forageUnlockedBits());
            encodeStrings(buf, p.hunterPreyOffIds());
            encodeInts(buf, p.seedCacheItemIds());
            encodeInts(buf, p.seedCacheCounts());
            ByteBufCodecs.BOOL.encode(buf, p.anarchy());
            ByteBufCodecs.BOOL.encode(buf, p.foresterKeepExtras());
            ByteBufCodecs.BOOL.encode(buf, p.jobPinned());
            ByteBufCodecs.BOOL.encode(buf, p.switchRefused());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopName());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopTypeId());
            ByteBufCodecs.VAR_INT.encode(buf, p.jobXp());
            encodeInts(buf, p.stockerTaskItemIds());
            encodeInts(buf, p.stockerTaskCounts());
            encodeStrings(buf, p.stockerTaskDests());
            encodeInts(buf, p.stockerTaskStates());
            ByteBufCodecs.BOOL.encode(buf, p.outpostManaged());
            ByteBufCodecs.VAR_INT.encode(buf, p.workStatus());
            ByteBufCodecs.BOOL.encode(buf, p.foresterPlantationUnlocked());
            ByteBufCodecs.BOOL.encode(buf, p.tradingCourier());
        },
        buf -> new CitizenJobStatePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            decodeStrings(buf),
            decodeInts(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeStrings(buf),
            decodeInts(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeInts(buf),
            decodeInts(buf),
            decodeStrings(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    private static void encodeStrings(ByteBuf buf, List<String> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (String s : list) ByteBufCodecs.STRING_UTF8.encode(buf, s);
    }

    private static List<String> decodeStrings(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return out;
    }

    private static void encodeInts(ByteBuf buf, List<Integer> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (int v : list) ByteBufCodecs.VAR_INT.encode(buf, v);
    }

    private static List<Integer> decodeInts(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.VAR_INT.decode(buf));
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
