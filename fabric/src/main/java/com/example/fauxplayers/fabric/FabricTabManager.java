package com.example.fauxplayers.fabric;

import com.example.fauxplayers.core.FauxPlayerEntry;
import com.example.fauxplayers.core.PluginConfig;
import com.example.fauxplayers.fabric.mixin.ClientboundPlayerInfoUpdatePacketAccessorMixin;
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
    private final FabricPlayerListObjectiveBridge playerListObjective = new FabricPlayerListObjectiveBridge();
    private final Set<UUID> sent = new HashSet<>();
    private final Map<UUID, FauxPlayerEntry> entries = new HashMap<>();
    private final Map<UUID, GameProfile> profileCache = new HashMap<>();
    private final Map<UUID, Long> nextPingUpdate = new HashMap<>();
    private PluginConfig config;

    FabricTabManager(MinecraftServer server) {
        this.server = server;
        this.profiles = new FabricProfileResolver(server);
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
            } else {
                // Reassert scores so they also return after TAB reloads its objective.
                for (ServerPlayer viewer : server.getPlayerList().getPlayers())
                    playerListObjective.add(viewer, entries.get(entry.uuid()));
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
    }

    CompletableFuture<String> canonicalName(String name) {
        return profiles.canonicalName(name);
    }

    private void resolveProfile(FauxPlayerEntry entry) {
        profiles.resolve(entry.name()).thenAccept(profile -> server.execute(() -> {
            if (profile == null || !entries.containsKey(entry.uuid())) return;
            profileCache.put(entry.uuid(), new GameProfile(entry.uuid(), profile.name(), profile.properties()));
            FauxPlayerEntry current = entries.get(entry.uuid());
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) sendAdd(viewer, current, current);
        }));
    }

    private void remove(UUID id) {
        FauxPlayerEntry old = entries.get(id);
        sent.remove(id);
        entries.remove(id);
        profileCache.remove(id);
        nextPingUpdate.remove(id);
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(id));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(packet);
            if (old != null) playerListObjective.remove(viewer, old.name());
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
        playerListObjective.update(viewer, value);
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
        playerListObjective.add(viewer, value);
    }
}
