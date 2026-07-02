package com.bannerbound.antiquity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.codex.CodexManager;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The "Starting Out" intro tutorial. On a player's first time in the world the
 * {@code bannerboundantiquity:starting_out} Chronicle entry is auto-pinned to the side objective
 * tracker, walking them through the early Antiquity progression (knap flint, carve a Crafting Stone,
 * make fire, split firewood, hunt). It is a normal pinned Chronicle tutorial, so the player can
 * <b>unpin it in the Chronicle</b> at any time to dismiss it.
 *
 * <p>Auto-pin happens exactly once per player (tracked in {@link IntroTutorialData}); after that we
 * never re-pin, so an unpin sticks across logins. The tutorial's objectives complete on their own
 * through the Chronicle trigger system — this class only adds the one trigger that has no vanilla
 * source: {@code animal_hunted}, fired when a player kills an animal (the "hunt" step).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class IntroTutorialEvents {
    private static final String ENTRY_ID = BannerboundAntiquity.MODID + ":starting_out";

    private IntroTutorialEvents() {
    }

    /** Auto-pin the intro guide the first time each player enters the world. */
    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        IntroTutorialData data = IntroTutorialData.get(player.getServer().overworld());
        if (!data.markPinned(player.getUUID())) {
            return; // already auto-pinned once before — respect any later unpin
        }
        // Ensure it is unlocked (it is unlocked-by-default, but reconcile order isn't guaranteed),
        // then pin it. togglePinnedJournalEntry only pins when not already pinned, which it isn't.
        CodexManager.unlock(player, ENTRY_ID, false);
        CodexManager.togglePinnedJournalEntry(player, ENTRY_ID);
    }

    /** Fire the {@code animal_hunted} tutorial trigger when a player fells an animal. */
    @SubscribeEvent
    static void onAnimalKilled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Animal)) {
            return;
        }
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof ServerPlayer player) || player.isCreative() || player.isSpectator()) {
            return;
        }
        CodexManager.onCustom(player, "animal_hunted", "");
    }

    /** World SavedData: the set of players who have already had the intro tutorial auto-pinned. */
    public static final class IntroTutorialData extends SavedData {
        private static final String DATA_NAME = "bannerboundantiquity_intro_tutorial";

        private final Set<UUID> pinned = new HashSet<>();

        public static IntroTutorialData get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(IntroTutorialData::new, IntroTutorialData::load), DATA_NAME);
        }

        /** Records that this player has been auto-pinned; returns true only the first time. */
        public boolean markPinned(UUID playerId) {
            if (!pinned.add(playerId)) {
                return false;
            }
            setDirty();
            return true;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            long[] ids = new long[pinned.size() * 2];
            int i = 0;
            for (UUID id : pinned) {
                ids[i++] = id.getMostSignificantBits();
                ids[i++] = id.getLeastSignificantBits();
            }
            tag.putLongArray("Pinned", ids);
            return tag;
        }

        public static IntroTutorialData load(CompoundTag tag, HolderLookup.Provider provider) {
            IntroTutorialData data = new IntroTutorialData();
            long[] ids = tag.getLongArray("Pinned");
            for (int i = 0; i + 1 < ids.length; i += 2) {
                data.pinned.add(new UUID(ids[i], ids[i + 1]));
            }
            return data;
        }
    }
}
