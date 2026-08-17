package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.client.RiftClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record MindAccessPromptPacket(UUID requesterId, String requesterName) {
    public static void encode(MindAccessPromptPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requesterId);
        buffer.writeUtf(packet.requesterName, 64);
    }

    public static MindAccessPromptPacket decode(FriendlyByteBuf buffer) {
        return new MindAccessPromptPacket(buffer.readUUID(), buffer.readUtf(64));
    }

    public static void handle(MindAccessPromptPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RiftClient.openMindAccessPrompt(packet)));
        context.setPacketHandled(true);
    }
}
