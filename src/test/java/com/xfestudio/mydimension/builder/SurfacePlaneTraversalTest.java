package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfacePlaneTraversalTest {
    @Test
    void verticalWallKeepsEightNeighbourConnectivityInTheSelectedPlane() {
        BlockPos seed = new BlockPos(0, 4, 7);
        Set<BlockPos> exposedWall = Set.of(
                seed,
                new BlockPos(1, 4, 7),
                new BlockPos(1, 5, 7),
                new BlockPos(-1, 3, 7));

        SurfacePlaneTraversal.Result result = SurfacePlaneTraversal.traverse(
                seed, Direction.Axis.Z, 128, 128, exposedWall::contains, ignored -> true);

        assertEquals(exposedWall, positions(result.nodes()));
        assertTrue(result.nodes().stream().allMatch(node -> node.pos().getZ() == 7));
        assertEquals(1, result.nodes().stream()
                .filter(node -> node.pos().equals(new BlockPos(1, 5, 7)))
                .findFirst().orElseThrow().distance());
    }

    @Test
    void horizontalGroundSurfaceDoesNotWrapDownOverItsEdge() {
        BlockPos seed = new BlockPos(0, 8, 0);
        Set<BlockPos> worldReferences = Set.of(
                seed,
                new BlockPos(1, 8, 0),
                new BlockPos(2, 7, 0),
                new BlockPos(2, 7, 1));

        SurfacePlaneTraversal.Result result = SurfacePlaneTraversal.traverse(
                seed, Direction.Axis.Y, 128, 128, worldReferences::contains, ignored -> true);

        assertEquals(Set.of(seed, new BlockPos(1, 8, 0)), positions(result.nodes()));
        assertTrue(result.nodes().stream().allMatch(node -> node.pos().getY() == seed.getY()));
    }

    @Test
    void occludedGroundRowDisconnectsExposedUndergroundContinuation() {
        BlockPos seed = new BlockPos(0, 2, 0);
        Set<BlockPos> exposedFaces = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            exposedFaces.add(new BlockPos(x, 1, 0));
            exposedFaces.add(new BlockPos(x, 2, 0));
            exposedFaces.add(new BlockPos(x, -1, 0));
            exposedFaces.add(new BlockPos(x, -2, 0));
        }
        // y=0 is the wall row backed by solid terrain on the selected side. It is
        // deliberately absent from the traversable (visible-face) set.
        SurfacePlaneTraversal.Result demolish = SurfacePlaneTraversal.traverse(
                seed, Direction.Axis.Z, 256, 256, exposedFaces::contains, ignored -> true);
        SurfacePlaneTraversal.Result build = SurfacePlaneTraversal.traverse(
                seed, Direction.Axis.Z, 256, 256, exposedFaces::contains,
                pos -> pos.getY() >= 1);

        assertTrue(demolish.nodes().stream().allMatch(node -> node.pos().getY() >= 1));
        assertTrue(build.nodes().stream().allMatch(node -> node.pos().getY() >= 1));
        assertFalse(positions(demolish.nodes()).contains(new BlockPos(0, -1, 0)));
    }

    private static Set<BlockPos> positions(List<SurfacePlaneTraversal.Node> nodes) {
        Set<BlockPos> result = new HashSet<>();
        for (SurfacePlaneTraversal.Node node : nodes) {
            result.add(node.pos());
        }
        return result;
    }
}
