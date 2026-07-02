package com.bannerbound.antiquity.metalworking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.HammerItem;
import com.bannerbound.antiquity.item.KnifeItem;
import com.bannerbound.antiquity.item.TongsItem;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * All net-new metalworking items, registered in templated loops so the casting flow stays
 * data-shaped (METALWORKING_PLAN.md). One generic naming scheme runs end to end:
 *
 * <pre>
 *   mold (tool-named)  ──cast──►  casting (part-named)  ──+stick──►  tool (tool-named)
 *   clay_mold_axe → fired_clay_mold_axe → copper_axe_head → copper_axe
 *   clay_mold_sword                      → copper_blade    → copper_sword
 *   clay_mold_chisel / clay_mold_ingot   → (direct, no head) → copper_chisel / *_ingot
 * </pre>
 *
 * <p>Items are stored in id-keyed maps ({@link #MOLDS}, {@link #CASTINGS}, {@link #TOOLS},
 * {@link #HAMMERS}) so the stations, recipes, and JEI can look them up by logical id rather than
 * needing a static field each. {@link #register()} is called from the mod constructor before the
 * registers are bound to the bus.
 */
public final class MetalworkingItems {
    private MetalworkingItems() {}

    /** Castable metals, in tech order. Copper uses the vanilla ingot; tin/bronze add their own.
     *  Per-metal numbers (colour, rank, melt point, mB) are data-driven in {@link MetalworkingData}. */
    public static final List<String> METALS = List.of("copper", "tin", "bronze");

    /** Haft shapes: a mold casts {@code <metal>_<castingSuffix>}, hafted (+stick) into the tool. */
    private enum Haft {
        AXE("axe", "axe_head"),
        PICKAXE("pickaxe", "pickaxe_head"),
        HOE("hoe", "hoe_head"),
        SHOVEL("shovel", "shovel_head"),
        SWORD("sword", "blade"),
        KNIFE("knife", "knife_head"),
        HAMMER("hammer", "hammer_head");
        final String tool;
        final String castingSuffix;
        Haft(String tool, String castingSuffix) { this.tool = tool; this.castingSuffix = castingSuffix; }
    }

    // Direct shapes (chisel, ingot) cast the finished item — no head, no hafting; handled inline below.

    /** Every mold shape (tool-named), in the order the data files reference them. The spear/arrow
     *  shapes cast a head ({@code <metal>_spear_point} / {@code <metal>_arrow_head}) that is finished
     *  off-anvil — hafted at the Crafting Stone (spear) or fletched at the Fletching Station (arrow). */
    public static final List<String> MOLD_SHAPES = List.of(
        "axe", "pickaxe", "hoe", "shovel", "sword", "knife", "hammer", "chisel", "ingot",
        "spear", "arrow");

    // ── Tiers ───────────────────────────────────────────────────────────────────────────────────
    public static final Tier COPPER = metalTier(180, 5.0F, 1.0F, BlockTags.INCORRECT_FOR_IRON_TOOL, 12, "copper");
    public static final Tier TIN    = metalTier(120, 4.0F, 0.5F, BlockTags.INCORRECT_FOR_STONE_TOOL, 8, "tin");
    public static final Tier BRONZE = metalTier(375, 7.0F, 2.0F, BlockTags.INCORRECT_FOR_IRON_TOOL, 18, "bronze");

    private static Tier tierFor(String metal) {
        return switch (metal) {
            case "copper" -> COPPER;
            case "tin" -> TIN;
            case "bronze" -> BRONZE;
            default -> Tiers.STONE;
        };
    }

    // ── Item maps (id → registered item) ─────────────────────────────────────────────────────────
    public static final Map<String, DeferredItem<Item>> MOLDS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<Item>> CASTINGS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<? extends Item>> TOOLS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<? extends Item>> HAMMERS = new LinkedHashMap<>();
    /** Crucible tongs: {@code wooden_tongs} (pre-metal) + one per metal. Carried off-hand to lift a
     *  molten crucible without burning (see {@link TongsItem}). */
    public static final Map<String, DeferredItem<? extends Item>> TONGS = new LinkedHashMap<>();

    public static void register() {
        var items = BannerboundAntiquity.ITEMS;

        // Molds: one unfired base, then per-shape unfired + fired (the fired one is what's placed/poured).
        MOLDS.put("clay_mold_base", items.registerSimpleItem("clay_mold_base", new Item.Properties()));
        for (String shape : MOLD_SHAPES) {
            MOLDS.put("clay_mold_" + shape,
                items.registerSimpleItem("clay_mold_" + shape, new Item.Properties()));
            MOLDS.put("fired_clay_mold_" + shape,
                items.registerSimpleItem("fired_clay_mold_" + shape, new Item.Properties()));
        }

        // Stone hammer + its knapped head (the entry point — works copper/tin, caps bronze at Standard).
        CASTINGS.put("stone_hammer_head",
            items.registerSimpleItem("stone_hammer_head", new Item.Properties()));
        HAMMERS.put("stone_hammer",
            items.registerItem("stone_hammer", p -> new HammerItem(p, Tiers.STONE, "stone"), new Item.Properties()));

        // Green-wood tongs — the pre-metal stopgap that lets you lift your very first molten crucible
        // (chars out fast). The per-metal tongs are registered alongside their metal in the loop below.
        TONGS.put("wooden_tongs",
            items.registerItem("wooden_tongs", p -> new TongsItem(p, tongsDurability("wooden")),
                new Item.Properties()));

        for (String metal : METALS) {
            Tier tier = tierFor(metal);

            // Haft-shape castings + their hafted tools.
            for (Haft h : Haft.values()) {
                CASTINGS.put(metal + "_" + h.castingSuffix,
                    items.registerSimpleItem(metal + "_" + h.castingSuffix, new Item.Properties()));
                TOOLS.put(metal + "_" + h.tool, registerTool(metal, h, tier));
                if (h == Haft.HAMMER) {
                    HAMMERS.put(metal + "_hammer", TOOLS.get(metal + "_hammer"));
                }
            }

            // Spear point + arrow head: cast like the other heads, but NOT Haft entries — they're
            // finished off-anvil (spear hafted at the Crafting Stone, arrow fletched at the Fletching
            // Station), so the hammer minigame (castingInfo) deliberately doesn't pick them up.
            CASTINGS.put(metal + "_spear_point",
                items.registerSimpleItem(metal + "_spear_point", new Item.Properties()));
            CASTINGS.put(metal + "_arrow_head",
                items.registerSimpleItem(metal + "_arrow_head", new Item.Properties()));

            // Crucible tongs for this metal — crafted (not cast), held off-hand to carry a molten crucible.
            TONGS.put(metal + "_tongs",
                items.registerItem(metal + "_tongs", p -> new TongsItem(p, tongsDurability(metal)),
                    new Item.Properties()));

            // Direct-cast finished items (chisel always; ingot only for tin/bronze — copper uses vanilla).
            TOOLS.put(metal + "_chisel",
                items.registerSimpleItem(metal + "_chisel", new Item.Properties()));
            if (!metal.equals("copper")) {
                TOOLS.put(metal + "_ingot",
                    items.registerSimpleItem(metal + "_ingot", new Item.Properties()));
            }
        }
    }

    private static DeferredItem<? extends Item> registerTool(String metal, Haft h, Tier tier) {
        var items = BannerboundAntiquity.ITEMS;
        String id = metal + "_" + h.tool;
        return switch (h) {
            case AXE -> items.registerItem(id,
                p -> new AxeItem(tier, p.attributes(AxeItem.createAttributes(tier, 5.0F, -3.1F))),
                new Item.Properties());
            case PICKAXE -> items.registerItem(id,
                p -> new PickaxeItem(tier, p.attributes(PickaxeItem.createAttributes(tier, 1.0F, -2.8F))),
                new Item.Properties());
            case HOE -> items.registerItem(id,
                p -> new HoeItem(tier, p.attributes(HoeItem.createAttributes(tier, 0.0F, -1.0F))),
                new Item.Properties());
            case SHOVEL -> items.registerItem(id,
                p -> new ShovelItem(tier, p.attributes(ShovelItem.createAttributes(tier, 1.5F, -3.0F))),
                new Item.Properties());
            case SWORD -> items.registerItem(id,
                p -> new SwordItem(tier, p.attributes(SwordItem.createAttributes(tier, 3, -2.4F))),
                new Item.Properties());
            case KNIFE -> items.registerItem(id,
                p -> new KnifeItem(p, tier.getUses(), 3.0, 2.0),
                new Item.Properties());
            case HAMMER -> items.registerItem(id,
                p -> new HammerItem(p, tier, metal),
                new Item.Properties());
        };
    }

    /** How many "iterations" (≈ seconds, 1 durability each) a tongs survives carrying a molten crucible.
     *  Green wood chars out fast; metal lasts, climbing with the metal's quality. */
    private static int tongsDurability(String metal) {
        return switch (metal) {
            case "tin" -> 128;
            case "copper" -> 192;
            case "bronze" -> 384;
            default -> 48; // wooden — the disposable green-wood stopgap
        };
    }

    private static Tier metalTier(int uses, float speed, float dmg, TagKey<Block> incorrect,
                                  int enchant, String metalId) {
        return new Tier() {
            @Override public int getUses() { return uses; }
            @Override public float getSpeed() { return speed; }
            @Override public float getAttackDamageBonus() { return dmg; }
            @Override public TagKey<Block> getIncorrectBlocksForDrops() { return incorrect; }
            @Override public int getEnchantmentValue() { return enchant; }
            @Override public Ingredient getRepairIngredient() { return repairIngredient(metalId); }
        };
    }

    /** Repair material for a metal: copper → vanilla ingot; tin/bronze → their cast ingot. */
    private static Ingredient repairIngredient(String metalId) {
        if (metalId.equals("copper")) return Ingredient.of(Items.COPPER_INGOT);
        DeferredItem<? extends Item> ingot = TOOLS.get(metalId + "_ingot");
        return ingot != null ? Ingredient.of(ingot.get()) : Ingredient.EMPTY;
    }

    /** The metal a raw ore melts into in a crucible, or {@code null} if it isn't a smeltable ore.
     *  Copper from vanilla copper; tin from our raw tin / tin ore. (Bronze is alloyed in the crucible
     *  by charging both copper and tin — there is no bronze ore.) */
    public static String oreMetal(ItemStack stack) {
        if (stack.is(Items.RAW_COPPER) || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)) {
            return "copper";
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.equals("raw_tin") || path.equals("tin_ore")) return "tin";
        return null;
    }

    /** The molten display colour for a metal id (data-driven). */
    public static int colorOf(String metalId) {
        return MetalworkingData.color(metalId);
    }

    /** The metal + mB a single smeltable item yields in a crucible (raw ore, ingot, casting, tool…). */
    public record MeltValue(String metalId, int mb) {}

    /** What {@code stack} melts into, or {@code null} if it isn't smeltable. Raw ore and any metal item
     *  (ingot/head/blade/tool) yield that metal's data-driven mB-per-unit. */
    public static MeltValue meltValue(ItemStack stack) {
        String ore = oreMetal(stack);
        if (ore != null) return new MeltValue(ore, MetalworkingData.mbPerUnit(ore));
        String m = metalOf(stack.getItem());
        if (!m.isEmpty()) return new MeltValue(m, MetalworkingData.mbPerUnit(m));
        return null;
    }

    public static boolean isSmeltable(ItemStack stack) {
        return meltValue(stack) != null;
    }

    /** Resolve a whole charge to one molten metal. Alloying is <b>ratio-driven</b>: copper + tin in the
     *  right proportions (per {@link MetalworkingData.AlloyDef}) alloy to <b>bronze</b>; an off-ratio mix
     *  (or a contaminated melt) falls back to the metal with the most mB. {@code null} if nothing smeltable. */
    public static MeltValue resolveCharge(List<ItemStack> charge) {
        Map<String, Integer> by = new LinkedHashMap<>();
        int total = 0;
        for (ItemStack s : charge) {
            MeltValue v = meltValue(s);
            if (v == null) continue;
            by.merge(v.metalId(), v.mb(), Integer::sum);
            total += v.mb();
        }
        if (total <= 0) return null;
        // Data-driven, ratio-gated alloying: the charge must hit each component's mB-fraction band.
        String metal = null;
        for (MetalworkingData.AlloyDef alloy : MetalworkingData.alloys()) {
            if (alloy.matches(by, total)) {
                metal = alloy.result();
                break;
            }
        }
        if (metal == null) {
            String best = "";
            int bestMb = -1;
            for (Map.Entry<String, Integer> e : by.entrySet()) {
                if (e.getValue() > bestMb) { bestMb = e.getValue(); best = e.getKey(); }
            }
            metal = best;
        }
        return new MeltValue(metal, total);
    }

    /** The metal an item belongs to from its id prefix ({@code copper_sword → "copper"}), or "". */
    public static String metalOf(Item item) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        for (String m : METALS) {
            if (path.startsWith(m + "_") || path.equals(m)) return m;
        }
        return "";
    }

    // ── Casting helpers (used by the anvil; will move to mold_recipes data later) ──────────────────

    /** Millibuckets a mold of this shape must hold before it casts (data-driven). */
    public static int requiredMb(String shape) {
        return MetalworkingData.requiredMb(shape);
    }

    /** The casting suffix for a haft shape (axe → axe_head, sword → blade), or {@code null} if the
     *  shape casts a finished item directly (chisel, ingot). */
    public static String haftCastingSuffix(String shape) {
        for (Haft h : Haft.values()) {
            if (h.tool.equals(shape)) return h.castingSuffix;
        }
        return null;
    }

    /** What a {@code (shape, metal)} mold casts: a head/blade for haft shapes, the finished item for
     *  direct shapes (copper ingot → vanilla). The cold-hammer minigame later stamps quality (and
     *  hafts +stick for haft shapes). Empty if the combo is unknown. */
    public static ItemStack castingFor(String shape, String metalId) {
        String suffix = haftCastingSuffix(shape);
        if (suffix != null) {
            DeferredItem<Item> casting = CASTINGS.get(metalId + "_" + suffix);
            return casting != null ? new ItemStack(casting.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("ingot")) {
            if (metalId.equals("copper")) return new ItemStack(Items.COPPER_INGOT);
            DeferredItem<? extends Item> ingot = TOOLS.get(metalId + "_ingot");
            return ingot != null ? new ItemStack(ingot.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("chisel")) {
            DeferredItem<? extends Item> chisel = TOOLS.get(metalId + "_chisel");
            return chisel != null ? new ItemStack(chisel.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("spear")) {
            DeferredItem<Item> head = CASTINGS.get(metalId + "_spear_point");
            return head != null ? new ItemStack(head.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("arrow")) {
            DeferredItem<Item> head = CASTINGS.get(metalId + "_arrow_head");
            return head != null ? new ItemStack(head.get()) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    /** The mold shape a <b>template tool</b> imprints into a base clay mold, or {@code null} if the
     *  held item isn't a valid template. Only <b>wood/stone</b> tools work (bone and metal don't):
     *  tiered diggers/swords gate on {@link Tiers#WOOD}/{@link Tiers#STONE}; knife/chisel/hammer match
     *  the primitive items; any ingot imprints the ingot mold. */
    public static String templateShape(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.TieredItem tiered) {
            Tier t = tiered.getTier();
            if (t == Tiers.WOOD || t == Tiers.STONE) {
                if (item instanceof net.minecraft.world.item.AxeItem) return "axe";
                if (item instanceof net.minecraft.world.item.PickaxeItem) return "pickaxe";
                if (item instanceof HoeItem) return "hoe";
                if (item instanceof net.minecraft.world.item.ShovelItem) return "shovel";
                if (item instanceof SwordItem) return "sword";
            }
            return null; // bone/metal tiers (or other tiered items) can't imprint
        }
        if (item instanceof com.bannerbound.antiquity.item.HammerItem hammer) {
            return hammer.rank() == 0 ? "hammer" : null; // stone hammer only
        }
        // Any spear imprints the spear-point mold; any arrow (the #minecraft:arrows tag) the arrow-head mold.
        if (item instanceof com.bannerbound.antiquity.item.SpearItem) return "spear";
        if (stack.is(net.minecraft.tags.ItemTags.ARROWS)) return "arrow";
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.equals("flint_knife") || path.equals("wooden_knife")) return "knife";
        if (path.equals("stone_chisel")) return "chisel";
        if (path.endsWith("_ingot")) return "ingot";
        return null;
    }

    /** What a cast head/blade hammers into. {@code resultTool} is the finished tool, {@code needsStick}
     *  whether the haft step consumes a stick. */
    public record CastingInfo(String metalId, String shape, Item resultTool, boolean needsStick) {}

    /** The hammer-minigame result for a haft casting ({@code copper_blade → copper sword}), or null if
     *  the item isn't a haftable metal casting. */
    public static CastingInfo castingInfo(Item item) {
        for (Map.Entry<String, DeferredItem<Item>> e : CASTINGS.entrySet()) {
            if (e.getValue().get() != item) continue;
            String id = e.getKey();
            for (String metal : METALS) {
                if (!id.startsWith(metal + "_")) continue;
                String suffix = id.substring(metal.length() + 1);
                for (Haft h : Haft.values()) {
                    if (h.castingSuffix.equals(suffix)) {
                        DeferredItem<? extends Item> tool = TOOLS.get(metal + "_" + h.tool);
                        if (tool != null) {
                            return new CastingInfo(metal, h.tool, tool.get(), true);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The mold shape a fired-mold item represents ({@code fired_clay_mold_axe → "axe"}), or null. */
    public static String shapeOfFiredMold(Item item) {
        for (Map.Entry<String, DeferredItem<Item>> e : MOLDS.entrySet()) {
            String id = e.getKey();
            if (id.startsWith("fired_clay_mold_") && e.getValue().get() == item) {
                return id.substring("fired_clay_mold_".length());
            }
        }
        return null;
    }
}
