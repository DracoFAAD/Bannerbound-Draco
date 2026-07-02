package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the available culture styles, synced from the server on join / reload.
 * Read by {@link SettleScreen}'s style-picker page.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientCultureStyleState {
    /** One selectable style: its id (sent back in the settle request) and its name lang key. */
    public record Entry(String id, String nameKey) {}

    private static volatile List<Entry> STYLES = List.of();

    private ClientCultureStyleState() {
    }

    public static void replace(List<String> ids, List<String> nameKeys) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < nameKeys.size(); i++) {
            list.add(new Entry(ids.get(i), nameKeys.get(i)));
        }
        STYLES = List.copyOf(list);
    }

    public static List<Entry> styles() {
        return STYLES;
    }
}
