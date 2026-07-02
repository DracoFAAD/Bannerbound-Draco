package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A poison-flavoured death screen shown instead of the vanilla one when the player dies poisoned (see
 * {@link PoisonClientEffects#onDeathScreenOpening}). Subclasses {@link DeathScreen} so the inherited
 * {@code init()} still builds the Respawn / Title-menu buttons and the post-death delay; we only swap
 * the background wash, the big title and the flavour line — each pulled from lang by the poison's id
 * ({@code death.bannerbound.<id>.title} / {@code .flavor}) and tinted by its {@code tintColor()}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PoisonDeathScreen extends DeathScreen {
    private final PoisonType poison;
    private final Component poisonTitle;
    private final Component flavor;

    public PoisonDeathScreen(PoisonType poison, boolean hardcore) {
        super(null, hardcore); // cause unused — we render our own title/flavour
        this.poison = poison;
        this.poisonTitle = Component.translatable("death.bannerbound." + poison.id() + ".title");
        this.flavor = Component.translatable("death.bannerbound." + poison.id() + ".flavor");
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // A dark gradient tinted toward the poison's colour (vanilla uses a red wash; this is per-poison).
        int top = 0x96000000 | darken(poison.tintColor(), 0.30F);
        g.fillGradient(0, 0, this.width, this.height, top, 0xC0080808);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        // Big title, scaled 2× at the vanilla DeathScreen position.
        g.pose().pushPose();
        g.pose().scale(2.0F, 2.0F, 2.0F);
        g.drawCenteredString(this.font, this.poisonTitle, this.width / 2 / 2, 30, 0xFFFFFFFF);
        g.pose().popPose();
        // Flavour line where vanilla puts the cause of death.
        g.drawCenteredString(this.font, this.flavor, this.width / 2, 85, 0xFFE8DCDC);
        // Respawn / Title-menu buttons (built by the inherited DeathScreen.init()).
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    /** Multiply an RGB colour's channels by {@code f} (ignores alpha); used to deepen the wash tint. */
    private static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int gg = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (gg << 8) | b;
    }
}
