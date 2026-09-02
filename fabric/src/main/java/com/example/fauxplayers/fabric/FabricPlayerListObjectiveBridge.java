package com.example.fauxplayers.fabric;

import com.example.fauxplayers.core.FauxPlayerEntry;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;

/** Mirrors TAB's player-list objective for presentation-only Fabric entries. */
final class FabricPlayerListObjectiveBridge {
    private static final String OBJECTIVE_NAME = "TAB-PlayerList";

    void add(ServerPlayer viewer, FauxPlayerEntry entry) {
        sendScore(viewer, entry.name(), entry.latency());
    }

    void update(ServerPlayer viewer, FauxPlayerEntry entry) {
        sendScore(viewer, entry.name(), entry.latency());
    }

    void remove(ServerPlayer viewer, String name) {
        viewer.connection.send(new ClientboundResetScorePacket(name, OBJECTIVE_NAME));
    }

    private void sendScore(ServerPlayer viewer, String name, int latency) {
        Component display = Component.literal("Ping: " + latency).withStyle(ChatFormatting.GRAY);
        viewer.connection.send(new ClientboundSetScorePacket(name, OBJECTIVE_NAME, latency,
                Optional.empty(), Optional.of(new FixedFormat(display))));
    }
}
