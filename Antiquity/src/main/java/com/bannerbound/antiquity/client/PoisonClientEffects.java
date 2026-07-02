package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.core.client.SoundMuffle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client game-bus driver for the local player's poison senses: crossfades the four stage ambience
 * drones of whichever poison is active, drives the wolfsbane world-muffle + FOV pull, the belladonna
 * false-sounds + phantom figures, the screen post-process, and the antidote heal-flash.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class PoisonClientEffects {
    /** The four stage drones for the currently-afflicting poison (recreated when the poison changes). */
    private static final PoisonAmbienceSound[] LAYERS = new PoisonAmbienceSound[4];
    private static PoisonType ambiencePoison = null;

    /** Random vanilla sounds belladonna plays from nowhere — threats that aren't there. (Antiquity's
     *  vanilla mobs are gone, but their sound events still exist.) */
    private static final SoundEvent[] FALSE_SOUNDS = {
        SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT,
        SoundEvents.ENDERMAN_AMBIENT, SoundEvents.CREEPER_PRIMED, SoundEvents.WITHER_SKELETON_AMBIENT,
        SoundEvents.HUSK_AMBIENT, SoundEvents.AMBIENT_CAVE.value(), SoundEvents.ZOMBIE_VILLAGER_AMBIENT
    };

    /** Duration of the antidote relief-flash (1.5s). */
    private static final float FLASH_TICKS = 30.0F;
    private static int lastStage = 0;
    private static long healFlashStart = Long.MIN_VALUE;

    private PoisonClientEffects() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            stopAll();
            ambiencePoison = null;
            SoundMuffle.set(1.0F);
            lastStage = 0;
            PoisonHallucinations.clear();
            PoisonHeartbeat.reset();
            return;
        }
        PoisonState s = player.getData(BannerboundAntiquity.POISON_STATE.get());
        PoisonType type = s.active() ? s.type() : null;
        int stage = s.active() ? s.stage() : 0;

        // Heal-flash fires when ANY poison clears.
        if (lastStage > 0 && stage == 0 && mc.level != null) {
            healFlashStart = mc.level.getGameTime();
        }
        lastStage = stage;

        driveAmbience(mc, type, stage);

        // Wolfsbane: world recedes (muffle). Belladonna: no muffle.
        int wolfsbaneStage = (type == PoisonType.WOLFSBANE) ? stage : 0;
        SoundMuffle.set(wolfsbaneStage <= 0 ? 1.0F : Math.max(0.36F, 1.0F - wolfsbaneStage * 0.16F));

        // Belladonna: false sounds + phantom figures.
        if (type == PoisonType.BELLADONNA && stage > 0) {
            RandomSource rng = player.getRandom();
            if (rng.nextFloat() < stage * 0.004F) {
                playFalseSound(mc, player, rng);
            }
            PoisonHallucinations.tick(player, stage, rng);
        } else {
            PoisonHallucinations.clear();
        }

        // Oleander: an accelerating heartbeat as the cardiac clock runs down.
        if (type == PoisonType.OLEANDER && stage > 0) {
            PoisonHeartbeat.tick(oleanderFraction(player, player.level().getGameTime()));
        } else {
            PoisonHeartbeat.reset();
        }
    }

    /** How far oleander's fixed cardiac clock has run: 0 at infection → 1 at arrest. Reads the synced
     *  deadline attachment and the (server-synced) clock-length config. 0 when no clock is set. */
    private static float oleanderFraction(LocalPlayer player, long now) {
        long deadline = player.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            return 0.0F;
        }
        float total = Config.POISON_OLEANDER_CLOCK_TICKS.get();
        if (total <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, 1.0F - (deadline - now) / total));
    }

    private static void playFalseSound(Minecraft mc, LocalPlayer player, RandomSource rng) {
        if (mc.level == null) {
            return;
        }
        SoundEvent sound = FALSE_SOUNDS[rng.nextInt(FALSE_SOUNDS.length)];
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 3.0 + rng.nextDouble() * 7.0;
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        double y = player.getY() + rng.nextDouble() * 2.0 - 0.4;
        // playLocalSound = only this client hears it (it's in your head).
        mc.level.playLocalSound(x, y, z, sound, SoundSource.HOSTILE,
            0.5F + rng.nextFloat() * 0.4F, 0.7F + rng.nextFloat() * 0.5F, false);
    }

    /** Heal-flash envelope (0 = inactive): a quick rise then a soft fade over {@link #FLASH_TICKS}. */
    public static float healFlash(float time) {
        if (healFlashStart == Long.MIN_VALUE) {
            return 0.0F;
        }
        float elapsed = time - healFlashStart;
        if (elapsed < 0.0F || elapsed > FLASH_TICKS) {
            return 0.0F;
        }
        float p = elapsed / FLASH_TICKS;
        return p < 0.15F ? p / 0.15F : Math.max(0.0F, 1.0F - (p - 0.15F) / 0.85F);
    }

    private static void driveAmbience(Minecraft mc, PoisonType type, int stage) {
        boolean hasAmbience = type == PoisonType.WOLFSBANE || type == PoisonType.BELLADONNA;
        if (!hasAmbience || stage <= 0) {
            stopAll();
            ambiencePoison = null;
            return;
        }
        // A different poison than the loaded drones → fade the old set out and start fresh (a layer
        // can't change which OGG it plays).
        if (ambiencePoison != type) {
            stopAll();
            for (int i = 0; i < LAYERS.length; i++) {
                LAYERS[i] = null;
            }
            ambiencePoison = type;
        }
        for (int i = 0; i < LAYERS.length; i++) {
            boolean active = (i + 1) == stage;
            PoisonAmbienceSound snd = LAYERS[i];
            if (active) {
                if (snd == null || snd.isStopped()) {
                    snd = new PoisonAmbienceSound(ambienceFor(type, i + 1));
                    LAYERS[i] = snd;
                    mc.getSoundManager().play(snd);
                }
                snd.setTarget(1.0F);
            } else if (snd != null && !snd.isStopped()) {
                snd.setTarget(0.0F); // crossfade out (self-stops at silence)
            }
        }
    }

    private static void stopAll() {
        for (PoisonAmbienceSound snd : LAYERS) {
            if (snd != null && !snd.isStopped()) {
                snd.setTarget(0.0F);
            }
        }
    }

    private static SoundEvent ambienceFor(PoisonType type, int stage) {
        if (type == PoisonType.BELLADONNA) {
            return switch (stage) {
                case 1 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_1.get();
                case 2 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_2.get();
                case 3 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_3.get();
                default -> BannerboundAntiquity.BELLADONNA_AMBIENCE_4.get();
            };
        }
        return switch (stage) {
            case 1 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_1.get();
            case 2 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_2.get();
            case 3 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_3.get();
            default -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_4.get();
        };
    }

    /** Run the poison-vision post-process after the world renders but before the HUD (so the HUD
     *  stays crisp). Iris-safe — this is the GUI stage, after the world/shaders are done. */
    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (!s.active()) {
            return;
        }
        float time = (float) mc.level.getGameTime();
        if (s.type() == PoisonType.WOLFSBANE || s.type() == PoisonType.BELLADONNA) {
            PoisonPostProcessor.render(s.type(), s.stage(), time, 0.0F, 0.0F);
        } else if (s.type() == PoisonType.OLEANDER) {
            PoisonPostProcessor.render(s.type(), s.stage(), time,
                oleanderFraction(mc.player, mc.level.getGameTime()), PoisonHeartbeat.pulse(time));
        }
    }

    /** Swap the vanilla death screen for a poison-flavoured one when the local player died while
     *  poisoned. Reads the still-set synced poison attachment (the lethal hit doesn't clear it). The
     *  poisoned-but-killed-by-something-else case still shows the poison screen — intended (it's a handy
     *  fast way to test the screens: fall off a cliff at a high stage instead of waiting out the clock). */
    @SubscribeEvent
    static void onDeathScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen) || event.getNewScreen() instanceof PoisonDeathScreen) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (s.active() && (s.type() == PoisonType.WOLFSBANE || s.type() == PoisonType.BELLADONNA
            || s.type() == PoisonType.OLEANDER)) {
            boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
            event.setNewScreen(new PoisonDeathScreen(s.type(), hardcore));
        }
    }

    @SubscribeEvent
    static void onComputeFov(ComputeFovModifierEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (!s.active() || s.type() != PoisonType.WOLFSBANE) {
            return; // FOV pull is wolfsbane's numbing; belladonna leaves FOV alone
        }
        float narrow = 1.0F - Math.min(0.12F, s.stage() * 0.035F); // up to ~-12% FOV, steady
        event.setNewFovModifier(event.getNewFovModifier() * narrow);
    }
}
