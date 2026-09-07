package com.cevapi.fauxplayers.fabric.mixin;

import com.cevapi.fauxplayers.fabric.FabricEntrypoint;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet-level defense against command enumeration, mirroring the AntiFly mod:
 * for non-operators the server's autocomplete reply is intercepted, rebuilt and
 * re-sent so that FauxPlayers never appears in it.
 *
 * <p>Why this is needed: the per-player command-tree packet ({@code
 * Commands.sendCommands}) honors each node's {@code requires} predicate and
 * therefore omits gated nodes, but the Brigadier suggestion path does not apply
 * that filter. {@code CommandDispatcher.getCompletionSuggestions} asks every
 * child literal of the suggestion context for prefix matches regardless of
 * {@code canUse}, so a vanilla server happily suggests operator-only commands
 * (including gated FauxPlayers roots and aliases) to a non-operator who sends
 * an autocomplete request such as "/f". This mixin is a second layer on top of
 * the dispatcher-level {@code CommandDispatcherSuggestionMixin}: inside one of
 * the roots the reply is emptied completely, and elsewhere every alias is
 * stripped before the packet is sent.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandSuggestionMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("FauxPlayers");
    private static final int MAX_LOGS_PER_PLAYER = 50;
    private static final Map<UUID, Integer> LOGGED_INTERCEPTS = new HashMap<>();

    static {
        LOGGER.info("CommandSuggestionMixin loaded and active.");
    }

    @Shadow public ServerPlayer player;

    @Inject(method = "handleCustomCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void fauxplayers$filterSuggestions(ServerboundCommandSuggestionPacket packet, CallbackInfo callback) {
        ServerPlayer target = player;
        CommandSourceStack source = target.createCommandSourceStack();
        if (FabricEntrypoint.admin(source)) return;

        String command = packet.getCommand();
        boolean insideFauxPlayers = fauxplayers$isAlias(fauxplayers$firstToken(command));

        MinecraftServer minecraftServer = target.level().getServer();
        if (minecraftServer == null) {
            callback.cancel();
            return;
        }
        StringReader reader = new StringReader(command);
        if (reader.canRead() && reader.peek() == '/') reader.skip();
        var dispatcher = minecraftServer.getCommands().getDispatcher();
        var parse = dispatcher.parse(reader, source);
        dispatcher.getCompletionSuggestions(parse).thenAccept(suggestions -> {
            List<Suggestion> filtered = new ArrayList<>();
            boolean sawAlias = false;
            for (Suggestion suggestion : suggestions.getList()) {
                String token = fauxplayers$firstToken(suggestion.getText());
                if (fauxplayers$isAlias(token)) {
                    sawAlias = true;
                    continue; // never let a FauxPlayers alias reach a non-operator
                }
                // Inside "/fauxplayers|/fp|/fakeplayers ..." nothing may be suggested.
                if (insideFauxPlayers) continue;
                filtered.add(suggestion);
            }
            int logged = LOGGED_INTERCEPTS.getOrDefault(target.getUUID(), 0);
            if (logged < MAX_LOGS_PER_PLAYER) {
                LOGGED_INTERCEPTS.put(target.getUUID(), logged + 1);
                LOGGER.info("Intercepted non-op autocomplete for {} (input: {}) -> raw {}, aliases {}{} -> kept {}",
                        target.getGameProfile().name(), command,
                        suggestions.getList().size(), sawAlias,
                        insideFauxPlayers ? " (inside root)" : "", filtered.size());
            }
            target.connection.send(new ClientboundCommandSuggestionsPacket(packet.getId(),
                    new Suggestions(suggestions.getRange(), filtered)));
        });
        callback.cancel();
    }

    private static boolean fauxplayers$isAlias(String text) {
        return text.equals("fauxplayers") || text.equals("fp") || text.equals("fakeplayers");
    }

    /**
     * Normalizes arbitrary suggestion/command text to its first token: strips a
     * leading '/', any namespace prefix, and trailing words, then lowercases.
     * This is robust to suggestion payloads that carry the full typed line.
     */
    private static String fauxplayers$firstToken(String text) {
        String line = text == null ? "" : text.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        if (space >= 0) line = line.substring(0, space);
        int colon = line.indexOf(':');
        if (colon >= 0) line = line.substring(colon + 1);
        return line.toLowerCase(Locale.ROOT);
    }
}
