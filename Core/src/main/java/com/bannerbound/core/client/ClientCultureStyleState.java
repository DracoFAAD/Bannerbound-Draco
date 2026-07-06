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
    /** One selectable style: its id (sent back in the settle request), its display name, and the
     *  ResourceLocation string of its preview image. */
    public record Entry(String id, String nameKey, String image) {}

    private static volatile List<Entry> STYLES = List.of();

    private ClientCultureStyleState() {
    }

    public static void replace(List<String> ids, List<String> nameKeys, List<String> images) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < nameKeys.size(); i++) {
            // Images sync as a parallel list; tolerate a short/absent one so a name-only payload
            // still populates the picker (image falls back to the id-based convention).
            String image = i < images.size() && !images.get(i).isBlank()
                ? images.get(i)
                : "bannerbound:textures/gui/culture/" + ids.get(i) + ".png";
            list.add(new Entry(ids.get(i), nameKeys.get(i), image));
        }
        STYLES = List.copyOf(list);
    }

    public static List<Entry> styles() {
        return STYLES;
    }
}
