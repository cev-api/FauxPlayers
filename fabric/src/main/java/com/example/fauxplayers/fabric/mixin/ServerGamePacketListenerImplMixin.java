package com.example.fauxplayers.fabric.mixin;

import com.example.fauxplayers.fabric.FabricEntrypoint;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    @Final
    private ServerPlayer player;

    @Redirect(
            method = "removePlayerFromWorld",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void fauxplayers$captureLeaveMessage(PlayerList list, Component message, boolean overlay) {
        FabricEntrypoint.instance().observeLeaveMessage(player, message);
        list.broadcastSystemMessage(message, overlay);
    }
}
