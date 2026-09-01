package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmwrightHistorySettingTest {
    @Test
    void historyRecordingIsOptInWhenThePersistedFieldIsAbsent() {
        assertFalse(RealmwrightData.recordsHistory((CompoundTag) null));
        assertFalse(RealmwrightData.recordsHistory(new CompoundTag()));
    }

    @Test
    void historyChoiceUsesThePersistedBooleanValue() {
        CompoundTag persisted = new CompoundTag();
        persisted.putBoolean("RecordHistory", true);
        assertTrue(RealmwrightData.recordsHistory(persisted));
        assertTrue(RealmwrightData.recordsHistory(persisted.copy()));

        persisted.putBoolean("RecordHistory", false);
        assertFalse(RealmwrightData.recordsHistory(persisted));
    }

    @Test
    void enabledHistoryStillUsesTheConservativePreflightEstimate() {
        assertEquals(4_096L,
                BuilderOperationManager.ordinaryHistoryEntryBytes(BuilderTransaction.Type.BUILD));
        assertEquals(4_096L,
                BuilderOperationManager.ordinaryHistoryEntryBytes(BuilderTransaction.Type.DEMOLISH));
    }

    @Test
    void pendingTaskNbtKeepsItsCreationTimePolicyAndProgress() {
        CompoundTag persisted = new CompoundTag();
        PendingBuildData.Task.writePolicyAndProgress(persisted, true, 37, 64);

        assertTrue(PendingBuildData.Task.readRecordHistory(persisted));
        assertTrue(PendingBuildData.Task.readHistoryPolicyKnown(persisted));
        assertEquals(37, PendingBuildData.Task.readCompleted(persisted));
        assertEquals(64, PendingBuildData.Task.readTotal(persisted, 11));

        PendingBuildData.Task.writePolicyAndProgress(persisted, false, 37, 64);
        assertFalse(PendingBuildData.Task.readRecordHistory(persisted));
    }

    @Test
    void legacyPendingTaskWithoutPolicyUsesTheCurrentScepterChoice() {
        CompoundTag legacyTask = new CompoundTag();

        assertFalse(PendingBuildData.Task.readRecordHistory(legacyTask));
        assertFalse(PendingBuildData.Task.readHistoryPolicyKnown(legacyTask));
        assertTrue(PendingBuildData.Task.resolveRecordHistory(false, false, true));
        assertFalse(PendingBuildData.Task.resolveRecordHistory(false, true, false));
        assertEquals(0, PendingBuildData.Task.readCompleted(legacyTask));
        assertEquals(11, PendingBuildData.Task.readTotal(legacyTask, 11));
    }

    @Test
    void explicitBlueprintHistoryPolicyOverridesLegacyTransactionalDefault() {
        CompoundTag legacyBlueprint = new CompoundTag();
        assertTrue(PendingBuildData.Task.loadedRecordHistory(
                legacyBlueprint, BuilderTransaction.Type.BLUEPRINT));
        assertTrue(PendingBuildData.Task.loadedHistoryPolicyKnown(
                legacyBlueprint, BuilderTransaction.Type.BLUEPRINT));

        CompoundTag historyOffBlueprint = new CompoundTag();
        historyOffBlueprint.putBoolean("RecordHistory", false);
        assertFalse(PendingBuildData.Task.loadedRecordHistory(
                historyOffBlueprint, BuilderTransaction.Type.BLUEPRINT));
        assertTrue(PendingBuildData.Task.loadedHistoryPolicyKnown(
                historyOffBlueprint, BuilderTransaction.Type.BLUEPRINT));
    }

    @Test
    void onlyCommittedRecordedContinuationsRefreshTheMergedAfterImage() {
        assertTrue(BuilderOperationManager.shouldRefreshMergedHistory(true, true, true));
        assertFalse(BuilderOperationManager.shouldRefreshMergedHistory(false, true, true));
        assertFalse(BuilderOperationManager.shouldRefreshMergedHistory(true, false, true));
        assertFalse(BuilderOperationManager.shouldRefreshMergedHistory(true, true, false));
    }

    @Test
    void ordinaryHistoryOffSuccessSkipsTheExpensiveFullBuilderSnapshot() {
        assertFalse(BuilderOperationManager.shouldSynchronizeBuilderState(false, 0));
        assertTrue(BuilderOperationManager.shouldSynchronizeBuilderState(false, 1));
        assertTrue(BuilderOperationManager.shouldSynchronizeBuilderState(true, 0));
    }

    @Test
    void pendingContinuationAccumulatesCompletedAndKeepsOriginalTotal() {
        BuilderOperationManager.PendingCounts first = BuilderOperationManager.pendingCounts(
                false, 0, 0, 12, 52);
        assertEquals(12, first.completed());
        assertEquals(64, first.total());

        BuilderOperationManager.PendingCounts resumed = BuilderOperationManager.pendingCounts(
                true, first.completed(), first.total(), 20, 32);
        assertEquals(32, resumed.completed());
        assertEquals(64, resumed.total());
    }

    @Test
    void legacyPendingProgressRecoversFromMatchingHistory() {
        PendingBuildData.Progress migrated = PendingBuildData.progress(0, 11, 11, 53);
        assertEquals(53, migrated.completed());
        assertEquals(64, migrated.total());

        PendingBuildData.Progress persisted = PendingBuildData.progress(37, 64, 11, 12);
        assertEquals(37, persisted.completed());
        assertEquals(64, persisted.total());
    }
}
