package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Explicitly releases the sender's server-side source-corner selection. */
public record BlueprintSelectionCancelPacket() {
    public static void encode(BlueprintSelectionCancelPacket packet, FriendlyByteBuf buffer) {
    }

    public static BlueprintSelectionCancelPacket decode(FriendlyByteBuf buffer) {
        return new BlueprintSelectionCancelPacket();
    }

    public static void handle(BlueprintSelectionCancelPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BlueprintServerService.get(player.getServer()).clearSelection(player.getUUID());
            }
        });
        context.setPacketHandled(true);
    }
}
