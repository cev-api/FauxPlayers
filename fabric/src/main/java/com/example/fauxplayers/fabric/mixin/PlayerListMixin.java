package com.example.fauxplayers.fabric.mixin;

import com.example.fauxplayers.fabric.FabricEntrypoint;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Unique
    private ServerPlayer fauxplayers$joiningPlayer;

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void fauxplayers$beginJoin(Connection connection, ServerPlayer player,
                                       CommonListenerCookie cookie, CallbackInfo callback) {
        fauxplayers$joiningPlayer = player;
    }

    @Redirect(
            method = "placeNewPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void fauxplayers$captureJoinMessage(PlayerList list, Component message, boolean overlay) {
        if (fauxplayers$joiningPlayer != null) {
            FabricEntrypoint.instance().observeJoinMessage(fauxplayers$joiningPlayer, message);
        }
        list.broadcastSystemMessage(message, overlay);
    }

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void fauxplayers$endJoin(Connection connection, ServerPlayer player,
                                     CommonListenerCookie cookie, CallbackInfo callback) {
        fauxplayers$joiningPlayer = null;
    }
}
