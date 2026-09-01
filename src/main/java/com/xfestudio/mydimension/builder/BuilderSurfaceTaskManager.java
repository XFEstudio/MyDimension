package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.history.BuilderHistoryData;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bounded server-tick executor for manually selected build/demolish surfaces.
 * A player can own at most one task, so rapid click packets are coalesced into
 * the already-running operation rather than forming an unbounded backlog.
 */
public final class BuilderSurfaceTaskManager {
    private static final int DEMOLISH_COST_UNITS = 4;
    private static final Map<MinecraftServer, BuilderSurfaceTaskManager> INSTANCES = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, ActiveTask> active = new HashMap<>();

    private BuilderSurfaceTaskManager(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized BuilderSurfaceTaskManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, BuilderSurfaceTaskManager::new);
    }

    public boolean hasActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public boolean start(ServerPlayer player, ItemStack scepter, BuilderMode mode,
                         List<SurfacePlanner.Candidate> candidates, Direction face) {
        return start(player, scepter, UUID.randomUUID(), mode, candidates, face);
    }

    public boolean start(ServerPlayer player, ItemStack scepter, UUID transactionId, BuilderMode mode,
                         List<SurfacePlanner.Candidate> candidates, Direction face) {
        UUID playerId = player.getUUID();
        if (candidates.isEmpty() || active.containsKey(playerId)) return false;
        BuilderTransaction.Type type = mode == BuilderMode.BUILD
                ? BuilderTransaction.Type.BUILD : BuilderTransaction.Type.DEMOLISH;
        if (!BuilderOperationManager.prepareSurfaceTask(player, scepter, transactionId, type,
                candidates, face)) {
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.history_budget_exceeded"), true);
            return false;
        }
        active.put(playerId, new ActiveTask(playerId, transactionId, RealmwrightData.id(scepter),
                player.level().dimension(), mode, candidates, face,
                scepter.copy(), BuilderOperationManager.beginSurfaceExecution(player)));
        return true;
    }

    public void tick() {
        if (!BuilderRuntime.settings().enabled()) {
            finishAll();
            return;
        }
        List<ServerPlayer> runnable = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ActiveTask task = active.get(player.getUUID());
            if (task != null && canRun(player, task)) runnable.add(player);
        }
        if (runnable.isEmpty()) return;

        int totalBudget = Math.max(1, BuilderRuntime.settings().editsPerTick());
        int baseShare = Math.max(1, totalBudget / runnable.size());
        int remaining = totalBudget;
        for (int index = 0; index < runnable.size() && remaining > 0; index++) {
            int playersLeft = runnable.size() - index;
            int share = Math.max(1, Math.min(remaining,
                    index == runnable.size() - 1 ? remaining : Math.max(baseShare, remaining / playersLeft)));
            ServerPlayer player = runnable.get(index);
            int used = tickPlayer(player, active.get(player.getUUID()), share);
            remaining -= Math.max(0, used);
        }
    }

    private int tickPlayer(ServerPlayer player, ActiveTask task, int budgetUnits) {
        if (task == null) return 0;
        if (task.cursor > 0 && !task.accumulator.matchesAfter(player.serverLevel())) {
            // Something outside this task changed an already completed slice. Stop before touching
            // more blocks and retain a non-executable conflict record; never widen the after-image
            // to include somebody else's work.
            task.worldConflict = true;
            finish(player, task);
            return 0;
        }
        int count = batchSize(task.mode, budgetUnits);
        int end = Math.min(task.candidates.size(), task.cursor + count);
        if (end <= task.cursor) return 0;
        List<SurfacePlanner.Candidate> batch = task.candidates.subList(task.cursor, end);
        BuilderOperationManager.SurfaceBatchResult result;
        try {
            result = BuilderOperationManager.executeSurfaceBatch(player, player.getMainHandItem(),
                    task.mode, batch, task.face, task.transactionId, task.accumulator);
        } catch (RuntimeException exception) {
            com.xfestudio.mydimension.MyDimension.LOGGER.warn(
                    "Builder surface task {} stopped while finalizing a bounded slice",
                    task.transactionId, exception);
            task.worldConflict = true;
            finish(player, task);
            return 0;
        }
        task.changed += result.changed();
        task.blocked += result.blocked();
        task.missing.addAll(result.missing());
        task.expectedOffhand = player.getOffhandItem().copy();
        task.cursor = end;
        if (task.cursor >= task.candidates.size()) finish(player, task);
        return budgetCost(task.mode, batch.size());
    }

    private boolean canRun(ServerPlayer player, ActiveTask task) {
        ItemStack scepter = player.getMainHandItem();
        return scepter.is(ModItems.REALMWRIGHT_SCEPTER.get())
                && task.scepterId.equals(RealmwrightData.id(scepter))
                && task.dimension.equals(player.level().dimension())
                && offhandMatches(task.expectedOffhand, player.getOffhandItem())
                && PendingBuildData.get(server).get(player.getUUID()) == null
                && !com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(server)
                .hasActive(player.getUUID());
    }

    private void finish(ServerPlayer player, ActiveTask task) {
        active.remove(player.getUUID());
        if (!settle(player, task)) {
            BuilderNetworkBridge.sync(player);
            return;
        }
        if (task.worldConflict) {
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.surface_world_conflict"), true);
            BuilderNetworkBridge.sync(player);
            return;
        }
        if (!task.missing.isEmpty()) {
            PendingBuildData.get(server).put(player.getUUID(), new PendingBuildData.Task(task.scepterId,
                    task.transactionId, task.dimension, task.mode == BuilderMode.BUILD
                    ? BuilderTransaction.Type.BUILD : BuilderTransaction.Type.DEMOLISH,
                    task.missing, System.currentTimeMillis()));
        }
        player.displayClientMessage(Component.translatable("message.mydimension.builder.result",
                task.changed, task.missing.size(), task.blocked), true);
        BuilderNetworkBridge.sync(player);
    }

    public boolean cancel(ServerPlayer player, ItemStack scepter) {
        ActiveTask task = active.get(player.getUUID());
        if (task == null || !task.scepterId.equals(RealmwrightData.id(scepter))) return false;
        active.remove(player.getUUID());
        settle(player, task);
        return true;
    }

    public boolean cancel(ServerPlayer player, ItemStack scepter, UUID transactionId) {
        ActiveTask task = active.get(player.getUUID());
        if (task == null || !task.transactionId.equals(transactionId)
                || !task.scepterId.equals(RealmwrightData.id(scepter))) return false;
        active.remove(player.getUUID());
        settle(player, task);
        return true;
    }

    public Status status(ServerPlayer player) {
        ActiveTask task = active.get(player.getUUID());
        if (task == null) return new Status(null, null, 0, 0, 0);
        return new Status(task.transactionId, task.mode, task.cursor,
                task.candidates.size(), task.missing.size());
    }

    public void removePlayer(ServerPlayer player) {
        ActiveTask task = active.remove(player.getUUID());
        if (task != null) settle(player, task);
    }

    private void finishAll() {
        for (ActiveTask task : List.copyOf(active.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(task.playerId);
            if (player != null) settle(player, task);
            // With no live player, retain PREPARED in SavedData. Restart recovery will expose a
            // conflict marker rather than silently making partially changed blocks non-undoable.
        }
        active.clear();
    }

    private boolean settle(ServerPlayer player, ActiveTask task) {
        if (!task.prepared) return task.committed;
        ServerLevel executionLevel = server.getLevel(task.dimension);
        if (executionLevel == null) {
            // Keep PREPARED durable. Loading it after restart produces a conflict marker instead of
            // ever committing or compensating these coordinates in the player's current dimension.
            return false;
        }
        if (!task.worldConflict && !task.accumulator.matchesAfter(executionLevel)) {
            task.worldConflict = true;
        }
        BuilderTransaction.Type type = task.mode == BuilderMode.BUILD
                ? BuilderTransaction.Type.BUILD : BuilderTransaction.Type.DEMOLISH;
        ItemStack held = player.getMainHandItem();
        ItemStack settlementScepter = held.is(ModItems.REALMWRIGHT_SCEPTER.get())
                && task.scepterId.equals(RealmwrightData.id(held)) ? held : task.scepterSnapshot;
        task.committed = BuilderOperationManager.commitSurfaceTask(player, settlementScepter, type,
                task.transactionId, task.accumulator, executionLevel, task.worldConflict);
        task.prepared = false;
        if (task.committed && task.worldConflict) {
            BuilderHistoryData.get(server).conflictApplied(task.playerId(), task.scepterId,
                    task.transactionId);
        }
        return task.committed;
    }

    public static synchronized void shutdown(MinecraftServer server) {
        BuilderSurfaceTaskManager manager = INSTANCES.remove(server);
        if (manager != null) manager.finishAll();
    }

    static int batchSize(BuilderMode mode, int editsPerTick) {
        int budget = Math.max(1, editsPerTick);
        return mode == BuilderMode.DEMOLISH ? Math.max(1, budget / DEMOLISH_COST_UNITS) : budget;
    }

    static int budgetCost(BuilderMode mode, int blocks) {
        int count = Math.max(0, blocks);
        return mode == BuilderMode.DEMOLISH ? saturatedMultiply(count, DEMOLISH_COST_UNITS) : count;
    }

    static boolean offhandMatches(ItemStack expected, ItemStack actual) {
        return ItemStack.matches(expected, actual);
    }

    private static int saturatedMultiply(int value, int multiplier) {
        long result = (long) value * multiplier;
        return (int) Math.min(Integer.MAX_VALUE, result);
    }

    public record Status(UUID transactionId, BuilderMode mode, int completed, int total, int missing) {
    }

    private static final class ActiveTask {
        private final UUID playerId;
        private final UUID transactionId;
        private final UUID scepterId;
        private final ResourceKey<Level> dimension;
        private final BuilderMode mode;
        private final List<SurfacePlanner.Candidate> candidates;
        private final Direction face;
        private final List<PendingBuildData.Entry> missing = new ArrayList<>();
        private boolean prepared = true;
        private boolean committed;
        private final ItemStack scepterSnapshot;
        private final BuilderOperationManager.SurfaceAccumulator accumulator;
        private ItemStack expectedOffhand;
        private int cursor;
        private int changed;
        private int blocked;
        private boolean worldConflict;

        private ActiveTask(UUID playerId, UUID transactionId, UUID scepterId, ResourceKey<Level> dimension,
                           BuilderMode mode, List<SurfacePlanner.Candidate> candidates,
                           Direction face, ItemStack scepterSnapshot,
                           BuilderOperationManager.SurfaceAccumulator accumulator) {
            this.playerId = playerId;
            this.transactionId = transactionId;
            this.scepterId = scepterId;
            this.dimension = dimension;
            this.mode = mode;
            this.candidates = List.copyOf(candidates);
            this.face = face;
            this.scepterSnapshot = scepterSnapshot;
            this.accumulator = accumulator;
            this.expectedOffhand = accumulator.initialOffhand();
        }

        private UUID playerId() {
            return playerId;
        }
    }
}
