package com.xfestudio.mydimension.builder;

import org.junit.jupiter.api.Test;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuilderSurfaceTaskManagerBudgetTest {
    @Test
    void defaultBuildBudgetSpreadsTwoHundredFiftySixBlocksAcrossFourTicks() {
        int cursor = 0;
        int ticks = 0;
        while (cursor < 256) {
            cursor += Math.min(256 - cursor,
                    BuilderSurfaceTaskManager.batchSize(BuilderMode.BUILD, 64));
            ticks++;
        }
        assertEquals(4, ticks);
        assertEquals(64, BuilderSurfaceTaskManager.batchSize(BuilderMode.BUILD, 64));
    }

    @Test
    void demolitionUsesFourBudgetUnitsAndSpreadsSixtyFourBlocksAcrossFourTicks() {
        int cursor = 0;
        int ticks = 0;
        while (cursor < 64) {
            cursor += Math.min(64 - cursor,
                    BuilderSurfaceTaskManager.batchSize(BuilderMode.DEMOLISH, 64));
            ticks++;
        }
        assertEquals(4, ticks);
        assertEquals(16, BuilderSurfaceTaskManager.batchSize(BuilderMode.DEMOLISH, 64));
        assertEquals(64, BuilderSurfaceTaskManager.budgetCost(BuilderMode.DEMOLISH, 16));
    }

    @Test
    void verySmallBudgetsStillMakeBoundedForwardProgress() {
        assertEquals(1, BuilderSurfaceTaskManager.batchSize(BuilderMode.BUILD, 0));
        assertEquals(1, BuilderSurfaceTaskManager.batchSize(BuilderMode.DEMOLISH, 1));
    }

    @Test
    void ordinaryHistoryPreflightUsesAConservativeConstantWithoutNbtSerialization() {
        assertEquals(4_096L,
                BuilderOperationManager.ordinaryHistoryEntryBytes(BuilderTransaction.Type.BUILD));
        assertEquals(4_096L,
                BuilderOperationManager.ordinaryHistoryEntryBytes(BuilderTransaction.Type.DEMOLISH));
    }
}
