package com.example.fauxplayers.fabric.mixin;

import com.example.fauxplayers.fabric.FabricEntrypoint;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.server.MinecraftServer")
public abstract class MinecraftServerMixin {
    @Inject(method = "getStatus", at = @At("RETURN"), cancellable = true)
    private void fauxplayers$rewriteStatus(CallbackInfoReturnable<ServerStatus> callback) {
        ServerStatus original = callback.getReturnValue();
        ServerStatus rewritten = FabricEntrypoint.instance().rewriteStatus((MinecraftServer) (Object) this, original);
        if (rewritten != original) callback.setReturnValue(rewritten);
    }
}
