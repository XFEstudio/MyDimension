package com.xfestudio.mydimension.client.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuilderAnchorPreviewTrackerTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.tryParse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.tryParse("minecraft:the_nether");

    @Test
    void rendersOnlyCurrentScepterBindingsInTheirSupplyOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID notBound = UUID.randomUUID();
        BlockPos firstPos = new BlockPos(12, 70, -4);
        BlockPos secondPos = new BlockPos(-31, 20, 48);

        List<BuilderClientSnapshot.AnchorView> allKnown = List.of(
                anchor(notBound, new BlockPos(1, 2, 3), OVERWORLD,
                        BuilderClientSnapshot.AnchorStatus.AVAILABLE),
                anchor(first, firstPos, OVERWORLD, BuilderClientSnapshot.AnchorStatus.UNLOADED),
                anchor(second, secondPos, OVERWORLD, BuilderClientSnapshot.AnchorStatus.FORBIDDEN));

        assertEquals(List.of(secondPos, firstPos), BuilderAnchorPreviewTracker.filterPositions(
                OVERWORLD, List.of(second, first), allKnown));
    }

    @Test
    void rejectsOtherDimensionsAndUnresolvedEntriesWithoutUsingPackedZeroAsASentinel() {
        UUID origin = UUID.randomUUID();
        UUID otherDimension = UUID.randomUUID();
        UUID disconnected = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();

        List<BuilderClientSnapshot.AnchorView> known = List.of(
                anchor(origin, BlockPos.ZERO, OVERWORLD, BuilderClientSnapshot.AnchorStatus.AVAILABLE),
                anchor(otherDimension, new BlockPos(4, 5, 6), NETHER,
                        BuilderClientSnapshot.AnchorStatus.AVAILABLE),
                anchor(disconnected, new BlockPos(7, 8, 9), OVERWORLD,
                        BuilderClientSnapshot.AnchorStatus.DISCONNECTED),
                anchor(unknown, new BlockPos(10, 11, 12), OVERWORLD,
                        BuilderClientSnapshot.AnchorStatus.UNKNOWN));

        assertEquals(List.of(BlockPos.ZERO), BuilderAnchorPreviewTracker.filterPositions(OVERWORLD,
                List.of(origin, otherDimension, disconnected, unknown), known));
    }

    @Test
    void bindingMatchIsOrderedSoAChangedMainHandForcesFreshMetadata() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<BuilderClientSnapshot.AnchorView> known = List.of(
                anchor(first, BlockPos.ZERO, OVERWORLD, BuilderClientSnapshot.AnchorStatus.AVAILABLE),
                anchor(second, BlockPos.ZERO, OVERWORLD, BuilderClientSnapshot.AnchorStatus.UNLOADED));

        assertTrue(BuilderAnchorPreviewTracker.matchesBindings(List.of(first, second), known));
        assertFalse(BuilderAnchorPreviewTracker.matchesBindings(List.of(second, first), known));
        assertFalse(BuilderAnchorPreviewTracker.matchesBindings(List.of(first), known));
    }

    @Test
    void evictsAResolvedPositionWhenTheLoadedAnchorIdentityNoLongerMatches() {
        UUID removed = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        BlockPos removedPos = new BlockPos(5, 40, 7);
        BlockPos remainingPos = new BlockPos(6, 40, 7);
        List<BuilderClientSnapshot.AnchorView> cached = List.of(
                anchor(removed, removedPos, OVERWORLD, BuilderClientSnapshot.AnchorStatus.AVAILABLE),
                anchor(remaining, remainingPos, OVERWORLD, BuilderClientSnapshot.AnchorStatus.AVAILABLE));

        assertEquals(List.of(remainingPos), BuilderAnchorPreviewTracker.filterPositions(
                OVERWORLD, List.of(removed, remaining), cached,
                (id, ignoredPosition) -> !id.equals(removed)));
    }

    private static BuilderClientSnapshot.AnchorView anchor(
            UUID id, BlockPos position, ResourceLocation dimension,
            BuilderClientSnapshot.AnchorStatus status) {
        return new BuilderClientSnapshot.AnchorView(id, "anchor", dimension, position.asLong(),
                status, false, false);
    }
}
