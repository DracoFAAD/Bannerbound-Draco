package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.client.ChronicleScreen;

import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Replaces vanilla Minecraft advancements with the Chronicle. Whenever something tries to open the
 * vanilla {@link AdvancementsScreen} (the 'L' key, the pause-menu button, etc.) we redirect to the
 * Chronicle instead — the mod's own progression/onboarding codex. Vanilla advancements are not part
 * of the Bannerbound experience.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class AdvancementScreenEvents {
    private AdvancementScreenEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof AdvancementsScreen) {
            event.setNewScreen(new ChronicleScreen());
        }
    }
}
