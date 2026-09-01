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
}
