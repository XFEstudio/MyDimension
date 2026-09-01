package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** Immutable server/UI state used by {@link BuilderToolScreen}. */
public record BuilderClientSnapshot(
        boolean enabled,
        BuilderMode mode,
        SurfaceMatchMode surfaceMatch,
        boolean historyRecording,
        int buildLimit,
        int demolishLimit,
        int maximumBuildLimit,
        int maximumDemolishLimit,
        int reach,
        String status,
        UUID activeJobId,
        int completedBlocks,
        int totalBlocks,
        boolean canUndo,
        boolean canRedo,
        List<AnchorView> anchors,
        List<HistoryView> history,
        UUID selectedBlueprintId
) {
    public static final BuilderClientSnapshot EMPTY = new BuilderClientSnapshot(
            true, BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, false,
            256, 64, 4096, 1024, 64, "", null,
            0, 0, false, false, List.of(), List.of(), null
    );

    public BuilderClientSnapshot {
        anchors = List.copyOf(anchors);
        history = List.copyOf(history);
        status = status == null ? "" : status;
    }

    public record AnchorView(UUID id, String name, ResourceLocation dimension, long packedPos,
                             AnchorStatus status, boolean owner, boolean publicAccess) {
        public AnchorView {
            name = name == null || name.isBlank() ? id.toString() : name;
        }
    }

    public enum AnchorStatus {
        AVAILABLE,
        UNLOADED,
        DISCONNECTED,
        FORBIDDEN,
        UNKNOWN
    }

    public record HistoryView(UUID id, String label, String dimension, int changedBlocks,
                              long createdAt, HistoryStatus status) {
        public HistoryView {
            label = label == null ? "" : label;
            dimension = dimension == null ? "" : dimension;
        }
    }

    public enum HistoryStatus {
        RUNNING,
        INCOMPLETE,
        COMPLETE,
        UNDONE,
        CONFLICTED
    }
}
