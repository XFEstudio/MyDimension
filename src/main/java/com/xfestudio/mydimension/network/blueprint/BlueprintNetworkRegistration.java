package com.xfestudio.mydimension.network.blueprint;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

/** Call from ModNetwork.register and use the returned next discriminator. */
public final class BlueprintNetworkRegistration {
    private BlueprintNetworkRegistration() {
    }

    public static int register(SimpleChannel channel, int firstId) {
        int id = firstId;
        channel.messageBuilder(BlueprintCaptureRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintCaptureRequestPacket::encode).decoder(BlueprintCaptureRequestPacket::decode)
                .consumerNetworkThread(BlueprintCaptureRequestPacket::handle).add();
        channel.messageBuilder(BlueprintUploadBeginPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintUploadBeginPacket::encode).decoder(BlueprintUploadBeginPacket::decode)
                .consumerNetworkThread(BlueprintUploadBeginPacket::handle).add();
        channel.messageBuilder(BlueprintUploadChunkPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintUploadChunkPacket::encode).decoder(BlueprintUploadChunkPacket::decode)
                .consumerNetworkThread(BlueprintUploadChunkPacket::handle).add();
        channel.messageBuilder(BlueprintUploadEndPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintUploadEndPacket::encode).decoder(BlueprintUploadEndPacket::decode)
                .consumerNetworkThread(BlueprintUploadEndPacket::handle).add();
        channel.messageBuilder(BlueprintTransferCancelPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintTransferCancelPacket::encode).decoder(BlueprintTransferCancelPacket::decode)
                .consumerNetworkThread(BlueprintTransferCancelPacket::handle).add();
        channel.messageBuilder(BlueprintPlaceRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintPlaceRequestPacket::encode).decoder(BlueprintPlaceRequestPacket::decode)
                .consumerNetworkThread(BlueprintPlaceRequestPacket::handle).add();
        channel.messageBuilder(BlueprintTransferBeginPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BlueprintTransferBeginPacket::encode).decoder(BlueprintTransferBeginPacket::decode)
                .consumerNetworkThread(BlueprintTransferBeginPacket::handle).add();
        channel.messageBuilder(BlueprintTransferChunkPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BlueprintTransferChunkPacket::encode).decoder(BlueprintTransferChunkPacket::decode)
                .consumerNetworkThread(BlueprintTransferChunkPacket::handle).add();
        channel.messageBuilder(BlueprintTransferEndPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BlueprintTransferEndPacket::encode).decoder(BlueprintTransferEndPacket::decode)
                .consumerNetworkThread(BlueprintTransferEndPacket::handle).add();
        channel.messageBuilder(BlueprintTransferResultPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BlueprintTransferResultPacket::encode).decoder(BlueprintTransferResultPacket::decode)
                .consumerNetworkThread(BlueprintTransferResultPacket::handle).add();
        channel.messageBuilder(BlueprintSelectionStartPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BlueprintSelectionStartPacket::encode).decoder(BlueprintSelectionStartPacket::decode)
                .consumerNetworkThread(BlueprintSelectionStartPacket::handle).add();
        return id;
    }
}
