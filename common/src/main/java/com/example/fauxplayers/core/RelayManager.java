package com.example.fauxplayers.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

/** Shared asynchronous STATUS/HTTP relay implementation. */
public final class RelayManager {
    public interface Host {
        void fine(String message);
        void warning(String message);
        void remoteChanged(PlayerSnapshot previous, PlayerSnapshot next);
    }

    private final Host host;
    private volatile PlayerSnapshot snapshot = PlayerSnapshot.empty();
    private volatile String lastError;
    private volatile String lastLoggedEndpoint;
    private volatile ScheduledExecutorService executor;

    public RelayManager(Host host) {
        this.host = host;
    }

    public synchronized void start(PluginConfig config) {
        stop();
        if (!config.relayEnabled || !(config.relaySource.equals("STATUS") || config.relaySource.equals("HTTP"))) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "FauxPlayers-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> refresh(config), 1, config.refreshSeconds, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        lastLoggedEndpoint = null;
    }

    public void refreshAsync(PluginConfig config) {
        ScheduledExecutorService current = executor;
        if (current != null) current.execute(() -> refresh(config));
        else Executors.newSingleThreadExecutor(r -> new Thread(r, "FauxPlayers-relay-once"))
                .execute(() -> refresh(config));
    }

    public void refresh(PluginConfig config) {
        if (!config.relayEnabled) return;
        try {
            PlayerSnapshot previous = withoutAnonymousPlayers(snapshot);
            PlayerSnapshot next = withoutAnonymousPlayers(
                    config.relaySource.equals("HTTP") ? http(config) : status(config));
            snapshot = next;
            lastError = null;
            if (config.relaySource.equals("HTTP")) host.remoteChanged(previous, next);
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            host.warning("Relay refresh failed: " + lastError);
            if (!config.keepCache) snapshot = PlayerSnapshot.empty();
        }
    }

    public PlayerSnapshot snapshot() { return snapshot; }
    public String lastError() { return lastError; }

    private PlayerSnapshot http(PluginConfig config) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(config.httpUrl).toURL().openConnection();
        connection.setConnectTimeout(config.connectTimeout);
        connection.setReadTimeout(config.readTimeout);
        try {
            String json = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int online = number(json, "online", 0);
            int max = number(json, "max", 0);
            List<FauxPlayerEntry> players = new ArrayList<>();
            Matcher names = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
            while (names.find()) {
                String name = names.group(1);
                if (isAnonymousPlayer(name)) continue;
                players.add(new FauxPlayerEntry(name,
                        FauxPlayerEntry.stable("http:" + config.httpUrl, name), name,
                        config.defaultLatency, config.defaultMode, true));
            }
            return new PlayerSnapshot(online, max, players, null, null, Instant.now());
        } finally {
            connection.disconnect();
        }
    }

    private InetSocketAddress resolveEndpoint(PluginConfig config) {
        try {
            Attributes attributes = new InitialDirContext().getAttributes(
                    "dns:/_minecraft._tcp." + config.relayHost, new String[]{"SRV"});
            Attribute srv = attributes.get("SRV");
            if (srv != null) {
                String[] parts = srv.get().toString().trim().split("\\s+");
                if (parts.length >= 4) {
                    int port = Integer.parseInt(parts[2]);
                    String target = parts[3];
                    if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
                    String endpoint = target + ":" + port;
                    if (!endpoint.equals(lastLoggedEndpoint)) {
                        lastLoggedEndpoint = endpoint;
                        host.fine("Resolved Minecraft endpoint " + endpoint + " for " + config.relayHost);
                    }
                    return new InetSocketAddress(target, port);
                }
            }
        } catch (Exception ignored) { }
        return new InetSocketAddress(config.relayHost, config.relayPort > 0 ? config.relayPort : 25565);
    }

    private PlayerSnapshot status(PluginConfig config) throws Exception {
        try (Socket socket = new Socket()) {
            InetSocketAddress endpoint = resolveEndpoint(config);
            socket.connect(endpoint, config.connectTimeout);
            socket.setSoTimeout(config.readTimeout);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(payload);
            byte[] hostBytes = endpoint.getHostString().getBytes(StandardCharsets.UTF_8);
            writeVar(packet, 0); writeVar(packet, config.protocolVersion);
            writeVar(packet, hostBytes.length); packet.write(hostBytes);
            packet.writeShort(endpoint.getPort()); writeVar(packet, 1);
            writePacket(output, payload.toByteArray());
            payload.reset(); packet = new DataOutputStream(payload); writeVar(packet, 0);
            writePacket(output, payload.toByteArray());

            int packetLength = readVar(input);
            byte[] response = input.readNBytes(packetLength);
            DataInputStream responseIn = new DataInputStream(new ByteArrayInputStream(response));
            readVar(responseIn);
            int jsonLength = readVar(responseIn);
            String json = new String(responseIn.readNBytes(jsonLength), StandardCharsets.UTF_8);
            return new PlayerSnapshot(number(json, "online", 0), number(json, "max", 0),
                    sampleNames(json, config), text(json, "description"), text(json, "name"), Instant.now());
        }
    }

    private static List<FauxPlayerEntry> sampleNames(String json, PluginConfig config) {
        List<FauxPlayerEntry> result = new ArrayList<>();
        Matcher sample = Pattern.compile("\\\"sample\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (!sample.find()) return result;
        Matcher names = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(sample.group(1));
        while (names.find()) {
            String name = names.group(1);
            if (isAnonymousPlayer(name)) continue;
            result.add(new FauxPlayerEntry(name,
                    FauxPlayerEntry.stable("status:" + config.relayHost + ":" + config.relayPort, name), name,
                    config.defaultLatency, config.defaultMode, true));
        }
        return result;
    }

    private static PlayerSnapshot withoutAnonymousPlayers(PlayerSnapshot source) {
        List<FauxPlayerEntry> players = source.players().stream()
                .filter(entry -> !isAnonymousPlayer(entry.name()))
                .toList();
        if (players.size() == source.players().size()) return source;
        return new PlayerSnapshot(source.reportedOnline(), source.reportedMax(), players,
                source.motd(), source.version(), source.refreshedAt());
    }

    private static boolean isAnonymousPlayer(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT)
                .replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ");
        return normalized.equals("anonymous") || normalized.equals("anonymous player");
    }

    private static int number(String json, String key, int fallback) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static String text(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void writePacket(DataOutputStream output, byte[] bytes) throws IOException {
        writeVar(output, bytes.length); output.write(bytes); output.flush();
    }

    private static void writeVar(DataOutputStream output, int value) throws IOException {
        while ((value & -128) != 0) { output.writeByte(value & 127 | 128); value >>>= 7; }
        output.writeByte(value);
    }

    private static int readVar(DataInputStream input) throws IOException {
        int value = 0, shift = 0, next;
        do {
            next = input.readUnsignedByte(); value |= (next & 127) << shift; shift += 7;
            if (shift > 35) throw new IOException("varint");
        } while ((next & 128) != 0);
        return value;
    }
}
