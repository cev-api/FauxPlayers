package com.cevapi.fauxplayers;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Packet-level defense against command enumeration on Paper, the counterpart of
 * the Fabric dispatcher-level filter.
 *
 * <p>The per-player command-tree packet is filtered by permission, but the
 * server's autocomplete reply path is not: Brigadier generates suggestions by
 * prefix alone and never checks a node's {@code requires} predicate. A scanner
 * can therefore send raw suggestion requests (e.g. "/f", "/fa", "/fak", "/")
 * and the server replies with operator-only commands - including FauxPlayers.
 * Every such reply reaches the client as one {@code
 * ClientboundCommandSuggestionsPacket}. This filter intercepts that outbound
 * packet for non-operator recipients and strips any suggestion whose command
 * root is one of the FauxPlayers aliases, so FauxPlayers never appears in any
 * autocomplete reply regardless of how the request entered the server.
 */
public final class CommandAutocompleteFilter {
    private final FauxPlayersPlugin plugin;
    private ProtocolManager protocol;
    private PacketAdapter listener;
    private final Map<UUID, Boolean> loggedStrikes = new HashMap<>();

    public CommandAutocompleteFilter(FauxPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("Command-autocomplete hiding requires ProtocolLib; install it to stop /fauxplayers being discoverable through suggestion packets.");
            return;
        }
        try {
            protocol = ProtocolLibrary.getProtocolManager();
            listener = new PacketAdapter(plugin, ListenerPriority.LOW, PacketType.Play.Server.TAB_COMPLETE) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    filter(event);
                }
            };
            protocol.addPacketListener(listener);
            plugin.getLogger().info("Command autocomplete filter enabled; /fauxplayers, /fp and /fakeplayers are stripped from suggestion packets for non-operators.");
        } catch (Throwable error) {
            plugin.getLogger().warning("Unable to enable command autocomplete filter: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            protocol = null;
            listener = null;
        }
    }

    public void close() {
        if (protocol != null && listener != null) protocol.removePacketListener(listener);
        protocol = null;
        listener = null;
        loggedStrikes.clear();
    }

    private void filter(PacketEvent event) {
        try {
            Player player = event.getPlayer();
            if (player == null || player.isOp()) return;
            Object handle = event.getPacket().getHandle();
            if (handle == null) return;

            Field suggestionsField = findSuggestionsField(handle.getClass());
            if (suggestionsField == null) return;
            Object current = suggestionsField.get(handle);
            if (!(current instanceof Suggestions suggestions)) return;
            List<Suggestion> all = suggestions.getList();
            if (all == null || all.isEmpty()) return;

            boolean removed = false;
            List<Suggestion> filtered = new ArrayList<>(all.size());
            for (Suggestion suggestion : all) {
                if (suggestion != null && isFauxPlayersSuggestion(suggestion.getText())) {
                    removed = true;
                } else {
                    filtered.add(suggestion);
                }
            }
            if (!removed) return;

            suggestionsField.set(handle, new Suggestions(suggestions.getRange(), filtered));
            if (!loggedStrikes.containsKey(player.getUniqueId())) {
                loggedStrikes.put(player.getUniqueId(), Boolean.TRUE);
                plugin.getLogger().info("Stripped FauxPlayers suggestions from autocomplete reply to "
                        + player.getName() + " (removed " + (all.size() - filtered.size()) + "/" + all.size() + ").");
            }
        } catch (Throwable error) {
            plugin.getLogger().fine("Unable to filter autocomplete reply: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /** Locates the brigadier Suggestions field regardless of its mapped name. */
    private static Field findSuggestionsField(Class<?> packetClass) {
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType() == Suggestions.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** True when a suggestion's first command token is a FauxPlayers alias. */
    private static boolean isFauxPlayersSuggestion(String text) {
        if (text == null) return false;
        String token = firstToken(text);
        return token.equals("fauxplayers") || token.equals("fp") || token.equals("fakeplayers");
    }

    /**
     * Normalizes arbitrary suggestion text to its first token: strips a leading
     * '/', any namespace prefix after ':', and trailing words, then lowercases.
     */
    private static String firstToken(String text) {
        String line = text.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        if (space >= 0) line = line.substring(0, space);
        int colon = line.indexOf(':');
        if (colon >= 0) line = line.substring(colon + 1);
        return line.toLowerCase(Locale.ROOT);
    }
}
