package com.cevapi.fauxplayers.fabric;

import com.cevapi.fauxplayers.core.FauxPlayerEntry;
import com.cevapi.fauxplayers.core.PluginConfig;
import com.cevapi.fauxplayers.fabric.mixin.ClientboundPlayerInfoUpdatePacketAccessorMixin;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Sends the same presentation-only player-info entries without ProtocolLib. */
final class FabricTabManager {
    private final MinecraftServer server;
    private final FabricProfileResolver profiles;
    private final Set<UUID> sent = new HashSet<>();
    private final Map<UUID, FauxPlayerEntry> entries = new HashMap<>();
    private final Map<UUID, GameProfile> profileCache = new HashMap<>();
    private final Map<UUID, Long> nextPingUpdate = new HashMap<>();
    /** When each entry may be asked for again, so a missing head is retried without hammering. */
    private final Map<UUID, Long> nextProfileAttempt = new HashMap<>();
    private static final long PROFILE_RETRY_MILLIS = 20_000L;
    /** A name Mojang reports as having no account is still retried, just far less often. */
    private static final long MISSING_PROFILE_RETRY_MILLIS = 600_000L;
    private java.util.function.Consumer<String> warning = message -> { };
    private int reportedProfileFailures;
    private PluginConfig config;

    FabricTabManager(MinecraftServer server) {
        this.server = server;
        this.profiles = new FabricProfileResolver();
    }

    /** Where to report a head that could not be read. */
    void warnHeadFailures(java.util.function.Consumer<String> sink) {
        this.warning = sink;
        profiles.warnWith(sink);
    }

    void sync(PluginConfig config, List<FauxPlayerEntry> wantedEntries) {
        this.config = config;
        if (!config.tabEnabled) {
            clear();
            return;
        }
        Map<UUID, FauxPlayerEntry> wanted = new HashMap<>();
        for (FauxPlayerEntry entry : wantedEntries) wanted.put(entry.uuid(), entry);

        for (UUID id : new ArrayList<>(sent)) {
            if (!wanted.containsKey(id)) remove(id);
        }
        long now = System.currentTimeMillis();
        for (FauxPlayerEntry entry : wanted.values()) {
            if (sent.add(entry.uuid())) {
                FauxPlayerEntry initial = effective(entry);
                entries.put(entry.uuid(), initial);
                nextPingUpdate.put(entry.uuid(), now + Math.max(1, config.pingRefreshSeconds) * 1000L);
                for (ServerPlayer viewer : server.getPlayerList().getPlayers()) sendAdd(viewer, entry, initial);
                resolveProfile(entry);
            } else if (config.randomPing && now >= nextPingUpdate.getOrDefault(entry.uuid(), 0L)) {
                FauxPlayerEntry next = effective(entry);
                nextPingUpdate.put(entry.uuid(), now + Math.max(1, config.pingRefreshSeconds) * 1000L);
                entries.put(entry.uuid(), next);
                for (ServerPlayer viewer : server.getPlayerList().getPlayers()) sendUpdate(viewer, next);
                retryProfile(entry, now);
            } else {
                retryProfile(entry, now);
            }
        }
    }

    void sendTo(ServerPlayer viewer) {
        if (config == null || !config.tabEnabled) return;
        for (FauxPlayerEntry entry : entries.values()) sendAdd(viewer, entry, entry);
    }

    void clear() {
        for (UUID id : new ArrayList<>(sent)) remove(id);
        entries.clear();
        profileCache.clear();
        nextPingUpdate.clear();
        nextProfileAttempt.clear();
        profiles.clear();
    }

    CompletableFuture<String> canonicalName(String name) {
        return profiles.canonicalName(name);
    }

    /**
     * Asks again for an entry that has no profile, or has one without a skin. A client keeps the
     * profile from the first entry it receives for an id, so the entry is removed and added again
     * once the real one arrives. Without this a single throttled lookup during the first burst
     * would leave that head on the default skin for the rest of the session.
     */
    private void retryProfile(FauxPlayerEntry entry, long now) {
        GameProfile cached = profileCache.get(entry.uuid());
        if (cached != null && FabricProfileResolver.hasTexture(cached)) return;
        // Keep trying either way. A name Mojang cannot find gets a longer gap between attempts so a
        // dead account does not cost a request every twenty seconds, but it is never given up on.
        long interval = profiles.isMissing(entry.name()) ? MISSING_PROFILE_RETRY_MILLIS : PROFILE_RETRY_MILLIS;
        Long due = nextProfileAttempt.get(entry.uuid());
        if (due != null && now < due) return;
        nextProfileAttempt.put(entry.uuid(), now + interval);
        resolveProfile(entry);
    }

    private void resolveProfile(FauxPlayerEntry entry) {
        // A failure here has to be reported. Without this the future completes exceptionally and
        // nothing runs, so the head stays wrong with no trace of why.
        profiles.resolve(entry.name())
                .exceptionally(error -> {
                    if (reportedProfileFailures++ < 3) {
                        warning.accept("Could not resolve the profile for " + entry.name() + ": " + error);
                    }
                    return null;
                })
                .thenAccept(profile -> server.execute(() -> {
            if (profile == null || !entries.containsKey(entry.uuid())) return;
            // An entry is only rebuilt when this is an improvement, so an unchanged answer costs
            // nothing and a better one replaces the head on every client.
            GameProfile previous = profileCache.get(entry.uuid());
            boolean better = previous == null
                    || (!FabricProfileResolver.hasTexture(previous) && FabricProfileResolver.hasTexture(profile));
            profileCache.put(entry.uuid(), new GameProfile(entry.uuid(), profile.name(), profile.properties()));
            if (!better) return;
            FauxPlayerEntry current = entries.get(entry.uuid());
            if (current == null) return;
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(entry.uuid())));
                sendAdd(viewer, current, current);
            }
        }));
    }

    private void remove(UUID id) {
        sent.remove(id);
        entries.remove(id);
        profileCache.remove(id);
        nextPingUpdate.remove(id);
        nextProfileAttempt.remove(id);
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(id));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(packet);
        }
    }

    private FauxPlayerEntry effective(FauxPlayerEntry entry) {
        if (config == null || !config.randomPing) return entry;
        int min = Math.max(0, config.pingMinimum);
        int max = Math.max(min, config.pingMaximum);
        if (min == max) return withLatency(entry, min);
        int ping = (int) Math.round((min + max) / 2.0
                + java.util.concurrent.ThreadLocalRandom.current().nextGaussian()
                * Math.max(0, config.pingStandardDeviation));
        return withLatency(entry, Math.max(min, Math.min(max, ping)));
    }

    private static FauxPlayerEntry withLatency(FauxPlayerEntry entry, int latency) {
        return new FauxPlayerEntry(entry.name(), entry.uuid(), entry.displayName(), latency,
                entry.gameMode(), entry.remote());
    }

    private void sendAdd(ServerPlayer viewer, FauxPlayerEntry logical, FauxPlayerEntry value) {
        send(viewer, logical, value, EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME));
    }

    private void sendUpdate(ServerPlayer viewer, FauxPlayerEntry value) {
        send(viewer, value, value, EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME));
    }

    private void send(ServerPlayer viewer, FauxPlayerEntry logical, FauxPlayerEntry value,
                      EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions) {
        GameProfile profile = profileCache.getOrDefault(logical.uuid(),
                new GameProfile(logical.uuid(), logical.name()));
        GameType gameType;
        try { gameType = GameType.valueOf(value.gameMode()); }
        catch (IllegalArgumentException error) { gameType = GameType.SURVIVAL; }
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                logical.uuid(), profile, true, value.latency(), gameType,
                Component.literal(value.displayName()), true, 0, null);
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
        ((ClientboundPlayerInfoUpdatePacketAccessorMixin) packet).fauxplayers$setEntries(List.of(entry));
        viewer.connection.send(packet);
    }
}
