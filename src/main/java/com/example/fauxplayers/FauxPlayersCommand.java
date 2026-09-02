package com.example.fauxplayers;

import com.example.fauxplayers.core.CommandCatalog;
import com.example.fauxplayers.core.FauxPlayerEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

public final class FauxPlayersCommand implements TabExecutor {
    private final FauxPlayersPlugin plugin;

    FauxPlayersCommand(FauxPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fauxplayers.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage("§aConfiguration reloaded.");
            }
            case "refresh" -> {
                plugin.refresh();
                sender.sendMessage("§aRelay refresh scheduled.");
            }
            case "status", "info" -> status(sender);
            case "list" -> list(sender);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "say", "chat" -> say(sender, args);
            case "ping" -> ping(sender, args);
            case "get" -> get(sender, args);
            case "set" -> set(sender, args);
            case "relay" -> relay(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        var snapshot = plugin.relay().snapshot();
        String age = snapshot.refreshedAt() == null
                ? "never"
                : Duration.between(snapshot.refreshedAt(), Instant.now()).toSeconds() + "s";
        sender.sendMessage("§eReal online: §f" + plugin.getServer().getOnlinePlayers().size());
        sender.sendMessage("§eStatic fakes: §f" + plugin.config().statics.size()
                + " §8| §7names: §f" + names(plugin.config().statics));
        sender.sendMessage("§eRemote known: §f" + snapshot.players().size()
                + " §8| §7names: §f" + names(snapshot.players()));
        sender.sendMessage("§eRemote reported: §f" + snapshot.reportedOnline()
                + " §8| §7max: §f" + snapshot.reportedMax());
        sender.sendMessage("§eSource: §f" + plugin.config().relaySource
                + " §8| §7enabled: §f" + plugin.config().relayEnabled
                + " §8| §7cache age: §f" + age);
        sender.sendMessage("§eLast error: §f"
                + (plugin.relay().lastError() == null ? "none" : plugin.relay().lastError()));
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§eStatic fake names: §f" + names(plugin.config().statics));
        sender.sendMessage("§eCached remote names: §f" + names(plugin.remoteEntries()));
    }

    private String names(Collection<FauxPlayerEntry> entries) {
        return entries.isEmpty()
                ? "(none)"
                : String.join(", ", entries.stream().map(FauxPlayerEntry::name).toList());
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/fauxplayers add <name>");
            return;
        }
        String requested = args[1];
        var existing = plugin.getConfig().getMapList("static-players");
        if (existing.stream().anyMatch(map -> requested.equalsIgnoreCase(String.valueOf(map.get("name"))))) {
            sender.sendMessage("§cThat static fake already exists.");
            return;
        }
        sender.sendMessage("§7Resolving Mojang profile for §f" + requested + "§7...");
        plugin.tab().canonicalName(requested).thenAccept(canonical ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    var list = new ArrayList<>(plugin.getConfig().getMapList("static-players"));
                    if (list.stream().anyMatch(map -> canonical.equalsIgnoreCase(String.valueOf(map.get("name"))))) {
                        sender.sendMessage("§cThat static fake already exists.");
                        return;
                    }
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("name", canonical);
                    entry.put("latency", plugin.config().defaultLatency);
                    list.add(entry);
                    plugin.getConfig().set("static-players", list);
                    plugin.saveConfig();
                    plugin.reloadPlugin();
                    plugin.fakeMessage(canonical, true);
                    sender.sendMessage("§aAdded static fake: §f" + canonical);
                }));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/fauxplayers remove <name>");
            return;
        }
        var list = new ArrayList<>(plugin.getConfig().getMapList("static-players"));
        String canonical = args[1];
        for (var entry : list) {
            if (args[1].equalsIgnoreCase(String.valueOf(entry.get("name")))) {
                canonical = String.valueOf(entry.get("name"));
            }
        }
        boolean removed = list.removeIf(entry -> args[1].equalsIgnoreCase(String.valueOf(entry.get("name"))));
        plugin.getConfig().set("static-players", list);
        plugin.saveConfig();
        plugin.reloadPlugin();
        if (removed) {
            plugin.fakeMessage(canonical, false);
        }
        sender.sendMessage(removed
                ? "§aRemoved static fake: §f" + canonical
                : "§cNo such static fake.");
    }

    private void say(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§eUsage: §f/fauxplayers say <fake-name> <message>");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (!plugin.isFauxName(args[1])) {
            sender.sendMessage("§cThat name is not an active faux player.");
            return;
        }
        plugin.sendFauxChat(args[1], message);
    }

    private void ping(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§eUsage: §f/fauxplayers ping <fake-name> <milliseconds>");
            return;
        }
        int ping;
        try {
            ping = Integer.parseInt(args[2]);
        } catch (NumberFormatException error) {
            sender.sendMessage("§cPing must be a number.");
            return;
        }
        if (ping < 0) {
            sender.sendMessage("§cPing cannot be negative.");
            return;
        }
        var list = new ArrayList<>(plugin.getConfig().getMapList("static-players"));
        boolean found = false;
        for (var entry : list) {
            if (args[1].equalsIgnoreCase(String.valueOf(entry.get("name")))) {
                ((Map) entry).put("latency", ping);
                found = true;
            }
        }
        if (!found) {
            sender.sendMessage("§cNo such static fake.");
            return;
        }
        plugin.getConfig().set("static-players", list);
        plugin.saveConfig();
        plugin.reloadPlugin();
        sender.sendMessage("§aSet fake ping for §f" + args[1] + " §ato §f" + ping + "ms§a.");
    }

    private void get(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUse §f/fauxplayers get <setting>§e. Supported settings:");
            sender.sendMessage("§7" + String.join("§8, §7", CommandCatalog.SETTINGS));
            return;
        }
        if (!CommandCatalog.SETTINGS.contains(args[1])) {
            sender.sendMessage("§cUnknown setting. §7Use /fauxplayers get for the list.");
            return;
        }
        sender.sendMessage("§e" + args[1] + " §8= §f" + plugin.getConfig().get(args[1]));
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUse §f/fauxplayers set <setting> <value>§e. Current settings:");
            for (String key : CommandCatalog.SETTINGS) {
                sender.sendMessage("§7" + key + " §8= §f" + plugin.getConfig().get(key));
            }
            return;
        }
        if (!CommandCatalog.SETTINGS.contains(args[1])) {
            sender.sendMessage("§cUnknown setting.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§e" + args[1] + " §8= §f" + plugin.getConfig().get(args[1])
                    + " §7(current value; provide a new value to change it)");
            return;
        }
        Object old = plugin.getConfig().get(args[1]);
        Object value = parse(args[2], old);
        if (value == null) {
            sender.sendMessage("§cInvalid value for " + args[1] + ".");
            return;
        }
        plugin.getConfig().set(args[1], value);
        plugin.saveConfig();
        plugin.reloadPlugin();
        sender.sendMessage("§aSet §f" + args[1] + " §a= §f" + value);
    }

    private Object parse(String raw, Object old) {
        try {
            if (old instanceof Boolean) {
                if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) return null;
                return Boolean.parseBoolean(raw);
            }
            if (old instanceof Number) return Integer.parseInt(raw);
            return raw;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void relay(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("§bRelay §8» §7enabled=§f" + plugin.config().relayEnabled
                    + " §8| §7source=§f" + plugin.config().relaySource
                    + " §8| §7host=§f" + plugin.config().relayHost
                    + " §8| §7port=§f" + plugin.config().relayPort);
            sender.sendMessage("§7Use §f/fauxplayers relay <enable|disable|host|port|source|refresh> ...");
            return;
        }

        String option = args[1].toLowerCase(Locale.ROOT);
        switch (option) {
            case "enable", "enabled" -> updateRelay(sender, "relay.enabled", true);
            case "disable" -> updateRelay(sender, "relay.enabled", false);
            case "refresh" -> {
                plugin.refresh();
                sender.sendMessage("§aRelay refresh scheduled.");
            }
            case "host" -> {
                if (args.length < 3) {
                    sender.sendMessage("§eUsage: §f/fauxplayers relay host <hostname>");
                } else {
                    updateRelay(sender, "relay.status.host", args[2]);
                }
            }
            case "port" -> {
                if (args.length < 3) {
                    sender.sendMessage("§eUsage: §f/fauxplayers relay port <port>");
                } else {
                    Integer port = integer(args[2]);
                    if (port == null || port < -1 || port > 65535) {
                        sender.sendMessage("§cPort must be between -1 and 65535.");
                    } else {
                        updateRelay(sender, "relay.status.port", port);
                    }
                }
            }
            case "source" -> {
                if (args.length < 3) {
                    sender.sendMessage("§eUsage: §f/fauxplayers relay source <STATUS|HTTP>");
                } else {
                    String source = args[2].toUpperCase(Locale.ROOT);
                    if (!List.of("STATUS", "HTTP").contains(source)) {
                        sender.sendMessage("§cRelay source must be STATUS or HTTP.");
                    } else {
                        updateRelay(sender, "relay.source", source);
                    }
                }
            }
            case "refresh-seconds" -> {
                if (args.length < 3) {
                    sender.sendMessage("§eUsage: §f/fauxplayers relay refresh-seconds <seconds>");
                } else {
                    Integer seconds = integer(args[2]);
                    if (seconds == null || seconds < 1) {
                        sender.sendMessage("§cRefresh seconds must be at least 1.");
                    } else {
                        updateRelay(sender, "relay.refresh-seconds", seconds);
                    }
                }
            }
            default -> {
                // Preserve the original shorthand: /fauxplayers relay <hostname>
                plugin.getConfig().set("relay.status.host", args[1]);
                plugin.getConfig().set("relay.status.port", -1);
                plugin.saveConfig();
                plugin.reloadPlugin();
                sender.sendMessage("§aSet relay host to §f" + args[1]
                        + " §a(automatic SRV/default port enabled).");
            }
        }
    }

    private Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void updateRelay(CommandSender sender, String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        plugin.reloadPlugin();
        sender.sendMessage("§aSet §f" + key + " §a= §f" + value);
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§b§lFauxPlayers §8» §7Command guide");
        sender.sendMessage("§f/fauxplayers §bstatus §8- §7Show the current presentation state");
        sender.sendMessage("§f/fauxplayers §blist §8- §7List static and relayed names");
        sender.sendMessage("§f/fauxplayers §badd <name> §8- §7Add a static fake");
        sender.sendMessage("§f/fauxplayers §bremove <name> §8- §7Remove a static fake");
        sender.sendMessage("§f/fauxplayers §bsay <name> <message> §8- §7Broadcast fake chat");
        sender.sendMessage("§f/fauxplayers §bget/set <setting> §8- §7Inspect or change settings");
        sender.sendMessage("§f/fauxplayers §brelay <enable|disable|host|port|source|refresh> ...");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fauxplayers.admin")) return List.of();
        if (args.length == 0 || args.length == 1) {
            return partial(args.length == 0 ? "" : args[0], CommandCatalog.ROOT);
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("add") || root.equals("remove") || root.equals("say")
                || root.equals("chat") || root.equals("ping")) {
            if (args.length == 2) return partial(args[1], knownNames());
        }
        if ((root.equals("get") || root.equals("set")) && args.length == 2) {
            return partial(args[1], CommandCatalog.SETTINGS);
        }
        if (root.equals("set") && args.length >= 3) {
            return partial(args[2], CommandCatalog.valuesFor(args[1]));
        }
        if (root.equals("relay")) {
            if (args.length == 2) return partial(args[1], CommandCatalog.RELAY_OPTIONS);
            if (args.length == 3 && args[1].equalsIgnoreCase("source")) {
                return partial(args[2], List.of("STATUS", "HTTP"));
            }
        }
        return List.of();
    }

    private Collection<String> knownNames() {
        Set<String> names = new LinkedHashSet<>();
        plugin.config().statics.forEach(entry -> names.add(entry.name()));
        plugin.tabEntries().forEach(entry -> names.add(entry.name()));
        return names;
    }

    private List<String> partial(String value, Collection<String> options) {
        String lower = value.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
