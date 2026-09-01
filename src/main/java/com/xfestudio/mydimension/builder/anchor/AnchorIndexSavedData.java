package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Global UUID to dimension/position index for supply anchors. */
public final class AnchorIndexSavedData extends SavedData {
    private static final String DATA_NAME = MyDimension.MOD_ID + "_supply_anchors";
    private static final String ENTRIES_TAG = "Entries";
    private static final String ID_TAG = "Id";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String POSITION_TAG = "Position";
    private static final String FACING_TAG = "Facing";

    private final Map<UUID, AnchorLocation> locations = new HashMap<>();
    /** Transient reverse lookup used to wake only cross-chunk anchors targeting a loaded chunk. */
    private final Map<TargetChunk, Set<UUID>> anchorsByTargetChunk = new HashMap<>();

    public static AnchorIndexSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                AnchorIndexSavedData::load,
                AnchorIndexSavedData::new,
                DATA_NAME
        );
    }

    public static AnchorIndexSavedData load(CompoundTag root) {
        AnchorIndexSavedData data = new AnchorIndexSavedData();
        ListTag entries = root.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID(ID_TAG) || !entry.contains(DIMENSION_TAG, Tag.TAG_STRING)
                    || !entry.contains(POSITION_TAG, Tag.TAG_LONG)) {
                continue;
            }
            try {
                UUID id = entry.getUUID(ID_TAG);
                ResourceLocation dimensionId = new ResourceLocation(entry.getString(DIMENSION_TAG));
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
                BlockPos position = BlockPos.of(entry.getLong(POSITION_TAG));
                Direction facing = Direction.from3DDataValue(entry.getByte(FACING_TAG));
                AnchorLocation location = new AnchorLocation(dimension, position, facing);
                if (data.locations.putIfAbsent(id, location) == null) {
                    data.indexTargetChunk(id, location);
                }
            } catch (RuntimeException exception) {
                MyDimension.LOGGER.warn("Ignoring malformed supply anchor index entry", exception);
            }
        }
        return data;
    }

    /**
     * Claims an ID for a concrete location. A duplicate at another location is
     * assigned a fresh ID so copied BlockEntityTag data cannot alias an anchor.
     */
    public synchronized UUID claim(UUID requestedId, AnchorLocation location) {
        UUID claimed = requestedId == null ? unusedId() : requestedId;
        AnchorLocation existing = locations.get(claimed);
        if (existing != null && !existing.equals(location)) {
            claimed = unusedId();
        }
        AnchorLocation previous = locations.put(claimed, location);
        if (previous != null && !previous.equals(location)) {
            unindexTargetChunk(claimed, previous);
        }
        indexTargetChunk(claimed, location);
        if (!location.equals(previous)) {
            setDirty();
        }
        return claimed;
    }

    public synchronized void update(UUID anchorId, AnchorLocation location) {
        AnchorLocation previous = locations.put(anchorId, location);
        if (previous != null && !previous.equals(location)) {
            unindexTargetChunk(anchorId, previous);
        }
        indexTargetChunk(anchorId, location);
        if (!location.equals(previous)) {
            setDirty();
        }
    }

    public synchronized Optional<AnchorLocation> find(UUID anchorId) {
        return Optional.ofNullable(locations.get(anchorId));
    }

    public synchronized boolean unregister(UUID anchorId, AnchorLocation expectedLocation) {
        AnchorLocation existing = locations.get(anchorId);
        if (existing == null || !existing.equals(expectedLocation)) {
            return false;
        }
        locations.remove(anchorId);
        unindexTargetChunk(anchorId, existing);
        setDirty();
        return true;
    }

    public synchronized boolean remove(UUID anchorId) {
        AnchorLocation removed = locations.remove(anchorId);
        if (removed == null) {
            return false;
        }
        unindexTargetChunk(anchorId, removed);
        setDirty();
        return true;
    }

    /**
     * Returns only anchors whose adjacent container cell belongs to {@code targetChunk}.
     * This avoids scanning every block on chunk load and does not load an anchor chunk.
     */
    public synchronized List<AnchorLocation> findTargetingChunk(ResourceKey<Level> dimension,
                                                                 ChunkPos targetChunk) {
        Set<UUID> ids = anchorsByTargetChunk.get(new TargetChunk(dimension, targetChunk.toLong()));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(locations::get)
                .filter(location -> location != null)
                .distinct()
                .toList();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag root) {
        ListTag entries = new ListTag();
        locations.forEach((id, location) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(ID_TAG, id);
            entry.putString(DIMENSION_TAG, location.dimension().location().toString());
            entry.putLong(POSITION_TAG, location.position().asLong());
            entry.putByte(FACING_TAG, (byte) location.facing().get3DDataValue());
            entries.add(entry);
        });
        root.put(ENTRIES_TAG, entries);
        return root;
    }

    private UUID unusedId() {
        UUID candidate;
        do {
            candidate = UUID.randomUUID();
        } while (locations.containsKey(candidate));
        return candidate;
    }

    private void indexTargetChunk(UUID anchorId, AnchorLocation location) {
        anchorsByTargetChunk.computeIfAbsent(TargetChunk.of(location), ignored -> new HashSet<>()).add(anchorId);
    }

    private void unindexTargetChunk(UUID anchorId, AnchorLocation location) {
        TargetChunk key = TargetChunk.of(location);
        Set<UUID> ids = anchorsByTargetChunk.get(key);
        if (ids == null) {
            return;
        }
        ids.remove(anchorId);
        if (ids.isEmpty()) {
            anchorsByTargetChunk.remove(key);
        }
    }

    public record AnchorLocation(ResourceKey<Level> dimension, BlockPos position, Direction facing) {
        public AnchorLocation {
            position = position.immutable();
        }

        public BlockPos containerPosition() {
            return position.relative(facing);
        }

        public Direction containerSide() {
            return facing.getOpposite();
        }
    }

    private record TargetChunk(ResourceKey<Level> dimension, long chunkPosition) {
        private static TargetChunk of(AnchorLocation location) {
            return new TargetChunk(location.dimension(), new ChunkPos(location.containerPosition()).toLong());
        }
    }
}
