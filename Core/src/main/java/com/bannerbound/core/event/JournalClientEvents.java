package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.client.ChronicleScreen;
import com.bannerbound.core.client.ClientChronicleState;
import com.bannerbound.core.client.ClientJournalState;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client-only (Dist.CLIENT) key-mapping registration and per-tick handling for the Chronicle open
 * hotkey and the journal-tracker collapse hotkey. Drains consumeClick each tick so queued presses
 * each fire exactly once.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class JournalClientEvents {
    private JournalClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientChronicleState.OPEN_KEY);
        event.register(ClientJournalState.TOGGLE_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (ClientChronicleState.OPEN_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.setScreen(new ChronicleScreen());
        }
        while (ClientJournalState.TOGGLE_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                ClientJournalState.toggleMinimized();
                mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.45f,
                    ClientJournalState.isMinimized() ? 0.85f : 1.15f);
            }
        }
        com.bannerbound.core.client.ClientTutorialPopups.tick(Minecraft.getInstance());
        com.bannerbound.core.client.ClipPlaybackManager.tick();
    }
}
