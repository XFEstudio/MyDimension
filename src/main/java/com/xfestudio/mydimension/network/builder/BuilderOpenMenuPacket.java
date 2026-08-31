package com.xfestudio.mydimension.network.builder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-authoritative request to display the Realmwright menu. */
public final class BuilderOpenMenuPacket {
    public static void encode(BuilderOpenMenuPacket packet, FriendlyByteBuf buffer) {
    }

    public static BuilderOpenMenuPacket decode(FriendlyByteBuf buffer) {
        return new BuilderOpenMenuPacket();
    }

    public static void handle(BuilderOpenMenuPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BuilderClientPacketHooks.receiver().openMenu());
        context.setPacketHandled(true);
    }
}
