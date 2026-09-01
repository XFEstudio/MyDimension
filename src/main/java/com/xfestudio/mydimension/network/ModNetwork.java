package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.BuilderNetworkBridge;
import com.xfestudio.mydimension.network.blueprint.BlueprintNetworkRegistration;
import com.xfestudio.mydimension.network.builder.BuilderNetworkRegistration;
import com.xfestudio.mydimension.network.builder.BuilderOpenMenuPacket;
import com.xfestudio.mydimension.network.builder.BuilderPreviewPacket;
import com.xfestudio.mydimension.network.builder.BuilderSnapshotPacket;
import com.xfestudio.mydimension.network.builder.BuilderAvailabilityPacket;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.MindTeamAccess;
import com.xfestudio.mydimension.world.PrivateMindFeature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;

public class ModNetwork {
    // BuilderSnapshotPacket gained the per-scepter history-recording flag and
    // BuilderCommandPacket gained its matching intent. Older v3 peers would
    // otherwise decode the remaining fields at the wrong offsets.
    private static final String PROTOCOL_VERSION = "4";

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
                id++,
                MindAccessPromptPacket.class,
                MindAccessPromptPacket::encode,
                MindAccessPromptPacket::decode,
                MindAccessPromptPacket::handle
        );
        id = BuilderNetworkRegistration.register(CHANNEL, id);
        BlueprintNetworkRegistration.register(CHANNEL, id);
        BuilderNetworkBridge.install(ModNetwork::sendBuilderOpenMenu, ModNetwork::sendBuilderState);
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

    public static void sendBuilderAvailability(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BuilderAvailabilityPacket(BuilderConfig.isEnabled()));
    }

    /** Opens only after the common-side caller has proved the player is holding the scepter. */
    public static void sendBuilderOpenMenu(ServerPlayer player) {
        if (!player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BuilderOpenMenuPacket());
        sendBuilderState(player);
    }

    /** Sends a matched UI snapshot and missing-material preview for the main-hand scepter. */
    public static void sendBuilderState(ServerPlayer player) {
        if (!player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())) return;
        sendBuilderSnapshot(player);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                BuilderPreviewPacket.from(player, player.getMainHandItem()));
    }

    /** Lightweight metadata refresh used by the open screen's periodic poll. */
    public static void sendBuilderSnapshot(ServerPlayer player) {
        if (!player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                BuilderSnapshotPacket.from(player, player.getMainHandItem()));
    }
}
