package com.bannerbound.core.event;

import java.util.Collection;
import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.chat.BannerboundGameRules;
import com.bannerbound.core.chat.ProximityChat;
import com.bannerbound.core.network.ProximityChatPayload;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Proximity chat. When the {@code globalChat} game rule is off (the default), both public chat and
 * private messages ({@code /msg}, {@code /tell}, {@code /w}) are range-limited to
 * {@link ProximityChat#MAX_RADIUS} blocks and fade toward transparency past
 * {@link ProximityChat#CLEAR_RADIUS} — so players can't communicate magically across the world.
 * Setting {@code /gamerule globalChat true} disables all of this and restores vanilla behaviour.
 *
 * <p>Both paths cancel the vanilla delivery and re-send the message to each in-range listener as a
 * {@link ProximityChatPayload} carrying a distance-derived alpha, which the client renders as
 * per-message text transparency (see {@code ChatComponentMixin}).
 *
 * <p>DIPLOMACY-TODO: the multiplayer player-list (TAB) overlay is intentionally left vanilla for now.
 * It is reserved for the upcoming diplomacy system (faction grouping / relations colouring); do not
 * repurpose or restyle it before that lands.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ChatEvents {
    private ChatEvents() {
    }

    private static boolean globalChat(ServerLevel level) {
        return level.getGameRules().getBoolean(BannerboundGameRules.GLOBAL_CHAT);
    }

    // ---------------------------------------------------------------- public chat

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        ServerLevel level = sender.serverLevel();
        if (globalChat(level)) {
            return; // vanilla server-wide chat
        }

        // Cancel the normal broadcast and deliver proximity copies ourselves. event.getMessage()
        // is the decorated message *content*; wrap it the way vanilla's CHAT type would.
        event.setCanceled(true);
        Component line = Component.translatable("chat.type.text", sender.getDisplayName(), event.getMessage());
        MinecraftServer server = level.getServer();
        BannerboundCore.LOGGER.info("[CHAT] <{}> {}", sender.getGameProfile().getName(), event.getRawText());

        Vec3 origin = sender.position();
        // Chat may be decorated off the main thread; do the player sweep + sends on the server thread.
        server.execute(() -> {
            for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
                float alpha;
                if (listener == sender) {
                    alpha = 1.0f; // you always hear yourself
                } else if (listener.level() != level) {
                    continue; // different dimension — out of earshot
                } else {
                    double dist = Math.sqrt(listener.position().distanceToSqr(origin));
                    if (!ProximityChat.inRange(dist)) {
                        continue;
                    }
                    alpha = ProximityChat.alphaFor(dist);
                    com.bannerbound.core.api.settlement.SettlementData data =
                        com.bannerbound.core.api.settlement.SettlementData.get(server.overworld());
                    com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                        server, data.getByPlayer(sender.getUUID()),
                        data.getByPlayer(listener.getUUID()), "chat");
                }
                PacketDistributor.sendToPlayer(listener, new ProximityChatPayload(line, alpha));
            }
        });
    }

    // ---------------------------------------------------------------- private messages

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        CommandSourceStack source = parse.getContext().getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            return; // console / command block — not a positional speaker
        }

        String input = parse.getReader().getString();
        String cmd = firstToken(input);
        if (!cmd.equals("msg") && !cmd.equals("tell") && !cmd.equals("w")) {
            return;
        }

        ServerLevel level = sender.serverLevel();
        if (globalChat(level)) {
            return; // vanilla whisper, unrestricted
        }

        Collection<ServerPlayer> targets;
        Component messageText;
        try {
            // Resolve the parsed arguments. For the /tell and /w aliases (Brigadier redirects to the
            // /msg node) the "targets" / "message" arguments live in the deepest child context.
            CommandContext<CommandSourceStack> ctx = parse.getContext().build(input);
            while (ctx.getChild() != null) {
                ctx = ctx.getChild();
            }
            targets = EntityArgument.getPlayers(ctx, "targets");
            messageText = MessageArgument.getMessage(ctx, "message");
        } catch (Exception e) {
            return; // couldn't parse it as a whisper — let vanilla run/report normally
        }

        // We're handling delivery ourselves.
        event.setCanceled(true);
        Vec3 origin = sender.position();

        for (ServerPlayer target : targets) {
            double dist = (target.level() == level)
                ? Math.sqrt(target.position().distanceToSqr(origin))
                : Double.POSITIVE_INFINITY;
            if (target != sender && !ProximityChat.inRange(dist)) {
                sender.sendSystemMessage(Component.translatable(
                    "bannerbound.chat.too_far", target.getDisplayName()).withStyle(ChatFormatting.RED));
                continue;
            }

            float alpha = (target == sender) ? 1.0f : ProximityChat.alphaFor(dist);
            Component incoming = Component.translatable(
                "commands.message.display.incoming", sender.getDisplayName(), messageText)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            Component outgoing = Component.translatable(
                "commands.message.display.outgoing", target.getDisplayName(), messageText)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

            PacketDistributor.sendToPlayer(target, new ProximityChatPayload(incoming, alpha));
            // Sender hears their own outgoing line clearly.
            PacketDistributor.sendToPlayer(sender, new ProximityChatPayload(outgoing, 1.0f));
            if (target != sender) {
                com.bannerbound.core.api.settlement.SettlementData data =
                    com.bannerbound.core.api.settlement.SettlementData.get(level.getServer().overworld());
                com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                    level.getServer(), data.getByPlayer(sender.getUUID()),
                    data.getByPlayer(target.getUUID()), "whisper");
            }
        }

        // Keep the server log parity with vanilla whispers.
        BannerboundCore.LOGGER.info("[{}] whispered: {}", sender.getGameProfile().getName(), messageText.getString());
    }

    private static String firstToken(String input) {
        String s = input.startsWith("/") ? input.substring(1) : input;
        int sp = s.indexOf(' ');
        String head = (sp < 0) ? s : s.substring(0, sp);
        return head.toLowerCase(Locale.ROOT);
    }
}
