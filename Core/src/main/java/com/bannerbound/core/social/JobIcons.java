package com.bannerbound.core.social;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.DiggerWorkGoal;
import com.bannerbound.core.entity.FarmerWorkGoal;
import com.bannerbound.core.entity.FisherWorkGoal;
import com.bannerbound.core.entity.ForagerWorkGoal;
import com.bannerbound.core.entity.ForesterWorkGoal;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Single source of truth for "which icon represents a citizen's job". Both the JOB speech bubble
 * (which draws the icon as an {@link ItemStack}) and the name-tag suffix glyph (a bitmap-font
 * character) resolve through here, so the two can never disagree — the herder-had-no-icon gap
 * came from two parallel lists drifting apart.
 *
 * <p>Every job's icon is <b>data-driven from the tool-age JSON</b> ({@code data/<ns>/tool_ages/*.json}
 * → {@link Settlement#getToolForRole}): forester=axe, digger=shovel, farmer=hoe, fisher=fishing_rod,
 * herder=rope, forager=forage. So changing an icon — e.g. the herder's {@code rope} role to an
 * Antiquity fiber rope in {@code bone.json}, or a {@code minecraft:lead} in a Core age — is a
 * datapack edit, no code. Each role falls back to a hardcoded Core baseline ({@link #defaultFor})
 * when the current age doesn't define it (wooden-tier tools, a fishing rod, a lead, a poppy); that
 * baseline is also the "no tool age assigned" default.
 *
 * <p>Core stays decoupled from the Antiquity expansion: every name-tag glyph references a
 * {@code minecraft:} texture. If a role resolves to an Antiquity-only item (e.g. a bone tool or
 * fiber rope) the JOB bubble still renders that real item, but the name-tag glyph — which needs a
 * Core font texture — falls back to the role's baseline glyph (wooden tier / lead / etc.).
 */
@ApiStatus.Internal
public final class JobIcons {
    /** Pseudo tool-role for the fisher's fishing rod — NOT a tool-age tier (any vanilla rod works). */
    public static final String ROLE_FISHING_ROD = "fishing_rod";
    /** Pseudo tool-role for the herder — defaults to a vanilla lead. The string stays {@code "rope"}
     *  for back-compat with the tool-slot gate in the network handler; a tool-age JSON can map it to
     *  any item (e.g. Antiquity's fiber rope in {@code bone.json}). */
    public static final String ROLE_ROPE = "rope";
    /** Pseudo tool-role for the forager (tool-free) — drives only the icon, never a tool slot.
     *  Defaults to a poppy; overridable per tool age via a {@code "forage"} entry. */
    public static final String ROLE_FORAGE = "forage";
    /** Tool-role for the hunter's weapon. Tiered like the tool roles ({@code "hunt"} entries in
     *  {@code tool_ages/*.json} — swords in the Core ages, a spear in Antiquity's bone age), with a
     *  bow prepended once the settlement researches {@code bannerbound.archery} (see
     *  {@link com.bannerbound.core.entity.JobTools#allowedToolsFor}). */
    public static final String ROLE_HUNT = "hunt";

    private JobIcons() {}

    /** Tool role a job's icon draws from: "axe"/"shovel"/"hoe" feed the tool-age table; the two
     *  pseudo-roles are fixed items. {@code null} for jobs with no held tool (forager, stockpile),
     *  which fall back to a generic {@link WorkstationIcons} item. */
    public static String roleForJob(String typeId) {
        if (ForesterWorkGoal.JOB_TYPE_ID.equals(typeId)) return "axe";
        if (DiggerWorkGoal.JOB_TYPE_ID.equals(typeId)) return "shovel";
        if (FarmerWorkGoal.JOB_TYPE_ID.equals(typeId)) return "hoe";
        if (FisherWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_FISHING_ROD;
        if (HerderWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_ROPE;
        if (ForagerWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_FORAGE;
        // Registry-defined jobs (expansion gatherers) declare their icon role via CitizenJobRegistry.
        return com.bannerbound.core.api.job.CitizenJobRegistry.iconRoleFor(typeId);
    }

    /** Whether this job needs a held tool to work under a GOVERNMENT. Every gatherer role requires
     *  its tool EXCEPT the forager, who scavenges bare-handed (its {@code POPPY} role item is just an
     *  icon, not a required tool). Jobs with no gatherer role (crafters, unemployed) aren't gated by
     *  this. In ANARCHY every gatherer works tool-free (slower), so callers must AND this with
     *  {@code !anarchy} — otherwise tool-less workers wrongly read as "no tool" instead of working. */
    public static boolean requiresTool(String typeId) {
        // Registry-defined jobs declare their tool-need explicitly: a job can have an icon role
        // purely for its bubble/glyph (e.g. the stocker's bundle, the builder's brick) yet need no
        // held tool. Honour that flag instead of inferring tool-need from "has an icon role", which
        // wrongly flagged tool-free logistics workers as NO_TOOL.
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(typeId);
        if (def != null) return def.toolRequired();
        // Built-in gatherers (forester/digger/farmer/fisher/herder): every role needs its tool
        // except the forager, who scavenges bare-handed.
        String role = roleForJob(typeId);
        return role != null && !ROLE_FORAGE.equals(role);
    }

    /** The Core baseline item for a role — used when a settlement has no tool age, or its current
     *  age doesn't define the role in {@code tool_ages/*.json}. Every entry is a {@code minecraft:}
     *  item, so this also backs the name-tag glyph when a role resolves to a modded item Core has no
     *  glyph for. Wooden-tier tools, a fishing rod, a lead (herder) and a poppy (forager);
     *  {@link Items#AIR} for an unknown role. Tool-age JSON entries override these. */
    private static Item defaultFor(String role) {
        if (role == null) return Items.AIR;
        return switch (role) {
            case "axe"            -> Items.WOODEN_AXE;
            case "shovel"         -> Items.WOODEN_SHOVEL;
            case "hoe"            -> Items.WOODEN_HOE;
            case ROLE_FISHING_ROD -> Items.FISHING_ROD;
            case ROLE_ROPE        -> Items.LEAD;
            case ROLE_FORAGE      -> Items.POPPY;
            case ROLE_HUNT        -> Items.WOODEN_SWORD;
            // Registry-defined roles (expansion jobs) declare their own minecraft: baseline.
            default               -> {
                Item baseline = com.bannerbound.core.api.job.CitizenJobRegistry.baselineForRole(role);
                yield baseline == null ? Items.AIR : baseline;
            }
        };
    }

    /** Icon item for {@code typeId}: the job's role tool for the settlement's current tool age,
     *  resolved straight from {@code tool_ages/*.json} via {@link Settlement#getToolForRole}, and
     *  falling back to the Core {@link #defaultFor baseline} when no age defines it. This is exactly
     *  what the JOB bubble draws. Jobs with no role (stockpile / unknown) use a generic
     *  {@link WorkstationIcons} item. */
    public static Item iconItem(Settlement settlement, String typeId) {
        String role = roleForJob(typeId);
        if (role == null) {
            // A generic Crafter has no tool role — so without a workshop-type icon it read as blank.
            // Default it to the declared crafting-stone station so a Crafter is never iconless; the
            // workshop-aware overload below swaps this for the worker's actual station family.
            if (com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(typeId)) {
                Item icon = com.bannerbound.core.api.workshop.WorkBlockRegistry.defaultCrafterIcon();
                if (icon != null) return icon;
            }
            ItemStack s = WorkstationIcons.itemOrdinal(WorkstationIcons.ordinalOf(typeId));
            return s.isEmpty() ? Items.AIR : s.getItem();
        }
        Item t = settlement == null ? Items.AIR : settlement.getToolForRole(role);
        return t == Items.AIR ? defaultFor(role) : t;
    }

    /** Numeric registry id of {@link #iconItem} ({@code 0} = air / no icon). Used by the JOB bubble
     *  payload and by the name-tag live-refresh to detect when a citizen's icon item changed. */
    public static int iconItemId(Settlement settlement, String typeId) {
        return BuiltInRegistries.ITEM.getId(iconItem(settlement, typeId));
    }

    /** Icon item for a citizen, with its assigned-workshop type. A generic Crafter ({@code typeId} =
     *  {@link com.bannerbound.core.entity.CrafterWorkGoal#JOB_TYPE_ID}) draws the icon of the workshop
     *  it staffs (fletchery → string, carpentry → planks, pottery → clay, …) so the four crafter
     *  specialties read distinctly even though they share one job id. Falls back to the plain
     *  {@link #iconItem(Settlement,String)} for non-crafters or an unknown/mixed workshop type. */
    public static Item iconItem(Settlement settlement, String typeId, String workshopType) {
        if (com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(typeId)
                && workshopType != null) {
            Item icon = com.bannerbound.core.api.workshop.WorkBlockRegistry.iconForType(workshopType);
            if (icon != null) return icon;
        }
        return iconItem(settlement, typeId);
    }

    /** Numeric id of the workshop-aware {@link #iconItem(Settlement,String,String)}. */
    public static int iconItemId(Settlement settlement, String typeId, String workshopType) {
        return BuiltInRegistries.ITEM.getId(iconItem(settlement, typeId, workshopType));
    }

    // ── Name-tag suffix glyphs ───────────────────────────────────────────────────────────────
    // Item id → PUA codepoint of the matching bitmap provider in
    // assets/bannerbound/font/icons.json. This table MUST stay in sync with that file (same item,
    // same codepoint). Core's providers use only minecraft: textures; an expansion may register a
    // glyph for its own item by shipping an ADDITIONAL assets/bannerbound/font/icons.json with the
    // matching provider — font files merge across packs (the bone spear below is Antiquity's; in a
    // Core-only install that entry is simply never hit, since the item doesn't exist).
    private static final Map<ResourceLocation, Character> GLYPHS = Map.ofEntries(
        Map.entry(id("minecraft", "wooden_axe"),    (char) 0xE110),
        Map.entry(id("minecraft", "stone_axe"),     (char) 0xE111),
        Map.entry(id("minecraft", "iron_axe"),      (char) 0xE112),
        Map.entry(id("minecraft", "wooden_shovel"), (char) 0xE113),
        Map.entry(id("minecraft", "stone_shovel"),  (char) 0xE114),
        Map.entry(id("minecraft", "iron_shovel"),   (char) 0xE115),
        Map.entry(id("minecraft", "wooden_hoe"),    (char) 0xE116),
        Map.entry(id("minecraft", "stone_hoe"),     (char) 0xE117),
        Map.entry(id("minecraft", "iron_hoe"),      (char) 0xE118),
        Map.entry(id("minecraft", "fishing_rod"),   (char) 0xE119),
        Map.entry(id("minecraft", "lead"),          (char) 0xE11A),
        Map.entry(id("minecraft", "poppy"),         (char) 0xE11B),
        Map.entry(id("minecraft", "wooden_sword"),  (char) 0xE11C),
        Map.entry(id("minecraft", "stone_sword"),   (char) 0xE11D),
        Map.entry(id("minecraft", "iron_sword"),    (char) 0xE11E),
        Map.entry(id("minecraft", "bow"),           (char) 0xE11F),
        Map.entry(id("minecraft", "cod"),           (char) 0xE120),
        Map.entry(id("bannerboundantiquity", "bone_spear"), (char) 0xE121));

    private static ResourceLocation id(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    /** The name-tag glyph string for an item, or {@code ""} when the item has no Core job glyph. */
    public static String glyphForItem(Item item) {
        Character c = GLYPHS.get(BuiltInRegistries.ITEM.getKey(item));
        return c == null ? "" : String.valueOf(c.charValue());
    }

    /** The name-tag glyph string for a citizen's job at the settlement's current tool age, or
     *  {@code ""} when unemployed / no matching glyph. A tiered tool with no Core glyph (an
     *  Antiquity-age tool) falls back to the wooden tier. Render it pinned to the icons font (e.g.
     *  {@link com.bannerbound.core.api.Glyphs#ICONS_STYLE}) so a parent colour doesn't tint it. */
    public static String jobGlyph(Settlement settlement, String typeId) {
        if (typeId == null) return "";
        String g = glyphForItem(iconItem(settlement, typeId));
        if (!g.isEmpty()) return g;
        // Resolved tool has no Core glyph (a modded tool-age item, e.g. an Antiquity fiber rope or
        // bone tool): show the role's Core baseline glyph (lead / wooden tier / poppy / rod).
        return glyphForItem(defaultFor(roleForJob(typeId)));
    }
}
