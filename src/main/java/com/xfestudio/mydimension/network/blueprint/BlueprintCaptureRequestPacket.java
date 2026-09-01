package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlueprintCaptureRequestPacket(UUID requestId, BlockPos first, BlockPos second,
                                            BlueprintSaveMode saveMode, String name,
                                            boolean finishSelection) {
    public static void encode(BlueprintCaptureRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeBlockPos(packet.first);
        buffer.writeBlockPos(packet.second);
        buffer.writeEnum(packet.saveMode);
        buffer.writeUtf(packet.name, 128);
        buffer.writeBoolean(packet.finishSelection);
    }

    public static BlueprintCaptureRequestPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintCaptureRequestPacket(buffer.readUUID(), buffer.readBlockPos(), buffer.readBlockPos(),
                buffer.readEnum(BlueprintSaveMode.class), buffer.readUtf(128), buffer.readBoolean());
    }

    public static void handle(BlueprintCaptureRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && BlueprintPacketSecurity.authorize(player, packet.requestId)) {
                BlueprintServerService.get(player.getServer()).capture(player, packet.requestId, packet.first,
                        packet.second, packet.saveMode, packet.name, packet.finishSelection);
            }
        });
        context.setPacketHandled(true);
    }
}
