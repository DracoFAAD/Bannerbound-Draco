package com.bannerbound.core.event;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Watches each loaded server level's weather state and, when rain or a thunderstorm ends,
 * stamps the matching {@code ThoughtKind} on every {@link CitizenEntity} in that level.
 * <p>
 * The trigger is the <i>transition</i> (raining → clear / thundering → clear), not the
 * ongoing condition: continuous rain doesn't keep re-applying RECENTLY_RAINED — the thought
 * lands once when the weather lifts and decays naturally over the next 2-3 min. Re-applying
 * during the decay window simply refreshes the entry (the Thoughts container de-dupes by
 * kind on solo thoughts), which is the right behaviour for back-to-back showers.
 * <p>
 * Per-dimension state lives in a static map keyed by the level's {@link ResourceKey} so the
 * Overworld and Nether don't share weather (they don't in vanilla either).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class WeatherThoughtEvents {
    private record WeatherSnapshot(boolean raining, boolean thundering) {}

    private static final Map<ResourceKey<Level>, WeatherSnapshot> LAST = new HashMap<>();

    private WeatherThoughtEvents() {}

    /** {@code LevelTickEvent.Post} runs once per level per tick on the server side — perfect for
     *  sampling the weather state without polling. We do nothing on the Pre phase. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;

        boolean rainingNow = sl.isRaining();
        boolean thunderingNow = sl.isThundering();
        WeatherSnapshot prev = LAST.get(sl.dimension());

        // First sample for this dimension — record and move on (no transition to react to).
        if (prev == null) {
            LAST.put(sl.dimension(), new WeatherSnapshot(rainingNow, thunderingNow));
            return;
        }

        // Two independent transitions to watch: rain ended, thunder ended. They can both fire
        // on the same tick (a storm that ends while the rain also stops). isThundering implies
        // isRaining in vanilla, so a thunder→clear transition with rain ALSO ending stamps both
        // thoughts — that's correct (it stormed AND it rained).
        boolean rainEnded = prev.raining() && !rainingNow;
        boolean thunderEnded = prev.thundering() && !thunderingNow;

        if (rainEnded || thunderEnded) {
            long now = sl.getGameTime();
            for (Entity e : sl.getAllEntities()) {
                if (!(e instanceof CitizenEntity c)) continue;
                if (rainEnded) c.getThoughts().add(ThoughtKind.RECENTLY_RAINED, null, now, sl.random);
                if (thunderEnded) c.getThoughts().add(ThoughtKind.RECENTLY_STORMED, null, now, sl.random);
                c.recomputeHappiness();
            }
        }

        LAST.put(sl.dimension(), new WeatherSnapshot(rainingNow, thunderingNow));
    }
}
