package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Optional;

/** Coordinate and state transform used by preview and authoritative placement. */
public record BlueprintTransform(boolean flipX, boolean flipY, boolean flipZ, Rotation rotationY) {
    public static final BlueprintTransform NONE = new BlueprintTransform(false, false, false, Rotation.NONE);

    public BlueprintTransform(boolean flipX, boolean flipY, boolean flipZ) {
        this(flipX, flipY, flipZ, Rotation.NONE);
    }

    public BlueprintTransform {
        if (rotationY == null) throw new IllegalArgumentException("Y rotation is missing");
    }

    public int mask() {
        return (flipX ? 1 : 0) | (flipY ? 2 : 0) | (flipZ ? 4 : 0)
                | (rotationY.ordinal() << 3);
    }

    public static BlueprintTransform fromMask(int mask) {
        if ((mask & ~31) != 0) {
            throw new IllegalArgumentException("Invalid blueprint transform mask: " + mask);
        }
        int rotation = (mask >>> 3) & 3;
        return new BlueprintTransform((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0,
                Rotation.values()[rotation]);
    }

    public BlockPos transform(BlockPos local, int sizeX, int sizeY, int sizeZ) {
        int x = flipX ? sizeX - 1 - local.getX() : local.getX();
        int y = flipY ? sizeY - 1 - local.getY() : local.getY();
        int z = flipZ ? sizeZ - 1 - local.getZ() : local.getZ();
        return switch (rotationY) {
            case NONE -> new BlockPos(x, y, z);
            case CLOCKWISE_90 -> new BlockPos(sizeZ - 1 - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(sizeX - 1 - x, y, sizeZ - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, sizeX - 1 - x);
        };
    }

    public BlockState transform(BlockState input) {
        BlockState state = input;
        if (flipX) {
            state = state.mirror(Mirror.FRONT_BACK);
        }
        if (flipZ) {
            state = state.mirror(Mirror.LEFT_RIGHT);
        }
        state = state.rotate(rotationY);
        return flipY ? verticalFlip(state) : state;
    }

    public int transformedSizeX(int sizeX, int sizeZ) {
        return rotationY == Rotation.CLOCKWISE_90 || rotationY == Rotation.COUNTERCLOCKWISE_90
                ? sizeZ : sizeX;
    }

    public int transformedSizeZ(int sizeX, int sizeZ) {
        return rotationY == Rotation.CLOCKWISE_90 || rotationY == Rotation.COUNTERCLOCKWISE_90
                ? sizeX : sizeZ;
    }

    private static BlockState verticalFlip(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            Comparable<?> current = state.getValue(property);
            Comparable<?> replacement = verticalValue(property, current);
            if (replacement != null && replacement != current) {
                state = setIfAllowed(state, property, replacement).orElse(state);
            }
        }
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            state = state.setValue(BlockStateProperties.HANGING,
                    !state.getValue(BlockStateProperties.HANGING));
        }
        return state;
    }

    private static Comparable<?> verticalValue(Property<?> property, Comparable<?> value) {
        if (value == Direction.UP) return Direction.DOWN;
        if (value == Direction.DOWN) return Direction.UP;
        if (value == Half.TOP) return Half.BOTTOM;
        if (value == Half.BOTTOM) return Half.TOP;
        if (value == SlabType.TOP) return SlabType.BOTTOM;
        if (value == SlabType.BOTTOM) return SlabType.TOP;
        if (value == AttachFace.FLOOR) return AttachFace.CEILING;
        if (value == AttachFace.CEILING) return AttachFace.FLOOR;
        if (value == DoubleBlockHalf.UPPER) return DoubleBlockHalf.LOWER;
        if (value == DoubleBlockHalf.LOWER) return DoubleBlockHalf.UPPER;
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<BlockState> setIfAllowed(BlockState state, Property property, Comparable value) {
        if (!property.getPossibleValues().contains(value)) {
            return Optional.empty();
        }
        return Optional.of(state.setValue(property, value));
    }
}
