package com.cevapi.fauxplayers;

import com.cevapi.fauxplayers.core.CommandCatalog;
import com.cevapi.fauxplayers.core.FauxPlayerEntry;
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
import org.bukkit.entity.Player;

public final class FauxPlayersCommand implements TabExecutor {
    private final FauxPlayersPlugin plugin;

    FauxPlayersCommand(FauxPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.isOp()) {
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
            case "replay" -> replay(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        var cached = plugin.relay().snapshot();
        var active = plugin.remoteEntries();
        String age = cached.refreshedAt() == null
                ? "never"
                : Duration.between(cached.refreshedAt(), Instant.now()).toSeconds() + "s";
        sender.sendMessage("§eReal online: §f" + plugin.getServer().getOnlinePlayers().size());
        sender.sendMessage("§eStatic fakes: §f" + plugin.config().statics.size()
                + " §8| §7names: §f" + names(plugin.config().statics));
        sender.sendMessage("§eRelay endpoint: §f" + relayEndpoint());
        sender.sendMessage("§eRelayed players: §f" + active.size() + " shown"
                + " §8| §7cached: §f" + cached.players().size()
                + " §8| §7names: §f" + names(active));
        sender.sendMessage("§eRemote reported: §f" + cached.reportedOnline()
                + " §8| §7max: §f" + cached.reportedMax());
        sender.sendMessage("§eSource: §f" + plugin.config().relaySource
                + " §8| §7enabled: §f" + plugin.config().relayEnabled
                + " §8| §7cache age: §f" + age);
        sender.sendMessage("§eReplay: §f" + plugin.config().replayEnabled
                + " §8| §7lines: §f" + plugin.replay().position() + "§7/§f" + plugin.replay().eventCount()
                + " §8| §7players: §f" + plugin.replay().playerCount());
        sender.sendMessage("§eLast error: §f"
                + (plugin.relay().lastError() == null ? "none" : plugin.relay().lastError()));
        if (!plugin.config().relayEnabled && !cached.players().isEmpty()) {
            sender.sendMessage("§7The relay is off, so its " + cached.players().size()
                    + " cached names are not shown. §f/fauxplayers relay enable §7uses them again.");
        }
    }

    private String relayEndpoint() {
        if ("HTTP".equals(plugin.config().relaySource)) {
            return plugin.config().httpUrl.isBlank() ? "(not configured)" : plugin.config().httpUrl;
        }
        return plugin.config().relayHost
                + (plugin.config().relayPort > 0 ? ":" + plugin.config().relayPort : " (SRV/default port)");
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§eStatic fake names: §f" + names(plugin.config().statics));
        sender.sendMessage("§eCached remote names: §f" + names(plugin.remoteEntries()));
        sender.sendMessage("§eReplayed names: §f" + names(plugin.replayEntries()));
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
            if (old instanceof Double || old instanceof Float) return Double.parseDouble(raw);
            if (old instanceof Number) return Integer.parseInt(raw);
            return raw;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void replay(CommandSender sender, String[] args) {
        var replay = plugin.replay();
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§bReplay §8» §7enabled=§f" + plugin.config().replayEnabled
                    + " §8| §7file=§f" + plugin.config().replayFile
                    + " §8| §7speed=§f" + plugin.config().replaySpeed
                    + " §8| §7loop=§f" + plugin.config().replayLoop);
            sender.sendMessage("§7Lines: §f" + replay.position() + "§7/§f" + replay.eventCount()
                    + " §8| §7players: §f" + replay.playerCount()
                    + " §8| §7finished: §f" + replay.finished());
            sender.sendMessage("§7Clock: §f" + span(replay.clockMillis()) + "§7 of §f" + span(replay.timelineMillis())
                    + " §8| §7next line in §f" + (replay.nextDueInMillis() < 0 ? "(none)" : seconds(replay.nextDueInMillis()))
                    + " §8| §7ticks driven: §f" + replay.ticks());
            sender.sendMessage("§7Loaded: §f" + (replay.loadedFile() == null ? "(none)" : replay.loadedFile()));
            sender.sendMessage("§7Server messages posted by: §f" + (replay.bridgeAuthors().isEmpty()
                    ? "(no account detected; only joins and leaves are recognised)"
                    : String.join("§8, §f", replay.bridgeAuthors())));
            sender.sendMessage("§7In-game chat relayed by: §f" + (replay.relayAuthors().isEmpty()
                    ? "(no relay account detected)"
                    : replay.relayAuthors().size() + " account(s)"));
            if (replay.lastError() != null) sender.sendMessage("§cLast error: §f" + replay.lastError());
            sender.sendMessage("§7Use §f/fauxplayers replay <" + CommandCatalog.replayUsage() + "> ...");
            return;
        }
        String option = args[1].toLowerCase(Locale.ROOT);
        switch (option) {
            case "enable", "enabled" -> updateReplay(sender, "replay.enabled", true);
            case "disable" -> updateReplay(sender, "replay.enabled", false);
            case "restart" -> {
                replay.restart();
                sender.sendMessage("§aReplay restarted from the first line.");
            }
            case "file" -> {
                if (args.length < 3) sender.sendMessage("§eUsage: §f/fauxplayers replay file <path>");
                else updateReplay(sender, "replay.file", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "speed" -> {
                Double speed = args.length < 3 ? null : decimal(args[2]);
                if (speed == null || speed < 0.01) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay speed <multiplier>");
                } else {
                    updateReplay(sender, "replay.speed", speed);
                }
            }
            case "loop", "chat", "discord-chat", "deaths", "events" -> {
                Boolean value = args.length < 3 ? null : flag(args[2]);
                if (value == null) sender.sendMessage("§eUsage: §f/fauxplayers replay " + option + " <true|false>");
                else updateReplay(sender, "replay." + option, value);
            }
            case "seek" -> {
                Integer line = args.length < 3 ? null : integer(args[2]);
                if (line == null || line < 0) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay seek <line>");
                } else if (replay.seek(line)) {
                    sender.sendMessage("§aNow at line §f" + replay.position() + "§a/§f" + replay.eventCount()
                            + "§a, players: §f" + replay.playerCount());
                } else {
                    sender.sendMessage("§cNo replay timeline is loaded yet.");
                }
            }
            case "skip" -> {
                Integer lines = args.length < 3 ? null : integer(args[2]);
                if (lines == null) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay skip <lines>§e (negative to go back)");
                } else if (replay.skip(lines)) {
                    sender.sendMessage("§aNow at line §f" + replay.position() + "§a/§f" + replay.eventCount()
                            + "§a, players: §f" + replay.playerCount());
                } else {
                    sender.sendMessage("§cNo replay timeline is loaded yet.");
                }
            }
            case "replace" -> {
                String pair = args.length < 3 ? null : String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                int split = pair == null ? -1 : pair.indexOf('=');
                if (split <= 0) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay replace <from>=<to>");
                    sender.sendMessage("§7The first §f=§7 splits it, so spaces work on both sides.");
                } else {
                    addReplacement(sender, pair.substring(0, split).strip(), pair.substring(split + 1).strip());
                }
            }
            case "unreplace" -> {
                String from = args.length < 3 ? null : String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip();
                if (from == null || from.isEmpty()) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay unreplace <from>");
                } else {
                    removeReplacement(sender, from);
                }
            }
            case "replacements" -> listReplacements(sender);
            case "maximum-gap-seconds", "maximum-players" -> {
                Integer value = args.length < 3 ? null : integer(args[2]);
                if (value == null || value < 0) {
                    sender.sendMessage("§eUsage: §f/fauxplayers replay " + option + " <number>");
                } else {
                    updateReplay(sender, "replay." + option, value);
                }
            }
            default -> replay(sender, new String[]{args[0]});
        }
    }

    private void listReplacements(CommandSender sender) {
        var rules = plugin.config().replayReplacements;
        if (rules.isEmpty()) {
            sender.sendMessage("§bReplay replacements §8» §7none");
            return;
        }
        sender.sendMessage("§bReplay replacements §8» §f" + rules.size());
        for (int index = 0; index < rules.size(); index++) {
            sender.sendMessage("§7" + index + ". §f" + rules.get(index)[0] + " §8-> §f"
                    + (rules.get(index)[1].isEmpty() ? "(removed)" : rules.get(index)[1]));
        }
    }

    private void addReplacement(CommandSender sender, String from, String to) {
        var list = new ArrayList<java.util.Map<String, Object>>();
        for (String[] rule : plugin.config().replayReplacements) {
            if (rule[0].equals(from)) {
                sender.sendMessage("§eReplacing the existing rule for §f" + from + "§e.");
                continue;
            }
            list.add(replacement(rule[0], rule[1]));
        }
        list.add(replacement(from, to));
        applyReplacements(sender, list, "§aAdded replacement: §f" + from + " §a-> §f"
                + (to.isEmpty() ? "(removed)" : to));
    }

    private void removeReplacement(CommandSender sender, String from) {
        var list = new ArrayList<java.util.Map<String, Object>>();
        boolean removed = false;
        for (String[] rule : plugin.config().replayReplacements) {
            if (rule[0].equals(from)) {
                removed = true;
                continue;
            }
            list.add(replacement(rule[0], rule[1]));
        }
        if (!removed) {
            sender.sendMessage("§cNo replacement starts with §f" + from + "§c.");
            return;
        }
        applyReplacements(sender, list, "§aRemoved the replacement for §f" + from);
    }

    private java.util.Map<String, Object> replacement(String from, String to) {
        var rule = new LinkedHashMap<String, Object>();
        rule.put("from", from);
        rule.put("to", to);
        return rule;
    }

    private void applyReplacements(CommandSender sender, List<java.util.Map<String, Object>> list, String message) {
        plugin.getConfig().set("replay.replacements", list);
        plugin.saveConfig();
        plugin.reloadPlugin();
        sender.sendMessage(message);
        sender.sendMessage("§7Run §f/fauxplayers replay restart §7to reapply it to the loaded lines.");
    }

    private Double decimal(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** Formats a duration as a compact d/h/m figure for the replay clock. */
    private String span(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds % 60 + "s";
        return seconds + "s";
    }

    private String seconds(long millis) {
        long value = Math.max(0, millis) / 1000;
        return value < 60 ? value + "s" : span(millis);
    }

    private Boolean flag(String value) {
        if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    private void updateReplay(CommandSender sender, String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        plugin.reloadPlugin();
        sender.sendMessage("§aSet §f" + key + " §a= §f" + value);
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
        sender.sendMessage("§f/fauxplayers §breplay <" + CommandCatalog.replayUsage() + "> ...");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player player && !player.isOp()) return List.of();
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
        if (root.equals("replay")) {
            if (args.length == 2) return partial(args[1], CommandCatalog.REPLAY_OPTIONS);
            if (args.length == 3 && List.of("loop", "chat", "discord-chat", "deaths", "events").contains(args[1].toLowerCase(Locale.ROOT))) {
                return partial(args[2], List.of("true", "false"));
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
