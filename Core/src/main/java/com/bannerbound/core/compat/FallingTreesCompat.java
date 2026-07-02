package com.bannerbound.core.compat;

import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Method;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Soft-dependency bridge to <a href="https://github.com/ThePandaOliver/Pandas-Falling-Trees">
 * Pandas Falling Trees</a>. Reflection-only — we don't pull PFT or PandaLib onto the gradle
 * classpath, so the mod compiles and runs identically with or without PFT installed.
 * <p>
 * Use {@link #fellTree(ServerLevel, BlockPos, double, double, double)} to delegate a chop to PFT.
 * Returns false if PFT isn't loaded; callers should fall back to their own felling logic in that
 * case.
 */
@ApiStatus.Internal
public final class FallingTreesCompat {
    private static final String PFT_MOD_ID = "fallingtrees";
    private static final String HANDLER_FQN = "dev.pandasystems.fallingtrees.api.TreeHandler";

    private static boolean checked;
    private static boolean available;
    private static Method destroyTreeMethod;

    private FallingTreesCompat() {
    }

    /**
     * One-shot detection. Probes {@link ModList} and binds the {@code TreeHandler.destroyTree}
     * method reference. Failures are logged once and remembered, so we don't retry every chop.
     */
    public static boolean isAvailable() {
        if (!checked) {
            checked = true;
            if (!ModList.get().isLoaded(PFT_MOD_ID)) {
                return false;
            }
            try {
                Class<?> handler = Class.forName(HANDLER_FQN);
                // @JvmStatic on a Kotlin object emits a static delegate on the enclosing class —
                // we can invoke it with a null receiver.
                destroyTreeMethod = handler.getMethod("destroyTree",
                    Level.class, BlockPos.class, Player.class);
                available = true;
                BannerboundCore.LOGGER.info("Pandas Falling Trees detected — citizen forester will defer tree-felling to PFT.");
            } catch (Throwable t) {
                BannerboundCore.LOGGER.warn("PFT is present but TreeHandler.destroyTree couldn't be bound: {}",
                    t.toString());
                available = false;
            }
        }
        return available;
    }

    /**
     * Asks PFT to fell the tree at {@code logPos}. The fake player's position is set to
     * ({@code atX}, {@code atY}, {@code atZ}) — typically the citizen's coordinates — so PFT's
     * drop-position math and stat tracking land at the worksite.
     *
     * @return true if PFT handled it; false if PFT isn't loaded or the call threw.
     */
    public static boolean fellTree(ServerLevel level, BlockPos logPos, double atX, double atY, double atZ) {
        if (!isAvailable()) return false;
        try {
            FakePlayer fp = FakePlayerFactory.getMinecraft(level);
            fp.moveTo(atX, atY, atZ);
            Object result = destroyTreeMethod.invoke(null, level, logPos, fp);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            BannerboundCore.LOGGER.warn("PFT destroyTree invocation failed: {}", t.toString());
            // Don't flip `available` — a one-off failure shouldn't permanently disable the bridge.
            return false;
        }
    }
}
