package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SurfacePlanner {
    private static final int ABSOLUTE_SCAN_LIMIT = 65_536;

    private SurfacePlanner() {
    }

    public static Plan plan(ServerLevel level, BlockPos seed, Direction face, BuilderMode operation,
                            SurfaceMatchMode matchMode, int operationLimit, BlockState overrideState) {
        BlockState seedState = level.getBlockState(seed);
        if (!isReference(seedState)) {
            return new Plan(List.of(), false);
        }

        int limit = Math.max(1, operationLimit);
        int scanLimit = Math.min(ABSOLUTE_SCAN_LIMIT, Math.max(limit * 16, limit));
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<Candidate> result = new ArrayList<>(limit);
        queue.add(new Node(seed.immutable(), 0));

        boolean truncated = false;
        while (!queue.isEmpty() && visited.size() < scanLimit) {
            Node node = queue.removeFirst();
            BlockPos pos = node.pos();
            if (!visited.add(pos)) {
                continue;
            }

            BlockState reference = level.getBlockState(pos);
            if (!matches(seedState, reference, matchMode)) {
                continue;
            }

            BlockPos target = operation == BuilderMode.BUILD ? pos.relative(face) : pos;
            BlockState desired = overrideState == null ? reference : overrideState;
            if (operation == BuilderMode.DEMOLISH || shouldBuild(level, target, desired)) {
                result.add(new Candidate(pos.immutable(), target.immutable(), desired, node.distance()));
                if (result.size() >= limit) {
                    truncated = !queue.isEmpty();
                    break;
                }
            }

            for (BlockPos neighbor : planeNeighbors(pos, face.getAxis())) {
                if (!visited.contains(neighbor)) {
                    queue.addLast(new Node(neighbor, node.distance() + 1));
                }
            }
        }
        if (!queue.isEmpty()) {
            truncated = true;
        }

        result.sort(Comparator.comparingInt(Candidate::distance)
                .thenComparingInt(candidate -> candidate.reference().getY())
                .thenComparingInt(candidate -> candidate.reference().getZ())
                .thenComparingInt(candidate -> candidate.reference().getX()));
        return new Plan(List.copyOf(result), truncated);
    }

    private static boolean shouldBuild(ServerLevel level, BlockPos target, BlockState desired) {
        if (target.getY() < level.getMinBuildHeight() || target.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        if (!level.getWorldBorder().isWithinBounds(target)) {
            return false;
        }
        BlockState existing = level.getBlockState(target);
        return existing != desired && !existing.equals(desired) && existing.canBeReplaced();
    }

    private static boolean matches(BlockState seed, BlockState state, SurfaceMatchMode mode) {
        if (!isReference(state)) {
            return false;
        }
        return mode == SurfaceMatchMode.ANY_BLOCK || state.getBlock() == seed.getBlock();
    }

    private static boolean isReference(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    private static List<BlockPos> planeNeighbors(BlockPos pos, Direction.Axis normal) {
        List<BlockPos> values = new ArrayList<>(8);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                values.add(switch (normal) {
                    case X -> pos.offset(0, a, b);
                    case Y -> pos.offset(a, 0, b);
                    case Z -> pos.offset(a, b, 0);
                });
            }
        }
        return values;
    }

    public record Candidate(BlockPos reference, BlockPos target, BlockState desiredState, int distance) {
    }

    public record Plan(List<Candidate> candidates, boolean truncated) {
    }

    private record Node(BlockPos pos, int distance) {
    }
}
