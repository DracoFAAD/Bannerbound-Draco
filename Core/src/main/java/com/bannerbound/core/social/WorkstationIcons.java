package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bannerbound.core.api.job.CitizenJobRegistry;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Two-way table between workstation type strings (e.g. {@code "farmers_granary"}) and small
 * positive integers used as the JOB topic's subType in {@link
 * com.bannerbound.core.entity.CitizenEntity}'s {@code DATA_BUBBLE} synched slot. The server
 * encodes via {@link #ordinalOf(String)} when writing the bubble; the client decodes via
 * {@link #itemOrdinal(int)} when rendering it.
 *
 * <p>Centralising the table here keeps both sides agreeing on the wire format. The {@code 0}
 * slot is reserved for "no job" (unemployed citizen → JOB topic renders as bubble-only, no
 * inner item icon). Slot order is stable — append-only — so an existing world's queued bubble
 * payload still decodes if a new workstation type is added at a higher slot.
 */
public final class WorkstationIcons {
    /** Ordered list of every workstation type id that can show as a JOB icon. Index in this
     *  array IS the subType ordinal — slot 0 (the "no job" sentinel) is intentionally absent
     *  from this list because it isn't a real type id. New workstation types append at the end. */
    private static final String[] TYPE_IDS = {
        "foresters_log",
        "diggers_slab",
        "farmers_granary",
        "fishers_creel",
        "stockpile_rack",
        "foragers_basket",
    };

    private WorkstationIcons() {}

    /** Registry-defined job ids, sorted by id so the ordinal mapping is stable across client/server
     *  and independent of registration order. Appended after the built-in {@link #TYPE_IDS} slots. */
    private static List<String> registryJobIds() {
        List<String> ids = new ArrayList<>();
        for (CitizenJobRegistry.JobDef d : CitizenJobRegistry.all()) ids.add(d.jobTypeId());
        Collections.sort(ids);
        return ids;
    }

    /** Encode: type-id string → 1-based ordinal. Built-ins first, then registry jobs (so an expansion
     *  job gets its own non-zero ordinal rather than colliding with "no job"). Unknown types → 0. */
    public static int ordinalOf(String typeId) {
        if (typeId == null) return 0;
        for (int i = 0; i < TYPE_IDS.length; i++) {
            if (TYPE_IDS[i].equals(typeId)) return i + 1;
        }
        int idx = registryJobIds().indexOf(typeId);
        return idx >= 0 ? TYPE_IDS.length + 1 + idx : 0;
    }

    /** Decode: ordinal → type-id string, or {@code null} for 0 / out-of-range. */
    public static String typeIdOf(int ordinal) {
        if (ordinal <= 0) return null;
        if (ordinal <= TYPE_IDS.length) return TYPE_IDS[ordinal - 1];
        List<String> reg = registryJobIds();
        int idx = ordinal - TYPE_IDS.length - 1;
        return idx < reg.size() ? reg.get(idx) : null;
    }

    /** Decode + resolve to a fresh {@link ItemStack} the client renderer can draw — the job's
     *  tool/representative item now that the workstation blocks are gone. Registry jobs draw their
     *  declared {@code iconBaseline}. Returns {@link ItemStack#EMPTY} for the "no job" sentinel or an
     *  unrecognised ordinal. */
    public static ItemStack itemOrdinal(int ordinal) {
        String id = typeIdOf(ordinal);
        if (id == null) return ItemStack.EMPTY;
        Item item = switch (id) {
            case "foresters_log"   -> Items.IRON_AXE;
            case "diggers_slab"    -> Items.IRON_SHOVEL;
            case "farmers_granary" -> Items.IRON_HOE;
            case "fishers_creel"   -> Items.FISHING_ROD;
            case "stockpile_rack"  -> Items.CHEST;
            case "foragers_basket" -> Items.POPPY;
            default -> null;
        };
        if (item == null) {
            CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(id);
            if (d != null) item = d.iconBaseline();
        }
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }
}
