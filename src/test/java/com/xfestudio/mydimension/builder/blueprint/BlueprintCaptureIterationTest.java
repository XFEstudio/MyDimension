package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintCaptureIterationTest {
    @Test
    void volumeThatWrapsVanillaIteratorToZeroStillStartsAtMinimum() {
        // 65,536 * 65,536 * 1 == 2^32. BlockPos.betweenClosed stores this product in an int
        // and consequently reports an empty iterator; blueprint capture must not do so.
        Iterator<BlockPos> positions = BlueprintCapture.positionsBetweenClosed(
                BlockPos.ZERO, new BlockPos(65_535, 65_535, 0)).iterator();

        assertTrue(positions.hasNext());
        assertEquals(BlockPos.ZERO, positions.next());
        assertEquals(new BlockPos(1, 0, 0), positions.next());
    }

    @Test
    void maximumIntegerCoordinateTerminatesWithoutIncrementOverflow() {
        Iterator<BlockPos> positions = BlueprintCapture.positionsBetweenClosed(
                new BlockPos(Integer.MAX_VALUE - 1, 4, 7),
                new BlockPos(Integer.MAX_VALUE, 4, 7)).iterator();

        assertEquals(new BlockPos(Integer.MAX_VALUE - 1, 4, 7), positions.next());
        assertEquals(new BlockPos(Integer.MAX_VALUE, 4, 7), positions.next());
        assertFalse(positions.hasNext());
        assertThrows(NoSuchElementException.class, positions::next);
    }

    @Test
    void iterationOrderMatchesVanillaForOrdinarySelections() {
        Iterator<BlockPos> positions = BlueprintCapture.positionsBetweenClosed(
                new BlockPos(2, 3, 4), new BlockPos(3, 4, 5)).iterator();

        assertEquals(new BlockPos(2, 3, 4), positions.next());
        assertEquals(new BlockPos(3, 3, 4), positions.next());
        assertEquals(new BlockPos(2, 4, 4), positions.next());
        assertEquals(new BlockPos(3, 4, 4), positions.next());
        assertEquals(new BlockPos(2, 3, 5), positions.next());
    }
}
