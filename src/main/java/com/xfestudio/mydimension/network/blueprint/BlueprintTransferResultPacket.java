package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintNetworkHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintTransferResultPacket(UUID requestId, boolean success, UUID cacheToken, String message) {
    private static final UUID EMPTY = new UUID(0L, 0L);

    public static void encode(BlueprintTransferResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeBoolean(packet.success);
        buffer.writeUUID(packet.cacheToken == null ? EMPTY : packet.cacheToken);
        buffer.writeUtf(packet.message, 256);
    }

    public static BlueprintTransferResultPacket decode(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        boolean success = buffer.readBoolean();
        UUID token = buffer.readUUID();
        return new BlueprintTransferResultPacket(request, success, token.equals(EMPTY) ? null : token,
                buffer.readUtf(256));
    }

    public static void handle(BlueprintTransferResultPacket packet, Supplier<NetworkEvent.Context> ignored) {
        NetworkEvent.Context context = ignored.get();
        context.enqueueWork(() -> BlueprintNetworkHooks.clientReceiver().result(packet.requestId,
                packet.success, packet.cacheToken, packet.message));
        context.setPacketHandled(true);
    }
}
