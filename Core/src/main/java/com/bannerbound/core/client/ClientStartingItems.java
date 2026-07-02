package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStartingItems {
    private static volatile Set<String> ITEMS = Set.of();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ClientStartingItems() {
    }

    public static void replace(Set<String> ids) {
        Set<String> next = Set.copyOf(ids);
        if (ITEMS.equals(next)) {
            return;
        }
        ITEMS = next;
        notifyListeners();
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static boolean contains(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && ITEMS.contains(id.toString());
    }

    /** Whether the server's starting-items set has arrived yet. The global set is never legitimately
     *  empty (Antiquity ships 100+), so empty means "not synced yet". JEI uses this to avoid removing
     *  items before knowledge is known — removing a starting item then re-adding it once synced
     *  leaves its crafting recipe stuck hidden in JEI. */
    public static boolean isLoaded() {
        return !ITEMS.isEmpty();
    }
}
