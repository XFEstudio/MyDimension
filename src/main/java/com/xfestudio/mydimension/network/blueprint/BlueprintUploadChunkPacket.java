package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintLimits;
import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintUploadChunkPacket(UUID transferId, int sequence, byte[] data) {
    public BlueprintUploadChunkPacket { data = data.clone(); }
    @Override public byte[] data() { return data.clone(); }

    public static void encode(BlueprintUploadChunkPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeVarInt(packet.sequence);
        buffer.writeByteArray(packet.data);
    }

    public static BlueprintUploadChunkPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintUploadChunkPacket(buffer.readUUID(), buffer.readVarInt(),
                buffer.readByteArray(BlueprintLimits.TRANSFER_CHUNK_BYTES));
    }

    public static void handle(BlueprintUploadChunkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && BlueprintPacketSecurity.authorize(player, packet.transferId)) {
                BlueprintServerService.get(player.getServer()).acceptUploadChunk(player,
                        packet.transferId, packet.sequence, packet.data);
            }
        });
        context.setPacketHandled(true);
    }
}
