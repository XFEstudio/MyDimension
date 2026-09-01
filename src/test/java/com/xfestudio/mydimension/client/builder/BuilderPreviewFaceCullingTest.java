package com.xfestudio.mydimension.client.builder;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderPreviewFaceCullingTest {
    @Test
    void adjacencyIsDetectedInsideOneSection() {
        assertAdjacent(new BlockPos(14, 64, 5), Direction.EAST);
    }

    @Test
    void adjacencyCrossesPositiveSectionBoundaryOnEveryAxis() {
        assertAdjacent(new BlockPos(15, 7, 9), Direction.EAST);
        assertAdjacent(new BlockPos(7, 15, 9), Direction.UP);
        assertAdjacent(new BlockPos(7, 9, 15), Direction.SOUTH);
    }

    @Test
    void adjacencyCrossesNegativeSectionBoundaryOnEveryAxis() {
        assertAdjacent(new BlockPos(-1, 7, 9), Direction.EAST);
        assertAdjacent(new BlockPos(7, -1, 9), Direction.UP);
        assertAdjacent(new BlockPos(7, 9, -1), Direction.SOUTH);
    }

    @Test
    void removingNeighbourMakesFaceVisibleAgain() {
        BlockPos origin = new BlockPos(15, 64, 0);
        LongSet projected = new LongOpenHashSet();
        projected.add(origin.relative(Direction.EAST).asLong());
        assertTrue(BuilderPreviewSectionMeshCache.hasProjectedNeighbour(
                projected, origin, Direction.EAST));

        projected.clear();
        assertFalse(BuilderPreviewSectionMeshCache.hasProjectedNeighbour(
                projected, origin, Direction.EAST));
    }

    private static void assertAdjacent(BlockPos origin, Direction direction) {
        LongSet projected = new LongOpenHashSet();
        projected.add(origin.relative(direction).asLong());

        assertTrue(BuilderPreviewSectionMeshCache.hasProjectedNeighbour(
                projected, origin, direction));
        assertFalse(BuilderPreviewSectionMeshCache.hasProjectedNeighbour(
                projected, origin, direction.getOpposite()));
    }
}
