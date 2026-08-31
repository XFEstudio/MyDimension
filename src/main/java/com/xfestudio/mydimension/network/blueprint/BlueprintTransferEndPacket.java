package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintNetworkHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintTransferEndPacket(UUID transferId) {
    public static void encode(BlueprintTransferEndPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
    }

    public static BlueprintTransferEndPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintTransferEndPacket(buffer.readUUID());
    }

    public static void handle(BlueprintTransferEndPacket packet, Supplier<NetworkEvent.Context> ignored) {
        NetworkEvent.Context context = ignored.get();
        context.enqueueWork(() -> BlueprintNetworkHooks.clientReceiver().end(packet.transferId));
        context.setPacketHandled(true);
    }
}
