package com.bannerbound.antiquity.compat.jade;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.ChunkResources;
import com.bannerbound.core.territory.CropChunks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.CropBlock;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade support for world/territory facts that only the server can resolve. Crop chunks: the
 * chunk's specialized resource type is a seeded server-side function (ChunkResources.typeAt needs
 * the world seed via ServerLevel), so CropData ships the type name plus whether the looked-at crop
 * matches it; CropDisplay then renders either the green double-harvest line or which crop this
 * chunk favors (CropChunks' static type-to-crop mapping is common code, safe to call client-side).
 * Faction banners: vanilla banner blocks carry no mod state -- whether one IS a settlement's main
 * or outpost banner lives on the Settlement record (bannerPos / outpostBannerPos keyed by the
 * banner's chunk), so BannerData resolves the owning settlement by chunk and flags main vs
 * outpost; the main banner gets the town-hall-locks warning since toppling it halts the town.
 * Town hall: the founding campfire is likewise a plain vanilla campfire, identified only by
 * Settlement.townHallPos, so TownHallData flags it and TownHallDisplay names it in gold.
 * All pairs silently show nothing when Jade is not installed server-side.
 */
public final class TerritoryJadeSupport {
    private TerritoryJadeSupport() {
    }

    public enum CropData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel sl)) {
                return;
            }
            ChunkResource type = ChunkResources.typeAt(sl, new ChunkPos(accessor.getPosition()));
            if (!CropChunks.isCropChunk(type)) {
                return;
            }
            data.putString("BbCropChunk", type.name());
            data.putBoolean("BbCropMatch", CropChunks.cropBlock(type) == accessor.getBlock());
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.CROP_CHUNK;
        }
    }

    public enum CropDisplay implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains("BbCropChunk")) {
                return;
            }
            if (data.getBoolean("BbCropMatch")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.crop.match")
                    .withStyle(ChatFormatting.GREEN));
                return;
            }
            ChunkResource type;
            try {
                type = ChunkResource.valueOf(data.getString("BbCropChunk"));
            } catch (IllegalArgumentException e) {
                return;
            }
            CropBlock favored = CropChunks.cropBlock(type);
            if (favored != null) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.crop.other",
                    favored.getName()).withStyle(ChatFormatting.GRAY));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.CROP_CHUNK;
        }
    }

    public enum TownHallData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel sl)) {
                return;
            }
            BlockPos pos = accessor.getPosition();
            Settlement owner = SettlementData.get(sl.getServer().overworld())
                .getByChunk(new ChunkPos(pos).toLong());
            if (owner != null && pos.equals(owner.townHallPos())) {
                data.putString("BbTownHall", owner.name());
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.TOWN_HALL;
        }
    }

    public enum TownHallDisplay implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (data.contains("BbTownHall")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.town_hall",
                    data.getString("BbTownHall")).withStyle(ChatFormatting.GOLD));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.TOWN_HALL;
        }
    }

    public enum BannerData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getLevel() instanceof ServerLevel sl)) {
                return;
            }
            BlockPos pos = accessor.getPosition();
            long chunk = new ChunkPos(pos).toLong();
            Settlement owner = SettlementData.get(sl.getServer().overworld()).getByChunk(chunk);
            if (owner == null) {
                return;
            }
            if (pos.equals(owner.bannerPos())) {
                data.putString("BbBanner", owner.name());
                data.putBoolean("BbBannerMain", true);
            } else if (pos.equals(owner.outpostBannerPos(chunk))) {
                data.putString("BbBanner", owner.name());
                data.putBoolean("BbBannerMain", false);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.BANNER;
        }
    }

    public enum BannerDisplay implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains("BbBanner")) {
                return;
            }
            boolean main = data.getBoolean("BbBannerMain");
            String key = main
                ? "bannerboundantiquity.jade.banner.main"
                : "bannerboundantiquity.jade.banner.outpost";
            tooltip.add(Component.translatable(key, data.getString("BbBanner"))
                .withStyle(ChatFormatting.GOLD));
            if (main) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.banner.hint")
                    .withStyle(ChatFormatting.GRAY));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.BANNER;
        }
    }
}
