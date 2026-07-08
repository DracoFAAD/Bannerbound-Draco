package com.bannerbound.antiquity.compat.jade;

import java.util.List;
import java.util.Locale;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.ClayTankBlock;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.BloomeryHeat;
import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.CrucibleBlockEntity;
import com.bannerbound.antiquity.block.entity.DryingRackBlockEntity;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
import com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.block.entity.WormCrateBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.recipe.BloomeryRecipe;
import com.bannerbound.antiquity.recipe.BloomeryRecipeManager;
import com.bannerbound.antiquity.recipe.GrogRecipe;
import com.bannerbound.antiquity.recipe.GrogRecipeManager;
import com.bannerbound.antiquity.recipe.KilnRecipe;
import com.bannerbound.antiquity.recipe.KilnRecipeManager;
import com.bannerbound.antiquity.workshop.MetalworkingItems;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.client.ClientBlockAppealState;
import com.bannerbound.core.client.ClientClaimState;
import com.bannerbound.core.network.ClaimEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Client-side Jade body providers for every Bannerbound block with live state worth surfacing.
 * All of these read straight from the CLIENT block entity: the Antiquity machine BEs mirror their
 * full persisted NBT to watchers (setChanged -> sendBlockUpdated + getUpdateTag), so no Jade
 * server-data round trip is needed. Multiblocks resolve their controller first (Kiln min-corner
 * PART 0, ClayTank pillar bottom, Bloomery lower segment) so any hit cell reads the one real BE.
 * Recipe lookups (kiln/bloomery/drying/grog) hit the client mirrors of the datapack managers --
 * the same ones JEI renders from. Spoiler safety is inherited rather than re-implemented: every
 * item rendered or named here goes through ItemStack.getHoverName()/ItemRenderer, which the
 * knowledge mixins already mask, and JadeKnowledgeMask wipes the whole tooltip for blocks the
 * player does not know, so these providers never leak an unresearched name. APPEAL is the
 * beauty-debug replacement: the same resolved per-type value the item tooltip shows
 * (ClientBlockAppealState, styled for the local settlement), rendered for the block being looked
 * at whenever it is nonzero. TERRITORY (shift-only, to keep the default overlay quiet) names the
 * settlement claiming the looked-at block's chunk from the already-synced ClientClaimState,
 * tinted with its banner colour; melt-band hints on crucible charges read the client-loaded
 * MetalworkingData tables.
 */
@OnlyIn(Dist.CLIENT)
public enum AntiquityJadeProviders implements IBlockComponentProvider {
    APPEAL("appeal", 1000) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            float appeal = ClientBlockAppealState.appealOf(accessor.getBlock());
            if (appeal == 0f) {
                return;
            }
            tooltip.add(Component.translatable("bannerbound.tooltip.appeal",
                String.format(Locale.ROOT, "%.2f", appeal)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    },

    TERRITORY("territory", 1100) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!accessor.showDetails()) {
                return;
            }
            ClaimEntry entry = ClientClaimState.getEntry(new ChunkPos(accessor.getPosition()).toLong());
            if (entry == null) {
                return;
            }
            tooltip.add(Component.translatable("bannerboundantiquity.jade.claimed_by",
                Component.literal(entry.settlementName())
                    .withStyle(SettlementColor.byIndex(entry.colorIndex()).formatting())));
        }
    },

    KILN("kiln", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            KilnBlockEntity kiln = KilnBlock.getController(accessor.getLevel(), accessor.getPosition());
            if (kiln == null) {
                return;
            }
            ItemStack held = kiln.getHeldItem();
            if (!held.isEmpty()) {
                itemLine(tooltip, held);
            }
            if (kiln.isLit()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.burn",
                    kiln.getLitTicks() / 20).withStyle(ChatFormatting.GOLD));
            } else {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.unlit")
                    .withStyle(ChatFormatting.GRAY));
            }
            KilnRecipe recipe = held.isEmpty() ? null : KilnRecipeManager.find(held);
            if (recipe == null) {
                return;
            }
            int total = recipe.ticks() * Math.max(1, held.getCount());
            if (kiln.getSmeltProgress() > 0 || kiln.isSmelting()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.firing",
                    percent(kiln.getSmeltProgress(), total)).withStyle(ChatFormatting.GOLD));
            }
            outputLine(tooltip, recipe.result());
        }
    },

    BLOOMERY("bloomery", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BloomeryBlockEntity be = BloomeryBlock.getController(accessor.getLevel(), accessor.getPosition());
            if (be == null) {
                return;
            }
            ItemStack held = be.getHeldItem();
            if (!held.isEmpty()) {
                itemLine(tooltip, held);
            }
            if (be.isLit()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.burn",
                    be.getLitTicks() / 20).withStyle(ChatFormatting.GOLD));
            }
            if (be.isLit() || be.temperatureC() > 30f) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.temperature",
                    String.format(Locale.ROOT, "%.0f", be.temperatureC())).withStyle(ChatFormatting.GOLD));
            }
            CrucibleContents charge = held.isEmpty()
                ? null : held.get(com.bannerbound.antiquity.BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            if (charge != null && charge.hasCharge() && !charge.molten()) {
                meltBandLine(tooltip, charge);
                if (be.isOpen()) {
                    tooltip.add(Component.translatable("bannerboundantiquity.jade.bloomery.door_open")
                        .withStyle(ChatFormatting.GRAY));
                }
            }
            BloomeryRecipe recipe = held.isEmpty() ? null : BloomeryRecipeManager.find(held);
            float total = recipe != null
                ? recipe.ticks() * Math.max(1, held.getCount())
                : BloomeryBlockEntity.CRUCIBLE_MELT_TICKS;
            if (be.getSmeltProgress() > 0 || be.isSmelting()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.smelting",
                    percent(be.getSmeltProgress(), total)).withStyle(ChatFormatting.GOLD));
            }
            if (recipe != null) {
                outputLine(tooltip, recipe.result());
            }
        }
    },

    FERMENTATION_TROUGH("fermentation_trough", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof FermentationTroughBlockEntity be)) {
                return;
            }
            long time = accessor.getLevel().getGameTime();
            if (be.isCharged()) {
                GrogRecipe recipe = GrogRecipeManager.byId(be.grogRecipeId());
                Component grog = recipe == null
                    ? Component.translatable("bannerboundantiquity.jade.trough.unknown_brew")
                    : Component.literal(recipe.name())
                        .withStyle(style -> style.withColor(TextColor.fromRgb(recipe.tint() & 0xFFFFFF)));
                if (be.grogReady(time)) {
                    tooltip.add(Component.translatable("bannerboundantiquity.jade.trough.ready", grog)
                        .withStyle(ChatFormatting.GREEN));
                } else {
                    tooltip.add(Component.translatable("bannerboundantiquity.jade.trough.fermenting",
                        grog, Math.round(be.fermentProgress(time) * 100f)));
                }
            } else {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.trough.water",
                    be.units(), FermentationTroughBlockEntity.UNITS_PER_CELL)
                    .withStyle(ChatFormatting.GRAY));
            }
        }
    },

    CLAY_TANK("clay_tank", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            ClayTankBlockEntity be = ClayTankBlock.getController(accessor.getLevel(), accessor.getPosition());
            if (be == null) {
                return;
            }
            if (be.getLiquid() == ClayTankBlockEntity.LiquidType.EMPTY || be.getBuckets() <= 0) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.tank.empty")
                    .withStyle(ChatFormatting.GRAY));
                return;
            }
            String key = be.hasCuring()
                ? "bannerboundantiquity.jade.tank.curing"
                : "bannerboundantiquity.jade.tank.water";
            tooltip.add(Component.translatable(key, be.getBuckets(), be.maxBuckets())
                .withStyle(style -> style.withColor(TextColor.fromRgb(be.getLiquid().color() & 0xFFFFFF))));
        }
    },

    TANNING_RACK("tanning_rack", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof TanningRackBlockEntity be)) {
                return;
            }
            ItemStack shown = be.getDisplayStack();
            if (!shown.isEmpty()) {
                itemLine(tooltip, shown);
            }
            if (be.isRaw()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.tanning.scrape")
                    .withStyle(ChatFormatting.GRAY));
            } else if (be.isDry()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.tanning.dry")
                    .withStyle(ChatFormatting.GREEN));
            } else if (!shown.isEmpty()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.tanning.drying",
                    Math.round(be.dryProgress() * 100f)));
            }
        }
    },

    DRYING_RACK("drying_rack", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof DryingRackBlockEntity be)) {
                return;
            }
            for (int slot = 0; slot < DryingRackBlockEntity.SLOTS; slot++) {
                ItemStack input = be.input(slot);
                if (input.isEmpty()) {
                    continue;
                }
                ItemStack result = be.result(slot);
                ItemStack shown = result.isEmpty() ? input : result;
                MutableComponent label = shown.getHoverName().copy();
                if (be.isDry(slot)) {
                    label = Component.translatable("bannerboundantiquity.jade.slot.ready", label)
                        .withStyle(ChatFormatting.GREEN);
                } else {
                    label = Component.translatable("bannerboundantiquity.jade.slot.progress", label,
                        Math.round(be.progress(slot) * 100f));
                }
                IElementHelper helper = IElementHelper.get();
                tooltip.add(helper.smallItem(input));
                tooltip.append(helper.text(label));
            }
        }
    },

    COOKING_POT("cooking_pot", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof StoneCookingPotBlockEntity be)) {
                return;
            }
            if (be.hasStew()) {
                Component stewName = Component.literal(be.previewName())
                    .withStyle(style -> style.withColor(TextColor.fromRgb(be.previewTint() & 0xFFFFFF)));
                tooltip.add(Component.translatable("bannerboundantiquity.jade.pot.stew", stewName));
                tooltip.add(Component.translatable("bannerbound.tooltip.food_value",
                    String.format(Locale.ROOT, "%.1f", be.remainingFoodValue()))
                    .withStyle(ChatFormatting.GREEN));
                return;
            }
            if (be.ingredientCount() > 0) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.pot.ingredients",
                    be.ingredientCount()).withStyle(ChatFormatting.GRAY));
            }
            if (be.isCooking()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.pot.cooking",
                    Math.round(be.cookFraction() * 100f)).withStyle(ChatFormatting.GOLD));
            } else if (be.hasWater() && be.ingredientCount() > 0 && !be.isHeated()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.pot.not_heated")
                    .withStyle(ChatFormatting.RED));
            } else if (be.hasWater()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.pot.water")
                    .withStyle(ChatFormatting.GRAY));
            }
        }
    },

    CRUCIBLE("crucible", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof CrucibleBlockEntity be)) {
                return;
            }
            CrucibleContents contents = be.contents();
            if (contents.molten()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.molten",
                    prettify(contents.metalId()), contents.mb())
                    .withStyle(style -> style.withColor(TextColor.fromRgb(contents.tintColor() & 0xFFFFFF))));
            } else if (contents.hasCharge()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.crucible.charge",
                    contents.charge().size(), CrucibleBlockEntity.CAPACITY).withStyle(ChatFormatting.GRAY));
                itemLine(tooltip, contents.lastItem());
                meltBandLine(tooltip, contents);
            }
        }
    },

    MORTAR("mortar", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof MortarAndPestleBlockEntity be)) {
                return;
            }
            if (!be.getIngredient().isEmpty()) {
                itemLine(tooltip, be.getIngredient());
            }
            if (be.hasLiquid()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.mortar.liquid",
                    prettify(be.getLiquidId())).withStyle(ChatFormatting.GRAY));
            }
        }
    },

    CHOPPING_STUMP("chopping_stump", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof ChoppingStumpBlockEntity be)) {
                return;
            }
            if (!be.getLogs().isEmpty()) {
                itemLine(tooltip, be.getLogs());
            }
        }
    },

    WORM_CRATE("worm_crate", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof WormCrateBlockEntity be)) {
                return;
            }
            tooltip.add(Component.translatable("bannerboundantiquity.jade.worms",
                be.getAmountOfWorms()).withStyle(ChatFormatting.GRAY));
        }
    },

    WORKSTATION("workstation", 0) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof GhostRecipeWorkstation station)) {
                return;
            }
            int materials = pileCount(accessor.getBlockEntity());
            if (materials > 0) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.workstation.materials",
                    materials).withStyle(ChatFormatting.GRAY));
            }
            ItemStack result = station.getResult();
            if (!result.isEmpty()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.workstation.ready")
                    .withStyle(ChatFormatting.GREEN));
                itemLine(tooltip, result);
            } else if (!station.getGhostResult().isEmpty()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.workstation.selected")
                    .withStyle(ChatFormatting.GRAY));
                itemLine(tooltip, station.getGhostResult());
            }
            if (accessor.getBlockEntity() instanceof MasonsBenchBlockEntity bench && bench.hasBuildList()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.workstation.build_list",
                    bench.getBuildList().size(), bench.remainingTotal()).withStyle(ChatFormatting.GRAY));
            } else if (accessor.getBlockEntity() instanceof WoodworkingTableBlockEntity saw && saw.hasBuildList()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.workstation.build_list_simple",
                    saw.getBuildList().size()).withStyle(ChatFormatting.GRAY));
            }
        }
    },

    STONE_ANVIL("stone_anvil", 10) {
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof StoneAnvilBlockEntity be)) {
                return;
            }
            if (be.molten() && be.fillMb() > 0) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.molten",
                    prettify(be.metalId()), be.fillMb())
                    .withStyle(style -> style.withColor(TextColor.fromRgb(be.tintColor() & 0xFFFFFF))));
            }
            if (be.moldShape() != null && !be.moldShape().isEmpty()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.anvil.mold",
                    prettify(be.moldShape())).withStyle(ChatFormatting.GRAY));
            }
        }
    };

    private final ResourceLocation uid;
    private final int priority;

    AntiquityJadeProviders(String path, int priority) {
        this.uid = ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, path);
        this.priority = priority;
    }

    @Override
    public ResourceLocation getUid() {
        return uid;
    }

    @Override
    public int getDefaultPriority() {
        return priority;
    }

    private static void itemLine(ITooltip tooltip, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        IElementHelper helper = IElementHelper.get();
        tooltip.add(helper.smallItem(stack));
        MutableComponent label = stack.getHoverName().copy();
        if (stack.getCount() > 1) {
            label.append(" x" + stack.getCount());
        }
        tooltip.append(helper.text(label));
    }

    private static void outputLine(ITooltip tooltip, ItemStack output) {
        if (output.isEmpty()) {
            return;
        }
        IElementHelper helper = IElementHelper.get();
        tooltip.add(helper.text(Component.translatable("bannerboundantiquity.jade.output")
            .withStyle(ChatFormatting.GRAY)));
        tooltip.append(helper.smallItem(output));
        tooltip.append(helper.text(output.getHoverName().copy().withStyle(ChatFormatting.GRAY)));
    }

    private static void meltBandLine(ITooltip tooltip, CrucibleContents contents) {
        MetalworkingItems.MeltValue resolved = MetalworkingItems.resolveCharge(contents.charge());
        if (resolved == null) {
            return;
        }
        int[] band = BloomeryHeat.meltBand(resolved.metalId());
        tooltip.add(Component.translatable("bannerboundantiquity.jade.melt_band",
            prettify(resolved.metalId()), band[0], band[1]).withStyle(ChatFormatting.GRAY));
    }

    private static int percent(float progress, float total) {
        return total <= 0f ? 0 : Math.min(100, Math.round(progress * 100f / total));
    }

    private static String prettify(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String tail = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }

    private static int pileCount(Object be) {
        List<ItemStack> pile = switch (be) {
            case CraftingStoneBlockEntity station -> station.getContents();
            case FletchingStationBlockEntity station -> station.getContents();
            case PotterySlabBlockEntity station -> station.getContents();
            case StoneAnvilBlockEntity station -> station.getContents();
            case WoodworkingTableBlockEntity station -> station.getLogs();
            case MasonsBenchBlockEntity station -> station.getStones();
            default -> List.of();
        };
        int total = 0;
        for (ItemStack stack : pile) {
            total += stack.getCount();
        }
        return total;
    }
}
