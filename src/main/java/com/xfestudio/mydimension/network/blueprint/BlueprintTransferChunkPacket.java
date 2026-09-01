package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintLimits;
import com.xfestudio.mydimension.builder.blueprint.BlueprintNetworkHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintTransferChunkPacket(UUID transferId, int sequence, byte[] data) {
    public BlueprintTransferChunkPacket { data = data.clone(); }
    @Override public byte[] data() { return data.clone(); }

    public static void encode(BlueprintTransferChunkPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeVarInt(packet.sequence);
        buffer.writeByteArray(packet.data);
    }

    public static BlueprintTransferChunkPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintTransferChunkPacket(buffer.readUUID(), buffer.readVarInt(),
                buffer.readByteArray(BlueprintLimits.TRANSFER_CHUNK_BYTES));
    }

    public static void handle(BlueprintTransferChunkPacket packet, Supplier<NetworkEvent.Context> ignored) {
        NetworkEvent.Context context = ignored.get();
        context.enqueueWork(() -> BlueprintNetworkHooks.clientReceiver().chunk(packet.transferId,
                packet.sequence, packet.data));
        context.setPacketHandled(true);
    }
}
