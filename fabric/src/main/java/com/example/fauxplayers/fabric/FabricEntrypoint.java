package com.example.fauxplayers.fabric;

import com.example.fauxplayers.core.FauxPlayerEntry;
import com.example.fauxplayers.core.CommandCatalog;
import com.example.fauxplayers.core.PlayerSnapshot;
import com.example.fauxplayers.core.PluginConfig;
import com.example.fauxplayers.core.PresentationMath;
import com.example.fauxplayers.core.RelayManager;
import com.example.fauxplayers.core.YamlConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric server entrypoint; all platform-independent behavior lives in :common. */
public final class FabricEntrypoint implements ModInitializer {
    private static FabricEntrypoint instance;
    private static final Logger LOGGER = LoggerFactory.getLogger("FauxPlayers");
    private final Path configPath = FabricLoader.getInstance().getConfigDir()
            .resolve("fauxplayers").resolve("config.yml");
    private MinecraftServer server;
    private YamlConfig document;
    private PluginConfig config;
    private RelayManager relay;
    private FabricTabManager tab;
    private FabricTabPlaceholderIntegration tabPlaceholders;
    private FabricMessageFormat messageFormat;
    private long tick;

    public FabricEntrypoint() {
        instance = this;
    }

    public static FabricEntrypoint instance() {
        if (instance == null) instance = new FabricEntrypoint();
        return instance;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
            if (minecraftServer == server) sendTo(handler.getPlayer());
        });
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        messageFormat = new FabricMessageFormat(configPath.getParent().resolve("message-format.yml"), this::warn);
        messageFormat.load();
        reload();
        tabPlaceholders = new FabricTabPlaceholderIntegration(this);
        tabPlaceholders.enable();
        log("FauxPlayers Fabric enabled; presentation-only entries are never server players.");
    }

    private void stop(MinecraftServer minecraftServer) {
        if (relay != null) relay.stop();
        if (tabPlaceholders != null) tabPlaceholders.close();
        if (tab != null) tab.clear();
        tabPlaceholders = null;
        messageFormat = null;
        server = null;
    }

    private void tick(MinecraftServer minecraftServer) {
        if (minecraftServer != server || config == null || tab == null) return;
        if (++tick % 5 == 0) tab.sync(config, tabEntries());
        if (tabPlaceholders != null) tabPlaceholders.tick();
    }

    private void reload() {
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.exists(configPath)) {
                try (var input = FabricEntrypoint.class.getResourceAsStream("/config.yml")) {
                    if (input == null) throw new IOException("missing bundled config.yml");
                    Files.copy(input, configPath);
                }
            }
            document = YamlConfig.load(configPath);
            config = PluginConfig.load(document);
            if (relay == null) {
                relay = new RelayManager(new RelayManager.Host() {
                    @Override public void fine(String message) { log(message); }
                    @Override public void warning(String message) { warn(message); }
                    @Override public void remoteChanged(PlayerSnapshot previous, PlayerSnapshot next) {
                        if (server != null) server.execute(() -> remoteChanged(previous, next));
                    }
                });
            }
            if (tab == null) tab = new FabricTabManager(server);
            relay.start(config);
            if (config.enabled) tab.sync(config, tabEntries()); else tab.clear();
        } catch (IOException error) {
            warn("Unable to load config: " + error.getMessage());
            document = YamlConfig.empty();
            config = PluginConfig.load(document);
        }
    }

    private void sendTo(ServerPlayer player) {
        if (config != null && config.enabled && tab != null) tab.sendTo(player);
    }

    public ServerStatus rewriteStatus(MinecraftServer minecraftServer, ServerStatus original) {
        if (minecraftServer != server || config == null || !config.enabled || !config.statusEnabled) return original;
        PlayerSnapshot remote = relay == null ? PlayerSnapshot.empty() : relay.snapshot();
        List<FauxPlayerEntry> faux = PresentationMath.statusEntries(config, remote);
        List<FauxPlayerEntry> real = minecraftServer.getPlayerList().getPlayers().stream()
                .map(player -> new FauxPlayerEntry(player.getGameProfile().name(), player.getUUID(),
                        player.getGameProfile().name(), 0, "SURVIVAL", false)).toList();
        int online = PresentationMath.statusCount(config, remote, real.size(), faux.size());
        int max = config.useMax && remote.reportedMax() > 0 ? remote.reportedMax()
                : config.fixedMax > 0 ? config.fixedMax
                : original.players().map(ServerStatus.Players::max).orElse(0);
        List<net.minecraft.server.players.NameAndId> sample = PresentationMath.sample(config, real, faux)
                .stream().map(entry -> new net.minecraft.server.players.NameAndId(entry.uuid(), entry.name())).toList();
        ServerStatus.Players players = new ServerStatus.Players(max, online, sample);
        return new ServerStatus(original.description(), java.util.Optional.of(players), original.version(),
                original.favicon(), original.enforcesSecureChat());
    }

    private List<FauxPlayerEntry> remoteEntries() {
        return config != null && relay != null && config.relayEnabled ? relay.snapshot().players() : List.of();
    }

    private List<FauxPlayerEntry> tabEntries() {
        return PresentationMath.tabEntries(config, relay == null ? PlayerSnapshot.empty() : relay.snapshot());
    }

    public int displayedOnlineCount() {
        if (server == null) return 0;
        int real = server.getPlayerList().getPlayerCount();
        return config != null && config.enabled && config.tabEnabled ? real + tabEntries().size() : real;
    }

    public void observeJoinMessage(ServerPlayer player, Component message) {
        if (messageFormat != null) messageFormat.observeJoin(player, message);
    }

    public void observeLeaveMessage(ServerPlayer player, Component message) {
        if (messageFormat != null) messageFormat.observeLeave(player, message);
    }

    private List<FauxPlayerEntry> statusEntries() {
        return PresentationMath.statusEntries(config, relay == null ? PlayerSnapshot.empty() : relay.snapshot());
    }

    private void remoteChanged(PlayerSnapshot previous, PlayerSnapshot next) {
        if (previous.refreshedAt() == null || config == null || !config.messageEnabled || server == null) return;
        Set<String> oldNames = names(previous.players());
        Set<String> newNames = names(next.players());
        next.players().stream().filter(entry -> !oldNames.contains(entry.name().toLowerCase(Locale.ROOT)))
                .forEach(entry -> fakeMessage(entry.name(), true));
        previous.players().stream().filter(entry -> !newNames.contains(entry.name().toLowerCase(Locale.ROOT)))
                .forEach(entry -> fakeMessage(entry.name(), false));
    }

    private static Set<String> names(Collection<FauxPlayerEntry> entries) {
        Set<String> result = new HashSet<>();
        for (FauxPlayerEntry entry : entries) result.add(entry.name().toLowerCase(Locale.ROOT));
        return result;
    }

    private void fakeMessage(String name, boolean join) {
        String key = join ? "multiplayer.player.joined" : "multiplayer.player.left";
        Component message = join ? messageFormat == null ? null : messageFormat.renderJoin(name)
                : messageFormat == null ? null : messageFormat.renderLeave(name);
        if (message == null) {
            message = Component.translatable(key, Component.literal(name)).withStyle(ChatFormatting.YELLOW);
        }
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                  CommandBuildContext buildContext,
                                  Commands.CommandSelection selection) {
        var rootBuilder = Commands.literal("fauxplayers")
                .requires(this::admin)
                .executes(context -> help(context.getSource()));
        rootBuilder.then(Commands.literal("status").executes(context -> status(context.getSource())));
        rootBuilder.then(Commands.literal("info").executes(context -> status(context.getSource())));
        rootBuilder.then(Commands.literal("list").executes(context -> list(context.getSource())));
        rootBuilder.then(Commands.literal("reload").executes(context -> {
            reload();
            return message(context, "§aConfiguration reloaded.");
        }));
        rootBuilder.then(Commands.literal("refresh").executes(context -> {
            relay.refreshAsync(config);
            return message(context, "§aRelay refresh scheduled.");
        }));
        rootBuilder.then(Commands.literal("add")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .executes(context -> add(context, StringArgumentType.getString(context, "name")))));
        rootBuilder.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .executes(context -> remove(context, StringArgumentType.getString(context, "name")))));
        rootBuilder.then(Commands.literal("say")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> say(context,
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "message"))))));
        rootBuilder.then(Commands.literal("chat")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> say(context,
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "message"))))));
        rootBuilder.then(Commands.literal("ping")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(Commands.argument("milliseconds", IntegerArgumentType.integer(0))
                                .executes(context -> ping(context,
                                        StringArgumentType.getString(context, "name"),
                                        IntegerArgumentType.getInteger(context, "milliseconds"))))));
        rootBuilder.then(Commands.literal("get")
                .then(Commands.argument("setting", StringArgumentType.word())
                        .suggests(this::suggestSettings)
                        .executes(context -> get(context, StringArgumentType.getString(context, "setting")))));
        rootBuilder.then(Commands.literal("set")
                .then(Commands.argument("setting", StringArgumentType.word())
                        .suggests(this::suggestSettings)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests(this::suggestSettingValues)
                                .executes(context -> set(context,
                                        StringArgumentType.getString(context, "setting"),
                                        StringArgumentType.getString(context, "value"))))));

        var relayBuilder = Commands.literal("relay")
                .executes(context -> relay(context.getSource()));
        relayBuilder.then(Commands.literal("enable")
                .executes(context -> setValue(context, "relay.enabled", true)));
        relayBuilder.then(Commands.literal("enabled")
                .executes(context -> setValue(context, "relay.enabled", true)));
        relayBuilder.then(Commands.literal("disable")
                .executes(context -> setValue(context, "relay.enabled", false)));
        relayBuilder.then(Commands.literal("refresh").executes(context -> {
            relay.refreshAsync(config);
            return message(context, "§aRelay refresh scheduled.");
        }));
        relayBuilder.then(Commands.literal("host")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers relay host <hostname>"))
                .then(Commands.argument("host", StringArgumentType.word())
                        .executes(context -> setRelayValue(context, "relay.status.host",
                                StringArgumentType.getString(context, "host")))));
        relayBuilder.then(Commands.literal("port")
                .then(Commands.argument("port", IntegerArgumentType.integer(-1))
                        .executes(context -> setRelayValue(context, "relay.status.port",
                                IntegerArgumentType.getInteger(context, "port")))));
        relayBuilder.then(Commands.literal("source")
                .then(Commands.argument("source", StringArgumentType.word())
                        .suggests((context, builder) -> suggest(builder, List.of("STATUS", "HTTP")))
                        .executes(context -> setRelayValue(context, "relay.source",
                                StringArgumentType.getString(context, "source")))));
        relayBuilder.then(Commands.literal("refresh-seconds")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(context -> setRelayValue(context, "relay.refresh-seconds",
                                IntegerArgumentType.getInteger(context, "seconds")))));
        relayBuilder.then(Commands.argument("hostname", StringArgumentType.word())
                .executes(context -> setRelayHostShortcut(context,
                        StringArgumentType.getString(context, "hostname"))));
        rootBuilder.then(relayBuilder);

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(rootBuilder);
        dispatcher.register(Commands.literal("fp").requires(this::admin).redirect(root));
        dispatcher.register(Commands.literal("fakeplayers").requires(this::admin).redirect(root));
    }

    private boolean admin(CommandSourceStack source) {
        return source.permissions() == net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS
                || source.permissions() instanceof LevelBasedPermissionSet level
                && level.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
    }

    private int help(CommandSourceStack source) {
        message(source, "§bFauxPlayers §8» §7Command guide");
        message(source, "§f/fauxplayers §bstatus §8- §7Show the current presentation state");
        message(source, "§f/fauxplayers §blist §8- §7List static and relayed names");
        message(source, "§f/fauxplayers §badd <name> §8- §7Add a static fake");
        message(source, "§f/fauxplayers §bremove <name> §8- §7Remove a static fake");
        message(source, "§f/fauxplayers §bsay <name> <message> §8- §7Broadcast fake chat");
        message(source, "§f/fauxplayers §bget/set <setting> §8- §7Inspect or change settings");
        return message(source, "§f/fauxplayers §brelay <enable|disable|host|port|source|refresh> ...");
    }

    private int status(CommandSourceStack source) {
        PlayerSnapshot snapshot = relay.snapshot();
        String age = snapshot.refreshedAt() == null ? "never" : Duration.between(snapshot.refreshedAt(), Instant.now()).toSeconds() + "s";
        message(source, "§eReal online: §f" + server.getPlayerList().getPlayerCount());
        message(source, "§eStatic fakes: §f" + config.statics.size() + " §8| §7names: §f" + namesText(config.statics));
        message(source, "§eRemote known: §f" + snapshot.players().size() + " §8| §7names: §f" + namesText(snapshot.players()));
        message(source, "§eRemote reported: §f" + snapshot.reportedOnline() + " §8| §7max: §f" + snapshot.reportedMax());
        message(source, "§eSource: §f" + config.relaySource + " §8| §7enabled: §f" + config.relayEnabled + " §8| §7cache age: §f" + age);
        return message(source, "§eLast error: §f" + (relay.lastError() == null ? "none" : relay.lastError()));
    }

    private int list(CommandSourceStack source) {
        message(source, "§eStatic fake names: §f" + namesText(config.statics));
        return message(source, "§eCached remote names: §f" + namesText(remoteEntries()));
    }

    private String namesText(Collection<FauxPlayerEntry> entries) {
        return entries.isEmpty() ? "(none)" : String.join(", ", entries.stream().map(FauxPlayerEntry::name).toList());
    }

    private int add(CommandContext<CommandSourceStack> context, String requested) {
        if (document.mapList("static-players").stream().anyMatch(map -> requested.equalsIgnoreCase(String.valueOf(map.get("name")))))
            return message(context, "§cThat static fake already exists.");
        message(context, "§7Resolving Mojang profile for §f" + requested + "§7...");
        tab.canonicalName(requested).thenAccept(canonical -> server.execute(() -> {
            List<Map<String, Object>> list = document.mapList("static-players");
            if (list.stream().anyMatch(map -> canonical.equalsIgnoreCase(String.valueOf(map.get("name"))))) {
                message(context, "§cThat static fake already exists."); return;
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", canonical); entry.put("latency", config.defaultLatency);
            list.add(entry); document.set("static-players", list); saveAndReload();
            fakeMessage(canonical, true); message(context, "§aAdded static fake: §f" + canonical);
        }));
        return 1;
    }

    private int remove(CommandContext<CommandSourceStack> context, String requested) {
        List<Map<String, Object>> list = document.mapList("static-players");
        String[] canonical = {requested};
        list.removeIf(map -> {
            boolean match = requested.equalsIgnoreCase(String.valueOf(map.get("name")));
            if (match) canonical[0] = String.valueOf(map.get("name"));
            return match;
        });
        boolean removed = list.size() != document.mapList("static-players").size();
        document.set("static-players", list); saveAndReload();
        if (removed) fakeMessage(canonical[0], false);
        return message(context, removed ? "§aRemoved static fake: §f" + canonical[0] : "§cNo such static fake.");
    }

    private int say(CommandContext<CommandSourceStack> context, String name, String text) {
        if (tabEntries().stream().noneMatch(entry -> entry.name().equalsIgnoreCase(name)))
            return message(context, "§cThat name is not an active faux player.");
        server.getPlayerList().broadcastSystemMessage(colored("§7<" + name + "> §f" + text), false);
        return 1;
    }

    private int ping(CommandContext<CommandSourceStack> context, String name, int milliseconds) {
        List<Map<String, Object>> list = document.mapList("static-players");
        boolean found = false;
        for (Map<String, Object> map : list) if (name.equalsIgnoreCase(String.valueOf(map.get("name")))) {
            map.put("latency", milliseconds); found = true;
        }
        if (!found) return message(context, "§cNo such static fake.");
        document.set("static-players", list); saveAndReload();
        return message(context, "§aSet fake ping for §f" + name + " §ato §f" + milliseconds + "ms§a.");
    }

    private int get(CommandContext<CommandSourceStack> context, String setting) {
        if (!CommandCatalog.SETTINGS.contains(setting)) return message(context, "§cUnknown setting. §7Use /fauxplayers get for the list.");
        return message(context, "§e" + setting + " §8= §f" + document.get(setting));
    }

    private int set(CommandContext<CommandSourceStack> context, String setting, String value) {
        if (!CommandCatalog.SETTINGS.contains(setting)) return message(context, "§cUnknown setting.");
        Object old = document.get(setting); Object parsed = parse(value, old);
        if (parsed == null) return message(context, "§cInvalid value for " + setting + ".");
        return setValue(context, setting, parsed);
    }

    private int setValue(CommandContext<CommandSourceStack> context, String setting, Object value) {
        document.set(setting, value); saveAndReload();
        return message(context, "§aSet §f" + setting + " §a= §f" + value);
    }

    private int setRelayValue(CommandContext<CommandSourceStack> context, String setting, Object value) {
        if ("relay.source".equals(setting)) {
            String source = String.valueOf(value).toUpperCase(Locale.ROOT);
            if (!List.of("STATUS", "HTTP").contains(source))
                return message(context, "§cRelay source must be STATUS or HTTP.");
            value = source;
        }
        return setValue(context, setting, value);
    }

    private int setRelayHostShortcut(CommandContext<CommandSourceStack> context, String host) {
        document.set("relay.status.host", host);
        document.set("relay.status.port", -1);
        saveAndReload();
        return message(context, "§aSet relay host to §f" + host
                + " §a(automatic SRV/default port enabled).");
    }

    private int relay(CommandSourceStack source) {
        message(source, "§bRelay §8» §7enabled=§f" + config.relayEnabled + " §8| §7source=§f" + config.relaySource
                + " §8| §7host=§f" + config.relayHost + " §8| §7port=§f" + config.relayPort);
        return message(source, "§7Use §f/fauxplayers relay <enable|disable|host|port|source|refresh> ...");
    }

    private Object parse(String raw, Object old) {
        if (old instanceof Boolean) return Boolean.parseBoolean(raw);
        if (old instanceof Number) try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return null; }
        return raw;
    }

    private void saveAndReload() {
        try { document.save(configPath); reload(); }
        catch (IOException error) { warn("Unable to save config: " + error.getMessage()); }
    }

    private CompletableFuture<Suggestions> suggestFakeNames(CommandContext<CommandSourceStack> context,
                                                            SuggestionsBuilder builder) {
        Set<String> names = new LinkedHashSet<>();
        if (config != null) {
            config.statics.forEach(entry -> names.add(entry.name()));
            tabEntries().forEach(entry -> names.add(entry.name()));
        }
        return suggest(builder, names);
    }

    private CompletableFuture<Suggestions> suggestSettings(CommandContext<CommandSourceStack> context,
                                                          SuggestionsBuilder builder) {
        return suggest(builder, CommandCatalog.SETTINGS);
    }

    private CompletableFuture<Suggestions> suggestSettingValues(CommandContext<CommandSourceStack> context,
                                                                SuggestionsBuilder builder) {
        String setting;
        try {
            setting = StringArgumentType.getString(context, "setting");
        } catch (IllegalArgumentException ignored) {
            return suggest(builder, List.of());
        }
        return suggest(builder, CommandCatalog.valuesFor(setting));
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Collection<String> values) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(remaining))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int message(CommandContext<CommandSourceStack> context, String text) {
        return message(context.getSource(), text);
    }

    private static int message(CommandSourceStack source, String text) {
        source.sendSystemMessage(colored(text)); return 1;
    }

    private static MutableComponent colored(String text) {
        MutableComponent result = Component.literal("");
        ChatFormatting active = null;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '§' && i + 1 < text.length()) {
                ChatFormatting next = ChatFormatting.getByCode(text.charAt(++i));
                if (next != null) {
                    appendPart(result, part, active);
                    active = next == ChatFormatting.RESET ? null : next;
                    continue;
                }
                part.append(current).append(text.charAt(i));
                continue;
            }
            part.append(current);
        }
        appendPart(result, part, active);
        return result;
    }

    private static void appendPart(MutableComponent result, StringBuilder part, ChatFormatting active) {
        if (part.length() == 0) return;
        MutableComponent component = Component.literal(part.toString());
        if (active != null) component.withStyle(active);
        result.append(component);
        part.setLength(0);
    }

    void log(String text) { LOGGER.info(text); }
    void warn(String text) { LOGGER.warn(text); }
}
