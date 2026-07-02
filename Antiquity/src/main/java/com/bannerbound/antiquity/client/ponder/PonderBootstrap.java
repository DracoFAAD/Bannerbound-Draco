package com.bannerbound.antiquity.client.ponder;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.research.ResearchPonderBridge;

import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One-shot client bootstrap that wires Bannerbound: Antiquity into Create's Ponder library:
 * <ul>
 *   <li>Registers {@link BannerboundAntiquityPonderPlugin} with {@link PonderIndex}.</li>
 *   <li>Installs an opener on Core's {@link ResearchPonderBridge} so the research screen can
 *       launch a ponder for the item id stored in a node's {@code "ponder"} field.</li>
 * </ul>
 * <p>
 * IMPORTANT: this class imports {@code net.createmod.ponder.*}. It must only be class-loaded
 * after a positive {@code ModList.isLoaded("create")} check — otherwise systems without Create
 * will crash on the missing classes. See the call site in {@code BannerboundAntiquityClient}.
 */
@OnlyIn(Dist.CLIENT)
public final class PonderBootstrap {
    private PonderBootstrap() {}

    /** Runs once at client setup, only when Create is installed. */
    public static void init() {
        PonderIndex.addPlugin(new BannerboundAntiquityPonderPlugin());

        ResearchPonderBridge.setOpener(PonderBootstrap::openForItemId);

        BannerboundAntiquity.LOGGER.info("Bannerbound: Antiquity → Ponder bridge installed.");
    }

    /**
     * Opens the Ponder UI for a research's {@code "ponder"} value, going straight into the
     * scenes — no intermediate tag screen.
     * <ul>
     *   <li>If the id resolves to a registered item, opens that item's scenes via
     *       {@link PonderUI#of(ItemStack)} (same UI as inventory hover-W).</li>
     *   <li>Otherwise treats the id as a Ponder <em>tag</em> id, looks up the first item in
     *       that tag that has registered scenes, and opens that item's PonderUI.</li>
     * </ul>
     * The tag's PonderTagScreen is intentionally skipped — the user wants W on the research
     * to drop straight into the lesson, with no extra click required.
     */
    private static void openForItemId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return;
        }
        Screen ui = null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item != Items.AIR) {
            ui = PonderUI.of(new ItemStack(item));
        } else {
            // Tag id — find the first member item that has scenes; open those directly.
            for (ResourceLocation memberItemId : PonderIndex.getTagAccess().getItems(rl)) {
                if (!PonderIndex.getSceneAccess().doScenesExistForId(memberItemId)) {
                    continue;
                }
                Item member = BuiltInRegistries.ITEM.get(memberItemId);
                if (member != Items.AIR) {
                    ui = PonderUI.of(new ItemStack(member));
                    break;
                }
            }
        }
        if (ui != null) {
            Minecraft.getInstance().setScreen(ui);
        }
    }
}
