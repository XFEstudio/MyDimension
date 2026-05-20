package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetRiftActionPacket {
    private final RiftAction action;

    public SetRiftActionPacket(RiftAction action) {
        this.action = action;
    }

    public static void encode(SetRiftActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
    }

    public static SetRiftActionPacket decode(FriendlyByteBuf buffer) {
        return new SetRiftActionPacket(buffer.readEnum(RiftAction.class));
    }

    public static void handle(SetRiftActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            setIfRift(player.getItemInHand(InteractionHand.MAIN_HAND), packet.action);
            setIfRift(player.getItemInHand(InteractionHand.OFF_HAND), packet.action);
        });
        context.setPacketHandled(true);
    }

    private static void setIfRift(ItemStack stack, RiftAction action) {
        if (stack.is(ModItems.RIFT.get())) {
            RiftItem.setSelectedAction(stack, action);
        }
    }
}
