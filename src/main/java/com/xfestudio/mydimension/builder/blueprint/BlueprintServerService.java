package com.xfestudio.mydimension.builder.blueprint;

import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.builder.PendingBuildData;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.BuilderReachValidator;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.network.blueprint.BlueprintTransferBeginPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintTransferChunkPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintTransferEndPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintTransferResultPacket;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-owned blueprint transfer, validation, cache and whole-plan queue. */
public final class BlueprintServerService {
    private static final int CAPTURE_COOLDOWN_TICKS = 20;
    private static final int PLACE_COOLDOWN_TICKS = 5;
    private static final Map<MinecraftServer, BlueprintServerService> SERVICES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final BlueprintServerCache cache = new BlueprintServerCache();
    private final Map<UUID, UploadSession> uploads = new HashMap<>();
    private final Map<UUID, Queue<QueuedPlacement>> placementQueues = new HashMap<>();
    private final BlueprintSelectionStore selections = new BlueprintSelectionStore();
    private final Map<UUID, Long> lastCaptureTicks = new HashMap<>();
    private final Map<UUID, Long> lastPlaceTicks = new HashMap<>();

    private BlueprintServerService(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized BlueprintServerService get(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, BlueprintServerService::new);
    }

    public static synchronized void shutdown(MinecraftServer server) {
        BlueprintServerService service = SERVICES.remove(server);
        if (service != null) {
            service.pauseQueuedPlacements();
            service.clear();
        }
    }

    /**
     * Records the first corner at click time.  The completed capture packet is
     * intentionally not trusted to introduce a first corner of its own: doing
     * so would either force a long selection's old corner to remain in reach,
     * or let a modified client read an arbitrary loaded area.
     */
    public void beginSelection(ServerPlayer player, BlockPos first) {
        UUID playerId = player.getUUID();
        selections.clear(playerId);
        try {
            if (!BuilderConfig.isEnabled()
                    || !player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())) {
                throw new IllegalArgumentException("The Realmwright Scepter must be enabled and held");
            }
            requireBuildablePosition(player, first, "Blueprint selection corner");
            requireAirAnchorReachAndSight(player, first);
            selections.begin(playerId, first, player.level().dimension(),
                    RealmwrightData.id(player.getMainHandItem()));
        } catch (Exception exception) {
            player.displayClientMessage(Component.literal(safeMessage(exception)), false);
        }
    }

    public void capture(ServerPlayer player, UUID requestId, BlockPos first, BlockPos second,
                        BlueprintSaveMode mode, String name, boolean finishSelection) {
        if (!acquireCooldown(lastCaptureTicks, player.getUUID(), CAPTURE_COOLDOWN_TICKS)) {
            sendResult(player, requestId, false, null, "Blueprint capture is cooling down");
            return;
        }
        try {
            BlueprintSelectionStore.Selection selection = requireSelection(player, first, second);
            validateSelectionBounds(first, second);
            BlueprintSaveMode effectiveMode = mode;
            if (mode == BlueprintSaveMode.FULL && !mayUseFullData(player)) {
                effectiveMode = BlueprintSaveMode.BLOCKS_ONLY;
                player.displayClientMessage(Component.translatable(
                        "message.mydimension.builder.blueprint_downgraded"), false);
            }
            BlueprintData blueprint = BlueprintCapture.capture(player.serverLevel(), player, first, second,
                    effectiveMode, name);
            validateConfiguredLimits(blueprint);
            byte[] compressed = BlueprintIo.encode(blueprint,
                    BuilderConfig.MAX_BLUEPRINT_UNCOMPRESSED_BYTES.get(),
                    BuilderConfig.MAX_BLUEPRINT_COMPRESSED_BYTES.get());
            BlueprintServerCache.Entry entry = cache.put(player, blueprint, compressed, server.getTickCount());
            sendDownload(player, requestId, entry);
            if (selection.second() == null || finishSelection) {
                // Keep the validated pair while its source cuboid remains visible on the client. This lets
                // SAVE be used repeatedly for Save As / replace without asking the player to select both
                // corners again. The tiny selection lives until an actual lifecycle event clears it.
                selections.complete(player.getUUID(), selection, second);
            }
        } catch (Exception exception) {
            sendResult(player, requestId, false, null, safeMessage(exception));
        }
    }

    public void beginUpload(ServerPlayer player, UUID transferId, int byteLength, int chunkCount, byte[] sha256) {
        UUID playerId = player.getUUID();
        if (uploads.containsKey(playerId)) {
            sendResult(player, transferId, false, null, "A blueprint upload is already active");
            return;
        }
        try {
            if (byteLength > Math.min(BlueprintLimits.MAX_COMPRESSED_BYTES,
                    BuilderConfig.MAX_BLUEPRINT_COMPRESSED_BYTES.get())) {
                throw new IllegalArgumentException("Blueprint upload exceeds the configured compressed limit");
            }
            BlueprintTransferAssembler assembler = new BlueprintTransferAssembler(transferId, byteLength,
                    chunkCount, sha256, server.getTickCount());
            uploads.put(playerId, new UploadSession(assembler));
        } catch (IllegalArgumentException exception) {
            sendResult(player, transferId, false, null, exception.getMessage());
        }
    }

    public void acceptUploadChunk(ServerPlayer player, UUID transferId, int sequence, byte[] data) {
        UploadSession session = uploads.get(player.getUUID());
        if (session == null || !session.assembler().transferId().equals(transferId)) {
            sendResult(player, transferId, false, null, "No matching blueprint upload exists");
            return;
        }
        try {
            session.assembler().accept(sequence, data, server.getTickCount());
        } catch (IOException exception) {
            uploads.remove(player.getUUID());
            sendResult(player, transferId, false, null, exception.getMessage());
        }
    }

    public void finishUpload(ServerPlayer player, UUID transferId) {
        UploadSession session = uploads.remove(player.getUUID());
        if (session == null || !session.assembler().transferId().equals(transferId)) {
            sendResult(player, transferId, false, null, "No matching blueprint upload exists");
            return;
        }
        try {
            byte[] compressed = session.assembler().finish(server.getTickCount());
            BlueprintData blueprint = BlueprintIo.decode(compressed,
                    BuilderConfig.MAX_BLUEPRINT_COMPRESSED_BYTES.get(),
                    BuilderConfig.MAX_BLUEPRINT_UNCOMPRESSED_BYTES.get());
            validateConfiguredLimits(blueprint);
            BlueprintServerCache.Entry entry = cache.put(player, blueprint, compressed, server.getTickCount());
            sendResult(player, transferId, true, entry.token(), "Blueprint ready");
        } catch (Exception exception) {
            sendResult(player, transferId, false, null, safeMessage(exception));
        }
    }

    public void cancelUpload(ServerPlayer player, UUID transferId) {
        UploadSession session = uploads.get(player.getUUID());
        if (session != null && session.assembler().transferId().equals(transferId)) {
            uploads.remove(player.getUUID());
        }
    }

    public void requestPlacement(ServerPlayer player, UUID requestId, UUID cacheToken,
                                 BlockPos targetAnchor, BlueprintTransform transform) {
        if (!acquireCooldown(lastPlaceTicks, player.getUUID(), PLACE_COOLDOWN_TICKS)) {
            sendResult(player, requestId, false, null, "Blueprint placement is cooling down");
            return;
        }
        ItemStack scepter = player.getMainHandItem();
        if (!BuilderConfig.isEnabled() || !scepter.is(ModItems.REALMWRIGHT_SCEPTER.get())) {
            sendResult(player, requestId, false, null, "The Realmwright Scepter must be enabled and held");
            return;
        }
        UUID scepterId = RealmwrightData.id(scepter);
        boolean recordHistory = RealmwrightData.recordsHistory(scepter);
        ResourceKey<Level> dimension = player.level().dimension();
        if (hasWorkConflict(player)) {
            sendResult(player, requestId, false, null,
                    "Finish or cancel the active or material-waiting builder task first");
            return;
        }
        Optional<BlueprintServerCache.Entry> resolved = cache.resolve(player, cacheToken, server.getTickCount());
        if (resolved.isEmpty()) {
            sendResult(player, requestId, false, null, "Blueprint cache token is missing or expired");
            return;
        }
        BlueprintPlacementPlan plan;
        try {
            requireBuildablePosition(player, targetAnchor, "Blueprint anchor");
            requireAirAnchorReachAndSight(player, targetAnchor);
            plan = BlueprintPlacementPlan.create(resolved.get().blueprint(), transform, targetAnchor);
            if (!plan.fullDataAllowedFor(player)) {
                throw new IllegalArgumentException("Full-data blueprint placement is forbidden by the server policy");
            }
            validateDestination(player, plan);
        } catch (Exception exception) {
            sendResult(player, requestId, false, null, safeMessage(exception));
            return;
        }
        // Revalidate the identity-bearing execution context immediately before publication. All accesses
        // happen on the server thread, but keeping this check here prevents future asynchronous callers
        // from accidentally enqueueing a plan under a changed hand or dimension.
        if (!BuilderConfig.isEnabled() || !player.level().dimension().equals(dimension)
                || !player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())
                || !RealmwrightData.id(player.getMainHandItem()).equals(scepterId)
                || hasWorkConflict(player)) {
            sendResult(player, requestId, false, null, "The blueprint execution context changed");
            return;
        }
        Queue<QueuedPlacement> queue = placementQueues.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        if (queue.size() >= BlueprintLimits.MAX_QUEUED_PLANS_PER_PLAYER) {
            sendResult(player, requestId, false, null, "Blueprint placement queue is full");
            return;
        }
        queue.add(new QueuedPlacement(plan, dimension, scepterId, recordHistory));
        sendResult(player, requestId, true, cacheToken, "Blueprint queued");
    }

    /** Called by the builder transaction engine; each value represents one whole auto-queued blueprint. */
    public Optional<QueuedPlacement> pollPlacementPlan(ServerPlayer player, ItemStack scepter) {
        Queue<QueuedPlacement> queue = placementQueues.get(player.getUUID());
        if (queue == null) return Optional.empty();
        QueuedPlacement queued = queue.peek();
        if (queued == null || !BuilderConfig.isEnabled()
                || !scepter.is(ModItems.REALMWRIGHT_SCEPTER.get())
                || !queued.scepterId().equals(RealmwrightData.id(scepter))
                || !queued.dimension().equals(player.level().dimension())
                || PendingBuildData.get(server).get(player.getUUID()) != null
                || BlueprintTaskManager.get(server).hasActive(player.getUUID())) {
            return Optional.empty();
        }
        queue.remove();
        if (queue.isEmpty()) placementQueues.remove(player.getUUID());
        return Optional.of(queued);
    }

    /** Cancels all of this player's not-yet-started plans, including one bound in another dimension. */
    public boolean cancelQueuedPlacement(ServerPlayer player) {
        Queue<QueuedPlacement> removed = placementQueues.remove(player.getUUID());
        return removed != null && !removed.isEmpty();
    }

    public void tick() {
        int tick = server.getTickCount();
        Iterator<Map.Entry<UUID, UploadSession>> iterator = uploads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UploadSession> entry = iterator.next();
            if (entry.getValue().assembler().timedOut(tick)) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    sendResult(player, entry.getValue().assembler().transferId(), false, null,
                            "Blueprint upload timed out");
                }
                iterator.remove();
            }
        }
        cache.tick(tick);
    }

    public void removePlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        persistQueuedPlacement(player, placementQueues.get(playerId));
        uploads.remove(playerId);
        placementQueues.remove(playerId);
        selections.clear(playerId);
        lastCaptureTicks.remove(playerId);
        lastPlaceTicks.remove(playerId);
        cache.removePlayer(playerId);
    }

    private void pauseQueuedPlacements() {
        for (Map.Entry<UUID, Queue<QueuedPlacement>> entry : List.copyOf(placementQueues.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) persistQueuedPlacement(player, entry.getValue());
        }
    }

    private void persistQueuedPlacement(ServerPlayer player, Queue<QueuedPlacement> queue) {
        if (queue == null || queue.isEmpty()
                || PendingBuildData.get(server).get(player.getUUID()) != null) return;
        QueuedPlacement queued = queue.peek();
        List<PendingBuildData.Entry> remaining = queued.plan().blocks().stream()
                .map(block -> new PendingBuildData.Entry(block.worldPos(), block.state(), block.blockEntityTag()))
                .toList();
        PendingBuildData.get(server).put(player.getUUID(), new PendingBuildData.Task(queued.scepterId(),
                UUID.randomUUID(), queued.dimension(),
                com.xfestudio.mydimension.builder.history.BuilderTransaction.Type.BLUEPRINT,
                queued.recordHistory(), true, false, 0, remaining.size(), remaining,
                System.currentTimeMillis()));
    }

    public void clear() {
        uploads.clear();
        placementQueues.clear();
        selections.clearAll();
        lastCaptureTicks.clear();
        lastPlaceTicks.clear();
        cache.clear();
    }

    public void rejectRequest(ServerPlayer player, UUID requestId, String message) {
        UploadSession upload = uploads.get(player.getUUID());
        if (upload != null && upload.assembler().transferId().equals(requestId)) {
            uploads.remove(player.getUUID());
        }
        sendResult(player, requestId, false, null, message);
    }

    private static void requireAirAnchorReachAndSight(ServerPlayer player, BlockPos pos) {
        if (!BuilderReachValidator.canReach(player, pos)) {
            throw new IllegalArgumentException("Blueprint position is outside the allowed interaction distance");
        }
        if (!BuilderReachValidator.canReachAirAnchor(player, pos)) {
            throw new IllegalArgumentException("Blueprint position is not in the player's line of sight");
        }
    }

    public void clearSelection(UUID playerId) {
        selections.clear(playerId);
    }

    private BlueprintSelectionStore.Selection requireSelection(ServerPlayer player, BlockPos first,
                                                                BlockPos second) {
        BlueprintSelectionStore.Selection selection = selections.get(player.getUUID());
        if (selection == null) {
            throw new IllegalArgumentException("Blueprint selection start is missing");
        }
        if (!selection.first().equals(first)) {
            throw new IllegalArgumentException("Blueprint selection start does not match the validated corner");
        }
        if (!selection.dimension().equals(player.level().dimension())
                || !player.getMainHandItem().is(ModItems.REALMWRIGHT_SCEPTER.get())
                || !selection.scepterId().equals(RealmwrightData.id(player.getMainHandItem()))) {
            selections.clear(player.getUUID());
            throw new IllegalArgumentException("Blueprint selection tool or dimension changed");
        }
        requireBuildablePosition(player, first, "Blueprint selection corner");
        requireBuildablePosition(player, second, "Blueprint selection corner");
        if (selection.second() == null) {
            // Only the newly clicked endpoint must still be visible.  The first endpoint was verified by
            // beginSelection at its own click time and remains bound to this player, tool and dimension.
            requireAirAnchorReachAndSight(player, second);
        } else if (!selection.second().equals(second)) {
            throw new IllegalArgumentException("Blueprint selection end does not match the validated corner");
        }
        return selection;
    }

    private static void requireBuildablePosition(ServerPlayer player, BlockPos pos, String label) {
        if (player.serverLevel().isOutsideBuildHeight(pos)
                || !player.serverLevel().getWorldBorder().isWithinBounds(pos)) {
            throw new IllegalArgumentException(label + " is outside the buildable world");
        }
    }

    private static boolean mayUseFullData(ServerPlayer player) {
        return switch (BuilderConfig.fullBlockEntityPolicy()) {
            case NEVER -> false;
            case CREATIVE_ONLY -> player.isCreative();
            case OP_ONLY -> player.hasPermissions(2);
            case CREATIVE_OR_OP -> player.isCreative() || player.hasPermissions(2);
        };
    }

    private static void validateSelectionBounds(BlockPos first, BlockPos second) {
        long x = Math.abs((long) first.getX() - second.getX()) + 1L;
        long y = Math.abs((long) first.getY() - second.getY()) + 1L;
        long z = Math.abs((long) first.getZ() - second.getZ()) + 1L;
        long maxAxis = Math.min(BlueprintLimits.MAX_AXIS, BuilderConfig.MAX_BLUEPRINT_AXIS.get());
        long maxVolume = Math.min(BlueprintLimits.MAX_VOLUME, BuilderConfig.MAX_BLUEPRINT_VOLUME.get());
        if (x > maxAxis || y > maxAxis || z > maxAxis || x * y * z > maxVolume) {
            throw new IllegalArgumentException("Selection exceeds the configured blueprint limit");
        }
    }

    private static void validateConfiguredLimits(BlueprintData blueprint) {
        int maxAxis = Math.min(BlueprintLimits.MAX_AXIS, BuilderConfig.MAX_BLUEPRINT_AXIS.get());
        int maxVolume = Math.min(BlueprintLimits.MAX_VOLUME, BuilderConfig.MAX_BLUEPRINT_VOLUME.get());
        int maxBlocks = Math.min(BlueprintLimits.MAX_BLOCKS, BuilderConfig.MAX_BLUEPRINT_BLOCKS.get());
        if (blueprint.sizeX() > maxAxis || blueprint.sizeY() > maxAxis || blueprint.sizeZ() > maxAxis
                || blueprint.volume() > maxVolume || blueprint.blocks().size() > maxBlocks) {
            throw new IllegalArgumentException("Blueprint exceeds the configured content limit");
        }
    }

    private boolean hasWorkConflict(ServerPlayer player) {
        if (PendingBuildData.get(server).get(player.getUUID()) != null
                || BlueprintTaskManager.get(server).hasActive(player.getUUID())) {
            return true;
        }
        Queue<QueuedPlacement> queued = placementQueues.get(player.getUUID());
        return queued != null && !queued.isEmpty();
    }

    private boolean acquireCooldown(Map<UUID, Long> timestamps, UUID playerId, int minimumTicks) {
        long now = server.overworld().getGameTime();
        Long previous = timestamps.get(playerId);
        if (previous != null && now - previous < minimumTicks) return false;
        timestamps.put(playerId, now);
        return true;
    }

    private static void validateDestination(ServerPlayer player, BlueprintPlacementPlan plan) {
        for (BlueprintPlacementPlan.PlannedBlock block : plan.blocks()) {
            BlockPos pos = block.worldPos();
            if (player.serverLevel().isOutsideBuildHeight(pos)
                    || !player.serverLevel().getWorldBorder().isWithinBounds(pos)) {
                throw new IllegalArgumentException("Blueprint extends outside the buildable world");
            }
            // Chunks are leased one at a time by BlueprintTaskManager immediately before mutation.
        }
    }

    private static void sendDownload(ServerPlayer player, UUID transferId, BlueprintServerCache.Entry entry) {
        byte[] compressed = entry.compressed();
        int chunks = (compressed.length + BlueprintLimits.TRANSFER_CHUNK_BYTES - 1)
                / BlueprintLimits.TRANSFER_CHUNK_BYTES;
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BlueprintTransferBeginPacket(transferId, entry.token(), compressed.length, chunks, entry.sha256()));
        for (int sequence = 0; sequence < chunks; sequence++) {
            int offset = sequence * BlueprintLimits.TRANSFER_CHUNK_BYTES;
            int length = Math.min(BlueprintLimits.TRANSFER_CHUNK_BYTES, compressed.length - offset);
            byte[] data = java.util.Arrays.copyOfRange(compressed, offset, offset + length);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BlueprintTransferChunkPacket(transferId, sequence, data));
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BlueprintTransferEndPacket(transferId));
    }

    private static void sendResult(ServerPlayer player, UUID requestId, boolean success, UUID token, String message) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BlueprintTransferResultPacket(requestId, success, token, message));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        String safe = message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    private record UploadSession(BlueprintTransferAssembler assembler) {
    }

    /** Immutable binding captured when the server accepts a placement request. */
    public record QueuedPlacement(BlueprintPlacementPlan plan, ResourceKey<Level> dimension, UUID scepterId,
                                  boolean recordHistory) {
    }
}
