package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.PickPenAnimalPayload;

import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Choose which animal a freshly-marked pen should raise. Opened by {@code OpenPenAnimalPickerPayload} after
 * the Foreman's Rod validates the enclosure; the choice is sent back via {@link PickPenAnimalPayload} and the
 * server commits the pen. The available list comes from the server (basics always; horse only on a horse chunk).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PenAnimalPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 220;
    private static final int ROW_HEIGHT = 24;

    private final BlockPos penPos;
    private final List<String> animalIds;

    public PenAnimalPickerScreen(BlockPos penPos, List<String> animalIds) {
        super(Component.translatable("bannerbound.pen_animal_picker.title"));
        this.penPos = penPos;
        this.animalIds = animalIds;
    }

    @Override
    protected void init() {
        final int panelHeight = 60 + animalIds.size() * ROW_HEIGHT;
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - panelHeight) / 2;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);
        });

        int listTop = panelY + 30;
        int row = 0;
        for (String id : animalIds) {
            int y = listTop + row * ROW_HEIGHT;
            row++;
            this.addRenderableWidget(PolishButton.polished(animalName(id), btn -> {
                    PacketDistributor.sendToServer(new PickPenAnimalPayload(penPos, id));
                    this.onClose();
                })
                .bounds(panelX + 12, y, PANEL_WIDTH - 24, 20)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(Component.translatable("gui.cancel"), btn -> this.onClose())
            .bounds(panelX + 12, panelY + panelHeight - 26, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    private static Component animalName(String id) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id))
            .<Component>map(EntityType::getDescription)
            .orElse(Component.literal(id));
    }
}
