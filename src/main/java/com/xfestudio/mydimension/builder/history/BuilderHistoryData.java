package com.xfestudio.mydimension.builder.history;

import com.xfestudio.mydimension.builder.BuilderRuntime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BuilderHistoryData extends SavedData {
    private static final String DATA_NAME = "mydimension_builder_history";
    private static final int DEFAULT_DEPTH = 20;

    private final Map<HistoryKey, History> histories = new HashMap<>();

    public static BuilderHistoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                BuilderHistoryData::load, BuilderHistoryData::new, DATA_NAME);
    }

    /**
     * Installs a durable reservation before inventory or world state is touched.  Reservations count against
     * both byte budgets; a same-id continuation also counts the already retained prefix against the single
     * transaction limit.
     */
    public PrepareResult prepare(UUID playerId, UUID wandId, UUID transactionId,
                                 ResourceKey<Level> dimension, BuilderTransaction.Type type,
                                 ItemStack offhand, long estimatedBytes) {
        long reservation = Math.max(1L, estimatedBytes);
        long transactionLimit = Math.max(0L, BuilderRuntime.settings().maxTransactionBytes());
        long playerLimit = Math.max(0L, BuilderRuntime.settings().maxHistoryBytesPerPlayer());
        if (reservation > transactionLimit) return PrepareResult.TRANSACTION_TOO_LARGE;
        if (reservation > playerLimit) return PrepareResult.PLAYER_BUDGET_EXCEEDED;

        History history = histories.computeIfAbsent(new HistoryKey(playerId, wandId), ignored -> new History());
        if (history.prepared != null) return PrepareResult.ALREADY_PREPARED;
        BuilderTransaction continuation = history.undo.peekLast();
        if (continuation != null && continuation.id().equals(transactionId)) {
            if (continuation.state() != BuilderTransaction.State.APPLIED
                    || saturatedAdd(continuation.estimatedSizeBytes(), reservation) > transactionLimit) {
                return PrepareResult.TRANSACTION_TOO_LARGE;
            }
            if (saturatedAdd(continuation.estimatedSizeBytes(), reservation) > playerLimit) {
                return PrepareResult.PLAYER_BUDGET_EXCEEDED;
            }
        } else {
            continuation = null;
        }

        if (!trimPlayerToFit(playerId, reservation, playerLimit, continuation)) {
            // The fit attempt may already have evicted older entries.
            setDirty();
            return PrepareResult.PLAYER_BUDGET_EXCEEDED;
        }
        history.prepared = BuilderTransaction.prepared(transactionId, wandId, dimension, type,
                System.currentTimeMillis(), offhand, reservation);
        setDirty();
        return PrepareResult.PREPARED;
    }

    /** Replaces the matching PREPARED reservation with the complete APPLIED ledger. */
    public boolean commitPrepared(UUID playerId, UUID wandId, BuilderTransaction transaction,
                                  int maximumDepth) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history == null || history.prepared == null
                || !history.prepared.id().equals(transaction.id())) return false;

        BuilderTransaction latest = history.undo.peekLast();
        BuilderTransaction combined;
        try {
            combined = latest != null && latest.id().equals(transaction.id())
                    ? latest.append(transaction) : transaction;
        } catch (IllegalArgumentException exception) {
            failPrepared(history);
            setDirty();
            return false;
        }
        long transactionLimit = Math.max(0L, BuilderRuntime.settings().maxTransactionBytes());
        long playerLimit = Math.max(0L, BuilderRuntime.settings().maxHistoryBytesPerPlayer());
        long preparedBytes = history.prepared.estimatedSizeBytes();
        long latestBytes = latest != null && latest.id().equals(transaction.id())
                ? latest.estimatedSizeBytes() : 0L;
        long combinedBytes = combined.estimatedSizeBytes();
        long additional = Math.max(0L, combinedBytes - preparedBytes - latestBytes);
        if (combinedBytes > transactionLimit
                || !trimPlayerToFit(playerId, additional, playerLimit, latestBytes == 0L ? null : latest)) {
            failPrepared(history);
            setDirty();
            return false;
        }

        history.prepared = null;
        if (latestBytes != 0L) history.undo.removeLast();
        history.undo.addLast(combined);
        history.redo.clear();
        trim(history.undo, Math.max(1, maximumDepth));
        trimPlayerToFit(playerId, 0L, playerLimit, combined);
        setDirty();
        return true;
    }

    public void abortPrepared(UUID playerId, UUID wandId, UUID transactionId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history != null && history.prepared != null && history.prepared.id().equals(transactionId)) {
            history.prepared = null;
            setDirty();
        }
    }

    public void conflictPrepared(UUID playerId, UUID wandId, UUID transactionId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history != null && history.prepared != null && history.prepared.id().equals(transactionId)) {
            failPrepared(history);
            setDirty();
        }
    }

    /** Marks the just-committed operation non-executable when its world image changed mid-task. */
    public boolean conflictApplied(UUID playerId, UUID wandId, UUID transactionId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        BuilderTransaction latest = history == null ? null : history.undo.peekLast();
        if (latest == null || !latest.id().equals(transactionId)
                || latest.state() != BuilderTransaction.State.APPLIED) return false;
        latest.setState(BuilderTransaction.State.CONFLICTED);
        setDirty();
        return true;
    }

    /** Removes only the empty marker produced by a failed commit after the caller fully rolled back. */
    public void discardConflictMarker(UUID playerId, UUID wandId, UUID transactionId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        BuilderTransaction latest = history == null ? null : history.undo.peekLast();
        if (latest != null && latest.id().equals(transactionId)
                && latest.state() == BuilderTransaction.State.CONFLICTED
                && latest.worldDeltas().isEmpty()) {
            history.undo.removeLast();
            setDirty();
        }
    }

    /**
     * Refreshes the final world image after a multi-batch task has settled its neighbour updates.  A refresh
     * that would violate either byte cap is conservatively made non-executable instead of widening history.
     */
    public boolean refreshAppliedAfter(UUID playerId, UUID wandId, UUID transactionId, ServerLevel level) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        BuilderTransaction current = history == null ? null : history.undo.peekLast();
        if (current == null || !current.id().equals(transactionId)
                || current.state() != BuilderTransaction.State.APPLIED
                || !current.dimension().equals(level.dimension())) return false;
        BuilderTransaction refreshed;
        try {
            refreshed = current.refreshAppliedAfter(level);
        } catch (RuntimeException exception) {
            current.setState(BuilderTransaction.State.CONFLICTED);
            setDirty();
            return false;
        }
        long transactionLimit = Math.max(0L, BuilderRuntime.settings().maxTransactionBytes());
        long playerLimit = Math.max(0L, BuilderRuntime.settings().maxHistoryBytesPerPlayer());
        long additional = Math.max(0L, refreshed.estimatedSizeBytes() - current.estimatedSizeBytes());
        if (refreshed.estimatedSizeBytes() > transactionLimit
                || !trimPlayerToFit(playerId, additional, playerLimit, current)) {
            current.setState(BuilderTransaction.State.CONFLICTED);
            setDirty();
            return false;
        }
        history.undo.removeLast();
        history.undo.addLast(refreshed);
        setDirty();
        return true;
    }

    public void push(UUID playerId, UUID wandId, BuilderTransaction transaction, int maximumDepth) {
        History history = histories.computeIfAbsent(new HistoryKey(playerId, wandId), ignored -> new History());
        BuilderTransaction latest = history.undo.peekLast();
        if (latest != null && latest.id().equals(transaction.id())) {
            history.undo.removeLast();
            history.undo.addLast(latest.append(transaction));
        } else {
            history.undo.addLast(transaction);
        }
        history.redo.clear();
        int max = Math.max(1, maximumDepth);
        while (history.undo.size() > max) {
            history.undo.removeFirst();
        }
        trimPlayerToFit(playerId, 0L,
                Math.max(0L, BuilderRuntime.settings().maxHistoryBytesPerPlayer()), transaction);
        setDirty();
    }

    public BuilderTransaction peekUndo(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        return history == null ? null : history.undo.peekLast();
    }

    public BuilderTransaction peekRedo(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        return history == null ? null : history.redo.peekLast();
    }

    public void markUndone(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history == null || history.undo.isEmpty()) {
            return;
        }
        BuilderTransaction transaction = history.undo.removeLast();
        transaction.setState(BuilderTransaction.State.UNDONE);
        history.redo.addLast(transaction);
        setDirty();
    }

    public void markRedone(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history == null || history.redo.isEmpty()) {
            return;
        }
        BuilderTransaction transaction = history.redo.removeLast();
        transaction.setState(BuilderTransaction.State.APPLIED);
        history.undo.addLast(transaction);
        setDirty();
    }

    public int undoCount(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        return history == null ? 0 : history.undo.size();
    }

    public int redoCount(UUID playerId, UUID wandId) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        return history == null ? 0 : history.redo.size();
    }

    /** Immutable newest-first view used by the history page; undo and redo stacks remain authoritative. */
    public List<BuilderTransaction> recent(UUID playerId, UUID wandId, int maximum) {
        History history = histories.get(new HistoryKey(playerId, wandId));
        if (history == null || maximum <= 0) return List.of();
        List<BuilderTransaction> result = new ArrayList<>();
        if (history.prepared != null) result.add(history.prepared);
        history.undo.descendingIterator().forEachRemaining(result::add);
        history.redo.descendingIterator().forEachRemaining(result::add);
        result.sort(java.util.Comparator.comparingLong(BuilderTransaction::createdAt).reversed()
                .thenComparing(BuilderTransaction::id));
        return List.copyOf(result.subList(0, Math.min(maximum, result.size())));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag historiesTag = new ListTag();
        for (Map.Entry<HistoryKey, History> entry : histories.entrySet()) {
            CompoundTag historyTag = new CompoundTag();
            historyTag.putUUID("Player", entry.getKey().playerId());
            historyTag.putUUID("Wand", entry.getKey().wandId());
            historyTag.put("Undo", saveTransactions(entry.getValue().undo));
            historyTag.put("Redo", saveTransactions(entry.getValue().redo));
            if (entry.getValue().prepared != null) {
                historyTag.put("Prepared", entry.getValue().prepared.save());
            }
            historiesTag.add(historyTag);
        }
        tag.put("Histories", historiesTag);
        return tag;
    }

    public static BuilderHistoryData load(CompoundTag tag) {
        BuilderHistoryData data = new BuilderHistoryData();
        ListTag historiesTag = tag.getList("Histories", Tag.TAG_COMPOUND);
        for (Tag value : historiesTag) {
            CompoundTag historyTag = (CompoundTag) value;
            if (!historyTag.hasUUID("Player") || !historyTag.hasUUID("Wand")) {
                continue;
            }
            History history = new History();
            history.undo.addAll(loadTransactions(historyTag.getList("Undo", Tag.TAG_COMPOUND)));
            history.redo.addAll(loadTransactions(historyTag.getList("Redo", Tag.TAG_COMPOUND)));
            if (historyTag.contains("Prepared", Tag.TAG_COMPOUND)) {
                // BuilderTransaction.load converts PREPARED to a non-executable conflict marker.
                history.undo.addLast(BuilderTransaction.load(historyTag.getCompound("Prepared")));
            }
            int depth = Math.max(1, BuilderRuntime.settings().undoDepth());
            trim(history.undo, depth);
            trim(history.redo, depth);
            data.histories.put(new HistoryKey(historyTag.getUUID("Player"), historyTag.getUUID("Wand")), history);
        }
        long transactionLimit = Math.max(0L, BuilderRuntime.settings().maxTransactionBytes());
        data.histories.values().forEach(history -> {
            history.undo.removeIf(transaction -> transaction.estimatedSizeBytes() > transactionLimit);
            history.redo.removeIf(transaction -> transaction.estimatedSizeBytes() > transactionLimit);
        });
        data.histories.keySet().stream().map(HistoryKey::playerId).distinct().toList().forEach(playerId ->
                data.trimPlayerToFit(playerId, 0L,
                        Math.max(0L, BuilderRuntime.settings().maxHistoryBytesPerPlayer()), null));
        data.setDirty();
        return data;
    }

    private static ListTag saveTransactions(Deque<BuilderTransaction> transactions) {
        ListTag list = new ListTag();
        transactions.forEach(transaction -> list.add(transaction.save()));
        return list;
    }

    private static List<BuilderTransaction> loadTransactions(ListTag list) {
        List<BuilderTransaction> result = new ArrayList<>();
        for (Tag value : list) {
            result.add(BuilderTransaction.load((CompoundTag) value));
        }
        return result;
    }

    private static <T> void trim(Deque<T> deque, int maximum) {
        while (deque.size() > maximum) {
            deque.removeFirst();
        }
    }

    private boolean trimPlayerToFit(UUID playerId, long additionalBytes, long maximumBytes,
                                    BuilderTransaction protectedTransaction) {
        while (saturatedAdd(playerBytes(playerId), additionalBytes) > maximumBytes) {
            Eviction eviction = oldestEvictable(playerId, protectedTransaction);
            if (eviction == null) return false;
            if (eviction.undo) eviction.history.undo.removeFirst();
            else eviction.history.redo.removeFirst();
        }
        return true;
    }

    private long playerBytes(UUID playerId) {
        long total = 0L;
        for (Map.Entry<HistoryKey, History> entry : histories.entrySet()) {
            if (!entry.getKey().playerId().equals(playerId)) continue;
            for (BuilderTransaction transaction : entry.getValue().undo) {
                total = saturatedAdd(total, transaction.estimatedSizeBytes());
            }
            for (BuilderTransaction transaction : entry.getValue().redo) {
                total = saturatedAdd(total, transaction.estimatedSizeBytes());
            }
            if (entry.getValue().prepared != null) {
                total = saturatedAdd(total, entry.getValue().prepared.estimatedSizeBytes());
            }
        }
        return total;
    }

    private Eviction oldestEvictable(UUID playerId, BuilderTransaction protectedTransaction) {
        Eviction oldest = null;
        for (Map.Entry<HistoryKey, History> entry : histories.entrySet()) {
            if (!entry.getKey().playerId().equals(playerId)) continue;
            BuilderTransaction undo = entry.getValue().undo.peekFirst();
            if (undo != null && undo != protectedTransaction
                    && (oldest == null || undo.createdAt() < oldest.transaction.createdAt())) {
                oldest = new Eviction(entry.getValue(), undo, true);
            }
            BuilderTransaction redo = entry.getValue().redo.peekFirst();
            if (redo != null && redo != protectedTransaction
                    && (oldest == null || redo.createdAt() < oldest.transaction.createdAt())) {
                oldest = new Eviction(entry.getValue(), redo, false);
            }
        }
        return oldest;
    }

    private static void failPrepared(History history) {
        BuilderTransaction marker = history.prepared.conflictMarker();
        history.prepared = null;
        history.undo.addLast(marker);
        history.redo.clear();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    public enum PrepareResult {
        PREPARED,
        ALREADY_PREPARED,
        TRANSACTION_TOO_LARGE,
        PLAYER_BUDGET_EXCEEDED
    }

    private record HistoryKey(UUID playerId, UUID wandId) {
    }

    private static final class History {
        private final Deque<BuilderTransaction> undo = new ArrayDeque<>();
        private final Deque<BuilderTransaction> redo = new ArrayDeque<>();
        private BuilderTransaction prepared;
    }

    private record Eviction(History history, BuilderTransaction transaction, boolean undo) { }
}
