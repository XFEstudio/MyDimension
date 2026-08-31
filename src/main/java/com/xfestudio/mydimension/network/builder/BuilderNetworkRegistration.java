package com.xfestudio.mydimension.network.builder;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

/** Registers ordinary builder traffic and returns the next free discriminator. */
public final class BuilderNetworkRegistration {
    private BuilderNetworkRegistration() {
    }

    public static int register(SimpleChannel channel, int firstId) {
        int id = firstId;
        channel.messageBuilder(BuilderCommandPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BuilderCommandPacket::encode).decoder(BuilderCommandPacket::decode)
                .consumerMainThread(BuilderCommandPacket::handle).add();
        channel.messageBuilder(BuilderSnapshotPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BuilderSnapshotPacket::encode).decoder(BuilderSnapshotPacket::decode)
                .consumerMainThread(BuilderSnapshotPacket::handle).add();
        channel.messageBuilder(BuilderPreviewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BuilderPreviewPacket::encode).decoder(BuilderPreviewPacket::decode)
                .consumerMainThread(BuilderPreviewPacket::handle).add();
        channel.messageBuilder(BuilderOpenMenuPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BuilderOpenMenuPacket::encode).decoder(BuilderOpenMenuPacket::decode)
                .consumerMainThread(BuilderOpenMenuPacket::handle).add();
        channel.messageBuilder(BuilderAvailabilityPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BuilderAvailabilityPacket::encode).decoder(BuilderAvailabilityPacket::decode)
                .consumerMainThread(BuilderAvailabilityPacket::handle).add();
        return id;
    }
}
