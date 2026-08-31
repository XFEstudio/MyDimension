package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import javax.annotation.Nullable;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.Registries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One persistent material-waiting task per player. */
public final class PendingBuildData extends SavedData {
    private static final String NAME = "mydimension_pending_builder_jobs";
    private final Map<UUID, Task> tasks = new HashMap<>();

    public static PendingBuildData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PendingBuildData::load,
                PendingBuildData::new, NAME);
    }

    public Task get(UUID player) {
        return tasks.get(player);
    }

    public void put(UUID player, Task task) {
        tasks.put(player, task);
        setDirty();
    }

    public void remove(UUID player) {
        if (tasks.remove(player) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag values = new ListTag();
        tasks.forEach((player, task) -> {
            CompoundTag tag = task.save();
            tag.putUUID("Player", player);
            values.add(tag);
        });
        root.put("Tasks", values);
        return root;
    }

    public static PendingBuildData load(CompoundTag root) {
        PendingBuildData result = new PendingBuildData();
        for (Tag value : root.getList("Tasks", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            if (tag.hasUUID("Player")) result.tasks.put(tag.getUUID("Player"), Task.load(tag));
        }
        return result;
    }

    public record Entry(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityTag) {
        public Entry {
            pos = pos.immutable();
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        public Entry(BlockPos pos, BlockState state) { this(pos, state, null); }
        @Override public CompoundTag blockEntityTag() {
            return blockEntityTag == null ? null : blockEntityTag.copy();
        }
    }

    public record Task(UUID scepterId, UUID transactionId, ResourceKey<Level> dimension,
                       BuilderTransaction.Type type, List<Entry> missing, long createdAt) {
        public Task {
            missing = List.copyOf(missing);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Scepter", scepterId);
            tag.putUUID("Transaction", transactionId);
            tag.putString("Dimension", dimension.location().toString());
            tag.putString("Type", type.name());
            tag.putLong("CreatedAt", createdAt);
            ListTag entries = new ListTag();
            for (Entry entry : missing) {
                CompoundTag item = new CompoundTag();
                item.putLong("Pos", entry.pos().asLong());
                item.put("State", NbtUtils.writeBlockState(entry.state()));
                if (entry.blockEntityTag() != null) item.put("BlockEntity", entry.blockEntityTag());
                entries.add(item);
            }
            tag.put("Missing", entries);
            return tag;
        }

        private static Task load(CompoundTag tag) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Dimension"));
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    id == null ? Level.OVERWORLD.location() : id);
            List<Entry> missing = new ArrayList<>();
            for (Tag value : tag.getList("Missing", Tag.TAG_COMPOUND)) {
                CompoundTag item = (CompoundTag) value;
                missing.add(new Entry(BlockPos.of(item.getLong("Pos")),
                        NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), item.getCompound("State")),
                        item.contains("BlockEntity", Tag.TAG_COMPOUND) ? item.getCompound("BlockEntity") : null));
            }
            BuilderTransaction.Type type;
            try { type = BuilderTransaction.Type.valueOf(tag.getString("Type")); }
            catch (IllegalArgumentException ignored) { type = BuilderTransaction.Type.BUILD; }
            return new Task(tag.hasUUID("Scepter") ? tag.getUUID("Scepter") : UUID.randomUUID(),
                    tag.hasUUID("Transaction") ? tag.getUUID("Transaction") : UUID.randomUUID(),
                    dimension, type, missing, tag.getLong("CreatedAt"));
        }
    }
}
