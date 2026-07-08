package com.bannerbound.antiquity.compat.jade;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.OreDisguise;
import com.bannerbound.core.client.ClientOreState;
import com.bannerbound.core.client.UnknownItemHelper;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.JadeIds;
import snownee.jade.api.callback.JadeRayTraceCallback;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;
import snownee.jade.api.ui.IBoxElement;

/**
 * The anti-spoiler layer of the Jade integration; two hooks that make the overlay obey the same
 * knowledge rules as every other client surface. The ray-trace callback fires while Jade decides
 * WHAT is being looked at: a block still ore-disguised for the local settlement (ClientOreState,
 * the same authority the world renderer's model swap uses) has its whole accessor rebuilt around
 * the disguise block's state, so the name, icon (Jade recomputes the picked stack from the
 * swapped state), mod line and even the advanced-tooltip registry id all read as the camouflage
 * block -- a disguised coal_ore is indistinguishable from real stone, including both being masked
 * while stone itself is still unresearched. The tooltip-collected callback fires after the full
 * tooltip is assembled: if the (possibly swapped) block's item form is unknown to the local player
 * (UnknownItemHelper, creative bypasses) it wipes every collected line -- name, mod, harvest info,
 * any provider content -- and re-adds the standard red "Unknown item" + action pair, mirroring
 * TooltipHandlers.onTooltip for inventory items. Blocks with no item form (kiln, bloomery) are
 * never masked since knowledge is tracked per-item. Known blocks get one more pass: Jade's
 * harvest-tool icons render the required tool ItemStack, which the item-model mixin masks to a
 * floating question mark when that tool is unresearched -- so the MC_HARVEST_TOOL elements are
 * stripped whenever the block's mineable-tag representative tool (wooden tier) is unknown.
 */
@OnlyIn(Dist.CLIENT)
public final class JadeKnowledgeMask implements JadeRayTraceCallback, JadeTooltipCollectedCallback {
    private final IWailaClientRegistration registration;

    public JadeKnowledgeMask(IWailaClientRegistration registration) {
        this.registration = registration;
    }

    @Override
    @Nullable
    public Accessor<?> onRayTrace(HitResult hit, @Nullable Accessor<?> accessor, @Nullable Accessor<?> original) {
        if (!(accessor instanceof BlockAccessor block)) {
            return accessor;
        }
        if (!ClientOreState.isCurrentlyDisguised(block.getBlock())) {
            return accessor;
        }
        OreDisguise disguise = ClientOreState.getDisguiseFor(block.getBlock());
        Block shown = disguise == null ? null : resolveBlock(disguise.disguiseId());
        if (shown == null) {
            return accessor;
        }
        return registration.blockAccessor().from(block).blockState(shown.defaultBlockState()).build();
    }

    @Override
    public void onTooltipCollected(IBoxElement box, Accessor<?> accessor) {
        if (!(accessor instanceof BlockAccessor block)) {
            return;
        }
        Item item = block.getBlock().asItem();
        if (item != Items.AIR && !UnknownItemHelper.isKnown(item)) {
            ITooltip tooltip = box.getTooltip();
            tooltip.clear();
            tooltip.add(UnknownItemHelper.unknownName());
            tooltip.add(UnknownItemHelper.unknownAction());
            return;
        }
        if (!knowsHarvestTool(block.getBlockState())) {
            box.getTooltip().remove(JadeIds.MC_HARVEST_TOOL);
        }
    }

    private static boolean knowsHarvestTool(BlockState state) {
        Item tool = null;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            tool = Items.WOODEN_PICKAXE;
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            tool = Items.WOODEN_AXE;
        } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            tool = Items.WOODEN_SHOVEL;
        } else if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            tool = Items.WOODEN_HOE;
        }
        return tool == null || UnknownItemHelper.isKnown(tool);
    }

    @Nullable
    private static Block resolveBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.BLOCK.containsKey(rl) ? BuiltInRegistries.BLOCK.get(rl) : null;
    }
}
