package com.bannerbound.antiquity.event;

import com.bannerbound.antiquity.block.*;
import com.bannerbound.core.codex.CodexTriggerContext;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.entity.RaftEntity;
import com.bannerbound.antiquity.item.KnifeItem;
import com.bannerbound.antiquity.recipe.BloomeryRecipeManager;
import com.bannerbound.antiquity.recipe.KilnRecipeManager;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipeManager;
import com.bannerbound.antiquity.recipe.FletchingRecipeManager;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.bannerbound.antiquity.recipe.PotteryRecipeManager;
import com.bannerbound.core.codex.CodexManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import com.bannerbound.antiquity.craft.Knapping;
import com.bannerbound.antiquity.craft.Hammer;
import com.bannerbound.antiquity.craft.Fletching;
import com.bannerbound.antiquity.craft.Pottery;
import com.bannerbound.antiquity.craft.MortarGrind;
import com.bannerbound.antiquity.craft.Masonry;
import com.bannerbound.antiquity.craft.Carpentry;
import com.bannerbound.antiquity.craft.Tannery;
import com.bannerbound.antiquity.workshop.CarpentryAssemblyManager;
import com.bannerbound.antiquity.workshop.CarpentryOutputManager;
import com.bannerbound.antiquity.workshop.MasonryOutputManager;
import com.bannerbound.antiquity.workshop.MetalworkingData;
import com.bannerbound.antiquity.workshop.MetalworkingItems;
import com.bannerbound.antiquity.workshop.HidePreferenceLoader;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * Game-bus event handlers for Bannerbound: Antiquity. This is the interception hub for the mod's
 * "gesture crafts": right-click gestures that turn vanilla blocks into Antiquity machines/multiblocks
 * or spawn entities. They are caught at {@link PlayerInteractEvent.RightClickBlock} / RightClickItem
 * rather than a block's own use method because the target is a vanilla block (mud bricks, cobblestone,
 * logs, thatch) or the gesture holds an item while (not) sneaking -- cases vanilla never routes through
 * a block's interaction methods. Handlers here: scoop mortar water; carve tanning rack / drying rack /
 * fermentation trough / chopping stump; form bloomery / kiln / pottery slab / stockpile / fletching
 * station / woodworking table / mason's bench / raft; knapping (gravel/flint/bone on a hard surface,
 * or two rocks held in both hands); clay-mold shaping; and tying a leashed raft to a fence.
 *
 * Conventions every gesture follows: non-sneak-only where the held item is also placeable (so vanilla
 * place/strip still works); server-authoritative research gates (client optimistically predicts and
 * re-syncs if wrong); and on success both setCancellationResult(SUCCESS) and setCanceled(true) -- the
 * SUCCESS result is mandatory or the cancel leaves the interaction PASS and the server reverts item
 * changes. Because these swap blocks with level.setBlock (which bypasses EntityPlaceEvent), any place-
 * time bookkeeping (the Stockpile record, multiblock refresh, etc.) is done by hand.
 *
 * Also registers every datapack reload listener (all the recipe/data managers) on AddReloadListenerEvent;
 * pushes the arrow-part registry to clients on join/reload so modpack-added parts render; augments
 * tooltips (crucible contents, hide quality, tool quality); applies effects (crucible-carry Slowness,
 * intoxication tick + wake-up hangover); gates log breaking (no axe -> zero break speed, so the zero-wood
 * bone axe is the bootstrap of the tree -> firewood -> campfire -> settlement path); harvests fiber/sticks
 * with cutting tools; preserves a snow layer over a broken snow-logged rock; and on logout forfeits every
 * in-progress hand-craft minigame (fletching, hammer, pottery, carpentry, masonry, mortar, tannery).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class AntiquityEvents {
    private AntiquityEvents() {
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onCrucibleCarry(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)) return;
        if (player.level().isClientSide) return;
        boolean carrying =
            player.getMainHandItem().is(BannerboundAntiquity.CRUCIBLE.get())
                && player.getMainHandItem().has(BannerboundAntiquity.CRUCIBLE_CONTENTS.get())
            || player.getOffhandItem().is(BannerboundAntiquity.CRUCIBLE.get())
                && player.getOffhandItem().has(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        if (carrying) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 10, 1, true, false, false));
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onIntoxicationTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)) return;
        if (player.level().isClientSide || player.tickCount % 20 != 0) return;
        com.bannerbound.antiquity.item.Intoxication.serverTick(player);
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onWakeUp(net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent event) {
        com.bannerbound.antiquity.item.Intoxication.startHangover(event.getEntity());
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void onMoldShaping(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return; // only process the hand pair once
        net.minecraft.world.entity.player.Player player = event.getEntity();
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        var molds = com.bannerbound.antiquity.workshop.MetalworkingItems.MOLDS;
        var baseHolder = molds.get("clay_mold_base");
        if (baseHolder == null) return;
        net.minecraft.world.item.Item base = baseHolder.get();
        net.minecraft.world.item.ItemStack baseStack;
        net.minecraft.world.item.ItemStack template;
        if (main.is(base)) { baseStack = main; template = off; }
        else if (off.is(base)) { baseStack = off; template = main; }
        else return;

        String shape = com.bannerbound.antiquity.workshop.MetalworkingItems.templateShape(template);
        if (shape == null) return;
        if (!player.level().isClientSide) {
            var shapedHolder = molds.get("clay_mold_" + shape);
            if (shapedHolder != null) {
                baseStack.shrink(1);
                net.minecraft.world.item.ItemStack out = new net.minecraft.world.item.ItemStack(shapedHolder.get());
                if (!player.getInventory().add(out)) player.drop(out, false);
                player.level().playSound(null, player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.GRAVEL_PLACE, net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.2F);
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    public static final TagKey<Block> HARD_SURFACE =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hard_surface"));
    public static final TagKey<Item> CUTTING_TOOLS =
        TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "cutting_tools"));

    private static final float GRAVEL_FLINT_CHANCE = 0.25f;
    private static final float GRASS_FIBER_CHANCE = 0.50f;
    private static final float LEAVES_STICK_CHANCE = 0.40f;
    private static final float DEAD_BUSH_FIBER_CHANCE = 0.25f;

    @SubscribeEvent
    static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new MortarRecipeManager());
        event.addListener(new BloomeryRecipeManager());
        event.addListener(new KilnRecipeManager());
        event.addListener(new CraftingStoneRecipeManager());
        event.addListener(new com.bannerbound.antiquity.recipe.DryingRackRecipeManager());
        event.addListener(new com.bannerbound.antiquity.recipe.GrogRecipeManager());
        event.addListener(new com.bannerbound.antiquity.recipe.StewRecipeManager());
        event.addListener(new com.bannerbound.antiquity.recipe.KnappingShapeManager());
        event.addListener(new FletchingRecipeManager());
        event.addListener(new com.bannerbound.antiquity.recipe.ArrowPartManager());
        event.addListener(new com.bannerbound.antiquity.recipe.AnvilRecipeManager());
        event.addListener(new com.bannerbound.antiquity.workshop.MetalworkingData());
        event.addListener(new PotteryRecipeManager());
        event.addListener(new com.bannerbound.antiquity.workshop.CarpentryOutputManager());
        event.addListener(new com.bannerbound.antiquity.workshop.CarpentryAssemblyManager());
        event.addListener(new com.bannerbound.antiquity.workshop.MasonryOutputManager());
        event.addListener(new com.bannerbound.antiquity.workshop.HidePreferenceLoader());
        event.addListener(new com.bannerbound.antiquity.food.FoodSpoilageData());
    }

    @SubscribeEvent
    static void onArrowLoose(net.neoforged.neoforge.event.entity.player.ArrowLooseEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || sp.isCreative()) return;
        ItemStack ammo = sp.getProjectile(event.getBow());
        if (!(ammo.getItem() instanceof com.bannerbound.antiquity.item.CompositeArrowItem)) return;
        com.bannerbound.core.api.settlement.Settlement settlement =
            com.bannerbound.core.api.research.SettlementDropFilter.settlementOf(sp);
        if (!com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, ammo)) {
            event.setCanceled(true);
            sp.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "bannerboundantiquity.arrow.unknown_ammo").withStyle(net.minecraft.ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    static void onDatapackSync(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        com.bannerbound.antiquity.network.ArrowPartsSyncPayload payload =
            new com.bannerbound.antiquity.network.ArrowPartsSyncPayload(
                com.bannerbound.antiquity.recipe.ArrowPartRegistry.all());
        if (event.getPlayer() != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(event.getPlayer(), payload);
        } else {
            for (ServerPlayer p : event.getPlayerList().getPlayers()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, payload);
            }
        }
    }

    private static String capitalize(String id) {
        return id == null || id.isEmpty() ? "Metal"
            : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    @SubscribeEvent
    static void onItemTooltip(ItemTooltipEvent event) {
        com.bannerbound.antiquity.item.CrucibleContents crucible =
            event.getItemStack().get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        if (crucible != null && !crucible.isEmpty()) {
            java.util.List<net.minecraft.network.chat.Component> tip = event.getToolTip();
            if (crucible.molten()) {
                tip.add(net.minecraft.network.chat.Component.literal(
                    "Molten " + capitalize(crucible.metalId()) + ": " + crucible.totalMb() + " mB")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            } else {
                for (net.minecraft.world.item.ItemStack s : crucible.charge()) {
                    tip.add(net.minecraft.network.chat.Component.literal(" • ").append(s.getHoverName())
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                com.bannerbound.antiquity.workshop.MetalworkingItems.MeltValue resolved =
                    com.bannerbound.antiquity.workshop.MetalworkingItems.resolveCharge(crucible.charge());
                if (resolved != null) {
                    tip.add(net.minecraft.network.chat.Component.literal(
                        "→ Molten " + capitalize(resolved.metalId()) + " (" + resolved.mb() + " mB)")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
                }
            }
        }
        com.bannerbound.antiquity.item.HideQuality hide =
            event.getItemStack().get(com.bannerbound.antiquity.BannerboundAntiquity.HIDE_QUALITY.get());
        if (hide != null) {
            java.util.List<net.minecraft.network.chat.Component> tip = event.getToolTip();
            if (!tip.isEmpty()) {
                tip.set(0, tip.get(0).copy().withStyle(hide.format()));
            }
            tip.add(net.minecraft.network.chat.Component
                .translatable("bannerboundantiquity.hide.quality")
                .append(net.minecraft.network.chat.Component.literal(" "))
                .append(hide.displayName()));
            return;
        }
        com.bannerbound.core.api.quality.QualityTier tier =
            event.getItemStack().get(com.bannerbound.core.BannerboundCore.TOOL_QUALITY.get());
        if (tier == null) {
            return;
        }
        java.util.List<net.minecraft.network.chat.Component> tip = event.getToolTip();
        if (!tip.isEmpty()) {
            tip.set(0, tip.get(0).copy().withStyle(tier.format()));
        }
        tip.add(net.minecraft.network.chat.Component
            .translatable("bannerbound.fletching.quality")
            .append(net.minecraft.network.chat.Component.literal(" "))
            .append(tier.displayName()));
    }

    @SubscribeEvent
    static void onScoopMortarWater(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isSecondaryUseActive()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!held.is(Items.GLASS_BOTTLE)) {
            return;
        }
        Level level = event.getLevel();
        if (!(level.getBlockEntity(event.getPos()) instanceof MortarAndPestleBlockEntity mortar)) {
            return;
        }
        if (mortar.isMixing() || !"water".equals(mortar.getLiquidId())) {
            return;
        }
        if (!level.isClientSide) {
            mortar.setLiquid("");
            if (!player.hasInfiniteMaterials()) {
                ItemStack waterBottle = PotionContents.createItemStack(Items.POTION, Potions.WATER);
                held.shrink(1);
                if (held.isEmpty()) {
                    player.setItemInHand(event.getHand(), waterBottle);
                } else if (!player.getInventory().add(waterBottle)) {
                    player.drop(waterBottle, false);
                }
            }
            level.playSound(null, event.getPos(), SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        // SUCCESS result required, else the cancel leaves it PASS and the server reverts the item changes above
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onCarveTanningRack(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack held = event.getItemStack();
        if (!held.is(CUTTING_TOOLS)) return;
        Level level = event.getLevel();
        BlockPos clicked = event.getPos();
        if (!level.getBlockState(clicked).is(net.minecraft.tags.BlockTags.LOGS)) return;

        net.minecraft.core.Direction facing = player.getDirection().getOpposite();
        net.minecraft.core.Direction width = facing.getClockWise();
        BlockPos master = findLogSquare(level, clicked, width);
        if (master == null) return;

        if (!level.isClientSide) {
            BlockState base = BannerboundAntiquity.TANNING_RACK.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
            BlockPos w = master.relative(width);
            level.setBlock(master, base.setValue(com.bannerbound.antiquity.block.TanningRackBlock.PART, 0),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.setBlock(w, base.setValue(com.bannerbound.antiquity.block.TanningRackBlock.PART, 1),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.setBlock(master.above(), base.setValue(com.bannerbound.antiquity.block.TanningRackBlock.PART, 2),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.setBlock(w.above(), base.setValue(com.bannerbound.antiquity.block.TanningRackBlock.PART, 3),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.playSound(null, master, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                    master.getX() + 0.5, master.getY() + 1.0, master.getZ() + 0.5, 14, 0.6, 0.8, 0.6, 0.02);
            }
            if (!player.hasInfiniteMaterials()) {
                held.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    private static BlockPos findLogSquare(Level level, BlockPos clicked, net.minecraft.core.Direction width) {
        BlockPos[] candidates = {
            clicked,
            clicked.relative(width.getOpposite()),
            clicked.below(),
            clicked.below().relative(width.getOpposite())
        };
        for (BlockPos m : candidates) {
            if (isLog(level, m) && isLog(level, m.relative(width))
                    && isLog(level, m.above()) && isLog(level, m.relative(width).above())) {
                return m;
            }
        }
        return null;
    }

    private static boolean isLog(Level level, BlockPos p) {
        return level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS);
    }

    @SubscribeEvent
    static void onCarveDryingRack(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.BONE_BLADE.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        net.minecraft.world.level.block.state.BlockState logState = level.getBlockState(pos);
        if (!logState.is(net.minecraft.tags.BlockTags.LOGS)) return;

        net.minecraft.core.Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) return;

        if (!level.isClientSide) {
            net.neoforged.neoforge.registries.DeferredBlock<com.bannerbound.antiquity.block.DryingRackBlock> rackBlock =
                BannerboundAntiquity.DRYING_RACK_BY_WOOD.getOrDefault(
                    dryingRackWoodFor(logState.getBlock()),
                    BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak"));
            level.setBlock(pos, rackBlock.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            com.bannerbound.antiquity.block.DryingRackBlock.refreshSelf(level, pos);
            level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":drying_rack");
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, logState),
                    pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 14, 0.3, 0.3, 0.3, 0.02);
            }
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    private static String dryingRackWoodFor(net.minecraft.world.level.block.Block log) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(log).getPath();
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        for (String suffix : new String[] { "_log", "_wood", "_stem", "_hyphae" }) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        return BannerboundAntiquity.DRYING_RACK_BY_WOOD.containsKey(path) ? path : "oak";
    }

    @SubscribeEvent
    static void onCarveFermentationTrough(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.BONE_KNIFE.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        net.minecraft.world.level.block.state.BlockState logState = level.getBlockState(pos);
        if (!logState.is(net.minecraft.tags.BlockTags.LOGS)) return;

        Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) return;

        net.neoforged.neoforge.registries.DeferredBlock<com.bannerbound.antiquity.block.FermentationTroughBlock> troughBlock =
            BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.getOrDefault(
                fermentationTroughWoodFor(logState.getBlock()),
                BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.get("oak"));

        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && com.bannerbound.core.event.UnknownItemBlocker.isUnknownForPlayer(sp, troughBlock.get().asItem())) {
            return;
        }

        if (!level.isClientSide) {
            level.setBlock(pos, troughBlock.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            com.bannerbound.antiquity.block.FermentationTroughBlock.refreshLine(level, pos);
            level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":fermentation_trough");
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, logState),
                    pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 14, 0.3, 0.3, 0.3, 0.02);
            }
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    private static String fermentationTroughWoodFor(net.minecraft.world.level.block.Block log) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(log).getPath();
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        for (String suffix : new String[] { "_log", "_wood", "_stem", "_hyphae" }) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        return BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.containsKey(path) ? path : "oak";
    }

    @SubscribeEvent
    static void onCarveCraftingStoneWithFlintBlade(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.FLINT_BLADE.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState source = level.getBlockState(pos);
        CraftingStoneBlock.Material material = KnifeItem.materialFor(source);
        if (material == null) return;

        if (!level.isClientSide) {
            Direction facing = player.getDirection().getOpposite();
            level.setBlock(pos, BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing)
                .setValue(CraftingStoneBlock.MATERIAL, material), Block.UPDATE_ALL);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
            spawnKnapParticles(level, pos, source);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":crafting_stone");
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFormBloomery(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!held.is(Items.COAL_BLOCK)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos bottom = event.getPos();
        BlockPos top = bottom.above();
        if (!level.getBlockState(bottom).is(Blocks.MUD_BRICKS)
                || !level.getBlockState(top).is(Blocks.MUD_BRICKS)) {
            return;
        }
        if (!level.isClientSide) {
            Player player = event.getEntity();
            Direction facing = player.getDirection().getOpposite();
            BlockState lower = BannerboundAntiquity.BLOOMERY.get().defaultBlockState()
                .setValue(BloomeryBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(BloomeryBlock.FACING, facing);
            level.setBlock(bottom, lower, Block.UPDATE_ALL);
            level.setBlock(top, lower.setValue(BloomeryBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            if (!player.hasInfiniteMaterials()) {
                held.shrink(1);
            }
            level.playSound(null, bottom, SoundType.TUFF_BRICKS.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":bloomery");
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static final String KILN_RESEARCH = BannerboundAntiquity.MODID + ":kiln";

    @SubscribeEvent
    static void onClayCobblestone(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!held.is(Items.CLAY_BALL)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
            return;
        }
        if (!level.isClientSide) {
            Player player = event.getEntity();
            if (!hasKilnResearch(level, player)) {
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component
                        .translatable("bannerboundantiquity.kiln.locked")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            } else {
                BlockState clayed = BannerboundAntiquity.CLAYED_COBBLESTONE.get().defaultBlockState();
                level.setBlock(pos, clayed, Block.UPDATE_ALL);
                if (!player.hasInfiniteMaterials()) {
                    held.shrink(1);
                }
                level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8F, 1.1F);
                if (level instanceof ServerLevel server) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, clayed),
                        pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.0);
                }
                KilnFormation.tryForm(level, pos, player);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onBasketCobblestone(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.BASKET_ITEM.get())) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
            return;
        }
        if (!level.isClientSide) {
            if (!com.bannerbound.core.api.research.BlockUseGate.isUnlocked(
                    level, pos, com.bannerbound.core.BannerboundCore.STOCKPILE_ITEM.get())) {
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component
                        .translatable("bannerboundantiquity.stockpile.locked")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            } else {
                BlockState stockpile = com.bannerbound.core.BannerboundCore.STOCKPILE.get().defaultBlockState();
                level.setBlock(pos, stockpile, Block.UPDATE_ALL);
                if (!player.hasInfiniteMaterials()) {
                    held.shrink(1);
                }
                level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
                if (level instanceof ServerLevel server) {
                    // setBlock skips EntityPlaceEvent, so register the Stockpile record by hand
                    com.bannerbound.core.block.StockpileBlock.registerOnPlace(server, pos);
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, stockpile),
                        pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.0);
                }
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFletchingStationCobblestone(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.PLANT_STRING.get())) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
            return;
        }
        if (!level.isClientSide) {
            if (!com.bannerbound.core.api.research.BlockUseGate.isUnlocked(
                    level, pos, BannerboundAntiquity.FLETCHING_STATION_ITEM.get())) {
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component
                        .translatable("bannerboundantiquity.fletching_station.locked")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            } else {
                BlockState station = BannerboundAntiquity.FLETCHING_STATION.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, player.getDirection().getOpposite());
                level.setBlock(pos, station, Block.UPDATE_ALL);
                if (!player.hasInfiniteMaterials()) {
                    held.shrink(1);
                }
                level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
                if (level instanceof ServerLevel server) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, station),
                        pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.0);
                }
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFormPotterySlab(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(Items.CLAY_BALL)) return;
        if (!player.hasInfiniteMaterials() && held.getCount() < 2) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState source = level.getBlockState(pos);
        if (!source.is(BannerboundAntiquity.CLAYED_COBBLESTONE.get())) return;

        if (!level.isClientSide) {
            if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, pos, BannerboundAntiquity.POTTERY_SLAB_ITEM.get())) {
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component
                        .translatable("bannerboundantiquity.pottery.locked")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            } else {
                BlockState formed = BannerboundAntiquity.POTTERY_SLAB.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, player.getDirection().getOpposite());
                level.setBlock(pos, formed, Block.UPDATE_ALL);
                if (!player.hasInfiniteMaterials()) {
                    held.shrink(2);
                }
                level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
                notifyBlockFormed(player, BannerboundAntiquity.MODID + ":pottery_slab");
                if (level instanceof ServerLevel server) {
                    server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CLAY.defaultBlockState()),
                        pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5, 14, 0.3, 0.08, 0.3, 0.01);
                }
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static boolean hasKilnResearch(Level level, Player player) {
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }
        com.bannerbound.core.api.settlement.Settlement s =
            com.bannerbound.core.api.settlement.SettlementData.get(server.overworld())
                .getByPlayer(player.getUUID());
        return s != null && s.hasCompletedResearch(KILN_RESEARCH);
    }

    @SubscribeEvent
    static void onFormWoodworkingTable(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.BONE_SAW.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(BlockTags.LOGS)) return;
        Direction dir = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).is(BlockTags.LOGS)) {
                dir = d;
                break;
            }
        }
        if (dir == null) return;

        if (!level.isClientSide) {
            BlockPos secondary = pos.relative(dir);
            BlockState main = BannerboundAntiquity.WOODWORKING_TABLE.get().defaultBlockState()
                .setValue(com.bannerbound.antiquity.block.WoodworkingTableBlock.FACING, dir)
                .setValue(com.bannerbound.antiquity.block.WoodworkingTableBlock.MAIN, true);
            level.setBlock(pos, main, Block.UPDATE_ALL);
            level.setBlock(secondary,
                main.setValue(com.bannerbound.antiquity.block.WoodworkingTableBlock.MAIN, false),
                Block.UPDATE_ALL);
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            level.playSound(null, pos, BannerboundAntiquity.SAW_DONE_SOUND.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":woodworking_table");
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STRIPPED_OAK_LOG.defaultBlockState()),
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 14, 0.4, 0.1, 0.4, 0.02);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static boolean isBenchStone(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
    }

    @SubscribeEvent
    static void onFormMasonsBench(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.STONE_CHISEL.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!isBenchStone(level.getBlockState(pos))) return;
        Direction dir = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isBenchStone(level.getBlockState(pos.relative(d)))) {
                dir = d;
                break;
            }
        }
        if (dir == null) return;

        if (!level.isClientSide) {
            BlockPos secondary = pos.relative(dir);
            BlockState main = BannerboundAntiquity.MASONS_BENCH.get().defaultBlockState()
                .setValue(com.bannerbound.antiquity.block.MasonsBenchBlock.FACING, dir)
                .setValue(com.bannerbound.antiquity.block.MasonsBenchBlock.MAIN, true);
            level.setBlock(pos, main, Block.UPDATE_ALL);
            level.setBlock(secondary,
                main.setValue(com.bannerbound.antiquity.block.MasonsBenchBlock.MAIN, false),
                Block.UPDATE_ALL);
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":masons_bench");
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                    pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 14, 0.4, 0.1, 0.4, 0.02);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFormRaft(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.OAR.get())) return;
        Level level = event.getLevel();
        BlockPos clicked = event.getPos();
        if (!level.getBlockState(clicked).is(BannerboundAntiquity.THATCH.get())) return;
        List<BlockPos> line = findRaftLine(level, clicked);
        if (line == null) return;

        if (!level.isClientSide) {
            for (BlockPos p : line) {
                level.removeBlock(p, false);
            }
            BlockPos center = line.get(1);
            float yaw = line.get(0).getX() != line.get(2).getX() ? 90.0F : 0.0F;
            RaftEntity raft = new RaftEntity(level,
                center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            raft.setYRot(yaw);
            raft.setRaftHealth(RaftEntity.MAX_HEALTH);
            level.addFreshEntity(raft);
            level.playSound(null, center, BannerboundAntiquity.THATCH_PLACE_SOUND,
                SoundSource.BLOCKS, 1.0F, 0.9F);
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static List<BlockPos> findRaftLine(Level level, BlockPos clicked) {
        for (Direction.Axis axis : new Direction.Axis[] { Direction.Axis.X, Direction.Axis.Z }) {
            Direction pos = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            Direction neg = pos.getOpposite();
            int forward = countThatch(level, clicked, pos);
            int backward = countThatch(level, clicked, neg);
            if (forward + backward + 1 < 3) continue;
            if (forward >= 1 && backward >= 1) {
                return List.of(clicked.relative(neg), clicked, clicked.relative(pos));
            } else if (forward >= 2) {
                return List.of(clicked, clicked.relative(pos), clicked.relative(pos, 2));
            } else {
                return List.of(clicked, clicked.relative(neg), clicked.relative(neg, 2));
            }
        }
        return null;
    }

    private static int countThatch(Level level, BlockPos from, Direction dir) {
        int n = 0;
        BlockPos p = from;
        for (int i = 0; i < 2; i++) {
            p = p.relative(dir);
            if (level.getBlockState(p).is(BannerboundAntiquity.THATCH.get())) n++;
            else break;
        }
        return n;
    }

    @SubscribeEvent
    static void onTieRaftWithFiberRope(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!held.is(BannerboundAntiquity.FIBER_ROPE.get())) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(BlockTags.FENCES)) return;
        Player player = event.getEntity();
        List<Leashable> rafts = LeadItem.leashableInArea(level, pos,
            l -> l instanceof RaftEntity && l.getLeashHolder() == player);
        if (rafts.isEmpty()) return;

        if (level instanceof ServerLevel server) {
            LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(server, pos);
            for (Leashable raft : rafts) {
                ((RaftEntity) raft).setFiberLeash(true);
                raft.setLeashedTo(knot, true);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onKnapHardSurface(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState surface = level.getBlockState(pos);
        if (!surface.is(HARD_SURFACE)) return;

        boolean handled = false;
        if (held.is(Items.GRAVEL)) {
            if (!level.isClientSide) {
                if (!player.hasInfiniteMaterials()) held.shrink(1);
                if (level.getRandom().nextFloat() < GRAVEL_FLINT_CHANCE) {
                    Block.popResource(level, pos.relative(event.getFace()), new ItemStack(Items.FLINT));
                }
                level.playSound(null, pos, SoundType.GRAVEL.getBreakSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
                spawnKnapParticles(level, pos, Blocks.GRAVEL.defaultBlockState());
            }
            handled = true;
        } else if (held.is(Items.FLINT)) {
            if (!level.isClientSide) {
                if (!player.hasInfiniteMaterials()) held.shrink(1);
                giveOrDrop(player, new ItemStack(BannerboundAntiquity.FLINT_BLADE.get()));
                playKnapping(level, pos);
                spawnKnapParticles(level, pos, surface);
                CodexManager.onItemObtained((ServerPlayer) player, "bannerboundantiquity:flint_blade");
            }
            handled = true;
        } else if (held.is(Items.BONE)) {
            if (!level.isClientSide) {
                if (!player.hasInfiniteMaterials()) held.shrink(1);
                giveOrDrop(player, new ItemStack(BannerboundAntiquity.BONE_BLADE.get(), 2));
                playKnapping(level, pos);
                spawnKnapParticles(level, pos, surface);
            }
            handled = true;
        }
        if (!handled) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onChopLoneLog(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return;
        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof AxeItem)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState logState = level.getBlockState(pos);
        if (!logState.is(BlockTags.LOGS)) return;
        for (Direction d : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(d));
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return;
        }
        if (!level.isClientSide) {
            Block sourceLog = logState.getBlock();
            level.setBlock(pos, BannerboundAntiquity.CHOPPING_STUMP.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity stump) {
                stump.setLogType(sourceLog);
            }
            held.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            level.playSound(null, pos, SoundType.WOOD.getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
            notifyBlockFormed(player, BannerboundAntiquity.MODID + ":chopping_stump");
            if (level instanceof ServerLevel sl) {
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, logState),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 16, 0.3, 0.2, 0.3, 0.02);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            Fletching.onPlayerDisconnect(sp);
            Hammer.onPlayerDisconnect(sp);
            Pottery.onPlayerDisconnect(sp);
            Carpentry.onPlayerDisconnect(sp);
            Masonry.onPlayerDisconnect(sp);
            MortarGrind.onPlayerDisconnect(sp);
            com.bannerbound.antiquity.craft.Tannery.onPlayerDisconnect(sp);
        }
    }

    @SubscribeEvent
    static void onLogBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player.hasInfiniteMaterials()) return;
        if (!event.getState().is(BlockTags.LOGS)) return;
        if (player.getMainHandItem().getItem() instanceof AxeItem) return;
        event.setNewSpeed(0.0f);
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onCuttingHarvest(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        ItemStack tool = player.getMainHandItem();
        if (!tool.is(CUTTING_TOOLS)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        RandomSource rng = level.getRandom();

        if (isGrassy(state)) {
            if (rng.nextFloat() < GRASS_FIBER_CHANCE) {
                Block.popResource(level, pos,
                    new ItemStack(BannerboundAntiquity.PLANT_FIBER.get(), 1 + rng.nextInt(2)));
            }
            damageIfDurable(tool, player);
        } else if (state.is(BlockTags.LEAVES)) {
            if (rng.nextFloat() < LEAVES_STICK_CHANCE) {
                Block.popResource(level, pos, new ItemStack(Items.STICK, 1 + rng.nextInt(2)));
            }
            damageIfDurable(tool, player);
        } else if (state.is(Blocks.DEAD_BUSH)) {
            if (rng.nextFloat() < DEAD_BUSH_FIBER_CHANCE) {
                Block.popResource(level, pos,
                    new ItemStack(BannerboundAntiquity.DRY_FIBER.get()));
            }
            damageIfDurable(tool, player);
        }
    }

    @SubscribeEvent
    static void onRockBreakKeepsSnow(BlockEvent.BreakEvent event) {
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof RockBlock)) return;
        int snow = state.getValue(RockBlock.SNOW);
        if (snow <= 0) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos().immutable();
        // defer to next tick: restore snow only once the broken block has actually become air
        level.getServer().execute(() -> {
            if (level.getBlockState(pos).isAir()) {
                level.setBlockAndUpdate(pos,
                    Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, snow));
            }
        });
    }

    private static boolean isGrassy(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN);
    }

    private static void damageIfDurable(ItemStack tool, Player player) {
        if (tool.isDamageableItem()) {
            tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void notifyBlockFormed(Player player, String blockId) {
        if (player instanceof ServerPlayer serverPlayer) {
            CodexManager.onBlockFormed(serverPlayer, blockId);
        }
    }

    private static void playKnapping(Level level, BlockPos pos) {
        level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
    }

    private static void spawnKnapParticles(Level level, BlockPos pos, BlockState dust) {
        if (!(level instanceof ServerLevel server)) return;
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dust),
            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 12, 0.25, 0.05, 0.25, 0.02);
    }

    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        handle(event);
    }

    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        handle(event);
    }

    private static void handle(PlayerInteractEvent event) {
        Player player = event.getEntity();
        if (!player.getMainHandItem().is(Knapping.KNAPPING_ROCKS)
                || !player.getOffhandItem().is(Knapping.KNAPPING_ROCKS)) {
            return;
        }
        // cancel both hands' events so neither rock is placed by this "knap" gesture
        ((net.neoforged.bus.api.ICancellableEvent) event).setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            Knapping.tryOpen(serverPlayer);
        }
    }
}
