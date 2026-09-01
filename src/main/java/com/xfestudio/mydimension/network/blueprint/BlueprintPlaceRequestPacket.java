package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintPlaceRequestPacket(UUID requestId, UUID cacheToken, BlockPos targetAnchor,
                                          BlueprintTransform transform) {
    public static void encode(BlueprintPlaceRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeUUID(packet.cacheToken);
        buffer.writeBlockPos(packet.targetAnchor);
        buffer.writeByte(packet.transform.mask());
    }

    public static BlueprintPlaceRequestPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintPlaceRequestPacket(buffer.readUUID(), buffer.readUUID(), buffer.readBlockPos(),
                BlueprintTransform.fromMask(buffer.readUnsignedByte()));
    }

    public static void handle(BlueprintPlaceRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && BlueprintPacketSecurity.authorize(player, packet.requestId)) {
                BlueprintServerService.get(player.getServer()).requestPlacement(player, packet.requestId,
                        packet.cacheToken, packet.targetAnchor, packet.transform);
            }
        });
        context.setPacketHandled(true);
    }
}
