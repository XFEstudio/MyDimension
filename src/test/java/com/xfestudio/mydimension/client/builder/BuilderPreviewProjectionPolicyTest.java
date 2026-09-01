package com.xfestudio.mydimension.client.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderPreviewProjectionPolicyTest {
    @Test
    void ordinarySurfaceBuildRetainsConcreteBlockProjection() {
        assertTrue(BuilderPreviewSectionMeshCache.permitsGhostKind(
                BuilderPreviewState.Kind.BUILD, false));
    }

    @Test
    void blueprintBuildRetainsConcreteBlockProjection() {
        assertTrue(BuilderPreviewSectionMeshCache.permitsGhostKind(
                BuilderPreviewState.Kind.BUILD, true));
    }

    @Test
    void missingMaterialProjectionIsIndependentOfBlueprintContext() {
        assertTrue(BuilderPreviewSectionMeshCache.permitsGhostKind(
                BuilderPreviewState.Kind.MISSING, false));
        assertTrue(BuilderPreviewSectionMeshCache.permitsGhostKind(
                BuilderPreviewState.Kind.MISSING, true));
    }
}
