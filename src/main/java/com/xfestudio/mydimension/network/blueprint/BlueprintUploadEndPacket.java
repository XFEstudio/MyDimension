package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintUploadEndPacket(UUID transferId) {
    public static void encode(BlueprintUploadEndPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
    }

    public static BlueprintUploadEndPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintUploadEndPacket(buffer.readUUID());
    }

    public static void handle(BlueprintUploadEndPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && BlueprintPacketSecurity.authorize(player, packet.transferId)) {
                BlueprintServerService.get(player.getServer()).finishUpload(player, packet.transferId);
            }
        });
        context.setPacketHandled(true);
    }
}
