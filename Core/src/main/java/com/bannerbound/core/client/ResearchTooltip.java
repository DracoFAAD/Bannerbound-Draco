package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ResearchPonderBridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared rich tooltip for a research node: title, description, effect lines, an unlocked-items
 * grid, and cost/progress/queue/lock status. Extracted so every research screen presents identical
 * information; the flat {@link ResearchScreen} keeps its own inline copy, new screens call
 * {@link #render}. The panel interleaves text lines with an item-slot grid because a vanilla
 * tooltip cannot draw both, and the grid bypasses the unknown-item swap so players preview what a
 * node grants before it is researched. Effect lines derive from a node's feature/flag strings:
 * known prefixes format bespoke lines, otherwise a "bannerbound.research.effect.&lt;suffix&gt;" lang
 * key is looked up; a flag with no such key is an internal gating flag and is suppressed so the raw
 * key never leaks into the tooltip -- add that lang entry to surface a green effect line. The
 * Ponder hint mirrors Create's "Hold [W] to Ponder" treatment (DARK_GRAY text plus a '|' progress
 * bar that fills as the player holds W) and only appears when a Create-aware expansion is loaded and
 * the node has a scene. ITEM_CELL is 18px = 16 sprite + 2 padding.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ResearchTooltip {

    private static final int ITEMS_PER_ROW = 6;
    private static final int ITEM_CELL = 18;

    private ResearchTooltip() {}

    public static void render(GuiGraphics graphics, Font font, ResearchDefinition def,
                              int mouseX, int mouseY, int screenW, int screenH) {
        List<Component> header = new ArrayList<>();
        header.add(Component.literal(def.name()).withColor(0xFF2A1808));
        if (!def.description().isEmpty()) {
            String formatted = applyAmpFormatting(def.description());
            for (String line : formatted.split("\n", -1)) {
                header.add(Component.literal(line).withColor(0xFF4A3520));
            }
        }
        appendEffectLines(def, header);

        List<ItemStack> items = new ArrayList<>();
        for (String id : def.unlocksItems()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == Items.AIR) continue;
            items.add(new ItemStack(item));
        }

        List<Component> footer = new ArrayList<>();
        if (def.cost() > 0) {
            if (ClientResearchState.isCompleted(def.id())) {
                footer.add(Component.translatable("bannerbound.research.node.completed")
                    .withColor(0xFF7A4E12));
            } else {
                double progress = ClientResearchState.getProgress(def.id());
                double remaining = Math.max(0.0, def.cost() - progress);
                String costText = String.format("%.1f / %.1f ", progress, def.cost());
                String timePrefix = "(" + formatTimeRemaining(remaining) + ") ";
                footer.add(Component.literal(timePrefix + costText).withColor(0xFF7A4E12)
                    .append(Icons.science()));
            }
        }
        int queuePos = ClientResearchState.getQueuePosition(def.id());
        if (queuePos > 1) {
            footer.add(Component.translatable("bannerbound.research.queue_position", queuePos)
                .withColor(0xFF1A5560));
        }
        if (!ClientResearchState.isCompleted(def.id())) {
            if (ClientEraState.getPlayerEra().ordinal() < def.minAge().ordinal()) {
                footer.add(Component.translatable("bannerbound.research.age_locked",
                        def.minAge().displayName()).withColor(0xFF8E2018));
            }
            if (def.requiresTribe() && !ClientPopulationState.isTribe()) {
                footer.add(Component.translatable("bannerbound.research.tribe_locked")
                    .withColor(0xFF8E2018));
            }
        }
        if (!ClientResearchState.prereqsMet(def) && !ClientResearchState.isCompleted(def.id())) {
            footer.add(Component.translatable("bannerbound.research.prereq_locked")
                .withColor(0xFF8E2018));
        }
        if (!def.ponderScene().isEmpty() && ResearchPonderBridge.isAvailable()) {
            footer.add(ResearchPonderBridge.holdToPonderHint(font));
        }

        Component itemsHeader = items.isEmpty() ? null
            : Component.translatable("bannerbound.research.node.unlocked_items")
                .withColor(0xFF4A3520);
        draw(graphics, font, header, itemsHeader, items, footer, mouseX, mouseY, screenW, screenH);
    }

    private static void draw(GuiGraphics graphics, Font font, List<Component> header,
                             Component itemsHeader, List<ItemStack> items, List<Component> footer,
                             int mouseX, int mouseY, int screenW, int screenH) {
        int lineH = font.lineHeight + 1;
        int itemRows = items.isEmpty() ? 0 : (int) Math.ceil(items.size() / (double) ITEMS_PER_ROW);
        int itemsBlockH = items.isEmpty() ? 0 : (lineH + itemRows * ITEM_CELL + 2);

        int widestText = 0;
        for (Component c : header) widestText = Math.max(widestText, font.width(c));
        for (Component c : footer) widestText = Math.max(widestText, font.width(c));
        if (itemsHeader != null) widestText = Math.max(widestText, font.width(itemsHeader));
        int itemsGridW = items.isEmpty() ? 0 : Math.min(items.size(), ITEMS_PER_ROW) * ITEM_CELL;
        int contentW = Math.max(widestText, itemsGridW);
        int panelW = contentW + 8;
        int panelH = header.size() * lineH + itemsBlockH + footer.size() * lineH + 8;

        int panelX = mouseX + 12;
        int panelY = mouseY - panelH;
        if (panelX + panelW > screenW) panelX = screenW - panelW - 2;
        if (panelY < 2) panelY = mouseY + 12;
        if (panelY + panelH > screenH) panelY = screenH - panelH - 2;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        try {
            graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
                0xFFEFDFB6, 0xFFD8C181);
            graphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF7A5413);
            if (panelW > 2 && panelH > 2) {
                graphics.renderOutline(panelX + 1, panelY + 1, panelW - 2, panelH - 2, 0xFFB58629);
            }

            int textX = panelX + 4;
            int y = panelY + 4;
            for (Component c : header) {
                graphics.drawString(font, c, textX, y, 0xFFFFFFFF, false);
                y += lineH;
            }
            if (!items.isEmpty()) {
                graphics.drawString(font, itemsHeader, textX, y, 0xFFCCCCCC, false);
                y += lineH;
                UnknownItemHelper.setBypassUnknownSwap(true);
                try {
                    for (int i = 0; i < items.size(); i++) {
                        int ix = textX + (i % ITEMS_PER_ROW) * ITEM_CELL;
                        int iy = y + (i / ITEMS_PER_ROW) * ITEM_CELL;
                        graphics.renderItem(items.get(i), ix, iy);
                    }
                } finally {
                    UnknownItemHelper.setBypassUnknownSwap(false);
                }
                y += itemRows * ITEM_CELL + 2;
            }
            for (Component c : footer) {
                graphics.drawString(font, c, textX, y, 0xFFFFFFFF, false);
                y += lineH;
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    private static String applyAmpFormatting(String s) {
        return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    private static void appendEffectLines(ResearchDefinition def, List<Component> lines) {
        for (String feature : def.unlocksFeatures()) {
            Component line = describeFeature(feature);
            if (line != null) lines.add(line);
        }
        for (String flag : def.unlocksFlags()) {
            Component line = describeFeature(flag);
            if (line != null) lines.add(line);
        }
    }

    private static Component describeFeature(String feature) {
        if (feature.startsWith("bannerbound.food_capacity_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.food_capacity_delta:".length()) + " ")
                .append(Icons.food()).append(Component.literal(" capacity"))
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.culture_capacity_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.culture_capacity_delta:".length()) + " ")
                .append(Icons.culture()).append(Component.literal(" capacity"))
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.science_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.science_per_second_delta:".length()) + " ")
                .append(Icons.science()).append(Component.literal("/s"))
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.food_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.food_per_second_delta:".length()) + " ")
                .append(Icons.food()).append(Component.literal("/s"))
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.culture_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.culture_per_second_delta:".length()) + " ")
                .append(Icons.culture()).append(Component.literal("/s"))
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.citizen_speed_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.citizen_speed_delta:".length())
                    + " citizen speed").withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.advance_age:")) {
            String eraKey = feature.substring("bannerbound.advance_age:".length());
            com.bannerbound.core.api.settlement.Era era =
                com.bannerbound.core.api.settlement.Era.fromName(eraKey);
            Component eraName = era != null ? era.displayName() : Component.literal(eraKey);
            return Component.translatable("bannerbound.research.effect.advance_age", eraName)
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.set_tool_age:")) {
            String ageId = feature.substring("bannerbound.set_tool_age:".length());
            com.bannerbound.core.api.research.ToolAge age =
                com.bannerbound.core.api.research.data.ToolAgeLoader.get(ageId);
            Component ageName = age != null ? age.displayName() : Component.literal(ageId);
            return Component.translatable("bannerbound.research.effect.set_tool_age", ageName)
                .withColor(0xFF2C5E2A);
        }
        if (feature.startsWith("bannerbound.showore:")) {
            String itemId = feature.substring("bannerbound.showore:".length());
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            Component itemName;
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                itemName = item == Items.AIR ? Component.literal(itemId) : item.getDescription();
            } else {
                itemName = Component.literal(itemId);
            }
            return Component.translatable("bannerbound.research.effect.showore", itemName)
                .withColor(0xFF2C5E2A);
        }
        return autoDescribeByLangKey(feature);
    }

    private static Component autoDescribeByLangKey(String feature) {
        int colon = feature.indexOf(':');
        String prefix = colon >= 0 ? feature.substring(0, colon) : feature;
        String arg = colon >= 0 ? feature.substring(colon + 1) : "";
        int lastDot = prefix.lastIndexOf('.');
        String suffix = lastDot >= 0 ? prefix.substring(lastDot + 1) : prefix;
        String langKey = "bannerbound.research.effect." + suffix;
        if (I18n.exists(langKey)) {
            return arg.isEmpty()
                ? Component.translatable(langKey).withColor(0xFF2C5E2A)
                : Component.translatable(langKey, arg).withColor(0xFF2C5E2A);
        }
        return null;
    }

    private static String formatTimeRemaining(double remaining) {
        double sps = ClientResearchState.getSciencePerSecond();
        if (sps <= 0.0) return "∞";
        int total = (int) Math.ceil(remaining / sps);
        if (total <= 0) return "0s";
        int h = total / 3600, m = (total / 60) % 60, s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02dh", h, m, s);
        if (m > 0) return String.format("%d:%02dm", m, s);
        return String.format("%ds", s);
    }
}
