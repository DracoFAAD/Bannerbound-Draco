package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.social.JobIcons;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shared job-tool resolution + remote provisioning. The accepted-tool list is the single source of
 * truth for both the Job-tab tool slot ({@code ServerPayloadHandler.allowedToolItemIds} delegates
 * here) and the settlement's auto-provisioning of tools from its preferred storage.
 */
@ApiStatus.Internal
public final class JobTools {
    private JobTools() {
    }

    /** The settlement's current tool-age order, or {@link Integer#MIN_VALUE} when no age is set. */
    public static int currentToolAgeOrder(Settlement settlement) {
        ToolAge age = ToolAgeLoader.get(settlement.getCurrentToolAge());
        return age == null ? Integer.MIN_VALUE : age.order();
    }

    /**
     * Valid tool items for a job's icon {@code role} at the settlement's current tool age — every tool
     * age's tool for the role whose order is ≤ the current age (no leaping ahead of tech). The fishing
     * rod and herder rope aren't tiered (any rod / any {@code #bannerbound:herder_rope} item). Empty
     * for the forager (tool-free) or an unknown role. Mirrors the Job-tab tool-slot allow-list.
     */
    public static List<Item> allowedToolsFor(Settlement settlement, String role) {
        List<Item> out = new ArrayList<>();
        if (role == null) return out;
        if (JobIcons.ROLE_FISHING_ROD.equals(role)) {
            out.add(Items.FISHING_ROD);
            return out;
        }
        if (JobIcons.ROLE_ROPE.equals(role)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(HerderWorkGoal.HERDER_ROPE_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (out.isEmpty()) out.add(Items.LEAD);
            return out;
        }
        if (JobIcons.ROLE_FORAGE.equals(role)) {
            return out;
        }
        // Hunter: once Archery is researched the bows lead the list (tryEquipToolFromStorage equips
        // the FIRST match, so hunters prefer a bow), then the tiered "hunt" entries fall through
        // below (swords in the Core ages, a spear in Antiquity's bone age). Bows are the
        // #bannerbound:hunter_bows tag — Antiquity contributes its primitive bow there.
        if (JobIcons.ROLE_HUNT.equals(role)
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.api.hunter.HunterHooks.FLAG_ARCHERY)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(HunterWorkGoal.HUNTER_BOWS_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (out.isEmpty()) out.add(Items.BOW);
        }
        // The spear fisher's ICON role is "spearfish" (a cod, so the job reads as fishing) but its
        // tool slot still takes the tiered "spear" items — alias for the tool lookup only.
        String toolRole = "spearfish".equals(role) ? "spear" : role;
        int currentOrder = currentToolAgeOrder(settlement);
        for (ToolAge a : ToolAgeLoader.getAll().values()) {
            if (a.order() > currentOrder) continue;
            Item tool = a.tools().get(toolRole);
            if (tool != null && tool != Items.AIR) out.add(tool);
        }
        // Guard: ranged options APPEND after the tiered melee entries (equipFrom takes the first
        // stocked match, so a watch defaults to melee and goes ranged only when the armory holds
        // slings/bows but no blades — stock ratios decide the watch's composition). Slings
        // (#bannerbound:guard_slings) are pre-Archery; bows join once Archery is researched,
        // mirroring the hunter block above.
        if ("guard".equals(role)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(GuardWorkGoal.GUARD_SLINGS_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.api.hunter.HunterHooks.FLAG_ARCHERY)) {
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getTag(HunterWorkGoal.HUNTER_BOWS_TAG)
                    .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            }
        }
        return out;
    }

    /**
     * If {@code citizen} holds a tool-using gatherer job but has no tool installed, try to pull ONE
     * valid tool from the settlement {@linkplain com.bannerbound.core.entity.SettlementStorage
     * storage pool} (remote — the citizen never walks to it) and equip it. Tools are pooled like any
     * other item now: a worker equips from the nearest take-open stockpile or loose basket holding
     * its tool — no per-role tool depot. Returns true if a tool was equipped. No-op in anarchy (no
     * pool there), for tool-free jobs (forager), or when nothing open holds a matching tool.
     */
    public static boolean tryEquipToolFromStorage(CitizenEntity citizen, Settlement settlement) {
        if (citizen.hasJobTool()) return false;
        String role = JobIcons.roleForJob(citizen.getJobType());
        List<Item> tools = allowedToolsFor(settlement, role);
        if (tools.isEmpty()) return false;
        return equipFrom(citizen, DropOffContainers.supplyPool(citizen), tools);
    }

    private static boolean equipFrom(CitizenEntity citizen, Container depot, List<Item> tools) {
        if (depot == null) return false;
        for (Item tool : tools) {
            if (!DropOffContainers.contains(depot, tool)) continue;
            ItemStack one = DropOffContainers.extractOne(depot, tool);
            if (!one.isEmpty()) {
                citizen.setJobTool(one);
                return true;
            }
        }
        return false;
    }
}
