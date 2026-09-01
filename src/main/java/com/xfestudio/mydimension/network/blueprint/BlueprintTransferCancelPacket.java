package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Explicitly releases an unfinished upload instead of waiting for its timeout. */
public record BlueprintTransferCancelPacket(UUID transferId) {
    public static void encode(BlueprintTransferCancelPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.transferId);
    }

    public static BlueprintTransferCancelPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintTransferCancelPacket(buffer.readUUID());
    }

    public static void handle(BlueprintTransferCancelPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player != null) BlueprintServerService.get(player.getServer())
                    .cancelUpload(player, packet.transferId);
        });
        context.setPacketHandled(true);
    }
}
