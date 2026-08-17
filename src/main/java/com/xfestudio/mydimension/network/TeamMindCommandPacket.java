package com.xfestudio.mydimension.network;

import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.MindTeamAccess;
import com.xfestudio.mydimension.world.MindType;
import com.xfestudio.mydimension.world.PrivateMindFeature;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record TeamMindCommandPacket(Command command, UUID playerId, MindType mindType) {
    private static final UUID EMPTY_UUID = new UUID(0L, 0L);

    public static TeamMindCommandPacket refresh() {
        return new TeamMindCommandPacket(Command.REFRESH, EMPTY_UUID, MindType.ETHEREAL);
    }

    public static TeamMindCommandPacket requestAccess(UUID ownerId) {
        return new TeamMindCommandPacket(Command.REQUEST_ACCESS, ownerId, MindType.ETHEREAL);
    }

    public static TeamMindCommandPacket respond(UUID guestId, boolean accepted) {
        return new TeamMindCommandPacket(accepted ? Command.ACCEPT : Command.DENY, guestId, MindType.ETHEREAL);
    }

    public static TeamMindCommandPacket revoke(UUID guestId) {
        return new TeamMindCommandPacket(Command.REVOKE, guestId, MindType.ETHEREAL);
    }

    public static TeamMindCommandPacket select(UUID ownerId, MindType mindType) {
        return new TeamMindCommandPacket(Command.SELECT, ownerId, mindType);
    }

    public static void encode(TeamMindCommandPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.command);
        buffer.writeUUID(packet.playerId);
        buffer.writeEnum(packet.mindType);
    }

    public static TeamMindCommandPacket decode(FriendlyByteBuf buffer) {
        return new TeamMindCommandPacket(buffer.readEnum(Command.class), buffer.readUUID(), buffer.readEnum(MindType.class));
    }

    public static void handle(TeamMindCommandPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            switch (packet.command) {
                case REFRESH -> ModNetwork.sendTeamMindData(player);
                case REQUEST_ACCESS -> requestAccess(player, packet.playerId);
                case ACCEPT -> respond(player, packet.playerId, true);
                case DENY -> respond(player, packet.playerId, false);
                case REVOKE -> revoke(player, packet.playerId);
                case SELECT -> select(player, packet.playerId, packet.mindType);
            }
        });
        context.setPacketHandled(true);
    }

    private static void requestAccess(ServerPlayer guest, UUID ownerId) {
        MindTeamAccess.RequestResult result = MindTeamAccess.request(guest, ownerId);
        switch (result.status()) {
            case CREATED -> {
                ModNetwork.sendMindAccessPrompt(result.owner(), result.request());
                guest.displayClientMessage(Component.translatable("message.mydimension.team.request_sent", result.owner().getDisplayName()), false);
            }
            case ALREADY_PENDING -> guest.displayClientMessage(Component.translatable("message.mydimension.team.request_pending"), false);
            case ALREADY_ALLOWED -> guest.displayClientMessage(Component.translatable("message.mydimension.team.already_allowed"), false);
            case OFFLINE -> guest.displayClientMessage(Component.translatable("message.mydimension.team.player_offline"), false);
            case SELF -> guest.displayClientMessage(Component.translatable("message.mydimension.team.cannot_self"), false);
            case UNAVAILABLE -> guest.displayClientMessage(Component.translatable("message.mydimension.team.unavailable"), false);
        }
    }

    private static void respond(ServerPlayer owner, UUID guestId, boolean accepted) {
        MindTeamAccess.ResponseResult result = MindTeamAccess.respond(owner, guestId, accepted);
        switch (result.status()) {
            case APPROVED -> {
                owner.displayClientMessage(Component.translatable("message.mydimension.team.approved", result.request().guestName()), false);
                if (result.guest() != null) {
                    result.guest().displayClientMessage(Component.translatable("message.mydimension.team.access_granted", owner.getDisplayName()), false);
                    ModNetwork.sendTeamMindData(result.guest());
                }
                ModNetwork.sendTeamMindData(owner);
            }
            case DENIED -> {
                owner.displayClientMessage(Component.translatable("message.mydimension.team.denied", result.request().guestName()), false);
                if (result.guest() != null) {
                    result.guest().displayClientMessage(Component.translatable("message.mydimension.team.access_denied", owner.getDisplayName()), false);
                    ModNetwork.sendTeamMindData(result.guest());
                }
            }
            case EXPIRED -> owner.displayClientMessage(Component.translatable("message.mydimension.team.request_expired"), false);
            case NO_SLOT -> owner.displayClientMessage(Component.translatable("message.mydimension.team.no_slot"), false);
        }
    }

    private static void revoke(ServerPlayer owner, UUID guestId) {
        if (!MindTeamAccess.revoke(owner, guestId)) {
            return;
        }

        ServerPlayer guest = owner.getServer().getPlayerList().getPlayer(guestId);
        owner.displayClientMessage(Component.translatable("message.mydimension.team.revoked"), false);
        if (guest != null) {
            guest.displayClientMessage(Component.translatable("message.mydimension.team.access_revoked", owner.getDisplayName()), false);
            ModNetwork.sendTeamMindData(guest);
        }
        ModNetwork.sendTeamMindData(owner);
    }

    private static void select(ServerPlayer player, UUID ownerId, MindType mindType) {
        if (!PrivateMindFeature.isEnabled() || !MindTeamAccess.hasAccess(player.getServer(), ownerId, player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.mydimension.team.not_allowed"), false);
            return;
        }

        boolean selected = setIfRift(player.getItemInHand(InteractionHand.MAIN_HAND), ownerId, mindType)
                | setIfRift(player.getItemInHand(InteractionHand.OFF_HAND), ownerId, mindType);
        if (!selected) {
            player.displayClientMessage(Component.translatable("message.mydimension.team.hold_rift"), false);
            return;
        }

        String ownerName = MindTeamAccess.entrancesFor(player).stream()
                .filter(entry -> entry.id().equals(ownerId))
                .map(MindTeamAccess.PlayerEntry::name)
                .findFirst()
                .orElse("?");
        player.displayClientMessage(Component.translatable("message.mydimension.team.selected", ownerName, mindType.displayName()), true);
    }

    private static boolean setIfRift(ItemStack stack, UUID ownerId, MindType mindType) {
        if (!stack.is(ModItems.RIFT.get())) {
            return false;
        }
        RiftItem.setTeamMindTarget(stack, ownerId, mindType.baseDimension());
        return true;
    }

    public enum Command {
        REFRESH,
        REQUEST_ACCESS,
        ACCEPT,
        DENY,
        REVOKE,
        SELECT
    }
}
