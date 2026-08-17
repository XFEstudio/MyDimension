package com.xfestudio.mydimension.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class MindTeamAccess {
    private static final String DATA_NAME = "mydimension_mind_team_access";
    private static final String OWNERS_TAG = "Owners";
    private static final String OWNER_NAME_TAG = "Name";
    private static final String GUESTS_TAG = "Guests";
    private static final long REQUEST_LIFETIME_MILLIS = 120_000L;
    private static final Map<MinecraftServer, Map<UUID, Map<UUID, PendingRequest>>> PENDING_REQUESTS = new WeakHashMap<>();

    private MindTeamAccess() {
    }

    public static boolean hasAccess(MinecraftServer server, UUID owner, UUID guest) {
        return owner.equals(guest) || data(server).hasAccess(owner, guest);
    }

    public static List<PlayerEntry> entrancesFor(ServerPlayer guest) {
        return data(guest.getServer()).entrancesFor(guest.getServer(), guest.getUUID());
    }

    public static List<PlayerEntry> candidatesFor(ServerPlayer guest) {
        List<PlayerEntry> candidates = new ArrayList<>();
        for (ServerPlayer player : guest.getServer().getPlayerList().getPlayers()) {
            if (!player.getUUID().equals(guest.getUUID()) && !hasAccess(guest.getServer(), player.getUUID(), guest.getUUID())) {
                candidates.add(new PlayerEntry(player.getUUID(), player.getGameProfile().getName()));
            }
        }
        candidates.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    public static List<PlayerEntry> guestsFor(ServerPlayer owner) {
        return data(owner.getServer()).guestsFor(owner.getUUID());
    }

    public static RequestResult request(ServerPlayer guest, UUID ownerId) {
        MinecraftServer server = guest.getServer();
        if (!PrivateMindFeature.isEnabled()) {
            return new RequestResult(RequestStatus.UNAVAILABLE, null, null);
        }
        if (guest.getUUID().equals(ownerId)) {
            return new RequestResult(RequestStatus.SELF, null, null);
        }
        if (hasAccess(server, ownerId, guest.getUUID())) {
            return new RequestResult(RequestStatus.ALREADY_ALLOWED, null, null);
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return new RequestResult(RequestStatus.OFFLINE, null, null);
        }

        long now = System.currentTimeMillis();
        Map<UUID, PendingRequest> ownerRequests = pending(server).computeIfAbsent(ownerId, ignored -> new HashMap<>());
        PendingRequest existing = ownerRequests.get(guest.getUUID());
        if (existing != null && existing.expiresAt() > now) {
            return new RequestResult(RequestStatus.ALREADY_PENDING, owner, existing);
        }

        PendingRequest request = new PendingRequest(guest.getUUID(), guest.getGameProfile().getName(), now + REQUEST_LIFETIME_MILLIS);
        ownerRequests.put(guest.getUUID(), request);
        return new RequestResult(RequestStatus.CREATED, owner, request);
    }

    public static ResponseResult respond(ServerPlayer owner, UUID guestId, boolean accepted) {
        Map<UUID, PendingRequest> ownerRequests = pending(owner.getServer()).get(owner.getUUID());
        PendingRequest request = ownerRequests == null ? null : ownerRequests.remove(guestId);
        if (request == null || request.expiresAt() <= System.currentTimeMillis()) {
            return new ResponseResult(ResponseStatus.EXPIRED, null, null);
        }

        ServerPlayer guest = owner.getServer().getPlayerList().getPlayer(guestId);
        if (!accepted) {
            return new ResponseResult(ResponseStatus.DENIED, guest, request);
        }

        if (MindInstances.slotFor(owner) < 0) {
            return new ResponseResult(ResponseStatus.NO_SLOT, guest, request);
        }

        data(owner.getServer()).grant(owner.getUUID(), owner.getGameProfile().getName(), guestId, request.guestName());
        return new ResponseResult(ResponseStatus.APPROVED, guest, request);
    }

    public static boolean revoke(ServerPlayer owner, UUID guestId) {
        return data(owner.getServer()).revoke(owner.getUUID(), guestId);
    }

    private static Map<UUID, Map<UUID, PendingRequest>> pending(MinecraftServer server) {
        return PENDING_REQUESTS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    private static TeamAccessData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TeamAccessData::load, TeamAccessData::new, DATA_NAME);
    }

    public enum RequestStatus {
        CREATED,
        ALREADY_PENDING,
        ALREADY_ALLOWED,
        OFFLINE,
        SELF,
        UNAVAILABLE
    }

    public enum ResponseStatus {
        APPROVED,
        DENIED,
        EXPIRED,
        NO_SLOT
    }

    public record PlayerEntry(UUID id, String name) {
    }

    public record PendingRequest(UUID guestId, String guestName, long expiresAt) {
    }

    public record RequestResult(RequestStatus status, ServerPlayer owner, PendingRequest request) {
    }

    public record ResponseResult(ResponseStatus status, ServerPlayer guest, PendingRequest request) {
    }

    private static class TeamAccessData extends SavedData {
        private final Map<UUID, OwnerPermissions> owners = new HashMap<>();

        private static TeamAccessData load(CompoundTag tag) {
            TeamAccessData data = new TeamAccessData();
            CompoundTag ownersTag = tag.getCompound(OWNERS_TAG);
            for (String ownerKey : ownersTag.getAllKeys()) {
                try {
                    UUID ownerId = UUID.fromString(ownerKey);
                    CompoundTag ownerTag = ownersTag.getCompound(ownerKey);
                    OwnerPermissions permissions = new OwnerPermissions(ownerTag.getString(OWNER_NAME_TAG));
                    CompoundTag guestsTag = ownerTag.getCompound(GUESTS_TAG);
                    for (String guestKey : guestsTag.getAllKeys()) {
                        try {
                            permissions.guests.put(UUID.fromString(guestKey), guestsTag.getString(guestKey));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    data.owners.put(ownerId, permissions);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag ownersTag = new CompoundTag();
            for (Map.Entry<UUID, OwnerPermissions> ownerEntry : owners.entrySet()) {
                CompoundTag ownerTag = new CompoundTag();
                ownerTag.putString(OWNER_NAME_TAG, ownerEntry.getValue().ownerName);
                CompoundTag guestsTag = new CompoundTag();
                for (Map.Entry<UUID, String> guestEntry : ownerEntry.getValue().guests.entrySet()) {
                    guestsTag.putString(guestEntry.getKey().toString(), guestEntry.getValue());
                }
                ownerTag.put(GUESTS_TAG, guestsTag);
                ownersTag.put(ownerEntry.getKey().toString(), ownerTag);
            }
            tag.put(OWNERS_TAG, ownersTag);
            return tag;
        }

        private boolean hasAccess(UUID owner, UUID guest) {
            OwnerPermissions permissions = owners.get(owner);
            return permissions != null && permissions.guests.containsKey(guest);
        }

        private void grant(UUID owner, String ownerName, UUID guest, String guestName) {
            OwnerPermissions permissions = owners.computeIfAbsent(owner, ignored -> new OwnerPermissions(ownerName));
            permissions.ownerName = ownerName;
            permissions.guests.put(guest, guestName);
            setDirty();
        }

        private boolean revoke(UUID owner, UUID guest) {
            OwnerPermissions permissions = owners.get(owner);
            if (permissions == null || permissions.guests.remove(guest) == null) {
                return false;
            }
            setDirty();
            return true;
        }

        private List<PlayerEntry> entrancesFor(MinecraftServer server, UUID guest) {
            List<PlayerEntry> entries = new ArrayList<>();
            for (Map.Entry<UUID, OwnerPermissions> entry : owners.entrySet()) {
                if (!entry.getValue().guests.containsKey(guest)) {
                    continue;
                }
                ServerPlayer onlineOwner = server.getPlayerList().getPlayer(entry.getKey());
                String name = onlineOwner == null ? entry.getValue().ownerName : onlineOwner.getGameProfile().getName();
                entries.add(new PlayerEntry(entry.getKey(), name));
            }
            entries.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER));
            return entries;
        }

        private List<PlayerEntry> guestsFor(UUID owner) {
            OwnerPermissions permissions = owners.get(owner);
            if (permissions == null) {
                return List.of();
            }
            List<PlayerEntry> entries = new ArrayList<>();
            permissions.guests.forEach((id, name) -> entries.add(new PlayerEntry(id, name)));
            entries.sort(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER));
            return entries;
        }
    }

    private static class OwnerPermissions {
        private String ownerName;
        private final Map<UUID, String> guests = new HashMap<>();

        private OwnerPermissions(String ownerName) {
            this.ownerName = ownerName;
        }
    }
}
