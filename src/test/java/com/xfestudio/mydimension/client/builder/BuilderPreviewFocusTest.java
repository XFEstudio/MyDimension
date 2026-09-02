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

    @Test
    void sourceSelectionMissingTaskAndSurfacePreviewRemainIndependent() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Cell surface = surface(0, 0, 0);
        BuilderPreviewState.Cell missing = missing(1, 0, 0);
        UUID jobId = UUID.randomUUID();
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(4, 5, 6), new BlockPos(7, 8, 9));

        state.updateSurfacePreview(DIMENSION, List.of(surface));
        state.updateSelection(selection);
        state.updateMissingPreview(DIMENSION, List.of(missing), jobId, true, 8, false);

        BuilderPreviewState.Snapshot snapshot = state.snapshot();
        assertNotNull(snapshot);
        assertEquals(List.of(surface, missing), snapshot.cells());
        assertEquals(selection, snapshot.selection());
        assertEquals(jobId, snapshot.activeJobId());
        assertFalse(snapshot.blueprintPreview());
        assertTrue(snapshot.cancelable());
    }

    @Test
    void deploymentExclusivelyOwnsWorldOverlayWithoutLosingMissingLayer() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Cell deployment = surface(2, 3, 4);
        BuilderPreviewState.Cell collidingMissing = missing(2, 3, 4);
        BuilderPreviewState.Cell remoteMissing = missing(30, 40, 50);

        state.updateMissingPreview(DIMENSION, List.of(collidingMissing, remoteMissing),
                UUID.randomUUID(), true, 2, false);
        state.updateDeploymentPreview(DIMENSION, List.of(deployment));

        assertEquals(List.of(deployment), state.snapshot().cells());
        assertEquals(new AABB(2, 3, 4, 3, 4, 5), state.blueprintBounds(),
                "independent missing cells must not enlarge the deployment focus volume");

        state.clearPlacementPreview();

        assertEquals(List.of(collidingMissing, remoteMissing), state.snapshot().cells(),
                "hidden missing work must return immediately after deployment cancellation");
    }

    @Test
    void deploymentHidesOnlySurfaceAndCancellationRestoresBackgroundWorkflow() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Cell surface = surface(0, 0, 0);
        BuilderPreviewState.Cell missing = missing(1, 0, 0);
        BuilderPreviewState.Cell deployment = surface(2, 0, 0);
        UUID jobId = UUID.randomUUID();
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(5, 5, 5), new BlockPos(6, 6, 6));
        state.updateSurfacePreview(DIMENSION, List.of(surface));
        state.updateSelection(selection);
        state.updateMissingPreview(DIMENSION, List.of(missing), jobId, true, 4, false);

        state.updateDeploymentPreview(DIMENSION, List.of(deployment));

        assertTrue(state.snapshot().blueprintPreview());
        assertFalse(state.snapshot().cells().contains(surface));
        assertEquals(List.of(deployment), state.snapshot().cells());
        assertEquals(selection, state.snapshot().selection());
        assertEquals(jobId, state.snapshot().activeJobId());

        state.clearPlacementPreview();

        assertFalse(state.snapshot().blueprintPreview());
        assertEquals(List.of(missing), state.snapshot().cells());
        assertEquals(selection, state.snapshot().selection());
        assertEquals(jobId, state.snapshot().activeJobId());
    }

    @Test
    void completingSelectionDoesNotPretendToBeADeployment() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));

        state.updateSelection(selection);

        assertTrue(state.hasSaveableSelection());
        assertFalse(state.isBlueprintPreviewActive());
        assertNull(state.blueprintBounds());
    }

    @Test
    void transientEmptyServerPreviewKeepsSameAdvertisedJob() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Cell surface = surface(0, 0, 0);
        BuilderPreviewState.Cell missing = missing(1, 0, 0);
        UUID jobId = UUID.randomUUID();
        state.updateSurfacePreview(DIMENSION, List.of(surface));
        state.updateMissingPreview(DIMENSION, List.of(missing), jobId, true, 3, false);
        BuilderPreviewState.Snapshot before = state.snapshot();

        state.updateMissingPreview(DIMENSION, List.of(), null, false, 4, true);

        assertSame(before, state.snapshot());
        assertEquals(jobId, state.activeJobId());
        assertEquals(List.of(surface, missing), state.snapshot().cells());

        state.updateMissingPreview(DIMENSION, List.of(), null, false, 5, false);

        assertNull(state.activeJobId());
        assertEquals(List.of(surface), state.snapshot().cells());
    }

    @Test
    void clearingSurfaceDoesNotClearSelectionOrMissingTask() {
        BuilderPreviewState state = new BuilderPreviewState();
        BuilderPreviewState.Cell missing = missing(2, 0, 0);
        UUID jobId = UUID.randomUUID();
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                DIMENSION, new BlockPos(3, 3, 3), null);
        state.updateSurfacePreview(DIMENSION, List.of(surface(0, 0, 0)));
        state.updateSelection(selection);
        state.updateMissingPreview(DIMENSION, List.of(missing), jobId, true, 2, false);

        state.clearSurfacePreview();

        assertEquals(List.of(missing), state.snapshot().cells());
        assertEquals(selection, state.snapshot().selection());
        assertEquals(jobId, state.snapshot().activeJobId());
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

    private static BuilderPreviewState.Cell surface(int x, int y, int z) {
        return new BuilderPreviewState.Cell(new BlockPos(x, y, z), null,
                BuilderPreviewState.Kind.BUILD, false);
    }
}
