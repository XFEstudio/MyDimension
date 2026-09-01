package com.xfestudio.mydimension.client.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderPreviewFocusTest {
    // Focus/cancel logic treats dimension keys opaquely; null avoids registry bootstrap in JUnit.
    private static final ResourceKey<Level> DIMENSION = null;

    @Test
    void faceEdgeAndCornerNeighboursFormOneMissingGroup() {
        BuilderPreviewState.Cell origin = missing(0, 0, 0);
        BuilderPreviewState.Cell face = missing(1, 0, 0);
        BuilderPreviewState.Cell edge = missing(2, 1, 0);
        BuilderPreviewState.Cell corner = missing(3, 2, 1);
        BuilderPreviewState.Cell separate = missing(6, 2, 1);
        Map<BlockPos, BuilderPreviewState.MissingTarget> indexed = index(
                origin, face, edge, corner, separate);

        BuilderPreviewState.MissingGroup connected = indexed.get(origin.pos()).group();
        assertSame(connected, indexed.get(face.pos()).group());
        assertSame(connected, indexed.get(edge.pos()).group());
        assertSame(connected, indexed.get(corner.pos()).group());
        assertFalse(connected == indexed.get(separate.pos()).group());
        assertEquals(4, connected.cells().size());
        assertEquals(new AABB(0, 0, 0, 4, 3, 2), connected.bounds());
    }

    @Test
    void rayFocusesNearestRealCellAndHighlightsItsWholeConnectedGroup() {
        BuilderPreviewState.Cell first = missing(0, 0, 0);
        BuilderPreviewState.Cell diagonalMember = missing(1, 1, 1);
        BuilderPreviewState.Cell fartherGroup = missing(4, 0, 0);
        Map<BlockPos, BuilderPreviewState.MissingTarget> indexed = index(
                first, diagonalMember, fartherGroup);

        BuilderPreviewState.Focus focus = BuilderPreviewState.focusMissing(
                new Vec3(-2.0D, 0.5D, 0.5D), new Vec3(1.0D, 0.0D, 0.0D), 16.0D, indexed);

        assertNotNull(focus);
        assertEquals(BuilderPreviewState.FocusKind.MISSING, focus.kind());
        assertEquals(first.pos(), focus.cell().pos());
        assertEquals(2.0D, focus.distance(), 1.0E-6D);
        assertEquals(2, focus.missingGroup().cells().size());
        assertEquals(new AABB(0, 0, 0, 2, 2, 2), focus.bounds());
    }

    @Test
    void eyeInsideMissingCellFocusesItsVolumeImmediately() {
        BuilderPreviewState.Cell cell = missing(3, 4, 5);
        BuilderPreviewState.Focus focus = BuilderPreviewState.focusMissing(
                new Vec3(3.5D, 4.5D, 5.5D), new Vec3(1.0D, 0.0D, 0.0D),
                16.0D, index(cell));

        assertNotNull(focus);
        assertEquals(0.0D, focus.distance(), 1.0E-6D);
        assertEquals(cell.pos(), focus.cell().pos());
    }

    @Test
    void deploymentAndSelectionHaveIndependentCancelTargets() {
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
        BuilderPreviewState.Snapshot snapshot = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(missing(8, 8, 8)), selection,
                null, true, false, 1);
        AABB deploymentBounds = new AABB(8, 8, 8, 9, 9, 9);

        assertEquals(BuilderPreviewState.CancelTarget.DEPLOYMENT,
                BuilderPreviewState.cancelTarget(snapshot, new BuilderPreviewState.Focus(
                        BuilderPreviewState.FocusKind.DEPLOYMENT, deploymentBounds, 1.0D, null)));
        assertEquals(BuilderPreviewState.CancelTarget.SELECTION,
                BuilderPreviewState.cancelTarget(snapshot, new BuilderPreviewState.Focus(
                        BuilderPreviewState.FocusKind.SELECTION, selection.bounds(), 1.0D, null)));

        BuilderPreviewState state = new BuilderPreviewState();
        state.accept(snapshot);
        state.clearPlacementPreview();
        assertTrue(state.snapshot().selection().complete());
        assertFalse(state.snapshot().blueprintPreview());
    }

    @Test
    void missingCancelRequiresTheActuallyFocusedConnectedGroup() {
        BuilderPreviewState.Cell cell = missing(0, 0, 0);
        BuilderPreviewState.MissingGroup group = index(cell).get(cell.pos()).group();
        BuilderPreviewState.Snapshot snapshot = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(cell), new BuilderPreviewState.Selection(DIMENSION, null, null),
                UUID.randomUUID(), false, true, 1);

        assertEquals(BuilderPreviewState.CancelTarget.MISSING,
                BuilderPreviewState.cancelTarget(snapshot, new BuilderPreviewState.Focus(
                        BuilderPreviewState.FocusKind.MISSING, group.bounds(), 1.0D, cell, group)));
        assertNull(BuilderPreviewState.cancelTarget(snapshot, new BuilderPreviewState.Focus(
                BuilderPreviewState.FocusKind.MISSING, cell.bounds(), 1.0D, cell)));
        assertNull(BuilderPreviewState.cancelTarget(snapshot, new BuilderPreviewState.Focus(
                BuilderPreviewState.FocusKind.CANDIDATE, cell.bounds(), 1.0D, cell)));
    }

    @Test
    void serverTaskPreviewPreservesOnlySameDimensionLiveClientSelection() {
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        BuilderPreviewState.Snapshot current = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(), selection, null, true, true, 7);

        assertTrue(BuilderPreviewState.shouldPreserveSelection(null, null, true, selection));
        assertTrue(BuilderPreviewState.mergeCancelable(false, false,
                selection));
        BuilderPreviewState.Snapshot mergedServerClear = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(), selection, null, false,
                BuilderPreviewState.mergeCancelable(false, false, selection), 8);
        assertEquals(BuilderPreviewState.CancelTarget.SELECTION,
                BuilderPreviewState.cancelTarget(mergedServerClear, new BuilderPreviewState.Focus(
                        BuilderPreviewState.FocusKind.SELECTION, selection.bounds(), 1.0D, null)));

        assertFalse(BuilderPreviewState.shouldPreserveSelection(null, null, false, selection));

        BuilderPreviewState.Snapshot explicitlyCleared = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(), new BuilderPreviewState.Selection(DIMENSION, null, null),
                null, false, false, 8);
        assertFalse(BuilderPreviewState.shouldPreserveSelection(null, null, true,
                explicitlyCleared.selection()));

        BlockPos replacementFirst = new BlockPos(9, 9, 9);
        assertFalse(BuilderPreviewState.shouldPreserveSelection(
                replacementFirst, null, true, current.selection()));
    }

    @Test
    void emptyServerTaskUpdateDoesNotBlankClientOwnedSurfacePreview() {
        BuilderPreviewState.Cell surface = new BuilderPreviewState.Cell(
                new BlockPos(2, 3, 4), null, BuilderPreviewState.Kind.BUILD, true);
        BuilderPreviewState.Selection empty = new BuilderPreviewState.Selection(
                DIMENSION, null, null);
        BuilderPreviewState.Snapshot current = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(surface), empty, null, false, false, 3);
        BuilderPreviewState.Snapshot incoming = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(), empty, null, false, false, 4);

        assertSame(current, BuilderPreviewState.mergeServerSnapshot(current, incoming, true));
        assertSame(incoming, BuilderPreviewState.mergeServerSnapshot(current, incoming, false));
    }

    @Test
    void emptyServerTaskUpdateRemovesOnlyCompletedMissingCells() {
        BuilderPreviewState.Cell missing = missing(0, 0, 0);
        BuilderPreviewState.Cell local = new BuilderPreviewState.Cell(
                new BlockPos(1, 0, 0), null, BuilderPreviewState.Kind.BUILD, true);
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(5, 5, 5), new BlockPos(6, 6, 6));
        BuilderPreviewState.Snapshot current = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(missing, local), selection,
                UUID.randomUUID(), false, true, 8);
        BuilderPreviewState.Snapshot incoming = new BuilderPreviewState.Snapshot(
                DIMENSION, List.of(), selection, null, false, true, 9);

        BuilderPreviewState.Snapshot merged = BuilderPreviewState.mergeServerSnapshot(
                current, incoming, true);

        assertEquals(List.of(local), merged.cells());
        assertNull(merged.activeJobId());
        assertTrue(merged.selection().complete());
    }

    private static Map<BlockPos, BuilderPreviewState.MissingTarget> index(
            BuilderPreviewState.Cell... cells) {
        Map<BlockPos, BuilderPreviewState.Cell> byPosition = new LinkedHashMap<>();
        for (BuilderPreviewState.Cell cell : cells) byPosition.put(cell.pos(), cell);
        return BuilderPreviewState.indexMissingGroups(byPosition);
    }

    private static BuilderPreviewState.Cell missing(int x, int y, int z) {
        // Grouping and hit tests never inspect the state; null avoids registry bootstrap in JUnit.
        return new BuilderPreviewState.Cell(new BlockPos(x, y, z), null,
                BuilderPreviewState.Kind.MISSING, true);
    }
}
