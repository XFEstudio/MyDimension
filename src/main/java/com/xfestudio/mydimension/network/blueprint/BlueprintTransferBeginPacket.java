package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintNetworkHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintTransferBeginPacket(UUID transferId, UUID cacheToken, int byteLength,
                                           int chunkCount, byte[] sha256) {
    public BlueprintTransferBeginPacket { sha256 = sha256.clone(); }
    @Override public byte[] sha256() { return sha256.clone(); }

    public static void encode(BlueprintTransferBeginPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeUUID(packet.cacheToken);
        buffer.writeVarInt(packet.byteLength);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeByteArray(packet.sha256);
    }

    public static BlueprintTransferBeginPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintTransferBeginPacket(buffer.readUUID(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readByteArray(32));
    }

    public static void handle(BlueprintTransferBeginPacket packet, Supplier<NetworkEvent.Context> ignored) {
        NetworkEvent.Context context = ignored.get();
        context.enqueueWork(() -> BlueprintNetworkHooks.clientReceiver().begin(packet.transferId,
                packet.cacheToken, packet.byteLength, packet.chunkCount, packet.sha256));
        context.setPacketHandled(true);
    }
}
