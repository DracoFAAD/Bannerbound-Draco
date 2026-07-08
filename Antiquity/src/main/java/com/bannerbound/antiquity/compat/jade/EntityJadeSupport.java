package com.bannerbound.antiquity.compat.jade;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Animal;

import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;

/**
 * Jade entity providers. Citizens first get their name line REBUILT: the baked custom name leads
 * with gender/job glyphs from the billboard icons font, which are sized for overhead rendering
 * and break Jade's name row, so tooltipSafeName keeps only the default-font parts (color styles
 * preserved, type name fallback) and swaps them into CORE_OBJECT_NAME -- barbarians get the name
 * fix too, then bail before any vitals leak. Citizens then show the client-synced vitals
 * (happiness colored by thirds, stamina, pregnancy, poison, the work-blocked warning) plus two
 * server-only strings shipped via
 * CitizenData: the canonical job id (only the icon int is synced to clients, so the readable name
 * needs a round trip; translated client-side through the same bannerbound.job.* keys the Jobs tab
 * uses) and the settlement name. Barbarians and mercenaries extend CitizenEntity and are matched
 * by the same registration, so both providers bail on BarbarianEntity -- a raider's mood and
 * home town are not the player's business. Livestock (any vanilla Animal) surfaces the herder's
 * TAMED_LIVESTOCK attachment, which is persisted server-side only and therefore also travels as
 * Jade server data. Without Jade on the server these lines silently drop; nothing client-side
 * breaks.
 */
public final class EntityJadeSupport {
    private EntityJadeSupport() {
    }

    public enum CitizenData implements IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (!(accessor.getEntity() instanceof CitizenEntity citizen)
                || citizen instanceof BarbarianEntity) {
                return;
            }
            String job = citizen.getJobType();
            data.putString("BbJob", job == null ? "" : job);
            Settlement settlement = citizen.getSettlement();
            if (settlement != null) {
                data.putString("BbSettlement", settlement.name());
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.CITIZEN;
        }
    }

    public enum CitizenDisplay implements IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof CitizenEntity citizen)) {
                return;
            }
            Component name = tooltipSafeName(citizen);
            if (!tooltip.replace(JadeIds.CORE_OBJECT_NAME, name)) {
                tooltip.add(0, name, JadeIds.CORE_OBJECT_NAME);
            }
            if (citizen instanceof BarbarianEntity) {
                return;
            }
            CompoundTag data = accessor.getServerData();
            if (data.contains("BbJob")) {
                String job = data.getString("BbJob");
                Component jobName = job.isEmpty()
                    ? Component.translatable("bannerbound.job.unemployed")
                    : Component.translatable("bannerbound.job." + job);
                tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.job", jobName));
            }
            int happiness = citizen.getHappiness();
            int happinessMax = Math.max(1, citizen.getHappinessMax());
            ChatFormatting mood = happiness * 3 >= happinessMax * 2 ? ChatFormatting.GREEN
                : happiness * 3 >= happinessMax ? ChatFormatting.YELLOW : ChatFormatting.RED;
            tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.happiness",
                happiness, happinessMax).withStyle(mood));
            tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.energy",
                citizen.getStamina(), citizen.getStaminaMax()).withStyle(ChatFormatting.AQUA));
            if (citizen.isPregnant()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.pregnant")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            if (citizen.isPoisoned()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.poisoned")
                    .withStyle(ChatFormatting.DARK_GREEN));
            }
            if (citizen.isWorkBlocked()) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.work_blocked")
                    .withStyle(ChatFormatting.RED));
            }
            if (data.contains("BbSettlement")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.citizen.settlement",
                    data.getString("BbSettlement")).withStyle(ChatFormatting.GRAY));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.CITIZEN;
        }

        private static Component tooltipSafeName(CitizenEntity citizen) {
            Component custom = citizen.getCustomName();
            MutableComponent clean = Component.empty();
            if (custom != null) {
                custom.visit((style, text) -> {
                    if (Style.DEFAULT_FONT.equals(style.getFont())
                        && (!text.isBlank() || !clean.getSiblings().isEmpty())) {
                        clean.append(Component.literal(text).withStyle(style));
                    }
                    return java.util.Optional.<Object>empty();
                }, Style.EMPTY);
            }
            Component base = clean.getString().isBlank()
                ? citizen.getType().getDescription() : clean;
            return IThemeHelper.get().title(base);
        }
    }

    public enum LivestockData implements IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (accessor.getEntity() instanceof Animal animal
                && animal.getData(BannerboundAntiquity.TAMED_LIVESTOCK.get())) {
                data.putBoolean("BbTamed", true);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.LIVESTOCK;
        }
    }

    public enum LivestockDisplay implements IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (accessor.getServerData().getBoolean("BbTamed")) {
                tooltip.add(Component.translatable("bannerboundantiquity.jade.livestock.tamed")
                    .withStyle(ChatFormatting.GREEN));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return BannerboundAntiquityJadePlugin.LIVESTOCK;
        }
    }
}
