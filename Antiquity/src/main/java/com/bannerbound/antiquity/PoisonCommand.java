package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /bannerbound poison <player> <poison>} — op/singleplayer test command that simulates a
 * poison dart landing on a player (applies the poison + plays its hit cue, so a second run escalates
 * the stage immediately, just like a real second dart). Registered under the same {@code /bannerbound}
 * root as Core's command — Brigadier merges same-named literal roots across mods.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class PoisonCommand {
    private PoisonCommand() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bannerbound")
            .then(Commands.literal("poison")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("poison", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (PoisonType t : PoisonType.values()) {
                                builder.suggest(t.id());
                            }
                            return builder.buildFuture();
                        })
                        .executes(PoisonCommand::execute))))
            .then(Commands.literal("vomit_overlay")
                .requires(s -> s.hasPermission(2))
                .executes(PoisonCommand::vomitOverlay)));
    }

    /** {@code /bannerbound vomit_overlay} — splatter green vomit goo on the running player's screen
     *  (fades over 10s). Singleplayer/op test for the "vomited-in-your-face" effect. */
    private static int vomitOverlay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        com.bannerbound.antiquity.item.Intoxication.splatter(self);
        ctx.getSource().sendSuccess(() -> Component.literal("Splat — green goo on your screen for 10s.")
            .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String id = StringArgumentType.getString(ctx, "poison");
        PoisonType type = PoisonType.fromId(id);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal(
                "Unknown poison '" + id + "'. Try: wolfsbane, curare, oleander, water_hemlock, belladonna."));
            return 0;
        }
        Poisons.applyPoison(target, type);
        int stage = Poisons.getPoison(target).stage();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Poisoned " + target.getGameProfile().getName() + " with " + type.id() + " (stage " + stage + ").")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
