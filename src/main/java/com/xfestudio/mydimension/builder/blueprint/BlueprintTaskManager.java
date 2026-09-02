package com.xfestudio.mydimension.builder.blueprint;

import com.xfestudio.mydimension.builder.BuilderNetworkBridge;
import com.xfestudio.mydimension.builder.BuilderOperationManager;
import com.xfestudio.mydimension.builder.BuilderRuntime;
import com.xfestudio.mydimension.builder.PendingBuildData;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.anchor.TemporaryAnchorChunkLeases;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Rate-limited automatic blueprint construction queue. */
public final class BlueprintTaskManager {
    private static final Map<MinecraftServer, BlueprintTaskManager> INSTANCES = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, ActiveTask> active = new HashMap<>();

    private BlueprintTaskManager(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized BlueprintTaskManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, BlueprintTaskManager::new);
    }

    public static void resume(ServerPlayer player, ItemStack scepter, PendingBuildData.Task pending) {
        if (pending.type() != BuilderTransaction.Type.BLUEPRINT) return;
        BlueprintTaskManager manager = get(player.getServer());
        if (manager.hasActive(player.getUUID())
                || !pending.scepterId().equals(RealmwrightData.id(scepter))
                || !pending.dimension().equals(player.level().dimension())) {
            return;
        }
        List<BlueprintPlacementPlan.PlannedBlock> values = pending.missing().stream()
                .map(entry -> new BlueprintPlacementPlan.PlannedBlock(entry.pos(), entry.pos(), entry.state(),
                        entry.blockEntityTag())).toList();
        boolean requiresFullDataPermission = pending.missing().stream()
                .anyMatch(entry -> entry.blockEntityTag() != null);
        manager.active.put(player.getUUID(), new ActiveTask(pending.transactionId(),
                pending.scepterId(), pending.dimension(), values, new ArrayList<>(), 0,
                requiresFullDataPermission, pending.recordHistory(), pending.completed(), pending.total(),
                pending.soundPlayed()));
        PendingBuildData.get(player.getServer()).remove(player.getUUID());
    }

    public boolean hasActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public void tick() {
        if (!BuilderRuntime.settings().enabled()) {
            pauseAll();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) tickPlayer(player);
    }

    private void tickPlayer(ServerPlayer player) {
        ItemStack scepter = player.getMainHandItem();
        if (!scepter.is(ModItems.REALMWRIGHT_SCEPTER.get())) return;
        ActiveTask task = active.get(player.getUUID());
        if (task == null) {
            // A blueprint must never consume the single persistent slot used by another waiting build.
            if (PendingBuildData.get(server).get(player.getUUID()) != null) return;
            Optional<BlueprintServerService.QueuedPlacement> queued =
                    BlueprintServerService.get(server).pollPlacementPlan(player, scepter);
            if (queued.isEmpty()) return;
            BlueprintServerService.QueuedPlacement accepted = queued.get();
            BlueprintPlacementPlan plan = accepted.plan();
            task = new ActiveTask(UUID.randomUUID(), accepted.scepterId(), accepted.dimension(),
                    plan.blocks(), new ArrayList<>(), 0,
                    plan.blueprint().saveMode() == BlueprintSaveMode.FULL, accepted.recordHistory(),
                    0, plan.blocks().size(), false);
            active.put(player.getUUID(), task);
        }
        if (!task.scepterId.equals(RealmwrightData.id(scepter))
                || !task.dimension.equals(player.level().dimension())
                || task.requiresFullDataPermission && !mayUseFullData(player)
                || PendingBuildData.get(server).get(player.getUUID()) != null) return;

        int maximumEnd = workEnd(task.cursor, task.blocks.size(),
                RealmwrightData.buildLimit(scepter, BuilderRuntime.settings().maxBuildLimit()),
                BuilderRuntime.settings().maxBuildLimit());
        // The usual case (the whole current budget is already in server view) is
        // deliberately one executor call.  This scans every remote inventory once
        // and prepares/commits history once, even when the blueprint crosses several
        // chunk borders.  Off-screen work retains the per-chunk lease fallback below.
        if (task.cursor < maximumEnd
                && allTargetChunksLoaded(player.serverLevel(), task.blocks, task.cursor, maximumEnd)) {
            BuilderOperationManager.BlueprintBatchResult result = BuilderOperationManager.executeBlueprintBatch(
                    player, scepter, task.blocks.subList(task.cursor, maximumEnd), task.transactionId,
                    task.recordHistory);
            if (!acceptBatch(player, task, result)) return;
            task.cursor = maximumEnd;
        }
        while (task.cursor < maximumEnd) {
            ChunkPos targetChunk = new ChunkPos(task.blocks.get(task.cursor).worldPos());
            int end = nextChunkEnd(task.blocks, task.cursor, maximumEnd);

            TemporaryAnchorChunkLeases.Acquisition acquisition =
                    TemporaryAnchorChunkLeases.acquireTargetChunk(player, player.serverLevel(), targetChunk);
            if (!acquisition.acquired()) return;

            try (TemporaryAnchorChunkLeases.Lease ignored = acquisition.lease()) {
                BuilderOperationManager.BlueprintBatchResult result = BuilderOperationManager.executeBlueprintBatch(
                        player, scepter, task.blocks.subList(task.cursor, end), task.transactionId,
                        task.recordHistory);
                if (!acceptBatch(player, task, result)) return;
                task.cursor = end;
            }
        }
        if (task.cursor >= task.blocks.size()) finish(player, task);
    }

    /** Inclusive cursor/exclusive end for one server tick's configured scepter budget. */
    static int workEnd(int cursor, int size, int requestedBuildLimit, int serverHardLimit) {
        int budget = Math.max(1, Math.min(requestedBuildLimit, Math.max(1, serverHardLimit)));
        return (int) Math.min((long) size, (long) cursor + budget);
    }

    /** Keeps each temporary target-chunk lease scoped to one contiguous plan segment. */
    static int nextChunkEnd(List<BlueprintPlacementPlan.PlannedBlock> blocks, int cursor, int maximumEnd) {
        net.minecraft.core.BlockPos first = blocks.get(cursor).worldPos();
        int chunkX = first.getX() >> 4;
        int chunkZ = first.getZ() >> 4;
        int end = cursor + 1;
        while (end < maximumEnd) {
            net.minecraft.core.BlockPos next = blocks.get(end).worldPos();
            if ((next.getX() >> 4) != chunkX || (next.getZ() >> 4) != chunkZ) break;
            end++;
        }
        return end;
    }

    static boolean allTargetChunksLoaded(net.minecraft.server.level.ServerLevel level,
                                         List<BlueprintPlacementPlan.PlannedBlock> blocks,
                                         int cursor, int maximumEnd) {
        for (int index = cursor; index < maximumEnd; index++) {
            BlockPos position = blocks.get(index).worldPos();
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) return false;
        }
        return true;
    }

    private boolean acceptBatch(ServerPlayer player, ActiveTask task,
                                BuilderOperationManager.BlueprintBatchResult result) {
        if (!result.committed()) {
            // The executor has either compensated the entire unrecorded batch or left a
            // CONFLICTED marker for manual recovery. Never advance into the next batch.
            active.remove(player.getUUID());
            BuilderNetworkBridge.sync(player);
            return false;
        }
        if (!task.soundPlayed && result.sound() != null) {
            BuilderOperationManager.playOperationSound(player, result.sound());
            task.soundPlayed = true;
        }
        task.completed = accumulatedCompleted(task.completed, result.changed());
        task.missing.addAll(result.missing());
        return true;
    }

    private void finish(ServerPlayer player, ActiveTask task) {
        if (task.recordHistory) {
            com.xfestudio.mydimension.builder.history.BuilderHistoryData.get(server)
                    .refreshAppliedAfter(player.getUUID(), task.scepterId,
                            task.transactionId, player.serverLevel());
        }
        if (task.missing.isEmpty()) {
            active.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.mydimension.builder.blueprint_complete",
                    task.blocks.size()), true);
        } else {
            PendingBuildData pending = PendingBuildData.get(server);
            PendingBuildData.Task existing = pending.get(player.getUUID());
            if (existing != null && !existing.transactionId().equals(task.transactionId)) return;
            active.remove(player.getUUID());
            int completed = task.completed;
            int total = task.persistedTotal > 0 ? task.persistedTotal
                    : saturatedCount(completed, task.missing.size());
            pending.put(player.getUUID(), new PendingBuildData.Task(task.scepterId,
                    task.transactionId, task.dimension, BuilderTransaction.Type.BLUEPRINT,
                    task.recordHistory, true, task.soundPlayed, completed, total,
                    task.missing, System.currentTimeMillis()));
            player.displayClientMessage(Component.translatable("message.mydimension.builder.blueprint_waiting",
                    task.missing.size()), true);
        }
        // This is the authoritative end of the pending -> active hand-off. A completed task clears
        // the retained yellow preview; a still-missing task replaces it with the new remainder.
        BuilderNetworkBridge.sync(player);
    }

    public boolean cancel(ServerPlayer player, ItemStack scepter) {
        ActiveTask task = active.get(player.getUUID());
        boolean removed = task != null && task.scepterId.equals(RealmwrightData.id(scepter));
        if (removed) active.remove(player.getUUID());
        return removed;
    }

    public Status status(ServerPlayer player) {
        ActiveTask task = active.get(player.getUUID());
        if (task == null) return new Status(null, 0, 0, 0);
        return new Status(task.transactionId, task.completed, task.persistedTotal, task.missing.size());
    }

    public void pausePlayer(ServerPlayer player) {
        ActiveTask task = active.get(player.getUUID());
        if (task == null) return;
        PendingBuildData pending = PendingBuildData.get(server);
        PendingBuildData.Task existing = pending.get(player.getUUID());
        // Preserve the older task rather than silently overwriting it. Under the normal enqueue guards this
        // branch is unreachable, but it also protects against commands from other integrations mid-build.
        if (existing != null && !existing.transactionId().equals(task.transactionId)) return;
        List<PendingBuildData.Entry> remaining = new ArrayList<>(task.missing);
        for (int i = task.cursor; i < task.blocks.size(); i++) {
            BlueprintPlacementPlan.PlannedBlock block = task.blocks.get(i);
            remaining.add(new PendingBuildData.Entry(block.worldPos(), block.state(), block.blockEntityTag()));
        }
        active.remove(player.getUUID());
        int completed = task.completed;
        int total = task.persistedTotal > 0 ? task.persistedTotal
                : saturatedCount(completed, remaining.size());
        pending.put(player.getUUID(), new PendingBuildData.Task(task.scepterId,
                task.transactionId, task.dimension, BuilderTransaction.Type.BLUEPRINT,
                task.recordHistory, true,
                task.soundPlayed, completed, total, remaining, System.currentTimeMillis()));
        BuilderNetworkBridge.sync(player);
    }

    static int accumulatedCompleted(int completed, int changed) {
        return saturatedCount(completed, changed);
    }

    private static int saturatedCount(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, left) + Math.max(0, right));
    }

    private static boolean mayUseFullData(ServerPlayer player) {
        return switch (BuilderConfig.fullBlockEntityPolicy()) {
            case NEVER -> false;
            case CREATIVE_ONLY -> player.isCreative();
            case OP_ONLY -> player.hasPermissions(2);
            case CREATIVE_OR_OP -> player.isCreative() || player.hasPermissions(2);
        };
    }

    private void pauseAll() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) pausePlayer(player);
    }

    public static synchronized void shutdown(MinecraftServer server) {
        BlueprintTaskManager manager = INSTANCES.remove(server);
        if (manager != null) manager.pauseAll();
    }

    public record Status(UUID transactionId, int completed, int total, int missing) {
    }

    private static final class ActiveTask {
        private final UUID transactionId;
        private final UUID scepterId;
        private final ResourceKey<Level> dimension;
        private final List<BlueprintPlacementPlan.PlannedBlock> blocks;
        private final List<PendingBuildData.Entry> missing;
        private final boolean requiresFullDataPermission;
        private final boolean recordHistory;
        private final int persistedTotal;
        private int cursor;
        private int completed;
        private boolean soundPlayed;

        private ActiveTask(UUID transactionId, UUID scepterId, ResourceKey<Level> dimension,
                           List<BlueprintPlacementPlan.PlannedBlock> blocks,
                           List<PendingBuildData.Entry> missing, int cursor,
                           boolean requiresFullDataPermission, boolean recordHistory, int persistedCompleted,
                           int persistedTotal, boolean soundPlayed) {
            this.transactionId = transactionId;
            this.scepterId = scepterId;
            this.dimension = dimension;
            this.blocks = List.copyOf(blocks);
            this.missing = missing;
            this.cursor = cursor;
            this.requiresFullDataPermission = requiresFullDataPermission;
            this.recordHistory = recordHistory;
            this.completed = Math.max(0, persistedCompleted);
            this.persistedTotal = Math.max(this.completed, persistedTotal);
            this.soundPlayed = soundPlayed;
        }
    }
}
