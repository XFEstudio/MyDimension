package com.xfestudio.mydimension.network.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.BuilderRuntime;
import com.xfestudio.mydimension.builder.PendingBuildData;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import com.xfestudio.mydimension.builder.anchor.AnchorBindings;
import com.xfestudio.mydimension.builder.anchor.AnchorIndexSavedData;
import com.xfestudio.mydimension.builder.anchor.ResonantSupplyAnchorBlockEntity;
import com.xfestudio.mydimension.builder.history.BuilderHistoryData;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-owned state consumed by the five-tab Realmwright screen and HUD. */
public record BuilderSnapshotPacket(boolean enabled, BuilderMode mode, SurfaceMatchMode surfaceMatch,
                                    boolean historyRecording,
                                    int buildLimit, int demolishLimit,
                                    int maximumBuildLimit, int maximumDemolishLimit,
                                    int reach, String status, @Nullable UUID activeJobId,
                                    int completedBlocks, int totalBlocks,
                                    boolean canUndo, boolean canRedo,
                                    List<Anchor> anchors, List<History> history) {
    private static final int MAX_ANCHORS = 256;
    private static final int MAX_HISTORY = 1_000;

    public BuilderSnapshotPacket {
        status = status == null ? "" : status;
        anchors = List.copyOf(anchors);
        history = List.copyOf(history);
    }

    public static BuilderSnapshotPacket from(ServerPlayer player, ItemStack scepter) {
        BuilderRuntime.Settings settings = BuilderRuntime.settings();
        UUID scepterId = RealmwrightData.id(scepter);
        PendingBuildData.Task pending = PendingBuildData.get(player.getServer()).get(player.getUUID());
        if (pending != null && !pending.scepterId().equals(scepterId)) pending = null;
        BlueprintTaskManager.Status running = BlueprintTaskManager.get(player.getServer()).status(player);

        BuilderHistoryData historyData = BuilderHistoryData.get(player.getServer());
        BuilderTransaction undo = historyData.peekUndo(player.getUUID(), scepterId);
        BuilderTransaction redo = historyData.peekRedo(player.getUUID(), scepterId);
        List<History> history = historyData.recent(player.getUUID(), scepterId,
                        Math.min(MAX_HISTORY, settings.undoDepth()))
                .stream().map(History::from).toList();

        int historyCompleted = pending != null && undo != null && undo.id().equals(pending.transactionId())
                ? undo.worldDeltas().size() : 0;
        PendingBuildData.Progress pendingProgress = pending == null
                ? new PendingBuildData.Progress(0, 0)
                : PendingBuildData.progress(pending.completed(), pending.total(),
                pending.missing().size(), historyCompleted);
        int completed = running.transactionId() != null ? running.completed()
                : pendingProgress.completed();
        int missing = running.transactionId() != null ? running.missing()
                : pending == null ? 0 : pending.missing().size();
        int total = running.transactionId() != null ? running.total()
                : pendingProgress.total();
        String status = running.transactionId() != null ? "Building blueprint"
                : pending == null ? "" : "Waiting for " + missing + " block(s)";
        return new BuilderSnapshotPacket(
                settings.enabled(),
                RealmwrightData.mode(scepter),
                RealmwrightData.matchMode(scepter),
                RealmwrightData.recordsHistory(scepter),
                RealmwrightData.buildLimit(scepter, settings.maxBuildLimit()),
                RealmwrightData.demolishLimit(scepter, settings.maxDemolishLimit()),
                settings.maxBuildLimit(),
                settings.maxDemolishLimit(),
                Math.max(1, (int) Math.ceil(settings.blockReach())),
                status,
                running.transactionId() != null ? running.transactionId()
                        : pending == null ? null : pending.transactionId(),
                completed,
                total,
                undo != null && undo.state() == BuilderTransaction.State.APPLIED,
                redo != null && redo.state() == BuilderTransaction.State.UNDONE,
                anchors(player, scepter),
                history
        );
    }

    private static List<Anchor> anchors(ServerPlayer player, ItemStack scepter) {
        AnchorIndexSavedData index = AnchorIndexSavedData.get(player.getServer());
        boolean bindingsChanged = AnchorBindings.pruneMissing(scepter, index);
        List<UUID> ids = AnchorBindings.read(scepter);
        List<Anchor> result = new ArrayList<>(Math.min(ids.size(), MAX_ANCHORS));
        for (int i = 0; i < ids.size() && i < MAX_ANCHORS; i++) {
            UUID id = ids.get(i);
            AnchorIndexSavedData.AnchorLocation location = index.find(id).orElse(null);
            if (location == null) {
                bindingsChanged = true;
                continue;
            }

            AnchorStatus status = AnchorStatus.UNLOADED;
            boolean owner = false;
            boolean publicAccess = false;
            ServerLevel level = player.getServer().getLevel(location.dimension());
            if (level == null) {
                index.unregister(id, location);
                bindingsChanged = true;
                continue;
            } else if (level.hasChunkAt(location.position())) {
                if (level.getBlockEntity(location.position()) instanceof ResonantSupplyAnchorBlockEntity anchor
                        && anchor.anchorId().equals(id)) {
                    owner = anchor.ownerId().map(player.getUUID()::equals).orElse(false);
                    publicAccess = anchor.publicAccess();
                    status = anchor.canUse(player) ? AnchorStatus.AVAILABLE : AnchorStatus.FORBIDDEN;
                } else {
                    // Heal an index entry whose block was removed by a path that did not leave a
                    // live matching block entity.  This also prevents later snapshots from
                    // continuing to advertise the obsolete world position.
                    index.unregister(id, location);
                    bindingsChanged = true;
                    continue;
                }
            }
            result.add(new Anchor(id, "Anchor " + (result.size() + 1), location.dimension().location(),
                    location.position().asLong(), status, owner, publicAccess));
        }
        if (bindingsChanged) {
            // Also removes entries whose index was invalidated while resolving loaded chunks.
            AnchorBindings.pruneMissing(scepter, index);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            if (player.inventoryMenu != player.containerMenu) {
                player.inventoryMenu.broadcastChanges();
            }
        }
        return result;
    }

    public static void encode(BuilderSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled);
        buffer.writeEnum(packet.mode);
        buffer.writeEnum(packet.surfaceMatch);
        buffer.writeBoolean(packet.historyRecording);
        buffer.writeVarInt(packet.buildLimit);
        buffer.writeVarInt(packet.demolishLimit);
        buffer.writeVarInt(packet.maximumBuildLimit);
        buffer.writeVarInt(packet.maximumDemolishLimit);
        buffer.writeVarInt(packet.reach);
        buffer.writeUtf(packet.status, 256);
        writeOptionalUuid(buffer, packet.activeJobId);
        buffer.writeVarInt(packet.completedBlocks);
        buffer.writeVarInt(packet.totalBlocks);
        buffer.writeBoolean(packet.canUndo);
        buffer.writeBoolean(packet.canRedo);

        buffer.writeVarInt(packet.anchors.size());
        for (Anchor anchor : packet.anchors) {
            buffer.writeUUID(anchor.id);
            buffer.writeUtf(anchor.name, 128);
            buffer.writeResourceLocation(anchor.dimension);
            buffer.writeLong(anchor.packedPos);
            buffer.writeEnum(anchor.status);
            buffer.writeBoolean(anchor.owner);
            buffer.writeBoolean(anchor.publicAccess);
        }

        buffer.writeVarInt(packet.history.size());
        for (History entry : packet.history) {
            buffer.writeUUID(entry.id);
            buffer.writeUtf(entry.label, 128);
            buffer.writeUtf(entry.dimension, 128);
            buffer.writeVarInt(entry.changedBlocks);
            buffer.writeLong(entry.createdAt);
            buffer.writeEnum(entry.status);
        }
    }

    public static BuilderSnapshotPacket decode(FriendlyByteBuf buffer) {
        boolean enabled = buffer.readBoolean();
        BuilderMode mode = buffer.readEnum(BuilderMode.class);
        SurfaceMatchMode match = buffer.readEnum(SurfaceMatchMode.class);
        boolean historyRecording = buffer.readBoolean();
        int buildLimit = buffer.readVarInt();
        int demolishLimit = buffer.readVarInt();
        int maximumBuildLimit = buffer.readVarInt();
        int maximumDemolishLimit = buffer.readVarInt();
        int reach = buffer.readVarInt();
        String status = buffer.readUtf(256);
        UUID activeJobId = readOptionalUuid(buffer);
        int completedBlocks = buffer.readVarInt();
        int totalBlocks = buffer.readVarInt();
        boolean canUndo = buffer.readBoolean();
        boolean canRedo = buffer.readBoolean();

        int anchorCount = boundedCount(buffer.readVarInt(), MAX_ANCHORS, "builder anchors");
        List<Anchor> anchors = new ArrayList<>(anchorCount);
        for (int i = 0; i < anchorCount; i++) {
            anchors.add(new Anchor(buffer.readUUID(), buffer.readUtf(128), buffer.readResourceLocation(),
                    buffer.readLong(), buffer.readEnum(AnchorStatus.class), buffer.readBoolean(),
                    buffer.readBoolean()));
        }

        int historyCount = boundedCount(buffer.readVarInt(), MAX_HISTORY, "builder history");
        List<History> history = new ArrayList<>(historyCount);
        for (int i = 0; i < historyCount; i++) {
            history.add(new History(buffer.readUUID(), buffer.readUtf(128), buffer.readUtf(128),
                    buffer.readVarInt(), buffer.readLong(), buffer.readEnum(HistoryStatus.class)));
        }
        return new BuilderSnapshotPacket(enabled, mode, match, historyRecording, buildLimit, demolishLimit,
                maximumBuildLimit, maximumDemolishLimit, reach, status, activeJobId,
                completedBlocks, totalBlocks, canUndo, canRedo, anchors, history);
    }

    public static void handle(BuilderSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BuilderClientPacketHooks.receiver().snapshot(packet));
        context.setPacketHandled(true);
    }

    private static int boundedCount(int value, int maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Invalid " + label + " count: " + value);
        }
        return value;
    }

    private static void writeOptionalUuid(FriendlyByteBuf buffer, @Nullable UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUUID(value);
    }

    @Nullable
    private static UUID readOptionalUuid(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUUID() : null;
    }

    public record Anchor(UUID id, String name, ResourceLocation dimension, long packedPos,
                         AnchorStatus status, boolean owner, boolean publicAccess) {
    }

    public enum AnchorStatus {
        AVAILABLE,
        UNLOADED,
        DISCONNECTED,
        FORBIDDEN,
        UNKNOWN
    }

    public record History(UUID id, String label, String dimension, int changedBlocks,
                          long createdAt, HistoryStatus status) {
        private static History from(BuilderTransaction transaction) {
            HistoryStatus status = switch (transaction.state()) {
                case PREPARED -> HistoryStatus.INCOMPLETE;
                case APPLIED -> HistoryStatus.COMPLETE;
                case UNDONE -> HistoryStatus.UNDONE;
                case CONFLICTED -> HistoryStatus.CONFLICTED;
            };
            return new History(transaction.id(), transaction.type().name(),
                    transaction.dimension().location().toString(), transaction.worldDeltas().size(),
                    transaction.createdAt(), status);
        }
    }

    public enum HistoryStatus {
        RUNNING,
        INCOMPLETE,
        COMPLETE,
        UNDONE,
        CONFLICTED
    }
}
