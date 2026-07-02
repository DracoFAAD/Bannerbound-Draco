package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.BarbarianParleyActionPayload;
import com.bannerbound.core.network.OpenBarbarianParleyPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The barbarian parley: opened when the player right-clicks a messenger at their hall. Shows the camp's
 * greeting + its data-driven demands and trade offers, with accept / refuse / trade buttons that send a
 * {@link BarbarianParleyActionPayload} back to the server (which re-validates everything).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BarbarianParleyScreen extends PolishedScreen {
    private static final int PANEL_W = 300;
    private final OpenBarbarianParleyPayload data;
    private int panelX;
    private int panelY;
    private int panelH;
    private int textTop; // where backdrop text starts

    public BarbarianParleyScreen(OpenBarbarianParleyPayload data) {
        super(Component.literal(data.campName()));
        this.data = data;
    }

    @Override
    protected void init() {
        // Size the panel to its content: greeting + demands header/rows + a button per action.
        int greetingLines = wrappedLineCount(this.font, greeting(), PANEL_W - 24);
        int demandRows = data.demands().isEmpty() ? 0 : data.demands().size() + 1; // +1 header
        int buttons = data.trades().size()
            + (data.demands().isEmpty() ? 0 : 1) // accept
            + (data.demands().isEmpty() ? 0 : 1) // refuse
            + 1; // leave
        panelH = 50 + greetingLines * (this.font.lineHeight + 1) + demandRows * 12 + 8 + buttons * 24 + 12;
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;

        int y = panelY + 34;
        y += greetingLines * (this.font.lineHeight + 1) + 6;
        if (!data.demands().isEmpty()) y += (data.demands().size() + 1) * 12 + 4;
        textTop = panelY + 34;

        int bx = panelX + 12;
        int bw = PANEL_W - 24;
        int by = y;

        if (!data.demands().isEmpty()) {
            Component accLabel = data.canImprove()
                ? Component.translatable("bannerbound.barbarian.parley.btn.tribute")
                : Component.translatable("bannerbound.barbarian.parley.btn.tribute_appease");
            addRenderableWidget(PolishButton.polished(accLabel.copy().withStyle(ChatFormatting.GREEN),
                b -> send(BarbarianParleyActionPayload.ACCEPT, 0))
                .bounds(bx, by, bw, 20).build());
            by += 24;
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.barbarian.parley.btn.refuse").copy()
                    .withStyle(ChatFormatting.RED),
                b -> send(BarbarianParleyActionPayload.REFUSE, 0))
                .bounds(bx, by, bw, 20).build());
            by += 24;
        }
        for (int i = 0; i < data.trades().size(); i++) {
            OpenBarbarianParleyPayload.Trade t = data.trades().get(i);
            final int idx = i;
            // Compose with Components (NOT string concatenation, which would stringify the name into a
            // "translation{key=…}" debug blob — the original bug).
            Component give = Component.literal(t.giveCount() + "× ").append(itemName(t.giveItem()));
            Component get = Component.literal(t.getCount() + "× ").append(itemName(t.getItem()));
            Component label = Component.translatable("bannerbound.barbarian.parley.btn.trade", give, get);
            addRenderableWidget(PolishButton.polished(label, b -> send(BarbarianParleyActionPayload.TRADE, idx))
                .bounds(bx, by, bw, 20).build());
            by += 24;
        }
        addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.barbarian.parley.btn.leave").copy()
                .withStyle(ChatFormatting.GRAY),
            b -> this.onClose())
            .bounds(bx, by, bw, 20).build());
    }

    private void send(int action, int tradeIndex) {
        PacketDistributor.sendToServer(new BarbarianParleyActionPayload(data.messengerEntityId(),
            action, tradeIndex));
        if (action != BarbarianParleyActionPayload.TRADE) this.onClose(); // trades keep the window open
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xE6101820);
        g.renderOutline(panelX, panelY, PANEL_W, panelH, 0xFF3A4450);
        // Header: camp name in its colour + the type.
        int color = 0xFF000000 | (data.campColor() & 0xFFFFFF);
        g.drawString(this.font, Component.literal(data.campName()).withStyle(s -> s.withColor(color)),
            panelX + 12, panelY + 10, color, true);
        g.drawString(this.font, Component.literal(data.typeName()).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(relationLabel()),
            panelX + 12, panelY + 22, 0xFFB8B2A8, false);

        int y = textTop;
        y = drawWrapped(g, this.font, greeting(), panelX + 12, y, PANEL_W - 24, 0xFFE3DACB);
        y += 6;
        if (!data.demands().isEmpty()) {
            g.drawString(this.font, Component.translatable("bannerbound.barbarian.parley.they_demand")
                .withStyle(ChatFormatting.YELLOW), panelX + 12, y, 0xFFE0C040, false);
            y += 12;
            for (OpenBarbarianParleyPayload.Demand d : data.demands()) {
                ItemStack icon = new ItemStack(item(d.item()));
                g.renderItem(icon, panelX + 16, y - 4);
                g.drawString(this.font, Component.literal(d.count() + "× ").append(itemName(d.item())),
                    panelX + 38, y, 0xFFE8E8E8, false);
                y += 12;
            }
        }
    }

    private Component greeting() {
        return Component.translatable(data.greetingKey());
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private static Component itemName(String id) {
        ItemStack stack = new ItemStack(item(id));
        // Known goods keep their normal name (incl. the civ's coined word). Un-researched goods reveal
        // their TRUE name anyway: a barter offer you can't read is useless, and meeting a foreign trader
        // is itself how a young civ learns a good exists. getDescription() sidesteps the unknown-name
        // mask, which only hooks ItemStack#getHoverName.
        return UnknownItemHelper.isUnknownForLocalPlayer(stack)
            ? item(id).getDescription()
            : stack.getHoverName();
    }

    /** Coloured stance word for the header ("hostile"/"neutral"/"friendly"). */
    private Component relationLabel() {
        com.bannerbound.core.barbarian.CampRelationState[] vals =
            com.bannerbound.core.barbarian.CampRelationState.values();
        int ord = Math.max(0, Math.min(vals.length - 1, data.relState()));
        com.bannerbound.core.barbarian.CampRelationState st = vals[ord];
        ChatFormatting color = switch (st) {
            case HOSTILE -> ChatFormatting.RED;
            case FRIENDLY -> ChatFormatting.GREEN;
            default -> ChatFormatting.YELLOW;
        };
        return Component.translatable(
            "bannerbound.barbarian.relation." + st.name().toLowerCase(java.util.Locale.ROOT))
            .withStyle(color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
