package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuilderToolScreenWorkflowTest {
    @Test
    void missingSelectionAndDeploymentAreListedAsIndependentRows() {
        UUID jobId = UUID.randomUUID();
        BuilderClientSnapshot server = snapshot(jobId, 3, 8);
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                null, new BlockPos(1, 2, 3), new BlockPos(4, 6, 8));
        BuilderPreviewState.Snapshot preview = new BuilderPreviewState.Snapshot(null, List.of(
                cell(BuilderPreviewState.Kind.MISSING),
                cell(BuilderPreviewState.Kind.BUILD)), selection,
                jobId, true, true, 1);

        List<BuilderToolScreen.WorkflowRow> rows = BuilderToolScreen.workflowRows(server, preview);

        assertEquals(List.of(
                        BuilderToolScreen.WorkflowKind.MISSING,
                        BuilderToolScreen.WorkflowKind.SELECTION,
                        BuilderToolScreen.WorkflowKind.DEPLOYMENT),
                rows.stream().map(BuilderToolScreen.WorkflowRow::kind).toList());
    }

    @Test
    void sourceSelectionAloneDoesNotCreateAMissingOrDeploymentRow() {
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                null, BlockPos.ZERO, null);
        BuilderPreviewState.Snapshot preview = new BuilderPreviewState.Snapshot(
                null, List.of(), selection, null, false, true, 1);

        List<BuilderToolScreen.WorkflowRow> rows = BuilderToolScreen.workflowRows(
                BuilderClientSnapshot.EMPTY, preview);

        assertEquals(List.of(BuilderToolScreen.WorkflowKind.SELECTION),
                rows.stream().map(BuilderToolScreen.WorkflowRow::kind).toList());
    }

    private static BuilderClientSnapshot snapshot(UUID activeJobId, int completed, int total) {
        return new BuilderClientSnapshot(true, BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK,
                false, false, 256, 64, 4_096, 1_024, 64, "", activeJobId,
                completed, total, false, false, List.of(), List.of(), null);
    }

    private static BuilderPreviewState.Cell cell(BuilderPreviewState.Kind kind) {
        return new BuilderPreviewState.Cell(BlockPos.ZERO, null, kind, true);
    }
}
