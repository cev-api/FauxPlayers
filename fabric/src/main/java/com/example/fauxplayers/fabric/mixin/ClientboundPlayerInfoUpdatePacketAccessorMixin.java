package com.example.fauxplayers.fabric.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")
public interface ClientboundPlayerInfoUpdatePacketAccessorMixin {
    @Mutable
    @Accessor("entries")
    void fauxplayers$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
