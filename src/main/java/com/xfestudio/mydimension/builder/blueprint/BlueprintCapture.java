package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Authoritative server-side selection capture. The first selected point is the saved anchor. */
public final class BlueprintCapture {
    private BlueprintCapture() {
    }

    public static BlueprintData capture(ServerLevel level, ServerPlayer player, BlockPos first, BlockPos second,
                                        BlueprintSaveMode mode, String requestedName) {
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
        int sizeX = max.getX() - min.getX() + 1;
        int sizeY = max.getY() - min.getY() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;
        requireLoaded(level, min, max);

        List<CapturedBlock> captured = new ArrayList<>();
        Set<BlockState> paletteSet = new HashSet<>();
        int blockEntityBytes = 0;
        for (BlockPos cursor : positionsBetweenClosed(min, max)) {
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) continue;
            BlockPos worldPos = cursor.immutable();
            CompoundTag blockEntityTag = null;
            if (mode == BlueprintSaveMode.FULL) {
                BlockEntity blockEntity = level.getBlockEntity(worldPos);
                if (blockEntity != null) {
                    blockEntityTag = blockEntity.saveWithId();
                    blockEntityTag.remove("x");
                    blockEntityTag.remove("y");
                    blockEntityTag.remove("z");
                    int encoded = encodedBytes(blockEntityTag);
                    if (encoded > BlueprintLimits.MAX_BLOCK_ENTITY_BYTES) {
                        throw new IllegalArgumentException("A block entity exceeds the 256 KiB limit");
                    }
                    blockEntityBytes = Math.addExact(blockEntityBytes, encoded);
                    if (blockEntityBytes > BlueprintLimits.MAX_BLOCK_ENTITY_TOTAL_BYTES) {
                        throw new IllegalArgumentException("Blueprint block entity data exceeds the 8 MiB limit");
                    }
                }
            }
            paletteSet.add(state);
            captured.add(new CapturedBlock(worldPos.subtract(min), state, blockEntityTag));
        }

        List<BlockState> palette = paletteSet.stream()
                .sorted(Comparator.comparing(BlueprintCapture::stateKey))
                .toList();
        if (palette.size() > BlueprintLimits.MAX_PALETTE) {
            throw new IllegalArgumentException("Selection contains too many distinct block states");
        }
        Map<BlockState, Integer> paletteIndices = new HashMap<>();
        for (int index = 0; index < palette.size(); index++) paletteIndices.put(palette.get(index), index);
        List<BlueprintData.BlockEntry> blocks = captured.stream()
                .map(block -> new BlueprintData.BlockEntry(block.relativePos(),
                        paletteIndices.get(block.state()), block.blockEntityTag()))
                .toList();

        return new BlueprintData(UUID.randomUUID(), requestedName, player.getGameProfile().getName(),
                player.getUUID(), System.currentTimeMillis(), mode, sizeX, sizeY, sizeZ,
                first.subtract(min), palette, blocks);
    }

    private static void requireLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4;
        int maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        for (int x = minChunkX; ; x++) {
            for (int z = minChunkZ; ; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) {
                    throw new IllegalArgumentException("Every source chunk must already be loaded");
                }
                if (z == maxChunkZ) break;
            }
            if (x == maxChunkX) break;
        }
    }

    /**
     * Overflow-safe replacement for {@link BlockPos#betweenClosed(BlockPos, BlockPos)}. The vanilla
     * iterator stores {@code sizeX * sizeY * sizeZ} in an int, so selections whose volume is a
     * multiple of 2^32 can appear empty and other large selections can run past their bounds.
     */
    static Iterable<BlockPos> positionsBetweenClosed(BlockPos min, BlockPos max) {
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("Minimum selection corner must not exceed maximum corner");
        }
        BlockPos immutableMin = min.immutable();
        BlockPos immutableMax = max.immutable();
        return () -> new Iterator<>() {
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            private int nextX = immutableMin.getX();
            private int nextY = immutableMin.getY();
            private int nextZ = immutableMin.getZ();
            private boolean available = true;

            @Override
            public boolean hasNext() {
                return available;
            }

            @Override
            public BlockPos next() {
                if (!available) throw new NoSuchElementException();
                cursor.set(nextX, nextY, nextZ);
                advance();
                return cursor;
            }

            private void advance() {
                if (nextX != immutableMax.getX()) {
                    nextX++;
                    return;
                }
                nextX = immutableMin.getX();
                if (nextY != immutableMax.getY()) {
                    nextY++;
                    return;
                }
                nextY = immutableMin.getY();
                if (nextZ != immutableMax.getZ()) {
                    nextZ++;
                    return;
                }
                available = false;
            }
        };
    }

    private static String stateKey(BlockState state) {
        return net.minecraft.nbt.NbtUtils.writeBlockState(state).toString();
    }

    private static int encodedBytes(CompoundTag tag) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(tag, new java.io.DataOutputStream(bytes));
            return bytes.size();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Unable to size block entity data", exception);
        }
    }

    private record CapturedBlock(BlockPos relativePos, BlockState state, CompoundTag blockEntityTag) {
    }
}
