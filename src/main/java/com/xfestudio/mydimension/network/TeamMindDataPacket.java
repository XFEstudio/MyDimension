package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.client.RiftClient;
import com.xfestudio.mydimension.world.MindTeamAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class TeamMindDataPacket {
    private static final int MAX_PLAYERS = 128;
    private final List<PlayerInfo> entrances;
    private final List<PlayerInfo> candidates;
    private final List<PlayerInfo> guests;

    public TeamMindDataPacket(List<PlayerInfo> entrances, List<PlayerInfo> candidates, List<PlayerInfo> guests) {
        this.entrances = List.copyOf(entrances);
        this.candidates = List.copyOf(candidates);
        this.guests = List.copyOf(guests);
    }

    public static TeamMindDataPacket from(List<MindTeamAccess.PlayerEntry> entrances,
                                          List<MindTeamAccess.PlayerEntry> candidates,
                                          List<MindTeamAccess.PlayerEntry> guests) {
        return new TeamMindDataPacket(convert(entrances), convert(candidates), convert(guests));
    }

    public static void encode(TeamMindDataPacket packet, FriendlyByteBuf buffer) {
        writePlayers(buffer, packet.entrances);
        writePlayers(buffer, packet.candidates);
        writePlayers(buffer, packet.guests);
    }

    public static TeamMindDataPacket decode(FriendlyByteBuf buffer) {
        return new TeamMindDataPacket(readPlayers(buffer), readPlayers(buffer), readPlayers(buffer));
    }

    public static void handle(TeamMindDataPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RiftClient.handleTeamMindData(packet)));
        context.setPacketHandled(true);
    }

    public List<PlayerInfo> entrances() {
        return entrances;
    }

    public List<PlayerInfo> candidates() {
        return candidates;
    }

    public List<PlayerInfo> guests() {
        return guests;
    }

    private static List<PlayerInfo> convert(List<MindTeamAccess.PlayerEntry> entries) {
        return entries.stream().map(entry -> new PlayerInfo(entry.id(), entry.name())).toList();
    }

    private static void writePlayers(FriendlyByteBuf buffer, List<PlayerInfo> players) {
        buffer.writeVarInt(players.size());
        for (PlayerInfo player : players) {
            buffer.writeUUID(player.id());
            buffer.writeUtf(player.name(), 64);
        }
    }

    private static List<PlayerInfo> readPlayers(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_PLAYERS) {
            throw new IllegalArgumentException("Invalid team mind player count: " + count);
        }
        List<PlayerInfo> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            players.add(new PlayerInfo(buffer.readUUID(), buffer.readUtf(64)));
        }
        return players;
    }

    public record PlayerInfo(UUID id, String name) {
    }
}
