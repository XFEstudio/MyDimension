package com.xfestudio.mydimension.client.builder;

/**
 * Resolves which world-overlay layers may be drawn for the current modifier gesture.
 *
 * <p>With no deployment, holding Ctrl gives the source corners, their cuboid, and the moving
 * candidate exclusive ownership of the overlay. With a deployment, Ctrl instead gives that
 * movable blueprint and its candidate exclusive ownership. Hidden layers remain untouched so
 * they can reappear immediately when Ctrl is released.</p>
 */
enum BuilderPreviewVisibility {
    NORMAL,
    BLUEPRINT_SELECTION_ONLY,
    BLUEPRINT_DEPLOYMENT_ONLY;

    static BuilderPreviewVisibility forModifiers(boolean controlDown, boolean altDown,
                                                 boolean deploymentActive) {
        if (!controlDown || altDown) return NORMAL;
        return deploymentActive ? BLUEPRINT_DEPLOYMENT_ONLY : BLUEPRINT_SELECTION_ONLY;
    }

    boolean controlGesture() {
        return this != NORMAL;
    }

    boolean selectsBlueprintPointOnUse() {
        return this == BLUEPRINT_SELECTION_ONLY;
    }

    boolean placesBlueprintOnUse() {
        return this == BLUEPRINT_DEPLOYMENT_ONLY;
    }

    boolean pausesSurfacePlanning() {
        return controlGesture();
    }

    boolean showsCachedCells(boolean cachedBlueprintPreview) {
        return this == NORMAL
                || (this == BLUEPRINT_DEPLOYMENT_ONLY && cachedBlueprintPreview);
    }

    boolean showsAnchors() {
        return this == NORMAL;
    }

    boolean showsDeploymentFrame() {
        return this != BLUEPRINT_SELECTION_ONLY;
    }

    boolean showsSelection(boolean deploymentActive) {
        return this == BLUEPRINT_SELECTION_ONLY
                || (this == NORMAL && !deploymentActive);
    }

    boolean showsFocus(BuilderPreviewState.FocusKind kind) {
        return switch (this) {
            case NORMAL -> true;
            case BLUEPRINT_SELECTION_ONLY -> kind == BuilderPreviewState.FocusKind.SELECTION
                    || kind == BuilderPreviewState.FocusKind.CANDIDATE;
            case BLUEPRINT_DEPLOYMENT_ONLY -> kind == BuilderPreviewState.FocusKind.DEPLOYMENT
                    || kind == BuilderPreviewState.FocusKind.CANDIDATE;
        };
    }
}
