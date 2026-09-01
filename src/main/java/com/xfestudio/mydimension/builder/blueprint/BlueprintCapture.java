package com.xfestudio.mydimension.builder.blueprint;

import com.xfestudio.mydimension.config.BuilderConfig;
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
import java.util.List;
import java.util.Map;
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
        long volume = (long) sizeX * sizeY * sizeZ;
        int maxAxis = Math.min(BlueprintLimits.MAX_AXIS, BuilderConfig.MAX_BLUEPRINT_AXIS.get());
        int maxVolume = Math.min(BlueprintLimits.MAX_VOLUME, BuilderConfig.MAX_BLUEPRINT_VOLUME.get());
        if (sizeX > maxAxis || sizeY > maxAxis || sizeZ > maxAxis || volume > maxVolume) {
            throw new IllegalArgumentException("Selection exceeds the blueprint size limit");
        }
        requireLoaded(level, min, max);

        List<CapturedBlock> captured = new ArrayList<>();
        Set<BlockState> paletteSet = new HashSet<>();
        int blockEntityBytes = 0;
        for (BlockPos mutable : BlockPos.betweenClosed(min, max)) {
            BlockPos worldPos = mutable.immutable();
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) continue;
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
            if (captured.size() > Math.min(BlueprintLimits.MAX_BLOCKS, BuilderConfig.MAX_BLUEPRINT_BLOCKS.get())) {
                throw new IllegalArgumentException("Selection contains too many non-air blocks");
            }
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
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) {
                    throw new IllegalArgumentException("Every source chunk must already be loaded");
                }
            }
        }
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
