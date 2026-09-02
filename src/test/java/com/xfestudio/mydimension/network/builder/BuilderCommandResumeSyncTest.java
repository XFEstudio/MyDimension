package com.xfestudio.mydimension.network.builder;

import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderCommandResumeSyncTest {
    @Test
    void acceptedBlueprintResumeDefersTheHandOffGapPreview() {
        assertTrue(BuilderCommandPacket.defersResumeSynchronization(
                BuilderTransaction.Type.BLUEPRINT, true));
    }

    @Test
    void surfaceAndRejectedResumesSynchronizeImmediately() {
        assertFalse(BuilderCommandPacket.defersResumeSynchronization(
                BuilderTransaction.Type.BUILD, true));
        assertFalse(BuilderCommandPacket.defersResumeSynchronization(
                BuilderTransaction.Type.DEMOLISH, true));
        assertFalse(BuilderCommandPacket.defersResumeSynchronization(
                BuilderTransaction.Type.BLUEPRINT, false));
    }
}
