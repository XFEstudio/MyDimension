package com.xfestudio.mydimension.network.builder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Lightweight login/config update for creative-tab visibility. */
public record BuilderAvailabilityPacket(boolean enabled) {
    public static void encode(BuilderAvailabilityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled);
    }

    public static BuilderAvailabilityPacket decode(FriendlyByteBuf buffer) {
        return new BuilderAvailabilityPacket(buffer.readBoolean());
    }

    public static void handle(BuilderAvailabilityPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BuilderClientPacketHooks.receiver().availability(packet.enabled));
        context.setPacketHandled(true);
    }
}
