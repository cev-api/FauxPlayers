package com.cevapi.fauxplayers.fabric;

import com.cevapi.fauxplayers.core.FauxPlayerEntry;
import com.cevapi.fauxplayers.core.CommandCatalog;
import com.cevapi.fauxplayers.core.PlayerSnapshot;
import com.cevapi.fauxplayers.core.PluginConfig;
import com.cevapi.fauxplayers.core.PresentationMath;
import com.cevapi.fauxplayers.core.RelayManager;
import com.cevapi.fauxplayers.core.ReplayManager;
import com.cevapi.fauxplayers.core.YamlConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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
import java.util.LinkedHashMap;
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
import net.minecraft.server.permissions.Permissions;
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
    private ReplayManager replay;
    private FabricTabManager tab;
    private FabricTabPlaceholderIntegration tabPlaceholders;
    private FabricMessageFormat messageFormat;
    private PlayerSnapshot announcedRemote = PlayerSnapshot.empty();
    /** The fake entries currently shown, so a configuration change can announce what it adds or removes. */
    private int tickFailures;
    private Instant announcedRemoteRefresh;
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
            ServerPlayer joinedPlayer = handler.getPlayer();
            log("Player " + joinedPlayer.getGameProfile().name()
                    + " joined; FauxPlayers command visibility: "
                    + (admin(joinedPlayer.createCommandSourceStack())
                            ? "visible (operator, level 2+)"
                            : "hidden (non-operator)"));
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
        log("Command tree hardened: /fauxplayers, /fp and /fakeplayers are hidden from non-operators.");
    }

    private void stop(MinecraftServer minecraftServer) {
        if (relay != null) relay.stop();
        if (replay != null) replay.stop();
        if (tabPlaceholders != null) tabPlaceholders.close();
        if (tab != null) tab.clear();
        tabPlaceholders = null;
        messageFormat = null;
        announcedRemote = PlayerSnapshot.empty();
        announcedRemoteRefresh = null;
        server = null;
    }

    private void tick(MinecraftServer minecraftServer) {
        if (minecraftServer != server || config == null || tab == null) return;
        if (++tick % 5 == 0) {
            // A failure here must not stop the replay from being driven again on the next tick.
            try {
                if (replay != null) replay.tick();
                tab.sync(config, tabEntries());
                announceRemoteChanges();
            } catch (Throwable error) {
                if (tickFailures++ < 3) {
                    warn("Replay presentation tick failed; the loop continues: " + error);
                }
            }
        }
        if (tabPlaceholders != null) tabPlaceholders.tick();
    }

    private void announceRemoteChanges() {
        PlayerSnapshot current = relay == null ? PlayerSnapshot.empty() : relay.snapshot();
        if (current.refreshedAt() == null || current.refreshedAt().equals(announcedRemoteRefresh)) return;
        PlayerSnapshot previous = announcedRemote;
        announcedRemote = current;
        announcedRemoteRefresh = current.refreshedAt();
        if (previous.refreshedAt() != null) remoteChanged(previous, current);
    }

    private void reload() {
        try {
            // Captured before the new settings load, so the announcement covers only this change.
            PlayerSnapshot snapshot = relay != null ? relay.snapshot() : PlayerSnapshot.empty();
            List<FauxPlayerEntry> before = entriesFor(config, snapshot);
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
                    // Relay worker threads must not enqueue chat broadcasts during login.
                    @Override public void remoteChanged(PlayerSnapshot previous, PlayerSnapshot next) { }
                });
            }
            if (tab == null) tab = new FabricTabManager(server);
            tab.warnHeadFailures(this::warn);
            if (replay == null) {
                replay = new ReplayManager(new ReplayManager.Host() {
                    @Override public void fine(String message) { log(message); }
                    @Override public void warning(String message) { warn(message); }
                    @Override public void onReplayEvent(ReplayManager.Event event) { replayEvent(event); }
                });
            }
            replay.start(config, replayPath(), knownReplayPlayers());
            relay.start(config);
            // Announce what this change adds or removes before the list is rebuilt, so switching a
            // mode off reads as its players leaving rather than the entries vanishing silently.
            announceFauxChanges(before, entriesFor(config, snapshot));
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

    private Path replayPath() {
        if (config == null || config.replayFile == null || config.replayFile.isBlank()) return null;
        Path resolved = Path.of(config.replayFile);
        return resolved.isAbsolute() ? resolved : configPath.getParent().resolve(config.replayFile);
    }

    /** Names the server already knows are in-game players, used to read relayed chat correctly. */
    private Set<String> knownReplayPlayers() {
        Set<String> names = new LinkedHashSet<>();
        if (config == null) return names;
        config.statics.forEach(entry -> names.add(entry.name()));
        relaySnapshot().players().forEach(entry -> names.add(entry.name()));
        return names;
    }

    /**
     * Compares the player list before a configuration change with the list after it, and announces
     * the difference. Comparing against a stored baseline instead would also pick up players the
     * relay or the replay added on their own, which made an unrelated command announce the whole
     * relay roster as joining.
     */
    private void announceFauxChanges(List<FauxPlayerEntry> before, List<FauxPlayerEntry> after) {
        Map<String, String> was = new LinkedHashMap<>();
        for (FauxPlayerEntry entry : before) was.put(entry.name().toLowerCase(Locale.ROOT), entry.name());
        Map<String, String> now = new LinkedHashMap<>();
        for (FauxPlayerEntry entry : after) now.put(entry.name().toLowerCase(Locale.ROOT), entry.name());
        for (Map.Entry<String, String> entry : now.entrySet()) {
            if (!was.containsKey(entry.getKey())) fakeMessage(entry.getValue(), true);
        }
        for (Map.Entry<String, String> entry : was.entrySet()) {
            if (!now.containsKey(entry.getKey())) fakeMessage(entry.getValue(), false);
        }
    }

    /** The player list one configuration would produce, using a snapshot captured once for both sides. */
    private List<FauxPlayerEntry> entriesFor(PluginConfig candidate, PlayerSnapshot snapshot) {
        if (candidate == null || !candidate.enabled || !candidate.tabEnabled) return List.of();
        PlayerSnapshot gated = candidate.relayEnabled ? snapshot : PlayerSnapshot.empty();
        List<FauxPlayerEntry> replayed = candidate.replayEnabled && replay != null ? replay.entries() : List.of();
        return PresentationMath.withoutRealPlayers(
                PresentationMath.tabEntries(candidate, gated, replayed), onlineRealNames());
    }

    /** Replayed lines: only the roster stays fake, the text is broadcast like a system message. */
    private void replayEvent(ReplayManager.Event event) {
        if (server == null) return;
        // Never speak for, or list, someone who is actually on the server. Their own entry and their
        // own words win, so the replayed line is left out rather than shown beside the real player.
        if (config.replaySkipRealPlayers && event.actor() != null && !event.actor().isBlank()
                && isOnlineReal(event.actor())) return;
        switch (event.kind()) {
            case JOIN -> fakeMessage(event.actor(), true);
            case LEAVE -> fakeMessage(event.actor(), false);
            case CHAT -> server.getPlayerList().broadcastSystemMessage(
                    colored("§7<" + event.actor() + "> §f" + event.text()), false);
            case DISCORD -> server.getPlayerList().broadcastSystemMessage(
                    colored("§9[Discord] §7<" + event.actor() + "> §f" + event.text()), false);
            default -> {
                if (event.text() != null && !event.text().isBlank()) {
                    server.getPlayerList().broadcastSystemMessage(colored("§7" + event.text()), false);
                }
            }
        }
    }

    public ServerStatus rewriteStatus(MinecraftServer minecraftServer, ServerStatus original) {
        if (minecraftServer != server || config == null || !config.enabled || !config.statusEnabled) return original;
        PlayerSnapshot remote = relaySnapshot();
        List<FauxPlayerEntry> faux = statusEntries();
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

    private PlayerSnapshot relaySnapshot() {
        // The cache outlives a disable so it can be used again on re-enable, but a disabled relay
        // must never keep contributing players to the list.
        return config != null && config.relayEnabled && relay != null
                ? relay.snapshot() : PlayerSnapshot.empty();
    }

    private List<FauxPlayerEntry> remoteEntries() {
        return relaySnapshot().players();
    }

    private List<FauxPlayerEntry> replayEntries() {
        return config == null || !config.replayEnabled || replay == null ? List.of() : replay.entries();
    }
    private List<FauxPlayerEntry> tabEntries() {
        return PresentationMath.withoutRealPlayers(PresentationMath.tabEntries(config,
                relaySnapshot(), replayEntries()), onlineRealNames());
    }

    /** Names of the real players online, so a fake entry never duplicates one of them. */
    private Set<String> onlineRealNames() {
        Set<String> names = new LinkedHashSet<>();
        if (server == null) return names;
        server.getPlayerList().getPlayers()
                .forEach(player -> names.add(player.getGameProfile().name()));
        return names;
    }

    /** True when a real player of that name is online right now. */
    private boolean isOnlineReal(String name) {
        if (server == null) return false;
        return server.getPlayerList().getPlayerByName(name) != null;
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
        return PresentationMath.withoutRealPlayers(PresentationMath.statusEntries(config,
                relaySnapshot(), replayEntries()), onlineRealNames());
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
                .requires(FabricEntrypoint::admin)
                .executes(context -> help(context.getSource()));
        rootBuilder.then(ctl("status").executes(context -> status(context.getSource())));
        rootBuilder.then(ctl("info").executes(context -> status(context.getSource())));
        rootBuilder.then(ctl("list").executes(context -> list(context.getSource())));
        rootBuilder.then(ctl("reload").executes(context -> {
            reload();
            return message(context, "§aConfiguration reloaded.");
        }));
        rootBuilder.then(ctl("refresh").executes(context -> {
            relay.refreshAsync(config);
            return message(context, "§aRelay refresh scheduled.");
        }));
        rootBuilder.then(ctl("add")
                .then(arg("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .executes(context -> add(context, StringArgumentType.getString(context, "name")))));
        rootBuilder.then(ctl("remove")
                .then(arg("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .executes(context -> remove(context, StringArgumentType.getString(context, "name")))));
        rootBuilder.then(ctl("say")
                .then(arg("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(arg("message", StringArgumentType.greedyString())
                                .executes(context -> say(context,
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "message"))))));
        rootBuilder.then(ctl("chat")
                .then(arg("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(arg("message", StringArgumentType.greedyString())
                                .executes(context -> say(context,
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "message"))))));
        rootBuilder.then(ctl("ping")
                .then(arg("name", StringArgumentType.word())
                        .suggests(this::suggestFakeNames)
                        .then(arg("milliseconds", IntegerArgumentType.integer(0))
                                .executes(context -> ping(context,
                                        StringArgumentType.getString(context, "name"),
                                        IntegerArgumentType.getInteger(context, "milliseconds"))))));
        rootBuilder.then(ctl("get")
                .then(arg("setting", StringArgumentType.word())
                        .suggests(this::suggestSettings)
                        .executes(context -> get(context, StringArgumentType.getString(context, "setting")))));
        rootBuilder.then(ctl("set")
                .then(arg("setting", StringArgumentType.word())
                        .suggests(this::suggestSettings)
                        .then(arg("value", StringArgumentType.greedyString())
                                .suggests(this::suggestSettingValues)
                                .executes(context -> set(context,
                                        StringArgumentType.getString(context, "setting"),
                                        StringArgumentType.getString(context, "value"))))));

        var relayBuilder = ctl("relay")
                .executes(context -> relay(context.getSource()));
        relayBuilder.then(ctl("enable")
                .executes(context -> setValue(context, "relay.enabled", true)));
        relayBuilder.then(ctl("enabled")
                .executes(context -> setValue(context, "relay.enabled", true)));
        relayBuilder.then(ctl("disable")
                .executes(context -> setValue(context, "relay.enabled", false)));
        relayBuilder.then(ctl("refresh").executes(context -> {
            relay.refreshAsync(config);
            return message(context, "§aRelay refresh scheduled.");
        }));
        relayBuilder.then(ctl("host")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers relay host <hostname>"))
                .then(arg("host", StringArgumentType.word())
                        .executes(context -> setRelayValue(context, "relay.status.host",
                                StringArgumentType.getString(context, "host")))));
        relayBuilder.then(ctl("port")
                .then(arg("port", IntegerArgumentType.integer(-1))
                        .executes(context -> setRelayValue(context, "relay.status.port",
                                IntegerArgumentType.getInteger(context, "port")))));
        relayBuilder.then(ctl("source")
                .then(arg("source", StringArgumentType.word())
                        .suggests((context, builder) -> suggest(builder, List.of("STATUS", "HTTP")))
                        .executes(context -> setRelayValue(context, "relay.source",
                                StringArgumentType.getString(context, "source")))));
        relayBuilder.then(ctl("refresh-seconds")
                .then(arg("seconds", IntegerArgumentType.integer(1))
                        .executes(context -> setRelayValue(context, "relay.refresh-seconds",
                                IntegerArgumentType.getInteger(context, "seconds")))));
        relayBuilder.then(arg("hostname", StringArgumentType.word())
                .executes(context -> setRelayHostShortcut(context,
                        StringArgumentType.getString(context, "hostname"))));
        rootBuilder.then(relayBuilder);

        var replayBuilder = ctl("replay")
                .executes(context -> replay(context.getSource()));
        replayBuilder.then(ctl("status")
                .executes(context -> replay(context.getSource())));
        replayBuilder.then(ctl("enable")
                .executes(context -> setValue(context, "replay.enabled", true)));
        replayBuilder.then(ctl("enabled")
                .executes(context -> setValue(context, "replay.enabled", true)));
        replayBuilder.then(ctl("disable")
                .executes(context -> setValue(context, "replay.enabled", false)));
        replayBuilder.then(ctl("restart").executes(context -> {
            if (replay != null) replay.restart();
            return message(context, "§aReplay restarted from the first line.");
        }));
        replayBuilder.then(ctl("seek")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers replay seek <line>"))
                .then(arg("line", IntegerArgumentType.integer(0))
                        .executes(context -> seek(context, IntegerArgumentType.getInteger(context, "line"), false))));
        replayBuilder.then(ctl("skip")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers replay skip <lines>§e, negative to go back"))
                .then(arg("lines", IntegerArgumentType.integer())
                        .executes(context -> seek(context, IntegerArgumentType.getInteger(context, "lines"), true))));
        replayBuilder.then(ctl("replace")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers replay replace <from>=<to>"))
                .then(arg("from=to", StringArgumentType.greedyString())
                        .executes(context -> replace(context, StringArgumentType.getString(context, "from=to")))));
        replayBuilder.then(ctl("unreplace")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers replay unreplace <from>"))
                .then(arg("from", StringArgumentType.greedyString())
                        .executes(context -> unreplace(context, StringArgumentType.getString(context, "from")))));
        replayBuilder.then(ctl("replacements")
                .executes(context -> replacements(context.getSource())));
        replayBuilder.then(ctl("file")
                .executes(context -> message(context, "§eUsage: §f/fauxplayers replay file <path>"))
                .then(arg("path", StringArgumentType.greedyString())
                        .executes(context -> setValue(context, "replay.file",
                                StringArgumentType.getString(context, "path")))));
        replayBuilder.then(ctl("speed")
                .then(arg("multiplier", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> setValue(context, "replay.speed",
                                DoubleArgumentType.getDouble(context, "multiplier")))));
        replayBuilder.then(ctl("loop")
                .then(arg("value", BoolArgumentType.bool())
                        .executes(context -> setValue(context, "replay.loop",
                                BoolArgumentType.getBool(context, "value")))));
        replayBuilder.then(ctl("chat")
                .then(arg("value", BoolArgumentType.bool())
                        .executes(context -> setValue(context, "replay.chat",
                                BoolArgumentType.getBool(context, "value")))));
        replayBuilder.then(ctl("discord-chat")
                .then(arg("value", BoolArgumentType.bool())
                        .executes(context -> setValue(context, "replay.discord-chat",
                                BoolArgumentType.getBool(context, "value")))));
        replayBuilder.then(ctl("deaths")
                .then(arg("value", BoolArgumentType.bool())
                        .executes(context -> setValue(context, "replay.deaths",
                                BoolArgumentType.getBool(context, "value")))));
        replayBuilder.then(ctl("events")
                .then(arg("value", BoolArgumentType.bool())
                        .executes(context -> setValue(context, "replay.events",
                                BoolArgumentType.getBool(context, "value")))));
        replayBuilder.then(ctl("maximum-gap-seconds")
                .then(arg("seconds", IntegerArgumentType.integer(0))
                        .executes(context -> setValue(context, "replay.maximum-gap-seconds",
                                IntegerArgumentType.getInteger(context, "seconds")))));
        replayBuilder.then(ctl("maximum-players")
                .then(arg("players", IntegerArgumentType.integer(0))
                        .executes(context -> setValue(context, "replay.maximum-players",
                                IntegerArgumentType.getInteger(context, "players")))));
        rootBuilder.then(replayBuilder);

        // The server-operator requirement is applied to every node in the tree, not
        // just the roots. Brigadier parses input syntactically without checking
        // requirements, so suggestion requests such as "/fauxplayers " or
        // "/fauxplayers add " must not be able to enumerate subcommands or argument
        // suggestions through child nodes that still default to canUse() == true.

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(rootBuilder);
        dispatcher.register(Commands.literal("fp").requires(FabricEntrypoint::admin).redirect(root));
        dispatcher.register(Commands.literal("fakeplayers").requires(FabricEntrypoint::admin).redirect(root));
    }

    /** A literal sub-command node gated by the server-operator requirement. */
    private static LiteralArgumentBuilder<CommandSourceStack> ctl(String name) {
        return Commands.literal(name).requires(FabricEntrypoint::admin);
    }

    /** An argument node gated by the server-operator requirement. */
    private static <A> RequiredArgumentBuilder<CommandSourceStack, A> arg(
            String name, ArgumentType<A> type) {
        return Commands.<A>argument(name, type)
                .requires(FabricEntrypoint::admin);
    }

    public static boolean admin(CommandSourceStack source) {
        // Non-player sources (console, RCON, command blocks, functions) are trusted.
        // Players must both appear in the operator list and hold at least the
        // "commands admin" permission level (2). This mirrors the operator check
        // used by AntiFly: an account merely listed in ops.json at a lower level,
        // or a player granted a level by a permissions mod, is still denied.
        if (source.getEntity() == null) return true;
        if (source.getEntity() instanceof ServerPlayer player) {
            MinecraftServer minecraftServer = source.getServer();
            return minecraftServer != null
                    && minecraftServer.getPlayerList().isOp(
                            new net.minecraft.server.players.NameAndId(player.getGameProfile()))
                    && source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
        }
        return false;
    }

    private int help(CommandSourceStack source) {
        message(source, "§bFauxPlayers §8» §7Command guide");
        message(source, "§f/fauxplayers §bstatus §8- §7Show the current presentation state");
        message(source, "§f/fauxplayers §blist §8- §7List static and relayed names");
        message(source, "§f/fauxplayers §badd <name> §8- §7Add a static fake");
        message(source, "§f/fauxplayers §bremove <name> §8- §7Remove a static fake");
        message(source, "§f/fauxplayers §bsay <name> <message> §8- §7Broadcast fake chat");
        message(source, "§f/fauxplayers §bget/set <setting> §8- §7Inspect or change settings");
        message(source, "§f/fauxplayers §brelay <enable|disable|host|port|source|refresh> ...");
        return message(source, "§f/fauxplayers §breplay <" + CommandCatalog.replayUsage() + "> ...");
    }

    private int status(CommandSourceStack source) {
        PlayerSnapshot snapshot = relay.snapshot();
        String age = snapshot.refreshedAt() == null ? "never" : Duration.between(snapshot.refreshedAt(), Instant.now()).toSeconds() + "s";
        message(source, "§eReal online: §f" + server.getPlayerList().getPlayerCount());
        message(source, "§eStatic fakes: §f" + config.statics.size() + " §8| §7names: §f" + namesText(config.statics));
        message(source, "§eRelay endpoint: §f" + relayEndpoint());
        message(source, "§eRelayed players: §f" + snapshot.players().size() + " §8| §7names: §f" + namesText(snapshot.players()));
        message(source, "§eRemote reported: §f" + snapshot.reportedOnline() + " §8| §7max: §f" + snapshot.reportedMax());
        message(source, "§eSource: §f" + config.relaySource + " §8| §7enabled: §f" + config.relayEnabled + " §8| §7cache age: §f" + age);
        message(source, "§eReplay: §f" + config.replayEnabled + " §8| §7lines: §f"
                + (replay == null ? 0 : replay.position()) + "§7/§f" + (replay == null ? 0 : replay.eventCount())
                + " §8| §7players: §f" + (replay == null ? 0 : replay.playerCount()));
        return message(source, "§eLast error: §f" + (relay.lastError() == null ? "none" : relay.lastError()));
    }

    private String relayEndpoint() {
        if ("HTTP".equals(config.relaySource)) return config.httpUrl.isBlank() ? "(not configured)" : config.httpUrl;
        return config.relayHost + (config.relayPort > 0 ? ":" + config.relayPort : " (SRV/default port)");
    }

    private int list(CommandSourceStack source) {
        message(source, "§eStatic fake names: §f" + namesText(config.statics));
        message(source, "§eCached remote names: §f" + namesText(remoteEntries()));
        return message(source, "§eReplayed names: §f" + namesText(replayEntries()));
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

    /** Formats a duration as a compact d/h/m figure for the replay clock. */
    private static String span(long millis) {
        long secs = Math.max(0, millis) / 1000;
        long days = secs / 86_400;
        long hours = secs % 86_400 / 3_600;
        long minutes = secs % 3_600 / 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + secs % 60 + "s";
        return secs + "s";
    }

    private static String seconds(long millis) {
        long value = Math.max(0, millis) / 1000;
        return value < 60 ? value + "s" : span(millis);
    }

    private int seek(CommandContext<CommandSourceStack> context, int lines, boolean relative) {
        if (replay == null) return message(context, "§cNo replay timeline is loaded yet.");
        boolean moved = relative ? replay.skip(lines) : replay.seek(lines);
        if (!moved) return message(context, "§cNo replay timeline is loaded yet.");
        return message(context, "§aNow at line §f" + replay.position() + "§a/§f" + replay.eventCount()
                + "§a, players: §f" + replay.playerCount());
    }

    /** Adds a rewrite from the {@code from=to} form, which allows spaces on both sides. */
    private int replace(CommandContext<CommandSourceStack> context, String pair) {
        int split = pair.indexOf('=');
        if (split <= 0) {
            message(context, "§eUsage: §f/fauxplayers replay replace <from>=<to>");
            return message(context, "§7The first §f=§7 splits it, so spaces work on both sides.");
        }
        String from = pair.substring(0, split).strip();
        String to = pair.substring(split + 1).strip();
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (String[] rule : config.replayReplacements) {
            if (rule[0].equals(from)) {
                message(context, "§eReplacing the existing rule for §f" + from + "§e.");
                continue;
            }
            list.add(replacement(rule[0], rule[1]));
        }
        list.add(replacement(from, to));
        return applyReplacements(context,
                "§aAdded replacement: §f" + from + " §a-> §f" + (to.isEmpty() ? "(removed)" : to), list);
    }

    private int unreplace(CommandContext<CommandSourceStack> context, String from) {
        String needle = from.strip();
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        boolean removed = false;
        for (String[] rule : config.replayReplacements) {
            if (rule[0].equals(needle)) {
                removed = true;
                continue;
            }
            list.add(replacement(rule[0], rule[1]));
        }
        if (!removed) return message(context, "§cNo replacement starts with §f" + needle + "§c.");
        return applyReplacements(context, "§aRemoved the replacement for §f" + needle, list);
    }

    private static Map<String, Object> replacement(String from, String to) {
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("from", from);
        rule.put("to", to);
        return rule;
    }

    private int applyReplacements(CommandContext<CommandSourceStack> context, String message,
                                  List<Map<String, Object>> list) {
        document.set("replay.replacements", list);
        saveAndReload();
        message(context, message);
        return message(context, "§7Run §f/fauxplayers replay restart §7to reapply it to the loaded lines.");
    }

    private int replacements(CommandSourceStack source) {
        if (config.replayReplacements.isEmpty()) return message(source, "§bReplay replacements §8» §7none");
        message(source, "§bReplay replacements §8» §f" + config.replayReplacements.size());
        for (int index = 0; index < config.replayReplacements.size(); index++) {
            String[] rule = config.replayReplacements.get(index);
            message(source, "§7" + index + ". §f" + rule[0] + " §8-> §f"
                    + (rule[1].isEmpty() ? "(removed)" : rule[1]));
        }
        return 1;
    }

    private int replay(CommandSourceStack source) {
        message(source, "§bReplay §8» §7enabled=§f" + config.replayEnabled + " §8| §7file=§f" + config.replayFile
                + " §8| §7speed=§f" + config.replaySpeed + " §8| §7loop=§f" + config.replayLoop);
        if (replay == null) return message(source, "§7No replay timeline is loaded yet.");
        message(source, "§7Lines: §f" + replay.position() + "§7/§f" + replay.eventCount()
                + " §8| §7players: §f" + replay.playerCount() + " §8| §7finished: §f" + replay.finished());
        message(source, "§7Clock: §f" + span(replay.clockMillis()) + "§7 of §f" + span(replay.timelineMillis())
                + " §8| §7next line in §f" + (replay.nextDueInMillis() < 0 ? "(none)" : seconds(replay.nextDueInMillis()))
                + " §8| §7ticks driven: §f" + replay.ticks());
        message(source, "§7Loaded: §f" + (replay.loadedFile() == null ? "(none)" : replay.loadedFile()));
        List<String> bridges = replay.bridgeAuthors();
        message(source, "§7Server messages posted by: §f" + (bridges.isEmpty()
                ? "(no account detected; only joins and leaves are recognised)"
                : String.join("§8, §f", bridges)));
        message(source, "§7In-game chat relayed by: §f" + (replay.relayAuthors().isEmpty()
                ? "(no relay account detected)"
                : replay.relayAuthors().size() + " account(s)"));
        message(source, "§7Use §f/fauxplayers replay <" + CommandCatalog.replayUsage() + "> ...");
        return replay.lastError() == null ? 1 : message(source, "§cLast error: §f" + replay.lastError());
    }

    private Object parse(String raw, Object old) {
        if (old instanceof Boolean) return Boolean.parseBoolean(raw);
        if (old instanceof Double || old instanceof Float)
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return null; }
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
