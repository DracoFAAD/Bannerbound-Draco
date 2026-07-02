package com.bannerbound.antiquity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Leashing animals with a fiber rope — exactly the vanilla lead, just with our cordage and (via
 * {@code MobRendererMixin} + {@link com.bannerbound.antiquity.client.LeashRopeRenderer}) the green
 * rope colour. A plain (non-shift) {@code #bannerbound:herder_rope} click on a leashable {@link Animal}
 * calls vanilla {@code Mob.setLeashedTo} — so vanilla's own {@code tickLeash} handles the follow/pull,
 * the elastic snap, the too-far break, and save/load persistence for free. Re-clicking your own animal
 * drops the leash; clicking a fence post while leading ties them to a knot (see {@link #tieLedAnimalsToFence}).
 *
 * <p>Shift is left to {@code FiberRopeItem}'s spear-reel, and curare-unconscious targets are left to the
 * {@link CurareDragEvents} kidnap drag — so the three rope interactions never collide.</p>
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class LeashRopeEvents {
    /** Research flag (granted by Animal Husbandry, alongside {@code allow_animal_breeding}) that lets a
     *  settlement leash animals — domestication is the point where you learn to lead a beast. */
    public static final String FLAG = "bannerbound.allow_leashing";
    /** How far from a fence a led animal can be and still get tied to it (vanilla lead uses ~7). */
    private static final double TIE_RANGE = 7.0;

    private LeashRopeEvents() {}

    /** Grab/release: a plain rope click on a leashable animal leashes it to you (or, if it's already
     *  yours, lets it go). Mirrors vanilla lead-on-mob. */
    @SubscribeEvent
    static void onLeash(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown()
            || !HerderWorkGoal.isRope(event.getItemStack())
            || !(event.getTarget() instanceof Animal animal)) {
            return;
        }
        // Curare-unconscious targets belong to the kidnap drag, not leashing.
        if (Poisons.isCurareUnconscious(animal, animal.level().getGameTime())) {
            return;
        }
        if (!player.level().isClientSide) {
            if (animal.isLeashed()) {
                if (animal.getLeashHolder() == player) {
                    animal.dropLeash(true, false); // re-click my own → let go (rope never left the hand)
                    player.swing(event.getHand());
                } else {
                    return; // held by someone else / tied to a fence — leave it
                }
            } else if (!leashingUnlocked(player)) {
                // Leashing is gated behind Animal Husbandry — tell them why, like the breeding gate.
                if (player instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Component.translatable("bannerbound.feature.cant_do_yet")
                        .withStyle(ChatFormatting.RED));
                }
            } else if (animal.canBeLeashed()) {
                animal.setLeashedTo(player, true);
                player.swing(event.getHand());
            } else {
                return; // this mob refuses a leash (vanilla rule)
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** Whether {@code player}'s settlement has researched leashing (the {@link #FLAG}, from Animal
     *  Husbandry). Server-only; mirrors {@code SpearFishing.unlocked} / {@code AnimalBreedingGate}. */
    public static boolean leashingUnlocked(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(serverPlayer.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false; // no settlement / not loaded → treat as not unlocked
        }
    }

    /** True if the player is currently leading at least one animal within tying range of {@code pos}
     *  — lets the rope-post interaction prefer "tie my animals here" over starting a post-to-post tie
     *  (works on both sides, since the leash holder is synced to the client). */
    public static boolean hasLedAnimalsNear(Player player, BlockPos pos) {
        return !ledAnimalsNear(player, pos).isEmpty();
    }

    /** Tie every animal the player is leading to a fence knot at {@code pos} (vanilla lead-to-fence).
     *  Server-only; returns true if at least one was tied. */
    public static boolean tieLedAnimalsToFence(Player player, Level level, BlockPos pos) {
        if (level.isClientSide) {
            return false;
        }
        List<Animal> led = ledAnimalsNear(player, pos);
        if (led.isEmpty()) {
            return false;
        }
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, pos);
        for (Animal animal : led) {
            animal.setLeashedTo(knot, true);
        }
        return true;
    }

    private static List<Animal> ledAnimalsNear(Player player, BlockPos pos) {
        return player.level().getEntitiesOfClass(Animal.class,
            new AABB(pos).inflate(TIE_RANGE),
            a -> a.isLeashed() && a.getLeashHolder() == player);
    }
}
