package com.xfestudio.mydimension.builder.history;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public final class BuilderTransaction {
    public enum Type { BUILD, DEMOLISH, BLUEPRINT }
    public enum State { PREPARED, APPLIED, UNDONE, CONFLICTED }

    private final UUID id;
    private final UUID wandId;
    private final ResourceKey<Level> dimension;
    private final Type type;
    private final long createdAt;
    private final List<WorldDelta> worldDeltas;
    private final List<ItemStack> materialDebits;
    private final List<ItemStack> dropCredits;
    private final ItemStack offhandBefore;
    private final ItemStack offhandAfter;
    private final long reservedBytes;
    private transient long measuredBytes = -1L;
    private State state;

    public BuilderTransaction(UUID id, UUID wandId, ResourceKey<Level> dimension, Type type, long createdAt,
                              List<WorldDelta> worldDeltas, List<ItemStack> materialDebits,
                               List<ItemStack> dropCredits, ItemStack offhandBefore,
                               ItemStack offhandAfter, State state) {
        this(id, wandId, dimension, type, createdAt, worldDeltas, materialDebits, dropCredits,
                offhandBefore, offhandAfter, state, 0L);
    }

    private BuilderTransaction(UUID id, UUID wandId, ResourceKey<Level> dimension, Type type, long createdAt,
                               List<WorldDelta> worldDeltas, List<ItemStack> materialDebits,
                               List<ItemStack> dropCredits, ItemStack offhandBefore,
                               ItemStack offhandAfter, State state, long reservedBytes) {
        this(id, wandId, dimension, type, createdAt, worldDeltas, materialDebits, dropCredits,
                offhandBefore, offhandAfter, state, reservedBytes, false);
    }

    private BuilderTransaction(UUID id, UUID wandId, ResourceKey<Level> dimension, Type type, long createdAt,
                               List<WorldDelta> worldDeltas, List<ItemStack> materialDebits,
                               List<ItemStack> dropCredits, ItemStack offhandBefore,
                               ItemStack offhandAfter, State state, long reservedBytes,
                               boolean listsAlreadyOwned) {
        this.id = id;
        this.wandId = wandId;
        this.dimension = dimension;
        this.type = type;
        this.createdAt = createdAt;
        this.worldDeltas = List.copyOf(worldDeltas);
        this.materialDebits = listsAlreadyOwned ? List.copyOf(materialDebits) : copyStacks(materialDebits);
        this.dropCredits = listsAlreadyOwned ? List.copyOf(dropCredits) : copyStacks(dropCredits);
        this.offhandBefore = offhandBefore.copy();
        this.offhandAfter = offhandAfter.copy();
        this.state = state;
        this.reservedBytes = Math.max(0L, reservedBytes);
    }

    public static BuilderTransaction prepared(UUID id, UUID wandId, ResourceKey<Level> dimension, Type type,
                                              long createdAt, ItemStack offhand, long reservedBytes) {
        return new BuilderTransaction(id, wandId, dimension, type, createdAt, List.of(), List.of(), List.of(),
                offhand, offhand, State.PREPARED, reservedBytes);
    }

    public UUID id() { return id; }
    public UUID wandId() { return wandId; }
    public ResourceKey<Level> dimension() { return dimension; }
    public Type type() { return type; }
    public long createdAt() { return createdAt; }
    public List<WorldDelta> worldDeltas() { return worldDeltas; }
    public List<ItemStack> materialDebits() { return copyStacks(materialDebits); }
    public List<ItemStack> dropCredits() { return copyStacks(dropCredits); }
    public ItemStack offhandBefore() { return offhandBefore.copy(); }
    public ItemStack offhandAfter() { return offhandAfter.copy(); }
    public State state() { return state; }
    public void setState(State state) {
        this.state = state;
        this.measuredBytes = -1L;
    }

    /** Serialized-memory estimate used for both reservations and retained-history accounting. */
    public long estimatedSizeBytes() {
        // Multi-batch blueprints retain a conservative accumulated reservation. Avoid the O(n^2)
        // NBT serialization path that would otherwise re-encode every prior batch on each tick.
        if (reservedBytes > 0L) return reservedBytes;
        if (measuredBytes < 0L) {
            measuredBytes = Integer.toUnsignedLong(save().sizeInBytes());
        }
        return measuredBytes;
    }

    /** Small durable marker used when a reservation cannot be safely committed after side effects began. */
    public BuilderTransaction conflictMarker() {
        return new BuilderTransaction(id, wandId, dimension, type, createdAt, List.of(), List.of(), List.of(),
                offhandBefore, offhandAfter, State.CONFLICTED, 0L);
    }

    /** True only while the live world still equals the transaction's recorded applied image. */
    public boolean matchesAppliedAfter(ServerLevel level) {
        if (state != State.APPLIED || !dimension.equals(level.dimension())) return false;
        for (WorldDelta delta : worldDeltas) {
            if (!delta.matchesAfter(level)) return false;
        }
        return true;
    }

    /** Re-snapshots only the applied side, retaining the original before-images and material ledger. */
    public BuilderTransaction refreshAppliedAfter(ServerLevel level) {
        List<WorldDelta> refreshed = new ArrayList<>(worldDeltas.size());
        for (WorldDelta delta : worldDeltas) {
            WorldDelta.Snapshot after = WorldDelta.snapshot(level, delta.pos());
            refreshed.add(new WorldDelta(delta.pos(), delta.beforeState(), delta.beforeBlockEntity(),
                    after.state(), after.blockEntity()));
        }
        return new BuilderTransaction(id, wandId, dimension, type, createdAt, refreshed,
                materialDebits, dropCredits, offhandBefore, offhandAfter, state, 0L);
    }

    public BuilderTransaction append(BuilderTransaction continuation) {
        if (!id.equals(continuation.id) || !wandId.equals(continuation.wandId)
                || !dimension.equals(continuation.dimension) || type != continuation.type
                || state != State.APPLIED || continuation.state != State.APPLIED) {
            throw new IllegalArgumentException("Transaction continuation does not match");
        }
        LinkedHashMap<net.minecraft.core.BlockPos, WorldDelta> deltasByPosition = new LinkedHashMap<>();
        worldDeltas.forEach(delta -> mergeDelta(deltasByPosition, delta));
        continuation.worldDeltas.forEach(delta -> mergeDelta(deltasByPosition, delta));
        List<WorldDelta> deltas = List.copyOf(deltasByPosition.values());
        List<ItemStack> debits = new ArrayList<>(materialDebits);
        debits.addAll(continuation.materialDebits);
        List<ItemStack> credits = new ArrayList<>(dropCredits);
        credits.addAll(continuation.dropCredits);
        long combinedEstimate = saturatedAdd(estimatedSizeBytes(), continuation.estimatedSizeBytes());
        return new BuilderTransaction(id, wandId, dimension, type, createdAt, deltas, debits, credits,
                offhandBefore, continuation.offhandAfter, State.APPLIED, combinedEstimate, true);
    }

    /** Retains the first observed before-image and the last observed after-image for each position. */
    private static void mergeDelta(LinkedHashMap<net.minecraft.core.BlockPos, WorldDelta> deltas,
                                   WorldDelta next) {
        WorldDelta first = deltas.get(next.pos());
        if (first == null) {
            deltas.put(next.pos(), next);
            return;
        }
        deltas.put(next.pos(), new WorldDelta(next.pos(), first.beforeState(), first.beforeBlockEntity(),
                next.afterState(), next.afterBlockEntity()));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("WandId", wandId);
        tag.putString("Dimension", dimension.location().toString());
        tag.putString("Type", type.name());
        tag.putString("State", state.name());
        tag.putLong("CreatedAt", createdAt);
        if (reservedBytes > 0L) tag.putLong("ReservedBytes", reservedBytes);
        ListTag deltas = new ListTag();
        worldDeltas.forEach(delta -> deltas.add(delta.save()));
        tag.put("WorldDeltas", deltas);
        tag.put("MaterialDebits", saveStacks(materialDebits));
        tag.put("DropCredits", saveStacks(dropCredits));
        tag.put("OffhandBefore", offhandBefore.save(new CompoundTag()));
        tag.put("OffhandAfter", offhandAfter.save(new CompoundTag()));
        return tag;
    }

    public static BuilderTransaction load(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                dimensionId == null ? Level.OVERWORLD.location() : dimensionId);
        List<WorldDelta> deltas = new ArrayList<>();
        ListTag deltaTags = tag.getList("WorldDeltas", Tag.TAG_COMPOUND);
        for (Tag value : deltaTags) {
            deltas.add(WorldDelta.load((CompoundTag) value));
        }
        State loadedState = enumValue(State.class, tag.getString("State"), State.CONFLICTED);
        long reservedBytes = Math.max(0L, tag.getLong("ReservedBytes"));
        // A PREPARED record means the process stopped between reservation and commit.  Its world/inventory
        // side effects are unknowable, so never make it executable after a restart.
        if (loadedState == State.PREPARED) {
            loadedState = State.CONFLICTED;
            reservedBytes = 0L;
        }
        return new BuilderTransaction(
                tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
                tag.hasUUID("WandId") ? tag.getUUID("WandId") : UUID.randomUUID(),
                dimension,
                enumValue(Type.class, tag.getString("Type"), Type.BUILD),
                tag.getLong("CreatedAt"),
                deltas,
                loadStacks(tag.getList("MaterialDebits", Tag.TAG_COMPOUND)),
                loadStacks(tag.getList("DropCredits", Tag.TAG_COMPOUND)),
                ItemStack.of(tag.getCompound("OffhandBefore")),
                ItemStack.of(tag.getCompound("OffhandAfter")),
                loadedState,
                reservedBytes);
    }

    private static ListTag saveStacks(List<ItemStack> stacks) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                list.add(stack.save(new CompoundTag()));
            }
        }
        return list;
    }

    private static List<ItemStack> loadStacks(ListTag list) {
        List<ItemStack> result = new ArrayList<>();
        for (Tag value : list) {
            ItemStack stack = ItemStack.of((CompoundTag) value);
            if (!stack.isEmpty()) {
                result.add(stack);
            }
        }
        return result;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> values) {
        return values.stream().map(ItemStack::copy).toList();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }
}
