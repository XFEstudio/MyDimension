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
    void ghostRenderingUsesResidencyAndNeverExposesPartialUploads() {
        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderGhostModels(false, false));
        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderGhostModels(false, true));
        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderGhostModels(true, false));
        assertTrue(BuilderPreviewSectionMeshCache.shouldRenderGhostModels(true, true));

        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderBoundaryCap(false, false));
        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderBoundaryCap(false, true));
        assertTrue(BuilderPreviewSectionMeshCache.shouldRenderBoundaryCap(true, false),
                "a ready section must retain its outer face while its neighbour uploads");
        assertFalse(BuilderPreviewSectionMeshCache.shouldRenderBoundaryCap(true, true),
                "the cap and neighbour must switch atomically once both are drawable");
    }

    @Test
    void directionalCapsConsumeTheirActualUploadBudget() {
        assertEquals(1, BuilderPreviewSectionMeshCache.uploadBudgetCost(0));
        assertEquals(1, BuilderPreviewSectionMeshCache.uploadBudgetCost(1));
        assertEquals(4, BuilderPreviewSectionMeshCache.uploadBudgetCost(4),
                "one main VBO plus three cap VBOs must consume four slots");
    }

    @Test
    void modelResidencyAddsOneConnectedRingWithoutWalkingTheWholeBlueprint() {
        BuilderPreviewSectionMeshCache.SectionKey seed =
                new BuilderPreviewSectionMeshCache.SectionKey(0, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey west =
                new BuilderPreviewSectionMeshCache.SectionKey(-1, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey east =
                new BuilderPreviewSectionMeshCache.SectionKey(1, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey secondRing =
                new BuilderPreviewSectionMeshCache.SectionKey(2, 4, 0);
        Set<BuilderPreviewSectionMeshCache.SectionBoundary> connections = Set.of(
                new BuilderPreviewSectionMeshCache.SectionBoundary(seed, west),
                new BuilderPreviewSectionMeshCache.SectionBoundary(seed, east),
                new BuilderPreviewSectionMeshCache.SectionBoundary(east, secondRing));

        Set<BuilderPreviewSectionMeshCache.SectionKey> residency =
                BuilderPreviewSectionMeshCache.expandModelResidency(
                        Set.of(seed), connections);

        assertEquals(Set.of(west, seed, east), residency);
        assertFalse(residency.contains(secondRing),
                "guard-band expansion must not become a connected-component closure");
    }

    @Test
    void firstGenerationScopesAtomicEdgesToResidentNeighbourhood() {
        BuilderPreviewSectionMeshCache.SectionKey first =
                new BuilderPreviewSectionMeshCache.SectionKey(0, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey neighbour =
                new BuilderPreviewSectionMeshCache.SectionKey(1, 4, 0);
        BuilderPreviewSectionMeshCache.SectionKey remote =
                new BuilderPreviewSectionMeshCache.SectionKey(2, 4, 0);
        BuilderPreviewSectionMeshCache.SectionBoundary nearEdge =
                new BuilderPreviewSectionMeshCache.SectionBoundary(first, neighbour);
        BuilderPreviewSectionMeshCache.SectionBoundary remoteEdge =
                new BuilderPreviewSectionMeshCache.SectionBoundary(neighbour, remote);

        Set<BuilderPreviewSectionMeshCache.SectionBoundary> scoped =
                BuilderPreviewSectionMeshCache.publicationDependenciesForResidency(
                        Set.of(nearEdge, remoteEdge), Set.of(first, neighbour));
        List<Set<BuilderPreviewSectionMeshCache.SectionKey>> groups =
                BuilderPreviewSectionMeshCache.connectedPublicationGroups(
                        Set.of(first, neighbour, remote), scoped);

        assertEquals(Set.of(nearEdge), scoped);
        assertTrue(groups.contains(Set.of(first, neighbour)),
                "the first cross-boundary pair must publish atomically");
        assertTrue(groups.contains(Set.of(remote)),
                "a remote outline section must not pull the whole blueprint into the group");
    }

    @Test
    void initialConnectedBoundaryCreatesAtomicDependency() {
        assertTrue(BuilderPreviewSectionMeshCache.requiresAtomicBoundaryPublication(
                false, true, true, true));
        assertFalse(BuilderPreviewSectionMeshCache.requiresAtomicBoundaryPublication(
                false, false, true, true));
        assertFalse(BuilderPreviewSectionMeshCache.requiresAtomicBoundaryPublication(
                true, true, false, false));
    }

    @Test
    void publishedSingletonCannotFastRevertAndLeaveItsMeshBehind() {
        Object previous = new Object();
        Object replacement = new Object();

        boolean insertedSingleton = BuilderPreviewSectionMeshCache.sectionMapEntryChanged(
                null, replacement);
        assertTrue(insertedSingleton,
                "a newly published singleton must prevent the fast source-revert path");
        assertFalse(BuilderPreviewSectionMeshCache.canFastRevertToSource(
                true, insertedSingleton));
        assertTrue(BuilderPreviewSectionMeshCache.canFastRevertToSource(true, false));
        assertFalse(BuilderPreviewSectionMeshCache.canFastRevertToSource(false, false));
        assertTrue(BuilderPreviewSectionMeshCache.sectionMapEntryChanged(previous, null));
        assertTrue(BuilderPreviewSectionMeshCache.sectionMapEntryChanged(previous, replacement));
        assertFalse(BuilderPreviewSectionMeshCache.sectionMapEntryChanged(previous, previous));
        assertFalse(BuilderPreviewSectionMeshCache.sectionMapEntryChanged(null, null));
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
