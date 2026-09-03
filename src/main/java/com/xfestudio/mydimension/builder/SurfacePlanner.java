package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class SurfacePlanner {
    private static final int ABSOLUTE_SCAN_LIMIT = 65_536;

    private SurfacePlanner() {
    }

    public static Plan plan(ServerLevel level, BlockPos seed, Direction face, BuilderMode operation,
                            SurfaceMatchMode matchMode, int operationLimit, BlockState overrideState) {
        return plan(level, seed, face, operation, matchMode, operationLimit, overrideState, false);
    }

    public static Plan plan(ServerLevel level, BlockPos seed, Direction face, BuilderMode operation,
                            SurfaceMatchMode matchMode, int operationLimit, BlockState overrideState,
                            boolean allowReplacement) {
        BlockState seedState = level.getBlockState(seed);
        if (!SurfacePlaneTraversal.isReference(seedState)
                || !isVisibleOperationFace(level, seed, face, seedState, operation,
                allowReplacement)) {
            return new Plan(List.of(), false);
        }

        int limit = Math.max(1, operationLimit);
        int scanLimit = Math.min(ABSOLUTE_SCAN_LIMIT, Math.max(limit * 16, limit));
        SurfacePlaneTraversal.Result traversal = SurfacePlaneTraversal.traverse(
                seed, face.getAxis(), scanLimit, limit,
                pos -> {
                    BlockState reference = level.getBlockState(pos);
                    return matches(seedState, reference, matchMode)
                            && isVisibleOperationFace(level, pos, face, reference, operation,
                            allowReplacement);
                },
                pos -> {
                    if (operation == BuilderMode.DEMOLISH) {
                        return true;
                    }
                    BlockState reference = level.getBlockState(pos);
                    BlockState desired = overrideState == null ? reference : overrideState;
                    return shouldBuild(level, pos.relative(face), desired, allowReplacement);
                });

        List<Candidate> result = new ArrayList<>(traversal.nodes().size());
        for (SurfacePlaneTraversal.Node node : traversal.nodes()) {
            BlockState reference = level.getBlockState(node.pos());
            BlockPos target = operation == BuilderMode.BUILD ? node.pos().relative(face) : node.pos();
            BlockState desired = overrideState == null ? reference : overrideState;
            result.add(new Candidate(node.pos(), target.immutable(), desired, node.distance()));
        }

        return new Plan(List.copyOf(result), traversal.truncated());
    }

    private static boolean shouldBuild(ServerLevel level, BlockPos target, BlockState desired,
                                       boolean allowReplacement) {
        if (target.getY() < level.getMinBuildHeight() || target.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        if (!level.getWorldBorder().isWithinBounds(target)) {
            return false;
        }
        BlockState existing = level.getBlockState(target);
        return existing != desired && !existing.equals(desired)
                && (existing.canBeReplaced() || allowReplacement);
    }

    /**
     * Replacement may operate on a solid protrusion in front of the reference plane, but only
     * while that protrusion itself presents the selected outer face. This keeps a visible obstacle
     * replaceable without turning a continuous floor into a bridge toward buried reference blocks.
     */
    private static boolean isVisibleOperationFace(ServerLevel level, BlockPos referencePos,
                                                  Direction face, BlockState reference,
                                                  BuilderMode operation, boolean allowReplacement) {
        if (SurfacePlaneTraversal.hasExposedReferenceFace(
                level, referencePos, face, reference)) return true;
        if (operation != BuilderMode.BUILD || !allowReplacement) return false;
        BlockPos target = referencePos.relative(face);
        BlockState obstacle = level.getBlockState(target);
        return BuilderReplacementPolicy.requiresBreaking(obstacle)
                && SurfacePlaneTraversal.hasExposedReferenceFace(level, target, face, obstacle);
    }

    private static boolean matches(BlockState seed, BlockState state, SurfaceMatchMode mode) {
        if (!SurfacePlaneTraversal.isReference(state)) {
            return false;
        }
        return mode == SurfaceMatchMode.ANY_BLOCK
                || BuilderBlockCompatibility.sameSurfaceType(seed, state);
    }

    public record Candidate(BlockPos reference, BlockPos target, BlockState desiredState, int distance) {
    }

    public record Plan(List<Candidate> candidates, boolean truncated) {
    }
}
