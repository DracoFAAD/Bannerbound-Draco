package com.bannerbound.core.block;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.block.entity.StockpileBlockEntity;
import com.bannerbound.core.building.StockpileEnclosure;
import com.bannerbound.core.stockpile.StockpileService;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The placed Stockpile Block — anchor for a settlement's community storage. The BE auto-scans the
 * surrounding fence/roof enclosure and aggregates the container blocks inside (see {@link Stockpile}
 * / {@code StockpileService}); the block can be assigned directly as a worker drop-off (no Stocker —
 * that's the later Warehouse tier).
 *
 * <p>All logic is Core; only the model + texture are the Ancient-era skin, referenced by the Core
 * blockstate. Right-click currently prints the enclosure status to chat — the scrollable storage
 * terminal arrives in a later step (custom payload + screen, like the House status panel).
 */
public class StockpileBlock extends Block implements EntityBlock {
    public static final MapCodec<StockpileBlock> CODEC = simpleCodec(StockpileBlock::new);

    public StockpileBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<StockpileBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StockpileBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        // Server-side validator only; the client BE needs no ticker.
        if (level.isClientSide || type != BannerboundCore.STOCKPILE_BE.get()) return null;
        return (l, p, s, be) -> StockpileBlockEntity.tick(l, p, s, (StockpileBlockEntity) be);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return InteractionResult.PASS;
        }
        MinecraftServer server = sl.getServer();
        if (server == null) return InteractionResult.CONSUME;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement owner = data.getByChunk(new ChunkPos(pos).toLong());
        BlockEntity be = level.getBlockEntity(pos);
        Stockpile stock = (owner != null && be instanceof StockpileBlockEntity sbe && sbe.getStockpileId() != null)
            ? owner.getStockpileById(sbe.getStockpileId()) : null;
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(sp, pos)) {
            sp.sendSystemMessage(Component.translatable("bannerbound.stockpile.error.no_access")
                .withStyle(net.minecraft.ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }
        // A stockpile left standing from before the civ learned Storage Logistics stays inert until
        // the owning settlement has actually researched it (mirrors the craft/drop knowledge gate).
        if (!com.bannerbound.core.api.research.BlockUseGate.checkUse(sp, level, pos,
                BannerboundCore.STOCKPILE_ITEM.get(), "bannerbound.stockpile.error.not_researched")) {
            return InteractionResult.CONSUME;
        }

        // Refresh validity so the terminal header is current the moment it opens, then open it.
        // (The enclosure wireframe is now on-demand via the terminal's Detect button, not on open.)
        if (stock != null) {
            StockpileService.validate(sl, stock);
            data.setDirty();
        }
        sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new com.bannerbound.core.menu.StockpileMenu(id, inv, pos, sp),
                Component.translatable("block.bannerbound.stockpile")),
            buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !newState.is(this) && level instanceof ServerLevel sl) {
            BlockEntity be = level.getBlockEntity(pos);
            UUID id = be instanceof StockpileBlockEntity sbe ? sbe.getStockpileId() : null;
            if (id != null) StockpileBlockEntity.onBlockRemoved(sl, pos, id);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    /** Re-scans the enclosure, refreshes the record, and flashes the wireframe (green floor, blue
     *  containers, red fail spot) to {@code player} for ~5 s — the terminal's Detect button, mirroring
     *  the House Detect preview. */
    public static void flashEnclosure(ServerLevel sl, BlockPos pos, ServerPlayer player) {
        StockpileEnclosure.Result r = StockpileEnclosure.scan(sl, pos);
        MinecraftServer server = sl.getServer();
        if (server != null) {
            Settlement owner = SettlementData.get(server.overworld()).getByChunk(new ChunkPos(pos).toLong());
            if (owner != null) {
                Stockpile sp = owner.getStockpile(pos);
                if (sp != null) StockpileService.validate(sl, sp);
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.ShowStockpileDebugPayload(
                new java.util.ArrayList<>(r.interior()),
                new java.util.ArrayList<>(r.storage()),
                java.util.Optional.ofNullable(r.failPos()),
                100));
    }

    /** Block-place hook: register a fresh {@link Stockpile} on the settlement owning this chunk and
     *  stash its id on the BE. No-op in unclaimed territory — the BE lazily registers if/when the
     *  chunk is later claimed. Mirrors {@code HouseBlock.registerOnPlace}. */
    public static void registerOnPlace(ServerLevel level, BlockPos pos) {
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(pos).toLong());
        if (owner == null) return;
        if (owner.getStockpile(pos) != null) return;
        UUID id = UUID.randomUUID();
        owner.putStockpile(new Stockpile(id, pos.immutable()));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof StockpileBlockEntity sbe) sbe.setStockpileId(id);
        data.setDirty();
    }
}
