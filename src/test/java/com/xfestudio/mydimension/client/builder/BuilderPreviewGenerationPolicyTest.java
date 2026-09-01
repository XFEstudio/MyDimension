package com.xfestudio.mydimension.client.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderPreviewGenerationPolicyTest {
    @Test
    void ordinaryOutlineSectionCanPromoteInOneFrame() {
        assertEquals(1, BuilderPreviewSectionMeshCache.maximumPromotionFrames(
                256, 0, false, false, 3));
    }

    @Test
    void fullSectionLatencyIsBoundedIndependentlyOfWholeBlueprintSize() {
        int fullSectionCells = 16 * 16 * 16;
        int projectedFrames = BuilderPreviewSectionMeshCache.maximumPromotionFrames(
                fullSectionCells, 0, true, true, 3);
        int missingFrames = BuilderPreviewSectionMeshCache.maximumPromotionFrames(
                fullSectionCells, fullSectionCells, true, true, 3);

        assertEquals(11, projectedFrames);
        assertEquals(17, missingFrames);
        assertTrue(missingFrames < 20,
                "one ready section must replace its predecessor without waiting for 65k cells");
    }

    @Test
    void continuouslyShrinkingSectionKeepsCurrentBuildAndCoalescesLatestSnapshot() {
        BuilderPreviewSectionMeshCache cache = new BuilderPreviewSectionMeshCache();
        BuilderPreviewState.Snapshot first = snapshot(64, 0);
        cache.advance(first, Integer.MAX_VALUE);

        BuilderPreviewState.Snapshot latest = null;
        for (int update = 1; update <= 20; update++) {
            latest = snapshot(64 - update, update);
            cache.advance(latest, Integer.MAX_VALUE);
        }

        assertSame(first, cache.inFlightSnapshotForTesting(),
                "per-tick progress must not restart an unfinished section upload");
        assertSame(latest, cache.queuedSnapshotForTesting(),
                "only the newest immutable progress snapshot should be retained");

        BuilderPreviewSectionMeshCache.LatestWinsQueue<BuilderPreviewState.Snapshot> handoff =
                new BuilderPreviewSectionMeshCache.LatestWinsQueue<>();
        for (int update = 1; update <= 20; update++) {
            handoff.offer(snapshot(64 - update, update));
        }
        assertEquals(44, handoff.take().cells().size(),
                "completion must advance directly to the newest coalesced snapshot");
        assertNull(handoff.take());
    }

    @Test
    void explicitEmptySnapshotBypassesInFlightAndQueuedGenerations() {
        BuilderPreviewSectionMeshCache cache = new BuilderPreviewSectionMeshCache();
        cache.advance(snapshot(64, 0), Integer.MAX_VALUE);
        cache.advance(snapshot(44, 20), Integer.MAX_VALUE);

        BuilderPreviewState.Snapshot cancelled = snapshot(0, 21);
        cache.advance(cancelled, Integer.MAX_VALUE);

        assertNull(cache.inFlightSnapshotForTesting());
        assertNull(cache.queuedSnapshotForTesting());
        assertTrue(cache.represents(cancelled));
    }

    @Test
    void startedGhostSectionCannotPublishPartiallyWhenPlayerMovesAway() {
        assertFalse(BuilderPreviewSectionMeshCache.requireGhostCompletion(false, false));
        assertTrue(BuilderPreviewSectionMeshCache.requireGhostCompletion(true, false));
        assertTrue(BuilderPreviewSectionMeshCache.requireGhostCompletion(false, true));
    }

    @Test
    void boundaryDependentSectionsFormOneAtomicPublicationGroup() {
        BuilderPreviewSectionMeshCache.SectionKey first =
                new BuilderPreviewSectionMeshCache.SectionKey(0, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey neighbour =
                new BuilderPreviewSectionMeshCache.SectionKey(1, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey independent =
                new BuilderPreviewSectionMeshCache.SectionKey(4, 4, 0);
        Set<BuilderPreviewSectionMeshCache.SectionKey> changed =
                new LinkedHashSet<>(List.of(first, neighbour, independent));
        Set<BuilderPreviewSectionMeshCache.SectionBoundary> dependencies = Set.of(
                new BuilderPreviewSectionMeshCache.SectionBoundary(first, neighbour));

        List<Set<BuilderPreviewSectionMeshCache.SectionKey>> groups =
                BuilderPreviewSectionMeshCache.connectedPublicationGroups(
                        changed, dependencies);

        assertEquals(2, groups.size());
        Set<BuilderPreviewSectionMeshCache.SectionKey> sharedGroup = groups.stream()
                .filter(group -> group.contains(first)).findFirst().orElseThrow();
        assertEquals(Set.of(first, neighbour), sharedGroup);
        assertFalse(BuilderPreviewSectionMeshCache.publicationGroupReady(
                sharedGroup, Set.of(first)::contains),
                "one ready side must not expose a new/old cross-section face pair");
        assertTrue(BuilderPreviewSectionMeshCache.publicationGroupReady(
                sharedGroup, Set.of(first, neighbour)::contains));

        Set<BuilderPreviewSectionMeshCache.SectionKey> independentGroup = groups.stream()
                .filter(group -> group.contains(independent)).findFirst().orElseThrow();
        assertTrue(BuilderPreviewSectionMeshCache.publicationGroupReady(
                independentGroup, Set.of(independent)::contains),
                "unrelated sections retain their one-section progressive publication");
    }

    private static BuilderPreviewState.Snapshot snapshot(int cellCount, int revision) {
        List<BuilderPreviewState.Cell> cells = new ArrayList<>(cellCount);
        for (int index = 0; index < cellCount; index++) {
            cells.add(new BuilderPreviewState.Cell(
                    new BlockPos(index & 3, index >> 4, index >> 2 & 3),
                    null, BuilderPreviewState.Kind.BLUEPRINT, false));
        }
        BuilderPreviewState.Selection selection = new BuilderPreviewState.Selection(
                null, null, null);
        return new BuilderPreviewState.Snapshot(null, cells, selection,
                null, true, false, revision);
    }
}
