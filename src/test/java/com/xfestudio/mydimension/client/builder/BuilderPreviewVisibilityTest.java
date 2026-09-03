package com.xfestudio.mydimension.client.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderPreviewVisibilityTest {
    @Test
    void controlGestureShowsOnlyBlueprintSelectionObjects() {
        BuilderPreviewVisibility visibility = BuilderPreviewVisibility.forModifiers(
                true, false, false);

        assertTrue(visibility.controlGesture());
        assertTrue(visibility.selectsBlueprintPointOnUse());
        assertFalse(visibility.placesBlueprintOnUse());
        assertTrue(visibility.pausesSurfacePlanning());
        assertFalse(visibility.showsCachedCells(false));
        assertFalse(visibility.showsCachedCells(true));
        assertFalse(visibility.showsAnchors());
        assertFalse(visibility.showsDeploymentFrame());
        assertTrue(visibility.showsSelection(true));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.SELECTION));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.CANDIDATE));
        assertFalse(visibility.showsFocus(BuilderPreviewState.FocusKind.DEPLOYMENT));
        assertFalse(visibility.showsFocus(BuilderPreviewState.FocusKind.MISSING));
    }

    @Test
    void controlWithDeploymentPlacesAndShowsOnlyThatDeployment() {
        BuilderPreviewVisibility visibility = BuilderPreviewVisibility.forModifiers(
                true, false, true);

        assertTrue(visibility.controlGesture());
        assertFalse(visibility.selectsBlueprintPointOnUse());
        assertTrue(visibility.placesBlueprintOnUse());
        assertTrue(visibility.pausesSurfacePlanning());
        assertFalse(visibility.showsCachedCells(false));
        assertTrue(visibility.showsCachedCells(true));
        assertFalse(visibility.showsAnchors());
        assertTrue(visibility.showsDeploymentFrame());
        assertFalse(visibility.showsSelection(true));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.DEPLOYMENT));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.CANDIDATE));
        assertFalse(visibility.showsFocus(BuilderPreviewState.FocusKind.SELECTION));
        assertFalse(visibility.showsFocus(BuilderPreviewState.FocusKind.MISSING));
    }

    @Test
    void releasingControlRestoresNormalLayerVisibility() {
        BuilderPreviewVisibility visibility = BuilderPreviewVisibility.forModifiers(
                false, false, true);

        assertFalse(visibility.controlGesture());
        assertFalse(visibility.pausesSurfacePlanning());
        assertTrue(visibility.showsCachedCells(false));
        assertTrue(visibility.showsCachedCells(true));
        assertTrue(visibility.showsAnchors());
        assertTrue(visibility.showsDeploymentFrame());
        assertFalse(visibility.showsSelection(true));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.DEPLOYMENT));
        assertTrue(visibility.showsFocus(BuilderPreviewState.FocusKind.MISSING));
    }

    @Test
    void altWheelTakesPriorityOverControlSelectionMode() {
        BuilderPreviewVisibility visibility = BuilderPreviewVisibility.forModifiers(
                true, true, true);

        assertFalse(visibility.controlGesture());
        assertTrue(visibility.showsCachedCells(false));
        assertTrue(visibility.showsAnchors());
    }
}
