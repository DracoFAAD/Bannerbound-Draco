package com.bannerbound.core.api.hunter;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Expansion hooks for the Hunter citizen job (see {@code HunterWorkGoal}). Core's hunter walks up
 * to wild prey and kills it with the tool-age melee weapon (or a bow, once {@link #FLAG_ARCHERY}
 * is researched). An expansion can deepen the hunt by installing an {@link Extension} — the
 * Antiquity expansion uses it to plug its immersive-hunting layer in:
 *
 * <ul>
 *   <li><b>Fed-livestock taming</b> — Antiquity's favourite-food taming lives on an Antiquity-only
 *       attachment Core can't read; {@link Extension#isDomesticated} folds it into the hunter's
 *       "is this animal wild?" test.</li>
 *   <li><b>Fear / stealth</b> — wild prey fears hunters the way it fears players, so the hunter
 *       crouch-stalks while {@link Extension#wantsStealth} says the prey is still calm, and switches
 *       to an open chase once {@link Extension#isPreyScared} flips.</li>
 *   <li><b>Spear opener</b> — {@link Extension#isThrowableSpear} + {@link Extension#throwSpear}
 *       let the hunter open an engagement with a thrown spear (bleed + slow) before closing in
 *       for the melee kill.</li>
 * </ul>
 *
 * <p>Mirrors the {@link com.bannerbound.core.api.fisher.FishingVessels} provider pattern: Core
 * ships a no-op default, an expansion calls {@link #setExtension} once during common setup.
 */
public final class HunterHooks {
    /** Research flag that unlocks bow hunting for a settlement's hunters (granted by the Archery
     *  research node; Core-owned constant so the goal and JobTools agree on the string). */
    public static final String FLAG_ARCHERY = "bannerbound.archery";

    /** Expansion-provided hunting behaviour. Every method has a Core-only default (no fear, no
     *  spears) so a Core-only install hunts in the plain walk-up-and-stab style. */
    public interface Extension {
        /** Extra domestication test (e.g. Antiquity's fed-favourite-food attachment). An animal
         *  reporting {@code true} is livestock, never prey. */
        default boolean isDomesticated(Mob animal) {
            return false;
        }

        /** True while {@code animal} is actively spooked (fleeing) — the hunter drops stealth and
         *  gives chase at full speed. */
        default boolean isPreyScared(Mob animal) {
            return false;
        }

        /** True when the hunter should crouch-stalk its approach so it doesn't spook {@code target}
         *  (Antiquity: prey that hasn't noticed the hunter yet). */
        default boolean wantsStealth(CitizenEntity hunter, Mob target) {
            return false;
        }

        /** True if {@code stack} is a spear the hunter can open an engagement by throwing. */
        default boolean isThrowableSpear(ItemStack stack) {
            return false;
        }

        /** Throw {@code spear} (a copy of the hunter's reusable tool — implementations must mark the
         *  projectile non-recoverable so it can't be duplicated) at {@code target} for {@code damage}.
         *  Returns true when a projectile actually launched. */
        default boolean throwSpear(CitizenEntity hunter, Mob target, ItemStack spear, double damage) {
            return false;
        }

        /** Launch-velocity factor for a hunter's shot with {@code bow} — expansion bows may be
         *  slower or craftsmanship-quality-scaled (Antiquity's primitive bow is both). The slower
         *  arrow also hits softer, exactly like a player's shot. {@code 1.0} = vanilla bow. */
        default float bowVelocityFactor(ItemStack bow) {
            return 1.0F;
        }

        /** The arrow entity a hunter's {@code bow} fires (e.g. Antiquity's flint arrow, with its own
         *  in-flight visual). Return {@code null} for the vanilla arrow. The caller owns base damage,
         *  the no-pickup rule, and the actual shot. */
        @org.jetbrains.annotations.Nullable
        default net.minecraft.world.entity.projectile.AbstractArrow createArrow(
                CitizenEntity hunter, ItemStack bow) {
            return null;
        }

        /** Fire a sling rock from {@code shooter} at {@code target} hitting for {@code damage} —
         *  a guard's ranged shot with a {@code #bannerbound:guard_slings} item. Core has no rock
         *  projectile, so the default is a no-op; Antiquity spawns its {@code ThrownRock}. The
         *  conjured ammo must never be collectible (no farming NPC shooters). Returns true when a
         *  projectile actually launched. */
        default boolean shootSling(CitizenEntity shooter,
                net.minecraft.world.entity.LivingEntity target, ItemStack sling, double damage) {
            return false;
        }
    }

    private static final Extension NO_OP = new Extension() {};

    private static Extension extension = NO_OP;

    private HunterHooks() {
    }

    /** Install the expansion's hunting behaviour (call once at mod setup). Last registration wins. */
    public static void setExtension(Extension e) {
        extension = e == null ? NO_OP : e;
    }

    /** The active extension — never null (no-op default). */
    public static Extension get() {
        return extension;
    }
}
