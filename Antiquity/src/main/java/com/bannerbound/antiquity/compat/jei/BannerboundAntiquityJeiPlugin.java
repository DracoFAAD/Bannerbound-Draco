package com.bannerbound.antiquity.compat.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.workshop.CarpentryAssembly;
import com.bannerbound.antiquity.workshop.CarpentryAssemblyManager;
import com.bannerbound.antiquity.workshop.CarpentryOutput;
import com.bannerbound.antiquity.workshop.CarpentryOutputManager;
import com.bannerbound.antiquity.workshop.WoodFamily;
import com.bannerbound.antiquity.workshop.MetalworkingData;
import com.bannerbound.antiquity.workshop.MetalworkingItems;
import com.bannerbound.antiquity.recipe.*;
import com.bannerbound.core.Config;
import com.bannerbound.core.compat.jei.BannerboundCoreJeiPlugin;
import com.bannerbound.core.client.ClientResearchState;
import com.bannerbound.core.client.ClientStartingItems;
import com.bannerbound.core.client.UnknownItemHelper;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.gui.placement.VerticalAlignment;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.bannerbound.antiquity.craft.Carpentry;

/**
 * JEI integration for Antiquity: registers every custom recipe category (crafting stone, fletching,
 * pottery, bloomery, kiln, mortar, drying rack, carpentry, construction, casting, cold hammer) plus
 * ingredient info pages and catalysts. Carpentry, Construction, and Casting entries are synthetic
 * records built in code: carpentry expands WoodFamily x CarpentryOutput plus multi-ingredient
 * assemblies; casting expands (metal, shape) pairs from the data-driven MetalworkingData /
 * MetalworkingItems mB tables (mold imprinting mirrors MetalworkingItems#templateShape; the Kiln
 * category covers firing the imprinted mold); Construction documents in-world right-click
 * interactions (knapping, tying, fire-lighting, spear fishing, manure/dung...). The two bootstrap
 * 2x2-inventory-grid recipes (flint knife, cobblestone from rocks) also live under Construction
 * because the mod gates the uncraftable crafting table out of JEI, which removes the whole vanilla
 * Crafting category and would take them with it. The fire_sticks entry outputs a Campfire identical
 * to its input (lit is a blockstate, not an item) so looking up the Campfire surfaces the lighting
 * interaction; stations with no item form (bloomery, kiln, raft) show ItemStack.EMPTY outputs and
 * rely on their note text. Knowledge gate: unless Config.JEI_SHOW_UNKNOWN is set, a recipe hides
 * when its output or every choice of any input is unknown to the local player, but a known output
 * always shows (the player can craft it, so input scans short-circuit); the gate re-runs from
 * ClientResearchState/ClientStartingItems listeners and diffs against hiddenRecipes, unhiding the
 * previous set before hiding the next so toggles never leak stale hides.
 */
@JeiPlugin
@OnlyIn(Dist.CLIENT)
public final class BannerboundAntiquityJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "recipes");

    public static final RecipeType<CraftingStoneRecipe> CRAFTING_STONE =
        RecipeType.create(BannerboundAntiquity.MODID, "crafting_stone", CraftingStoneRecipe.class);
    public static final RecipeType<FletchingRecipe> FLETCHING =
        RecipeType.create(BannerboundAntiquity.MODID, "fletching", FletchingRecipe.class);
    public static final RecipeType<PotteryRecipe> POTTERY =
        RecipeType.create(BannerboundAntiquity.MODID, "pottery", PotteryRecipe.class);
    public static final RecipeType<BloomeryRecipe> BLOOMERY =
        RecipeType.create(BannerboundAntiquity.MODID, "bloomery", BloomeryRecipe.class);
    public static final RecipeType<KilnRecipe> KILN =
        RecipeType.create(BannerboundAntiquity.MODID, "kiln", KilnRecipe.class);
    public static final RecipeType<MortarRecipe> MORTAR =
        RecipeType.create(BannerboundAntiquity.MODID, "mortar", MortarRecipe.class);
    public static final RecipeType<DryingRackRecipe> DRYING =
        RecipeType.create(BannerboundAntiquity.MODID, "drying", DryingRackRecipe.class);
    public static final RecipeType<CarpentryRecipe> CARPENTRY =
        RecipeType.create(BannerboundAntiquity.MODID, "carpentry", CarpentryRecipe.class);
    public static final RecipeType<ConstructionRecipe> CONSTRUCTION =
        RecipeType.create(BannerboundAntiquity.MODID, "construction", ConstructionRecipe.class);
    public static final RecipeType<CastingRecipe> CASTING =
        RecipeType.create(BannerboundAntiquity.MODID, "casting", CastingRecipe.class);
    public static final RecipeType<AnvilRecipe> COLD_HAMMER =
        RecipeType.create(BannerboundAntiquity.MODID, "cold_hammer", AnvilRecipe.class);
    public static final RecipeType<KnappingRecipe> KNAPPING =
            RecipeType.create(BannerboundAntiquity.MODID, "knapping", KnappingRecipe.class);

    private static List<ConstructionRecipe> constructionRecipes;
    private static List<CarpentryRecipe> carpentryRecipeCache = List.of();
    private static List<KnappingRecipe> knappingRecipeCache = List.of();
    private static List<CastingRecipe> castingRecipes;

    private static List<ConstructionRecipe> constructionRecipes() {
        if (constructionRecipes == null) {
            List<ConstructionRecipe> base = new ArrayList<>(List.of(
                new ConstructionRecipe(
                    id("knap_gravel"),
                    List.of(
                        List.of(new ItemStack(Items.GRAVEL)),
                        hardSurfaces()
                    ),
                    new ItemStack(Items.FLINT),
                    Component.translatable("bannerboundantiquity.jei.construction.knap_gravel.note")
                ),
                new ConstructionRecipe(
                    id("knap_flint_blade"),
                    List.of(
                        List.of(new ItemStack(Items.FLINT)),
                        hardSurfaces()
                    ),
                    new ItemStack(BannerboundAntiquity.FLINT_BLADE.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.knap_flint_blade.note")
                ),
                new ConstructionRecipe(
                    id("knap_bone_blade"),
                    List.of(
                        List.of(new ItemStack(Items.BONE)),
                        hardSurfaces()
                    ),
                    new ItemStack(BannerboundAntiquity.BONE_BLADE.get(), 2),
                    Component.translatable("bannerboundantiquity.jei.construction.knap_bone_blade.note")
                ),
                new ConstructionRecipe(
                    id("cut_plant_fiber"),
                    List.of(
                        cuttingTools(),
                        grassChoices()
                    ),
                    new ItemStack(BannerboundAntiquity.PLANT_FIBER.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.cut_plant_fiber.note")
                ),
                new ConstructionRecipe(
                    id("cut_dry_fiber"),
                    List.of(
                        cuttingTools(),
                        List.of(new ItemStack(Items.DEAD_BUSH))
                    ),
                    new ItemStack(BannerboundAntiquity.DRY_FIBER.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.cut_dry_fiber.note")
                ),
                new ConstructionRecipe(
                    id("cut_sticks"),
                    List.of(
                        cuttingTools(),
                        leafChoices()
                    ),
                    new ItemStack(Items.STICK),
                    Component.translatable("bannerboundantiquity.jei.construction.cut_sticks.note")
                ),
                new ConstructionRecipe(
                    id("flint_knife"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.FLINT_BLADE.get())),
                        List.of(new ItemStack(Items.STICK)),
                        List.of(new ItemStack(BannerboundAntiquity.PLANT_FIBER.get()),
                                new ItemStack(BannerboundAntiquity.DRY_FIBER.get()))
                    ),
                    new ItemStack(BannerboundAntiquity.FLINT_KNIFE.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.flint_knife.note")
                ),
                new ConstructionRecipe(
                    id("cobblestone_from_rocks"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.STONE_ROCK_ITEM.get(), 4))
                    ),
                    new ItemStack(Items.COBBLESTONE),
                    Component.translatable("bannerboundantiquity.jei.construction.cobblestone_from_rocks.note")
                ),
                new ConstructionRecipe(
                    id("crafting_stone"),
                    List.of(
                        craftingStoneCarvers(),
                        craftingStoneSources()
                    ),
                    new ItemStack(BannerboundAntiquity.CRAFTING_STONE_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.crafting_stone.note")
                ),
                new ConstructionRecipe(
                    id("chopping_stump"),
                    List.of(
                        axeChoices(),
                        logChoices(1)
                    ),
                    new ItemStack(BannerboundAntiquity.CHOPPING_STUMP_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.chopping_stump.note")
                ),
                new ConstructionRecipe(
                    id("split_firewood"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.CHOPPING_STUMP_ITEM.get())),
                        logChoices(1),
                        axeChoices()
                    ),
                    new ItemStack(BannerboundAntiquity.FIREWOOD.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.split_firewood.note")
                ),
                new ConstructionRecipe(
                    id("stack_firewood"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.FIREWOOD.get(), 4))
                    ),
                    new ItemStack(Items.CAMPFIRE),
                    Component.translatable("bannerboundantiquity.jei.construction.stack_firewood.note")
                ),
                new ConstructionRecipe(
                    id("fire_sticks"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get())),
                        List.of(new ItemStack(Items.CAMPFIRE))
                    ),
                    new ItemStack(Items.CAMPFIRE),
                    Component.translatable("bannerboundantiquity.jei.construction.fire_sticks.note")
                ),
                new ConstructionRecipe(
                    id("mortar_water"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.MORTAR_AND_PESTLE_ITEM.get())),
                        List.of(new ItemStack(Items.GLASS_BOTTLE))
                    ),
                    waterBottle(),
                    Component.translatable("bannerboundantiquity.jei.construction.mortar_water.note")
                ),
                new ConstructionRecipe(
                    id("bloomery"),
                    List.of(
                        List.of(new ItemStack(Items.MUD_BRICKS, 2)),
                        List.of(new ItemStack(Items.COAL_BLOCK))
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.bloomery.note")
                ),
                new ConstructionRecipe(
                    id("kiln"),
                    List.of(
                        List.of(new ItemStack(Items.CLAY_BALL, 8)),
                        List.of(new ItemStack(Items.COBBLESTONE, 8))
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.kiln.note")
                ),
                new ConstructionRecipe(
                    id("stockpile"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.BASKET_ITEM.get())),
                        List.of(new ItemStack(Items.COBBLESTONE))
                    ),
                    new ItemStack(com.bannerbound.core.BannerboundCore.STOCKPILE_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.stockpile.note")
                ),
                new ConstructionRecipe(
                    id("fletching_station"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.PLANT_STRING.get())),
                        List.of(new ItemStack(Items.COBBLESTONE))
                    ),
                    new ItemStack(BannerboundAntiquity.FLETCHING_STATION_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.fletching_station.note")
                ),
                new ConstructionRecipe(
                    id("pottery_slab"),
                    List.of(
                        List.of(new ItemStack(Items.CLAY_BALL, 2)),
                        List.of(new ItemStack(BannerboundAntiquity.CLAYED_COBBLESTONE_ITEM.get()))
                    ),
                    new ItemStack(BannerboundAntiquity.POTTERY_SLAB_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.pottery_slab.note")
                ),
                new ConstructionRecipe(
                    id("woodworking_table"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.BONE_SAW.get())),
                        List.of(new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.SPRUCE_LOG, 2),
                            new ItemStack(Items.BIRCH_LOG, 2), new ItemStack(Items.JUNGLE_LOG, 2),
                            new ItemStack(Items.ACACIA_LOG, 2), new ItemStack(Items.DARK_OAK_LOG, 2),
                            new ItemStack(Items.MANGROVE_LOG, 2), new ItemStack(Items.CHERRY_LOG, 2))
                    ),
                    new ItemStack(BannerboundAntiquity.WOODWORKING_TABLE_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.woodworking_table.note")
                ),
                new ConstructionRecipe(
                    id("masons_bench"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.STONE_CHISEL.get())),
                        List.of(new ItemStack(Items.STONE, 2), new ItemStack(Items.COBBLESTONE, 2))
                    ),
                    new ItemStack(BannerboundAntiquity.MASONS_BENCH_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.masons_bench.note")
                ),
                new ConstructionRecipe(
                    id("tanning_rack"),
                    List.of(
                        cuttingTools(),
                        logChoices(4)
                    ),
                    new ItemStack(BannerboundAntiquity.TANNING_RACK_ITEM.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.tanning_rack.note")
                ),
                new ConstructionRecipe(
                    id("drying_rack"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.BONE_BLADE.get())),
                        logChoices(1)
                    ),
                    new ItemStack(BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak").get()),
                    Component.translatable("bannerboundantiquity.jei.construction.drying_rack.note")
                ),
                new ConstructionRecipe(
                    id("raft"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.OAR.get())),
                        List.of(new ItemStack(BannerboundAntiquity.THATCH_ITEM.get(), 3))
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.raft.note")
                ),
                new ConstructionRecipe(
                    id("rope_fence_tie"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.FIBER_ROPE.get())),
                        ropeFenceChoices()
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.rope_fence_tie.note")
                ),
                new ConstructionRecipe(
                    id("raft_tie"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.FIBER_ROPE.get())),
                        fenceChoices()
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.raft_tie.note")
                ),
                new ConstructionRecipe(
                    id("spear_fishing"),
                    List.of(
                        spearChoices(),
                        fishChoices()
                    ),
                    new ItemStack(Items.COD),
                    Component.translatable("bannerboundantiquity.jei.construction.spear_fishing.note")
                ),
                new ConstructionRecipe(
                    id("clear_manure"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.MANURE_ITEM.get())),
                        shovelChoices()
                    ),
                    new ItemStack(BannerboundAntiquity.DUNG.get()),
                    Component.translatable("bannerboundantiquity.jei.construction.clear_manure.note")
                ),
                new ConstructionRecipe(
                    id("fertilize"),
                    List.of(
                        List.of(new ItemStack(BannerboundAntiquity.DUNG.get()), new ItemStack(Items.BONE_MEAL)),
                        fertilizeTargets()
                    ),
                    ItemStack.EMPTY,
                    Component.translatable("bannerboundantiquity.jei.construction.fertilize.note")
                )
            ));
            base.addAll(moldImprintRecipes());
            constructionRecipes = List.copyOf(base);
        }
        return constructionRecipes;
    }

    private static List<ConstructionRecipe> moldImprintRecipes() {
        List<ConstructionRecipe> list = new ArrayList<>();
        ItemStack base = new ItemStack(MetalworkingItems.MOLDS.get("clay_mold_base").get());
        for (String shape : MetalworkingItems.MOLD_SHAPES) {
            var mold = MetalworkingItems.MOLDS.get("clay_mold_" + shape);
            List<ItemStack> templates = templateChoices(shape);
            if (mold == null || templates.isEmpty()) {
                continue;
            }
            list.add(new ConstructionRecipe(
                id("imprint_mold/" + shape),
                List.of(templates, List.of(base.copy())),
                new ItemStack(mold.get()),
                Component.translatable("bannerboundantiquity.jei.construction.imprint_mold.note")));
        }
        return list;
    }

    private static List<ItemStack> templateChoices(String shape) {
        return switch (shape) {
            case "axe" -> List.of(new ItemStack(Items.WOODEN_AXE), new ItemStack(Items.STONE_AXE));
            case "pickaxe" -> List.of(new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE));
            case "hoe" -> List.of(new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.STONE_HOE));
            case "shovel" -> List.of(new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.STONE_SHOVEL));
            case "sword" -> List.of(new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD));
            case "knife" -> List.of(new ItemStack(BannerboundAntiquity.FLINT_KNIFE.get()),
                new ItemStack(BannerboundAntiquity.WOODEN_KNIFE.get()));
            case "chisel" -> List.of(new ItemStack(BannerboundAntiquity.STONE_CHISEL.get()));
            case "hammer" -> stoneHammerChoice();
            case "ingot" -> List.of(new ItemStack(Items.COPPER_INGOT));
            default -> List.of();
        };
    }

    private static List<ItemStack> stoneHammerChoice() {
        var hammer = MetalworkingItems.HAMMERS.get("stone_hammer");
        return hammer == null ? List.of() : List.of(new ItemStack(hammer.get()));
    }

    private static List<KnappingRecipe> knappingRecipes() {
        List<KnappingShape> shapes = KnappingShapeManager.all();
        List<KnappingRecipe> recipes = new ArrayList<>();

        for (KnappingShape shape : shapes) {
            recipes.add(new KnappingRecipe(shape.keep(), shape.head().getDefaultInstance(), Component.empty()));
        }

        return recipes;
    }
    private static List<CastingRecipe> castingRecipes() {
        if (castingRecipes == null) {
            List<CastingRecipe> list = new ArrayList<>();
            for (String metal : MetalworkingItems.METALS) {
                for (String shape : MetalworkingItems.MOLD_SHAPES) {
                    ItemStack output = MetalworkingItems.castingFor(shape, metal);
                    if (output.isEmpty()) {
                        continue;
                    }
                    int mb = MetalworkingItems.requiredMb(shape);
                    int per = Math.max(1, MetalworkingData.mbPerUnit(metal));
                    int units = Math.max(1, (int) Math.ceil(mb / (double) per));
                    List<List<ItemStack>> inputs = new ArrayList<>();
                    Component note;
                    if (metal.equals("bronze")) {
                        // Bronze alloy ratio: copper must stay the majority (60-90% of melt); tin ~25% of units.
                        int tinUnits = Math.max(1, Math.round(units * 0.25F));
                        int copperUnits = Math.max(tinUnits + 1, units - tinUnits);
                        inputs.add(oreChoices("copper", copperUnits));
                        inputs.add(oreChoices("tin", tinUnits));
                        note = Component.translatable("bannerboundantiquity.jei.casting.note.bronze", mb);
                    } else {
                        inputs.add(oreChoices(metal, units));
                        note = Component.translatable("bannerboundantiquity.jei.casting.note", mb);
                    }
                    list.add(new CastingRecipe(id("casting/" + metal + "_" + shape),
                        inputs, firedMold(shape), copy(output), note));
                }
            }
            castingRecipes = List.copyOf(list);
        }
        return castingRecipes;
    }

    private static List<ItemStack> oreChoices(String metal, int count) {
        return switch (metal) {
            case "copper" -> List.of(new ItemStack(Items.RAW_COPPER, count),
                new ItemStack(Items.COPPER_ORE, count), new ItemStack(Items.DEEPSLATE_COPPER_ORE, count));
            case "tin" -> List.of(new ItemStack(BannerboundAntiquity.RAW_TIN.get(), count),
                new ItemStack(BannerboundAntiquity.TIN_ORE_ITEM.get(), count));
            default -> List.of();
        };
    }

    private static ItemStack firedMold(String shape) {
        var mold = MetalworkingItems.MOLDS.get("fired_clay_mold_" + shape);
        return mold == null ? ItemStack.EMPTY : new ItemStack(mold.get());
    }

    private static List<CarpentryRecipe> carpentryRecipes() {
        List<CarpentryRecipe> recipes = new ArrayList<>();
        for (ItemStack log : carpentryLogChoices()) {
            WoodFamily family = WoodFamily.fromLog(log.getItem());
            if (family == null) {
                continue;
            }
            String familyKey = family.key().replace(':', '_');
            for (CarpentryOutput output : CarpentryOutputManager.all()) {
                Item item = family.variant(output.variant());
                if (item == null) {
                    continue;
                }
                recipes.add(new CarpentryRecipe(
                    id("carpentry/" + familyKey + "/" + output.variant()),
                    List.of(List.of(log.copyWithCount(output.logCost()))),
                    new ItemStack(item, output.yield()),
                    Component.translatable("bannerboundantiquity.jei.carpentry.note",
                        output.logCost(), output.yield())
                ));
            }
        }
        for (CarpentryAssembly assembly : CarpentryAssemblyManager.all()) {
            List<List<ItemStack>> inputs = new ArrayList<>();
            for (CarpentryAssembly.Ingredient ingredient : assembly.ingredients()) {
                List<ItemStack> choices = new ArrayList<>();
                for (Item candidate : ingredient.candidates()) {
                    choices.add(new ItemStack(candidate, ingredient.count()));
                }
                if (!choices.isEmpty()) {
                    inputs.add(choices);
                }
            }
            if (inputs.isEmpty()) {
                continue;
            }
            recipes.add(new CarpentryRecipe(
                id("carpentry/assembly/" + assembly.name()),
                inputs,
                new ItemStack(assembly.result(), assembly.yield()),
                Component.translatable("bannerboundantiquity.jei.carpentry.assembly.note",
                    assembly.yield())
            ));
        }
        return List.copyOf(recipes);
    }

    private static List<ItemStack> hardSurfaces() {
        return List.of(
            new ItemStack(Items.COBBLESTONE),
            new ItemStack(Items.STONE),
            new ItemStack(Items.SANDSTONE),
            new ItemStack(Items.COBBLED_DEEPSLATE),
            new ItemStack(Items.BLACKSTONE)
        );
    }

    private static List<ItemStack> craftingStoneSources() {
        return List.of(
            new ItemStack(Items.COBBLESTONE),
            new ItemStack(Items.SANDSTONE),
            new ItemStack(Items.RED_SANDSTONE)
        );
    }

    private static List<ItemStack> craftingStoneCarvers() {
        return List.of(
            new ItemStack(BannerboundAntiquity.FLINT_BLADE.get()),
            new ItemStack(BannerboundAntiquity.FLINT_KNIFE.get()),
            new ItemStack(BannerboundAntiquity.BONE_KNIFE.get())
        );
    }

    private static List<ItemStack> cuttingTools() {
        return List.of(
            new ItemStack(BannerboundAntiquity.FLINT_BLADE.get()),
            new ItemStack(BannerboundAntiquity.BONE_BLADE.get()),
            new ItemStack(BannerboundAntiquity.FLINT_KNIFE.get()),
            new ItemStack(BannerboundAntiquity.BONE_KNIFE.get())
        );
    }

    private static List<ItemStack> grassChoices() {
        return List.of(
            new ItemStack(Blocks.SHORT_GRASS),
            new ItemStack(Blocks.FERN),
            new ItemStack(Blocks.TALL_GRASS),
            new ItemStack(Blocks.LARGE_FERN)
        );
    }

    private static List<ItemStack> leafChoices() {
        return List.of(
            new ItemStack(Blocks.OAK_LEAVES),
            new ItemStack(Blocks.SPRUCE_LEAVES),
            new ItemStack(Blocks.BIRCH_LEAVES),
            new ItemStack(Blocks.JUNGLE_LEAVES),
            new ItemStack(Blocks.ACACIA_LEAVES),
            new ItemStack(Blocks.DARK_OAK_LEAVES),
            new ItemStack(Blocks.MANGROVE_LEAVES),
            new ItemStack(Blocks.CHERRY_LEAVES)
        );
    }

    private static List<ItemStack> logChoices(int count) {
        return List.of(
            new ItemStack(Items.OAK_LOG, count),
            new ItemStack(Items.SPRUCE_LOG, count),
            new ItemStack(Items.BIRCH_LOG, count),
            new ItemStack(Items.JUNGLE_LOG, count),
            new ItemStack(Items.ACACIA_LOG, count),
            new ItemStack(Items.DARK_OAK_LOG, count),
            new ItemStack(Items.MANGROVE_LOG, count),
            new ItemStack(Items.CHERRY_LOG, count)
        );
    }

    private static List<ItemStack> carpentryLogChoices() {
        return List.of(
            new ItemStack(Items.OAK_LOG),
            new ItemStack(Items.SPRUCE_LOG),
            new ItemStack(Items.BIRCH_LOG),
            new ItemStack(Items.JUNGLE_LOG),
            new ItemStack(Items.ACACIA_LOG),
            new ItemStack(Items.DARK_OAK_LOG),
            new ItemStack(Items.MANGROVE_LOG),
            new ItemStack(Items.CHERRY_LOG),
            new ItemStack(Items.CRIMSON_STEM),
            new ItemStack(Items.WARPED_STEM)
        );
    }

    private static List<ItemStack> axeChoices() {
        return List.of(
            new ItemStack(BannerboundAntiquity.BONE_AXE.get()),
            new ItemStack(Items.WOODEN_AXE),
            new ItemStack(Items.STONE_AXE),
            new ItemStack(Items.IRON_AXE),
            new ItemStack(Items.DIAMOND_AXE),
            new ItemStack(Items.NETHERITE_AXE)
        );
    }

    private static List<ItemStack> shovelChoices() {
        return List.of(
            new ItemStack(BannerboundAntiquity.BONE_SHOVEL.get()),
            new ItemStack(Items.WOODEN_SHOVEL),
            new ItemStack(Items.STONE_SHOVEL),
            new ItemStack(Items.IRON_SHOVEL),
            new ItemStack(Items.DIAMOND_SHOVEL),
            new ItemStack(Items.NETHERITE_SHOVEL)
        );
    }

    private static List<ItemStack> fertilizeTargets() {
        return List.of(
            new ItemStack(Items.WHEAT_SEEDS),
            new ItemStack(Items.CARROT),
            new ItemStack(Items.POTATO),
            new ItemStack(Items.BEETROOT_SEEDS),
            new ItemStack(Items.OAK_SAPLING),
            new ItemStack(Items.MELON_SEEDS),
            new ItemStack(Items.PUMPKIN_SEEDS)
        );
    }

    private static List<ItemStack> fenceChoices() {
        return List.of(
            new ItemStack(Items.OAK_FENCE),
            new ItemStack(Items.SPRUCE_FENCE),
            new ItemStack(Items.BIRCH_FENCE),
            new ItemStack(Items.JUNGLE_FENCE),
            new ItemStack(Items.ACACIA_FENCE),
            new ItemStack(Items.DARK_OAK_FENCE)
        );
    }

    private static List<ItemStack> ropeFenceChoices() {
        List<ItemStack> choices = new ArrayList<>();
        for (var item : BannerboundAntiquity.ROPE_FENCE_ITEMS) {
            choices.add(new ItemStack(item.get()));
        }
        return choices.isEmpty() ? List.of(new ItemStack(Items.OAK_FENCE)) : List.copyOf(choices);
    }

    private static List<ItemStack> spearChoices() {
        return List.of(
            new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get()),
            new ItemStack(BannerboundAntiquity.BONE_SPEAR.get()),
            new ItemStack(BannerboundAntiquity.STONE_SPEAR.get())
        );
    }

    private static List<ItemStack> fishChoices() {
        return List.of(
            new ItemStack(Items.COD),
            new ItemStack(Items.SALMON),
            new ItemStack(Items.TROPICAL_FISH),
            new ItemStack(Items.PUFFERFISH)
        );
    }

    private final Map<RecipeType<?>, List<?>> hiddenRecipes = new HashMap<>();
    private final Runnable knowledgeListener = this::applyKnowledgeGate;
    private IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
            new CraftingStoneCategory(gui),
            new FletchingCategory(gui),
            new PotteryCategory(gui),
            new BloomeryCategory(gui),
            new KilnCategory(gui),
            new MortarCategory(gui),
            new DryingRackCategory(gui),
            new CarpentryCategory(gui),
            new ConstructionCategory(gui),
            new CastingCategory(gui),
            new ColdHammerCategory(gui),
            new KnappingCategory(gui)
        );
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(CRAFTING_STONE, CraftingStoneRecipeManager.all());
        registration.addRecipes(FLETCHING, FletchingRecipeManager.all());
        registration.addRecipes(POTTERY, PotteryRecipeManager.all());
        registration.addRecipes(BLOOMERY, BloomeryRecipeManager.all());
        registration.addRecipes(KILN, KilnRecipeManager.all());
        registration.addRecipes(MORTAR, MortarRecipeManager.all());
        registration.addRecipes(DRYING, DryingRackRecipeManager.all());
        // Lists built fresh per call must be cached at registration: JEI hideRecipes matches by
        // instance, so hiding a rebuilt list is a silent no-op against the registered recipes.
        carpentryRecipeCache = carpentryRecipes();
        registration.addRecipes(CARPENTRY, carpentryRecipeCache);
        registration.addRecipes(CONSTRUCTION, constructionRecipes());
        registration.addRecipes(CASTING, castingRecipes());
        registration.addRecipes(COLD_HAMMER, AnvilRecipeManager.all());
        knappingRecipeCache = knappingRecipes();
        registration.addRecipes(KNAPPING, knappingRecipeCache);

        registration.addIngredientInfo(BannerboundAntiquity.CRAFTING_STONE_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.crafting_stone"));
        registration.addIngredientInfo(BannerboundAntiquity.FLETCHING_STATION_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.fletching_station"));
        registration.addIngredientInfo(BannerboundAntiquity.POTTERY_SLAB_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.pottery_slab"));
        registration.addIngredientInfo(BannerboundAntiquity.MORTAR_AND_PESTLE_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.mortar"));
        registration.addIngredientInfo(BannerboundAntiquity.BELLOWS_BLOCK_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.bellows"));
        registration.addIngredientInfo(BannerboundAntiquity.FLINT_BLADE.get(),
            Component.translatable("bannerboundantiquity.jei.info.flint_blade"));
        registration.addIngredientInfo(BannerboundAntiquity.BONE_BLADE.get(),
            Component.translatable("bannerboundantiquity.jei.info.bone_blade"));
        registration.addIngredientInfo(BannerboundAntiquity.FLINT_KNIFE.get(),
            Component.translatable("bannerboundantiquity.jei.info.flint_knife"));
        registration.addIngredientInfo(BannerboundAntiquity.BONE_KNIFE.get(),
            Component.translatable("bannerboundantiquity.jei.info.bone_knife"));
        registration.addIngredientInfo(BannerboundAntiquity.WOODEN_KNIFE.get(),
            Component.translatable("bannerboundantiquity.jei.info.wooden_knife"));
        registration.addIngredientInfo(BannerboundAntiquity.STONE_KNIFE.get(),
            Component.translatable("bannerboundantiquity.jei.info.stone_knife"));
        registration.addIngredientInfo(BannerboundAntiquity.CHOPPING_STUMP_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.chopping_stump"));
        registration.addIngredientInfo(BannerboundAntiquity.FIREWOOD.get(),
            Component.translatable("bannerboundantiquity.jei.info.firewood"));
        registration.addIngredientInfo(BannerboundAntiquity.FIRE_STICKS.get(),
            Component.translatable("bannerboundantiquity.jei.info.fire_sticks"));
        registration.addIngredientInfo(BannerboundAntiquity.FIBER_ROPE.get(),
            Component.translatable("bannerboundantiquity.jei.info.fiber_rope"));
        registration.addIngredientInfo(BannerboundAntiquity.BONE_SAW.get(),
            Component.translatable("bannerboundantiquity.jei.info.bone_saw"));
        registration.addIngredientInfo(BannerboundAntiquity.WOODWORKING_TABLE_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.woodworking_table"));
        registration.addIngredientInfo(BannerboundAntiquity.STONE_CHISEL.get(),
            Component.translatable("bannerboundantiquity.jei.info.stone_chisel"));
        registration.addIngredientInfo(BannerboundAntiquity.MASONS_BENCH_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.masons_bench"));
        registration.addIngredientInfo(BannerboundAntiquity.OAR.get(),
            Component.translatable("bannerboundantiquity.jei.info.oar"));
        registration.addIngredientInfo(BannerboundAntiquity.CRUCIBLE.get(),
            Component.translatable("bannerboundantiquity.jei.info.crucible"));
        registration.addIngredientInfo(BannerboundAntiquity.STONE_ANVIL_ITEM.get(),
            Component.translatable("bannerboundantiquity.jei.info.stone_anvil"));
        registration.addIngredientInfo(BannerboundAntiquity.SALT.get(),
            Component.translatable("bannerboundantiquity.jei.info.salt"));
        registration.addIngredientInfo(BannerboundAntiquity.SPOILED_FOOD.get(),
            Component.translatable("bannerboundantiquity.jei.info.spoiled_food"));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(BannerboundAntiquity.CRAFTING_STONE.get(), CRAFTING_STONE);
        registration.addRecipeCatalyst(BannerboundAntiquity.FLETCHING_STATION.get(), FLETCHING);
        registration.addRecipeCatalyst(BannerboundAntiquity.POTTERY_SLAB.get(), POTTERY);
        registration.addRecipeCatalyst(BannerboundAntiquity.MORTAR_AND_PESTLE.get(), MORTAR);
        registration.addRecipeCatalyst(BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak").get(), DRYING);
        registration.addRecipeCatalyst(BannerboundAntiquity.WOODWORKING_TABLE.get(), CARPENTRY);
        registration.addRecipeCatalyst(BannerboundAntiquity.FIRE_STICKS.get(), BLOOMERY);
        registration.addRecipeCatalyst(BannerboundAntiquity.BELLOWS_BLOCK_ITEM.get(), BLOOMERY);
        registration.addRecipeCatalyst(BannerboundAntiquity.FIRE_STICKS.get(), KILN);
        registration.addRecipeCatalyst(Items.CHARCOAL, KILN);
        registration.addRecipeCatalyst(Items.COAL, KILN);
        registration.addRecipeCatalyst(BannerboundAntiquity.STONE_ROCK.get(), KNAPPING);
        // Never register CONSTRUCTION tools/items as catalysts: a catalyst dumps the whole category on click; input/output slots already drive lookup.
        registration.addRecipeCatalyst(BannerboundAntiquity.BONE_SAW.get(), CARPENTRY);
        registration.addRecipeCatalyst(BannerboundAntiquity.CRUCIBLE.get(), CASTING);
        for (String shape : MetalworkingItems.MOLD_SHAPES) {
            var mold = MetalworkingItems.MOLDS.get("fired_clay_mold_" + shape);
            if (mold != null) {
                registration.addRecipeCatalyst(mold.get(), CASTING);
            }
        }
        registration.addRecipeCatalyst(BannerboundAntiquity.STONE_ANVIL_ITEM.get(), COLD_HAMMER);
        var stoneHammer = MetalworkingItems.HAMMERS.get("stone_hammer");
        if (stoneHammer != null) {
            registration.addRecipeCatalyst(stoneHammer.get(), COLD_HAMMER);
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        ClientResearchState.addKnowledgeListener(knowledgeListener);
        ClientStartingItems.addListener(knowledgeListener);
        applyKnowledgeGate();
    }

    @Override
    public void onRuntimeUnavailable() {
        ClientResearchState.removeKnowledgeListener(knowledgeListener);
        ClientStartingItems.removeListener(knowledgeListener);
        if (runtime != null) {
            unhideAll(runtime.getRecipeManager());
        }
        runtime = null;
        hiddenRecipes.clear();
    }

    private void applyKnowledgeGate() {
        if (runtime == null) {
            return;
        }
        IRecipeManager jeiRecipes = runtime.getRecipeManager();
        if (Config.JEI_SHOW_UNKNOWN.get()) {
            unhideAll(jeiRecipes);
            return;
        }
        syncCustomType(jeiRecipes, CRAFTING_STONE, CraftingStoneRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideCraftingStone);
        syncCustomType(jeiRecipes, FLETCHING, FletchingRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideFletching);
        syncCustomType(jeiRecipes, POTTERY, PotteryRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHidePottery);
        syncCustomType(jeiRecipes, BLOOMERY, BloomeryRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideBloomery);
        syncCustomType(jeiRecipes, KILN, KilnRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideKiln);
        syncCustomType(jeiRecipes, MORTAR, MortarRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideMortar);
        syncCustomType(jeiRecipes, CARPENTRY, carpentryRecipeCache,
            BannerboundAntiquityJeiPlugin::shouldHideCarpentry);
        syncCustomType(jeiRecipes, DRYING, DryingRackRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideDrying);
        syncCustomType(jeiRecipes, CONSTRUCTION, constructionRecipes(),
            BannerboundAntiquityJeiPlugin::shouldHideConstruction);
        syncCustomType(jeiRecipes, CASTING, castingRecipes(),
            BannerboundAntiquityJeiPlugin::shouldHideCasting);
        syncCustomType(jeiRecipes, COLD_HAMMER, AnvilRecipeManager.all(),
            BannerboundAntiquityJeiPlugin::shouldHideColdHammer);
        syncCustomType(jeiRecipes, KNAPPING, knappingRecipeCache,
            BannerboundAntiquityJeiPlugin::shouldHideKnapping);
    }

    private <T> void syncCustomType(IRecipeManager recipeManager, RecipeType<T> recipeType,
                                    List<T> recipes, Predicate<T> hidePredicate) {
        List<T> unknown = recipes.stream()
            .filter(hidePredicate)
            .toList();
        replaceHidden(recipeManager, recipeType, unknown);
    }

    private static boolean shouldHideOutput(ItemStack output) {
        return BannerboundCoreJeiPlugin.shouldHideStack(output);
    }

    private static boolean outputKnown(ItemStack output) {
        return output != null && !output.isEmpty() && !BannerboundCoreJeiPlugin.shouldHideStack(output);
    }

    private static boolean shouldHideCraftingStone(CraftingStoneRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        if (shouldHideOutput(recipe.result())) {
            return true;
        }
        for (CraftingStoneRecipe.Ing ing : recipe.ingredients()) {
            if (shouldHideOutput(new ItemStack(ing.item()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldHideFletching(FletchingRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        if (shouldHideOutput(recipe.result())) {
            return true;
        }
        for (FletchingRecipe.Ing ing : recipe.ingredients()) {
            if (shouldHideOutput(new ItemStack(ing.item()))) {
                return true;
            }
        }
        return recipe.inProgress().isPresent()
            && shouldHideOutput(new ItemStack(recipe.inProgress().get()));
    }

    private static boolean shouldHidePottery(PotteryRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        if (shouldHideOutput(recipe.result())) {
            return true;
        }
        for (PotteryRecipe.Ing ing : recipe.ingredients()) {
            if (shouldHideOutput(new ItemStack(ing.item()))) {
                return true;
            }
        }
        return recipe.inProgress().isPresent()
            && shouldHideOutput(new ItemStack(recipe.inProgress().get()));
    }

    private static boolean shouldHideBloomery(BloomeryRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        return shouldHideOutput(recipe.result())
            || allIngredientChoicesHidden(recipe.ingredient())
            || shouldHideOutput(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
    }

    private static boolean shouldHideKiln(KilnRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        return shouldHideOutput(recipe.result())
            || allIngredientChoicesHidden(recipe.ingredient());
    }

    private static boolean shouldHideMortar(MortarRecipe recipe) {
        if (outputKnown(mortarDisplayOutput(recipe))) {
            return false;
        }
        return shouldHideOutput(mortarDisplayOutput(recipe))
            || allIngredientChoicesHidden(recipe.ingredient());
    }

    private static boolean shouldHideConstruction(ConstructionRecipe recipe) {
        if (outputKnown(recipe.output())) {
            return false;
        }
        if (shouldHideOutput(recipe.output())) {
            return true;
        }
        for (List<ItemStack> choices : recipe.inputs()) {
            if (BannerboundCoreJeiPlugin.allChoicesHidden(choices)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldHideCarpentry(CarpentryRecipe recipe) {
        if (outputKnown(recipe.output())) {
            return false;
        }
        if (shouldHideOutput(recipe.output())
            || shouldHideOutput(new ItemStack(BannerboundAntiquity.WOODWORKING_TABLE_ITEM.get()))) {
            return true;
        }
        for (List<ItemStack> input : recipe.inputs()) {
            if (BannerboundCoreJeiPlugin.allChoicesHidden(input)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldHideCasting(CastingRecipe recipe) {
        if (outputKnown(recipe.output())) {
            return false;
        }
        if (shouldHideOutput(recipe.output())) {
            return true;
        }
        for (List<ItemStack> choices : recipe.inputs()) {
            if (BannerboundCoreJeiPlugin.allChoicesHidden(choices)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldHideColdHammer(AnvilRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        if (shouldHideOutput(recipe.result())) {
            return true;
        }
        for (AnvilRecipe.Ing ing : recipe.ingredients()) {
            if (shouldHideOutput(new ItemStack(ing.item()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldHideKnapping(KnappingRecipe recipe) {
        return shouldHideOutput(recipe.output());
    }

    private static boolean shouldHideDrying(DryingRackRecipe recipe) {
        if (outputKnown(recipe.result())) {
            return false;
        }
        return shouldHideOutput(recipe.result())
            || shouldHideOutput(new ItemStack(recipe.input()));
    }

    private static boolean allIngredientChoicesHidden(net.minecraft.world.item.crafting.Ingredient ingredient) {
        return BannerboundCoreJeiPlugin.allChoicesHidden(List.of(ingredient.getItems()));
    }

    private static List<ItemStack> visibleChoices(List<ItemStack> stacks) {
        if (Config.JEI_SHOW_UNKNOWN.get()) {
            return stacks;
        }
        List<ItemStack> visible = stacks.stream()
            .filter(stack -> !UnknownItemHelper.isUnknownForLocalPlayer(stack))
            .toList();
        return visible.isEmpty() ? stacks : visible;
    }

    private static List<ItemStack> visibleIngredientChoices(net.minecraft.world.item.crafting.Ingredient ingredient) {
        return visibleChoices(List.of(ingredient.getItems()));
    }

    private void unhideAll(IRecipeManager recipeManager) {
        for (Map.Entry<RecipeType<?>, List<?>> entry : hiddenRecipes.entrySet()) {
            unhideRaw(recipeManager, entry.getKey(), entry.getValue());
        }
        hiddenRecipes.clear();
    }

    private <T> void replaceHidden(IRecipeManager recipeManager, RecipeType<T> recipeType, List<T> nextHidden) {
        @SuppressWarnings("unchecked")
        List<T> previous = (List<T>) hiddenRecipes.getOrDefault(recipeType, List.of());
        if (!previous.isEmpty()) {
            recipeManager.unhideRecipes(recipeType, previous);
        }
        if (!nextHidden.isEmpty()) {
            List<T> copy = List.copyOf(nextHidden);
            recipeManager.hideRecipes(recipeType, copy);
            hiddenRecipes.put(recipeType, copy);
        } else {
            hiddenRecipes.remove(recipeType);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void unhideRaw(IRecipeManager recipeManager, RecipeType<?> recipeType, List<?> recipes) {
        recipeManager.unhideRecipes((RecipeType) recipeType, (List) recipes);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, path);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    private static ItemStack mortarDisplayOutput(MortarRecipe recipe) {
        if (!recipe.resultItem().isEmpty()) {
            return recipe.resultItem().copy();
        }
        return dyeForLiquid(recipe.resultLiquid());
    }

    private static ItemStack dyeForLiquid(String liquid) {
        return switch (liquid) {
            case "ink" -> new ItemStack(Items.INK_SAC);
            case "white" -> new ItemStack(Items.WHITE_DYE);
            case "light_gray" -> new ItemStack(Items.LIGHT_GRAY_DYE);
            case "gray" -> new ItemStack(Items.GRAY_DYE);
            case "brown" -> new ItemStack(Items.BROWN_DYE);
            case "red" -> new ItemStack(Items.RED_DYE);
            case "orange" -> new ItemStack(Items.ORANGE_DYE);
            case "yellow" -> new ItemStack(Items.YELLOW_DYE);
            case "lime" -> new ItemStack(Items.LIME_DYE);
            case "green" -> new ItemStack(Items.GREEN_DYE);
            case "cyan" -> new ItemStack(Items.CYAN_DYE);
            case "light_blue" -> new ItemStack(Items.LIGHT_BLUE_DYE);
            case "blue" -> new ItemStack(Items.BLUE_DYE);
            case "purple" -> new ItemStack(Items.PURPLE_DYE);
            case "magenta" -> new ItemStack(Items.MAGENTA_DYE);
            case "pink" -> new ItemStack(Items.PINK_DYE);
            default -> ItemStack.EMPTY;
        };
    }

    private static final class CraftingStoneCategory extends AbstractRecipeCategory<CraftingStoneRecipe> {
        CraftingStoneCategory(IGuiHelper gui) {
            super(CRAFTING_STONE, Component.translatable("bannerboundantiquity.jei.crafting_stone"),
                gui.createDrawableItemLike(BannerboundAntiquity.CRAFTING_STONE.get()), 150, 78);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, CraftingStoneRecipe recipe, IFocusGroup focuses) {
            int i = 0;
            for (CraftingStoneRecipe.Ing ing : recipe.ingredients()) {
                var slot = builder.addInputSlot(1 + (i % 3) * 20, 1 + (i / 3) * 20)
                    .setStandardSlotBackground()
                    .addItemStack(new ItemStack(ing.item(), ing.count()));
                if (ing.item() == BannerboundAntiquity.PLANT_FIBER.get()) {
                    slot.addItemStack(new ItemStack(BannerboundAntiquity.DRY_FIBER.get(), ing.count()));
                }
                i++;
            }
            builder.addOutputSlot(119, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
            builder.setShapeless(100, 2);
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, CraftingStoneRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(82, 20);
            builder.addText(Component.translatable("bannerboundantiquity.jei.crafting_stone.note"), 146, 16)
                .setPosition(0, 60)
                .setColor(0xFF808080);
        }
    }

    private static final class FletchingCategory extends AbstractRecipeCategory<FletchingRecipe> {
        FletchingCategory(IGuiHelper gui) {
            super(FLETCHING, Component.translatable("bannerboundantiquity.jei.fletching"),
                gui.createDrawableItemLike(BannerboundAntiquity.FLETCHING_STATION.get()), 150, 78);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, FletchingRecipe recipe, IFocusGroup focuses) {
            int i = 0;
            for (FletchingRecipe.Ing ing : recipe.ingredients()) {
                builder.addInputSlot(1 + (i % 3) * 20, 1 + (i / 3) * 20)
                    .setStandardSlotBackground()
                    .addItemStack(new ItemStack(ing.item(), ing.count()));
                i++;
            }
            builder.addOutputSlot(119, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
            builder.setShapeless(100, 2);
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, FletchingRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(82, 20);
            builder.addText(Component.translatable("bannerboundantiquity.jei.fletching.note",
                    recipe.stretches()), 146, 16)
                .setPosition(0, 60)
                .setColor(0xFF808080);
        }
    }

    private static final class PotteryCategory extends AbstractRecipeCategory<PotteryRecipe> {
        PotteryCategory(IGuiHelper gui) {
            super(POTTERY, Component.translatable("bannerboundantiquity.jei.pottery"),
                gui.createDrawableItemLike(BannerboundAntiquity.POTTERY_SLAB.get()), 150, 78);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, PotteryRecipe recipe, IFocusGroup focuses) {
            int i = 0;
            for (PotteryRecipe.Ing ing : recipe.ingredients()) {
                builder.addInputSlot(1 + (i % 3) * 20, 1 + (i / 3) * 20)
                    .setStandardSlotBackground()
                    .addItemStack(new ItemStack(ing.item(), ing.count()));
                i++;
            }
            builder.addOutputSlot(119, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
            builder.setShapeless(100, 2);
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, PotteryRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(82, 20);
            builder.addText(Component.translatable("bannerboundantiquity.jei.pottery.note",
                    recipe.spins()), 146, 16)
                .setPosition(0, 60)
                .setColor(0xFF808080);
        }
    }

    private static final class BloomeryCategory extends AbstractRecipeCategory<BloomeryRecipe> {
        BloomeryCategory(IGuiHelper gui) {
            super(BLOOMERY, Component.translatable("bannerboundantiquity.jei.bloomery"),
                gui.createDrawableItemStack(new ItemStack(BannerboundAntiquity.BELLOWS_BLOCK_ITEM.get())), 120, 62);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, BloomeryRecipe recipe, IFocusGroup focuses) {
            builder.addInputSlot(1, 20)
                .setStandardSlotBackground()
                .addItemStacks(visibleIngredientChoices(recipe.ingredient()));
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, 1, 42)
                .setStandardSlotBackground()
                .addItemStack(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
            builder.addOutputSlot(91, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, BloomeryRecipe recipe, IFocusGroup focuses) {
            builder.addAnimatedRecipeFlame(300).setPosition(1, 2);
            builder.addAnimatedRecipeArrow(Math.max(20, recipe.ticks())).setPosition(46, 21);
            int seconds = Math.max(1, recipe.ticks() / 20);
            int chance = Math.round(recipe.chance() * 100.0F);
            builder.addText(Component.translatable("bannerboundantiquity.jei.bloomery.note", seconds, chance), 116, 14)
                .setPosition(0, 0)
                .setColor(0xFF808080);
        }
    }

    private static final class KilnCategory extends AbstractRecipeCategory<KilnRecipe> {
        KilnCategory(IGuiHelper gui) {
            super(KILN, Component.translatable("bannerboundantiquity.jei.kiln"),
                gui.createDrawableItemStack(new ItemStack(Items.CHARCOAL)), 120, 62);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, KilnRecipe recipe, IFocusGroup focuses) {
            builder.addInputSlot(1, 20)
                .setStandardSlotBackground()
                .addItemStacks(visibleIngredientChoices(recipe.ingredient()));
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, 1, 42)
                .setStandardSlotBackground()
                .addItemStacks(List.of(new ItemStack(Items.CHARCOAL), new ItemStack(Items.COAL)));
            builder.addOutputSlot(91, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, KilnRecipe recipe, IFocusGroup focuses) {
            builder.addAnimatedRecipeFlame(300).setPosition(1, 2);
            builder.addAnimatedRecipeArrow(Math.max(20, recipe.ticks())).setPosition(46, 21);
            int seconds = Math.max(1, recipe.ticks() / 20);
            int chance = Math.round(recipe.chance() * 100.0F);
            builder.addText(Component.translatable("bannerboundantiquity.jei.kiln.note", seconds, chance), 116, 14)
                .setPosition(0, 0)
                .setColor(0xFF808080);
        }
    }

    private static final class DryingRackCategory extends AbstractRecipeCategory<DryingRackRecipe> {
        DryingRackCategory(IGuiHelper gui) {
            super(DRYING, Component.translatable("bannerboundantiquity.jei.drying"),
                gui.createDrawableItemLike(BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak").get()), 120, 62);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, DryingRackRecipe recipe, IFocusGroup focuses) {
            builder.addInputSlot(1, 20)
                .setStandardSlotBackground()
                .addItemStack(new ItemStack(recipe.input()));
            builder.addOutputSlot(91, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, DryingRackRecipe recipe, IFocusGroup focuses) {
            builder.addAnimatedRecipeArrow(Math.max(20, recipe.ticks())).setPosition(46, 21);
            int seconds = Math.max(1, recipe.ticks() / 20);
            builder.addText(Component.translatable("bannerboundantiquity.jei.drying.note", seconds), 116, 14)
                .setPosition(0, 0)
                .setColor(0xFF808080);
        }
    }

    private static final class MortarCategory extends AbstractRecipeCategory<MortarRecipe> {
        MortarCategory(IGuiHelper gui) {
            super(MORTAR, Component.translatable("bannerboundantiquity.jei.mortar"),
                gui.createDrawableItemLike(BannerboundAntiquity.MORTAR_AND_PESTLE.get()), 138, 64);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MortarRecipe recipe, IFocusGroup focuses) {
            builder.addInputSlot(1, 20)
                .setStandardSlotBackground()
                .addItemStacks(visibleIngredientChoices(recipe.ingredient()));
            if (!recipe.baseLiquid().isBlank()) {
                builder.addInputSlot(25, 20)
                    .setStandardSlotBackground()
                    .addItemStack(waterBottle());
            }
            ItemStack output = mortarDisplayOutput(recipe);
            if (!output.isEmpty()) {
                builder.addOutputSlot(105, 20)
                    .setOutputSlotBackground()
                    .addItemStack(output);
            }
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, MortarRecipe recipe, IFocusGroup focuses) {
            if (!recipe.baseLiquid().isBlank()) {
                builder.addRecipePlusSign().setPosition(20, 23);
            }
            builder.addRecipeArrow().setPosition(66, 21);
            String result = recipe.resultLiquid().isBlank() ? "item" : recipe.resultLiquid();
            builder.addText(Component.translatable("bannerboundantiquity.jei.mortar.note", result), 134, 14)
                .setPosition(0, 0)
                .setColor(0xFF808080);
        }
    }

    private static final class CarpentryCategory extends AbstractRecipeCategory<CarpentryRecipe> {
        CarpentryCategory(IGuiHelper gui) {
            super(CARPENTRY, Component.translatable("bannerboundantiquity.jei.carpentry"),
                gui.createDrawableItemLike(BannerboundAntiquity.WOODWORKING_TABLE.get()), 176, 92);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, CarpentryRecipe recipe, IFocusGroup focuses) {
            int x = 1;
            for (List<ItemStack> input : recipe.inputs()) {
                builder.addInputSlot(x, 20)
                    .setStandardSlotBackground()
                    .addItemStacks(visibleChoices(input));
                x += 24;
            }
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, x, 20)
                .setStandardSlotBackground()
                .addItemStack(new ItemStack(BannerboundAntiquity.BONE_SAW.get()));
            builder.addOutputSlot(145, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.output()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, CarpentryRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(110, 21);
            builder.addText(recipe.note(), 172, 38)
                .setPosition(0, 50)
                .setColor(0xFF808080)
                .setTextAlignment(HorizontalAlignment.LEFT)
                .setTextAlignment(VerticalAlignment.TOP);
        }

        @Override
        public ResourceLocation getRegistryName(CarpentryRecipe recipe) {
            return recipe.id();
        }
    }

    private static final class ConstructionCategory extends AbstractRecipeCategory<ConstructionRecipe> {
        ConstructionCategory(IGuiHelper gui) {
            super(CONSTRUCTION, Component.translatable("bannerboundantiquity.jei.construction"),
                gui.createDrawableItemStack(new ItemStack(BannerboundAntiquity.FLINT_BLADE.get())), 176, 92);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, ConstructionRecipe recipe, IFocusGroup focuses) {
            int x = 1;
            for (List<ItemStack> input : recipe.inputs()) {
                builder.addInputSlot(x, 20)
                    .setStandardSlotBackground()
                    .addItemStacks(visibleChoices(input));
                x += 24;
            }
            if (!recipe.output().isEmpty()) {
                builder.addOutputSlot(145, 20)
                    .setOutputSlotBackground()
                    .addItemStack(copy(recipe.output()));
            }
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, ConstructionRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(110, 21);
            builder.addText(recipe.note(), 172, 38)
                .setPosition(0, 50)
                .setColor(0xFF808080)
                .setTextAlignment(HorizontalAlignment.LEFT)
                .setTextAlignment(VerticalAlignment.TOP);
        }

        @Override
        public ResourceLocation getRegistryName(ConstructionRecipe recipe) {
            return recipe.id();
        }
    }

    private static final class CastingCategory extends AbstractRecipeCategory<CastingRecipe> {
        CastingCategory(IGuiHelper gui) {
            super(CASTING, Component.translatable("bannerboundantiquity.jei.casting"),
                gui.createDrawableItemLike(BannerboundAntiquity.CRUCIBLE.get()), 176, 80);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, CastingRecipe recipe, IFocusGroup focuses) {
            int x = 1;
            for (List<ItemStack> input : recipe.inputs()) {
                builder.addInputSlot(x, 20)
                    .setStandardSlotBackground()
                    .addItemStacks(visibleChoices(input));
                x += 24;
            }
            if (!recipe.mold().isEmpty()) {
                builder.addSlot(RecipeIngredientRole.CATALYST, x, 20)
                    .setStandardSlotBackground()
                    .addItemStack(recipe.mold());
                x += 24;
            }
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, x, 20)
                .setStandardSlotBackground()
                .addItemStack(new ItemStack(BannerboundAntiquity.CRUCIBLE.get()));
            builder.addOutputSlot(145, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.output()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, CastingRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(110, 21);
            builder.addText(recipe.note(), 172, 30)
                .setPosition(0, 46)
                .setColor(0xFF808080)
                .setTextAlignment(HorizontalAlignment.LEFT)
                .setTextAlignment(VerticalAlignment.TOP);
        }

        @Override
        public ResourceLocation getRegistryName(CastingRecipe recipe) {
            return recipe.id();
        }
    }

    private static final class ColdHammerCategory extends AbstractRecipeCategory<AnvilRecipe> {
        ColdHammerCategory(IGuiHelper gui) {
            super(COLD_HAMMER, Component.translatable("bannerboundantiquity.jei.cold_hammer"),
                gui.createDrawableItemLike(BannerboundAntiquity.STONE_ANVIL_ITEM.get()), 176, 80);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, AnvilRecipe recipe, IFocusGroup focuses) {
            int x = 1;
            for (AnvilRecipe.Ing ing : recipe.ingredients()) {
                builder.addInputSlot(x, 20)
                    .setStandardSlotBackground()
                    .addItemStack(new ItemStack(ing.item(), ing.count()));
                x += 24;
            }
            var stoneHammer = MetalworkingItems.HAMMERS.get("stone_hammer");
            if (stoneHammer != null) {
                builder.addSlot(RecipeIngredientRole.CATALYST, x, 20)
                    .setStandardSlotBackground()
                    .addItemStack(new ItemStack(stoneHammer.get()));
            }
            builder.addOutputSlot(145, 20)
                .setOutputSlotBackground()
                .addItemStack(copy(recipe.result()));
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, AnvilRecipe recipe, IFocusGroup focuses) {
            builder.addRecipeArrow().setPosition(110, 21);
            builder.addText(Component.translatable("bannerboundantiquity.jei.cold_hammer.note",
                    Math.max(1, recipe.strikes())), 172, 30)
                .setPosition(0, 46)
                .setColor(0xFF808080)
                .setTextAlignment(HorizontalAlignment.LEFT)
                .setTextAlignment(VerticalAlignment.TOP);
        }

        @Override
        public ResourceLocation getRegistryName(AnvilRecipe recipe) {
            ResourceLocation id = AnvilRecipeManager.idOf(recipe);
            return id != null ? id
                : ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID,
                    "cold_hammer/" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(recipe.result().getItem()).getPath());
        }
    }

    private static final class KnappingCategory extends AbstractRecipeCategory<KnappingRecipe> {
        private static final ResourceLocation STONE_TEX =
                ResourceLocation.withDefaultNamespace("textures/block/stone.png");

        public KnappingCategory(IGuiHelper gui) {
            super(KNAPPING, Component.translatable("bannerboundantiquity.jei.knapping"),
                    gui.createDrawableItemLike(BannerboundAntiquity.STONE_ROCK.get()), 176, 80);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, KnappingRecipe recipe, IFocusGroup focuses) {
            builder.addOutputSlot(145, 32)
                    .setOutputSlotBackground()
                    .addItemStack(copy(recipe.output()));
        }

        @Override
        public void draw(KnappingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            int gridStartX = 8;
            int gridStartY = 8;
            int gridOffset = 5;
            int slotSize = 18;

            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int x = gridStartX + (col * slotSize) + (col * gridOffset);
                    int y = gridStartY + (row * slotSize) + (row * gridOffset);

                    if (recipe.keep.contains(row * 3 + col)) {
                        guiGraphics.blit(STONE_TEX, x, y, 0, 0, 18, 18, 16, 16);
                    } else {
                        guiGraphics.fill(x, y, x + 18, y + 18, 0xC0101010);
                    }
                }
            }
        }

        @Override
        public @Nullable ResourceLocation getRegistryName(KnappingRecipe recipe) {
            return null;
        }
    }

    public record CarpentryRecipe(ResourceLocation id, List<List<ItemStack>> inputs,
                                  ItemStack output, Component note) {
    }

    public record ConstructionRecipe(ResourceLocation id, List<List<ItemStack>> inputs,
                                     ItemStack output, Component note) {
    }

    public record CastingRecipe(ResourceLocation id, List<List<ItemStack>> inputs,
                                ItemStack mold, ItemStack output, Component note) {
    }

    public record KnappingRecipe(List<Integer> keep, ItemStack output, Component note) {
    }
}
