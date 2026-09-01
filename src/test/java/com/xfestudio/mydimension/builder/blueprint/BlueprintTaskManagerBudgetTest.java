package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueprintTaskManagerBudgetTest {
    @Test
    void tickBudgetUsesTheScepterBuildLimitInsteadOfTheLegacySixtyFourCap() {
        assertEquals(256, BlueprintTaskManager.workEnd(0, 1_000, 256, 4_096));
        assertEquals(356, BlueprintTaskManager.workEnd(100, 1_000, 256, 4_096));
    }

    @Test
    void tickBudgetStillHonorsServerMaximumAndRemainingWork() {
        assertEquals(128, BlueprintTaskManager.workEnd(0, 1_000, 512, 128));
        assertEquals(1_000, BlueprintTaskManager.workEnd(900, 1_000, 256, 4_096));
    }

    @Test
    void oneTickBudgetCanAdvanceAcrossSeveralChunkSegments() {
        List<BlueprintPlacementPlan.PlannedBlock> blocks = List.of(
                blockAt(15), blockAt(16), blockAt(17), blockAt(32));
        int maximumEnd = BlueprintTaskManager.workEnd(0, blocks.size(), 256, 4_096);
        List<Integer> ends = new ArrayList<>();
        int cursor = 0;
        while (cursor < maximumEnd) {
            cursor = BlueprintTaskManager.nextChunkEnd(blocks, cursor, maximumEnd);
            ends.add(cursor);
        }
        assertEquals(List.of(1, 3, 4), ends);
        assertEquals(maximumEnd, cursor);
    }

    @Test
    void placementOrderKeepsChunksContiguousAndBuildsBottomUpWithinEachChunk() {
        List<BlueprintPlacementPlan.PlannedBlock> blocks = new ArrayList<>(List.of(
                blockAt(16, 80, 0), blockAt(0, 81, 0), blockAt(17, 70, 0), blockAt(1, 64, 0)));
        blocks.sort(BlueprintPlacementPlan::compareForExecution);
        assertEquals(List.of(1, 0, 17, 16), blocks.stream()
                .map(block -> block.worldPos().getX()).toList());
    }

    @Test
    void completedProgressAdvancesOnlyByActuallyChangedBlocks() {
        int completed = BlueprintTaskManager.accumulatedCompleted(12, 20);
        assertEquals(32, completed);

        // A batch may advance its execution cursor while every remaining cell is
        // missing, blocked, or already equal. None of those are successful edits.
        assertEquals(32, BlueprintTaskManager.accumulatedCompleted(completed, 0));
    }

    @Test
    void completedProgressSaturatesWithoutWrapping() {
        assertEquals(Integer.MAX_VALUE,
                BlueprintTaskManager.accumulatedCompleted(Integer.MAX_VALUE - 3, 20));
    }

    private static BlueprintPlacementPlan.PlannedBlock blockAt(int x) {
        return blockAt(x, 64, 0);
    }

    private static BlueprintPlacementPlan.PlannedBlock blockAt(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        // The chunk segmentation helper reads only worldPos; keeping state null avoids bootstrapping
        // Minecraft's global registries in this small pure unit test.
        return new BlueprintPlacementPlan.PlannedBlock(pos, pos, null, null);
    }
}
