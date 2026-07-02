package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bannerbound.core.network.EditFieldPayload;

import net.minecraft.ChatFormatting;
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
 * Field-edit popup, opened by shift-right-clicking a farmer field with the Foreman's Rod
 * ({@code OpenFieldEditPayload}). Two columns: the left lists the candidate crops, the right lists
 * "All Farmers" + every farmer in the settlement. The current crop / worker is highlighted; clicking
 * a cell selects it (the panel rebuilds to move the highlight). "Save" ships {@link EditFieldPayload};
 * "Cancel" / Esc leaves the field untouched.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FieldEditScreen extends PolishedScreen {
    /** "All farmers" sentinel — mirrors {@code BlockSelection.NO_CITIZEN} without pulling in the API type. */
    private static final UUID ALL_FARMERS = new UUID(0L, 0L);

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 22;
    private static final int LEFT_COL_X = 28;   // crop buttons (icon to the left of these)
    private static final int RIGHT_COL_X = PANEL_WIDTH / 2 + 12;
    private static final int COL_BUTTON_W = PANEL_WIDTH / 2 - 40;

    private final UUID rodId;
    private final List<String> seeds;       // already filtered to known items in init
    private final List<UUID> farmerIds;
    private final List<String> farmerNames;
    /** Seed ids that earn the crop-chunk 2× bonus on this field — flagged green with a star. */
    private final List<String> bonusSeeds;

    private String selectedSeed;
    private UUID selectedWorker;

    public FieldEditScreen(UUID rodId, List<String> candidateSeeds, String currentSeed,
                           List<UUID> farmerIds, List<String> farmerNames, UUID currentWorker,
                           List<String> bonusSeeds) {
        super(Component.translatable("bannerbound.field_edit.title"));
        this.rodId = rodId;
        this.seeds = new ArrayList<>();
        for (String id : candidateSeeds) {
            Item item = resolveItem(id);
            if (item != Items.AIR && UnknownItemHelper.isKnown(item)) this.seeds.add(id);
        }
        this.farmerIds = farmerIds;
        this.farmerNames = farmerNames;
        this.bonusSeeds = bonusSeeds;
        this.selectedSeed = currentSeed == null ? "" : currentSeed;
        this.selectedWorker = currentWorker == null ? ALL_FARMERS : currentWorker;
    }

    @Override
    protected void init() {
        int rows = Math.max(seeds.size(), farmerIds.size() + 1); // +1 for "All Farmers"
        final int panelHeight = Math.min(this.height - 8, 50 + rows * ROW_HEIGHT + 18);
        // Rows that fit inside the (possibly clamped) panel — extra list entries are hidden
        // rather than drawn past the panel edge (no scrolling infrastructure here).
        final int maxRows = Math.max(1, (panelHeight - 50 - 18) / ROW_HEIGHT);
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - panelHeight) / 2;
        final int listTop = panelY + 44;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);
            graphics.drawString(this.font, Component.translatable("bannerbound.field_edit.crop"),
                panelX + 10, panelY + 30, 0xFFC0C0C0, false);
            graphics.drawString(this.font, Component.translatable("bannerbound.field_edit.worker"),
                panelX + RIGHT_COL_X - 16, panelY + 30, 0xFFC0C0C0, false);
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.field_edit.hint").withStyle(ChatFormatting.DARK_GRAY),
                panelX + PANEL_WIDTH / 2, panelY + panelHeight - 12, 0xFF808080);
        });

        // ─── Crops (left column) ───────────────────────────────────────────────────────────
        int row = 0;
        for (String seedId : seeds) {
            if (row >= maxRows) break;
            final int rowY = listTop + row * ROW_HEIGHT;
            row++;
            final ItemStack icon = new ItemStack(resolveItem(seedId));
            boolean sel = seedId.equals(selectedSeed);
            this.addRenderableWidget(PolishButton.polished(
                    cropRowLabel(icon.getHoverName(), sel, bonusSeeds.contains(seedId)),
                    btn -> { selectedSeed = seedId; rebuild(); })
                .bounds(panelX + LEFT_COL_X, rowY, COL_BUTTON_W, 20)
                .accent(primaryAccent())
                .build());
            this.addRenderableOnly((graphics, mx, my, pt) ->
                graphics.renderItem(icon, panelX + 8, rowY + 2));
        }

        // ─── Workers (right column): "All Farmers" then each farmer ──────────────────────────
        boolean allSel = ALL_FARMERS.equals(selectedWorker);
        this.addRenderableWidget(PolishButton.polished(
                rowLabel(Component.translatable("bannerbound.field_edit.all"), allSel),
                btn -> { selectedWorker = ALL_FARMERS; rebuild(); })
            .bounds(panelX + RIGHT_COL_X, listTop, COL_BUTTON_W, 20)
            .accent(primaryAccent())
            .build());
        for (int i = 0; i < farmerIds.size() && i + 1 < maxRows; i++) {
            final UUID id = farmerIds.get(i);
            final int rowY = listTop + (i + 1) * ROW_HEIGHT;
            boolean sel = id.equals(selectedWorker);
            this.addRenderableWidget(PolishButton.polished(
                    rowLabel(Component.literal(farmerNames.get(i)), sel),
                    btn -> { selectedWorker = id; rebuild(); })
                .bounds(panelX + RIGHT_COL_X, rowY, COL_BUTTON_W, 20)
                .accent(primaryAccent())
                .build());
        }
    }

    @Override
    public void onClose() {
        // No buttons — closing (Esc) IS the save. A field always starts with a crop, so selectedSeed
        // is normally set; the empty guard just avoids a no-op packet if it somehow isn't.
        if (!selectedSeed.isEmpty()) {
            PacketDistributor.sendToServer(new EditFieldPayload(rodId, selectedSeed, selectedWorker));
        }
        super.onClose();
    }

    /** Re-lay-out so the highlight follows the new selection. {@code openedAtMs} (the settle pose)
     *  lives on the instance, so a rebuild does NOT re-pop the panel. */
    private void rebuild() {
        this.rebuildWidgets();
    }

    /** A selected row gets a leading ✔ and turns green; unselected stays plain. */
    private static Component rowLabel(Component base, boolean selected) {
        if (selected) {
            return Component.literal("✔ ").append(base).withStyle(ChatFormatting.GREEN);
        }
        return base;
    }

    /** Crop row: a leading ✔ when selected and a trailing ★ when this seed's crop chunk gives the 2×
     *  harvest bonus on this field. Either marker turns the row green so the bonus reads at a glance. */
    private static Component cropRowLabel(Component base, boolean selected, boolean bonus) {
        if (!selected && !bonus) return base;
        Component body = bonus ? Component.empty().append(base).append(" ★") : base;
        if (selected) body = Component.literal("✔ ").append(body);
        return Component.empty().append(body).withStyle(ChatFormatting.GREEN);
    }

    private static Item resolveItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
