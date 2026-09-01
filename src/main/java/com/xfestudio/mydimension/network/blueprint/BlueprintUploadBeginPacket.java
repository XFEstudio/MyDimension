package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintUploadBeginPacket(UUID transferId, int byteLength, int chunkCount, byte[] sha256) {
    public BlueprintUploadBeginPacket { sha256 = sha256.clone(); }
    @Override public byte[] sha256() { return sha256.clone(); }

    public static void encode(BlueprintUploadBeginPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
        buffer.writeVarInt(packet.byteLength);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeByteArray(packet.sha256);
    }

    public static BlueprintUploadBeginPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintUploadBeginPacket(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(32));
    }

    public static void handle(BlueprintUploadBeginPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && BlueprintPacketSecurity.authorize(player, packet.transferId)) {
                BlueprintServerService.get(player.getServer()).beginUpload(player, packet.transferId,
                        packet.byteLength, packet.chunkCount, packet.sha256);
            }
        });
        context.setPacketHandled(true);
    }
}
