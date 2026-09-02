package com.xfestudio.mydimension.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BuilderOperationSoundTest {
    @Test
    void buildUsesTheBlocksPlacementSound() {
        assertEquals("place", BuilderOperationManager.selectOperationSound(
                "place", "break", BuilderOperationManager.OperationSoundKind.PLACE));
    }

    @Test
    void demolitionUsesTheBlocksBreakSound() {
        assertEquals("break", BuilderOperationManager.selectOperationSound(
                "place", "break", BuilderOperationManager.OperationSoundKind.BREAK));
    }
}
