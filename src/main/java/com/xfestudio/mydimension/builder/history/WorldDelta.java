package com.xfestudio.mydimension.builder.history;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Objects;

public record WorldDelta(BlockPos pos, BlockState beforeState, @Nullable CompoundTag beforeBlockEntity,
                         BlockState afterState, @Nullable CompoundTag afterBlockEntity) {
    private static final String POS = "Pos";
    private static final String BEFORE = "Before";
    private static final String AFTER = "After";
    private static final String BEFORE_BE = "BeforeBlockEntity";
    private static final String AFTER_BE = "AfterBlockEntity";

    public static Snapshot snapshot(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        CompoundTag tag = blockEntity == null ? null : blockEntity.saveWithFullMetadata();
        return new Snapshot(level.getBlockState(pos), tag == null ? null : tag.copy());
    }

    public boolean matchesBefore(ServerLevel level) {
        return matches(level, beforeState, beforeBlockEntity);
    }

    public boolean matchesAfter(ServerLevel level) {
        return matches(level, afterState, afterBlockEntity);
    }

    public boolean restoreBefore(ServerLevel level) {
        return restore(level, beforeState, beforeBlockEntity);
    }

    public boolean restoreAfter(ServerLevel level) {
        return restore(level, afterState, afterBlockEntity);
    }

    private boolean matches(ServerLevel level, BlockState state, @Nullable CompoundTag blockEntityTag) {
        if (!level.getBlockState(pos).equals(state)) {
            return false;
        }
        BlockEntity current = level.getBlockEntity(pos);
        CompoundTag currentTag = current == null ? null : current.saveWithFullMetadata();
        return Objects.equals(currentTag, blockEntityTag);
    }

    private boolean restore(ServerLevel level, BlockState state, @Nullable CompoundTag blockEntityTag) {
        try {
            level.setBlock(pos, state, 3);
            if (!level.getBlockState(pos).equals(state)) return false;
            if (blockEntityTag == null) return level.getBlockEntity(pos) == null;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) return false;
            CompoundTag copy = blockEntityTag.copy();
            copy.putInt("x", pos.getX());
            copy.putInt("y", pos.getY());
            copy.putInt("z", pos.getZ());
            blockEntity.load(copy);
            blockEntity.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
            return matches(level, state, blockEntityTag);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(POS, pos.asLong());
        tag.put(BEFORE, NbtUtils.writeBlockState(beforeState));
        tag.put(AFTER, NbtUtils.writeBlockState(afterState));
        if (beforeBlockEntity != null) {
            tag.put(BEFORE_BE, beforeBlockEntity.copy());
        }
        if (afterBlockEntity != null) {
            tag.put(AFTER_BE, afterBlockEntity.copy());
        }
        return tag;
    }

    public static WorldDelta load(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong(POS));
        BlockState before = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(BEFORE));
        BlockState after = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(AFTER));
        CompoundTag beforeBe = tag.contains(BEFORE_BE, Tag.TAG_COMPOUND) ? tag.getCompound(BEFORE_BE) : null;
        CompoundTag afterBe = tag.contains(AFTER_BE, Tag.TAG_COMPOUND) ? tag.getCompound(AFTER_BE) : null;
        return new WorldDelta(pos, before, beforeBe, after, afterBe);
    }

    public record Snapshot(BlockState state, @Nullable CompoundTag blockEntity) {
    }
}
