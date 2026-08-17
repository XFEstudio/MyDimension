package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.world.MindTeamAccess;
import com.xfestudio.mydimension.world.PrivateMindFeature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MyDimension.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(
                id++,
                SetRiftActionPacket.class,
                SetRiftActionPacket::encode,
                SetRiftActionPacket::decode,
                SetRiftActionPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                TeamMindCommandPacket.class,
                TeamMindCommandPacket::encode,
                TeamMindCommandPacket::decode,
                TeamMindCommandPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                TeamMindDataPacket.class,
                TeamMindDataPacket::encode,
                TeamMindDataPacket::decode,
                TeamMindDataPacket::handle
        );
        CHANNEL.registerMessage(
                id,
                MindAccessPromptPacket.class,
                MindAccessPromptPacket::encode,
                MindAccessPromptPacket::decode,
                MindAccessPromptPacket::handle
        );
    }

    public static void sendTeamMindData(ServerPlayer player) {
        if (!PrivateMindFeature.isEnabled()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new TeamMindDataPacket(List.of(), List.of(), List.of()));
            return;
        }

        TeamMindDataPacket packet = TeamMindDataPacket.from(
                MindTeamAccess.entrancesFor(player),
                MindTeamAccess.candidatesFor(player),
                MindTeamAccess.guestsFor(player)
        );
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendMindAccessPrompt(ServerPlayer owner, MindTeamAccess.PendingRequest request) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new MindAccessPromptPacket(request.guestId(), request.guestName()));
    }
}
