package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Registers the first blueprint corner while it is actually in the player's
 * reach and line of sight.  A later capture request may therefore validate
 * only the second corner without trusting an arbitrary client-supplied first
 * position.
 */
public record BlueprintSelectionStartPacket(BlockPos first) {
    public static void encode(BlueprintSelectionStartPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.first);
    }

    public static BlueprintSelectionStartPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintSelectionStartPacket(buffer.readBlockPos());
    }

    public static void handle(BlueprintSelectionStartPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BlueprintServerService.get(player.getServer()).beginSelection(player, packet.first);
            }
        });
        context.setPacketHandled(true);
    }
}
