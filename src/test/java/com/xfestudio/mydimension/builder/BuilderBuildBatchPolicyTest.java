package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderBuildBatchPolicyTest {
    @Test
    void stabilizationAreaDeduplicatesTargetsAndDirectNeighbours() {
        BlockPos first = BlockPos.ZERO;
        BlockPos second = first.east();

        List<BlockPos> positions = BuilderOperationManager.buildStabilizationPositions(
                List.of(first, second, first));

        assertEquals(12, positions.size());
        assertTrue(positions.contains(first));
        assertTrue(positions.contains(second));
        assertTrue(positions.contains(first.west()));
        assertTrue(positions.contains(second.east()));
    }

    @Test
    void neighbourNotificationsDeduplicateSharedDirectedRelationships() {
        BlockPos first = BlockPos.ZERO;
        BlockPos second = first.east();

        List<BuilderOperationManager.NeighborNotification> notifications =
                BuilderOperationManager.buildNeighborNotifications(List.of(first, second, first));

        assertEquals(22, notifications.size());
        assertEquals(notifications.size(), new HashSet<>(notifications).size());
        assertEquals(1L, notifications.stream()
                .filter(value -> value.target().equals(first) && value.source().equals(second)).count());
        assertEquals(1L, notifications.stream()
                .filter(value -> value.target().equals(second) && value.source().equals(first)).count());
    }

    @Test
    void successfulTargetsSkipRedundantBlockEntityTagComparison() {
        assertFalse(BuilderOperationManager.requiresBlockEntityComparison(true));
        assertTrue(BuilderOperationManager.requiresBlockEntityComparison(false));
    }

    @Test
    void deferredFirstTickIsRestrictedToVettedStructuralBlockEntities() {
        assertTrue(BuilderOperationManager.requiresDeferredStructuralFirstTick(
                new ResourceLocation("create", "item_vault")));
        assertFalse(BuilderOperationManager.requiresDeferredStructuralFirstTick(
                new ResourceLocation("minecraft", "furnace")));
        assertFalse(BuilderOperationManager.requiresDeferredStructuralFirstTick(
                new ResourceLocation("minecraft", "moving_piston")));
    }
}
