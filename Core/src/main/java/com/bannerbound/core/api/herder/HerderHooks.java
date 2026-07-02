package com.bannerbound.core.api.herder;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * Expansion hook for the Herder citizen job (see {@code HerderWorkGoal}). When the herder culls a
 * surplus animal it rolls the vanilla loot table directly into the harvest chest. An expansion can
 * add its own harvest product — Antiquity uses it to drop a quality-tagged raw HIDE (the tannery's
 * input) graded by the pen's living conditions and the herder's skill.
 *
 * <p>Core supplies the two scalars it can compute (living-conditions 0..1 and herder XP) and the
 * vanilla {@code Animal}; the expansion owns the item id and the POOR/STANDARD/GREAT mapping and
 * returns a finished, already-tagged {@link ItemStack}, so Core never names an expansion type.
 * Mirrors {@link com.bannerbound.core.api.hunter.HunterHooks}: Core ships a no-op default; an
 * expansion calls {@link #setExtension} once during common setup.
 */
public final class HerderHooks {
    /** Expansion-provided herder harvest behaviour. */
    public interface Extension {
        /** The hide (or other product) for a culled {@code victim}, already quality-tagged, or
         *  {@link ItemStack#EMPTY} when this species yields none.
         *  @param livingConditions the pen's 0..1 quality ({@code BreedingEvents.penBreedQuality})
         *  @param herderXp          the herder's {@code "herders_pen"} job XP */
        default ItemStack herdHide(Animal victim, double livingConditions, int herderXp) {
            return ItemStack.EMPTY;
        }
    }

    private static final Extension NO_OP = new Extension() {};

    private static Extension extension = NO_OP;

    private HerderHooks() {
    }

    /** Install the expansion's herder behaviour (call once at mod setup). Last registration wins. */
    public static void setExtension(Extension e) {
        extension = e == null ? NO_OP : e;
    }

    /** The active extension — never null (no-op default). */
    public static Extension get() {
        return extension;
    }
}
