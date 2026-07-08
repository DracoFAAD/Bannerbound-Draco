package com.bannerbound.antiquity.compat.jade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.block.entity.StockpileBlockEntity;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ItemView;
import snownee.jade.api.view.ViewGroup;

/**
 * Jade support for the stockpile, the one block whose interesting state lives OFF the block
 * entity: the BE anchors only a UUID while status, worker toggles and the enclosed container set
 * sit on the server-side Stockpile settlement record. ServerData ships status + toggle flags per
 * look; Storage aggregates the enclosed containers' item handlers (skipping secondary double-chest
 * halves exactly like StockpileService, merging by item, top TOP_STACKS by count) into Jade's
 * standard item-storage channel so the contents render like a chest's would -- unknown items in
 * the pool arrive as real stacks but the client draws them through the masked model/name path, so
 * nothing unresearched is spoiled. Display renders the synced status line (short Jade-specific
 * strings, not the verbose terminal ones) plus deviations from the defaults: worker take/deposit
 * OFF, trading ON. All three run without Jade installed server-side; the tooltip then just shows
 * less (no status/contents), never wrong data.
 */
public final class StockpileJadeSupport {
    static final int TOP_STACKS = 54;

    private StockpileJadeSupport() {
    }

    private static Stockpile resolve(BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel sl)
            || !(accessor.getBlockEntity() instanceof StockpileBlockEntity be)
            || be.getStockpileId() == null) {
            return null;
        }
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(accessor.getPosition()).toLong());
        return owner == null ? null : owner.getStockpileById(be.getStockpileId());
    }

    public enum ServerData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            Stockpile sp = resolve(accessor);
            if (sp == null) {
                return;
            }
            data.putString("BbStatus", sp.status().name());
            data.putInt("BbContainers", sp.containerCount());
            data.putBoolean("BbTake", sp.allowWorkerTake());
            data.putBoolean("BbDeposit", sp.allowWorkerDeposit());
            data.putBoolean("BbTrading", sp.showForTrading());
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.STOCKPILE;
        }
    }

    public enum Display implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains("BbStatus")) {
                return;
            }
            String status = data.getString("BbStatus").toLowerCase(Locale.ROOT);
            boolean valid = "valid".equals(status);
            tooltip.add(Component.translatable("bannerboundantiquity.jade.stockpile.status." + status,
                    data.getInt("BbContainers"), Stockpile.MAX_CONTAINERS)
                .withStyle(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (!data.getBoolean("BbTake")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.stockpile.no_take")
                    .withStyle(ChatFormatting.GRAY));
            }
            if (!data.getBoolean("BbDeposit")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.stockpile.no_deposit")
                    .withStyle(ChatFormatting.GRAY));
            }
            if (data.getBoolean("BbTrading")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.stockpile.trading")
                    .withStyle(ChatFormatting.GOLD));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.STOCKPILE;
        }
    }

    public enum StorageServer implements IServerExtensionProvider<ItemStack> {
        INSTANCE;

        @Override
        public List<ViewGroup<ItemStack>> getGroups(Accessor<?> accessor) {
            if (!(accessor instanceof BlockAccessor block)
                || !(block.getLevel() instanceof ServerLevel sl)) {
                return null;
            }
            Stockpile sp = resolve(block);
            if (sp == null || !sp.valid()) {
                return null;
            }
            LinkedHashMap<Item, Integer> totals = new LinkedHashMap<>();
            for (BlockPos cpos : sp.containers()) {
                if (DropOffContainers.isSecondaryChestHalf(sl, cpos)) {
                    continue;
                }
                IItemHandler handler = sl.getCapability(Capabilities.ItemHandler.BLOCK, cpos, null);
                if (handler == null) {
                    continue;
                }
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty()) {
                        totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
            List<ItemStack> stacks = new ArrayList<>(Math.min(totals.size(), TOP_STACKS));
            totals.entrySet().stream()
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .limit(TOP_STACKS)
                .forEach(entry -> stacks.add(new ItemStack(entry.getKey(), entry.getValue())));
            return List.of(new ViewGroup<>(stacks));
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.STOCKPILE_STORAGE;
        }
    }

    public enum StorageClient implements IClientExtensionProvider<ItemStack, ItemView> {
        INSTANCE;

        @Override
        public List<ClientViewGroup<ItemView>> getClientGroups(Accessor<?> accessor,
                                                               List<ViewGroup<ItemStack>> groups) {
            return ClientViewGroup.map(groups, ItemView::new, null);
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.STOCKPILE_STORAGE;
        }
    }
}
