package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Shared eight-neighbour traversal used by both client prediction and server authority. */
public final class SurfacePlaneTraversal {
    private SurfacePlaneTraversal() {
    }

    public static Result traverse(BlockPos seed, Direction.Axis normal, int scanLimit, int resultLimit,
                                  Predicate<BlockPos> traversable, Predicate<BlockPos> accepted) {
        int cappedScanLimit = Math.max(1, scanLimit);
        int cappedResultLimit = Math.max(1, resultLimit);
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<BlockPos> discovered = new HashSet<>();
        List<Node> result = new ArrayList<>(Math.min(cappedResultLimit, 4096));
        BlockPos immutableSeed = seed.immutable();
        queue.addLast(new Node(immutableSeed, 0));
        discovered.add(immutableSeed);

        boolean scanCapped = false;
        boolean resultCapped = false;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (!traversable.test(node.pos())) {
                continue;
            }

            if (accepted.test(node.pos())) {
                result.add(node);
            }

            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) {
                        continue;
                    }
                    BlockPos neighbour = switch (normal) {
                        case X -> node.pos().offset(0, a, b);
                        case Y -> node.pos().offset(a, 0, b);
                        case Z -> node.pos().offset(a, b, 0);
                    };
                    if (discovered.contains(neighbour)) {
                        continue;
                    }
                    if (discovered.size() >= cappedScanLimit) {
                        scanCapped = true;
                        continue;
                    }
                    discovered.add(neighbour);
                    queue.addLast(new Node(neighbour, node.distance() + 1));
                }
            }

            if (result.size() >= cappedResultLimit) {
                resultCapped = !queue.isEmpty() || scanCapped;
                break;
            }
        }

        result.sort(Comparator.comparingInt(Node::distance)
                .thenComparingInt(node -> node.pos().getY())
                .thenComparingInt(node -> node.pos().getZ())
                .thenComparingInt(node -> node.pos().getX()));
        return new Result(List.copyOf(result), resultCapped || scanCapped);
    }

    /**
     * A reference belongs to the selected surface only while some part of the
     * face hit by the player is actually visible. This prevents a flood fill
     * from following a wall through the solid ground or another backing layer.
     */
    public static boolean hasExposedReferenceFace(BlockGetter level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        return hasExposedReferenceFace(level, pos, face, state);
    }

    public static boolean hasExposedReferenceFace(BlockGetter level, BlockPos pos, Direction face,
                                                  BlockState state) {
        if (!isReference(state)) {
            return false;
        }
        BlockPos outward = pos.relative(face);
        // Air is overwhelmingly the common surface case. Avoid shape lookup and
        // the occlusion-cache path for every cell of a large exposed layer.
        if (level.getBlockState(outward).isAir()) {
            return true;
        }
        return Block.shouldRenderFace(state, level, pos, face, outward);
    }

    public static boolean isReference(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    public record Node(BlockPos pos, int distance) {
    }

    public record Result(List<Node> nodes, boolean truncated) {
    }
}
