package com.xfestudio.mydimension.client.builder;

import java.util.List;

/**
 * Implements the agreed Alt blueprint state machine:
 * navigation scrolls the action wheel, Alt+RMB activates; one-shot actions
 * execute immediately, while repeatable actions enter an adjustment state in
 * which scroll applies signed steps and Alt+RMB confirms.
 */
public final class BlueprintAltActionController {
    public enum Action {
        FLIP_X(false),
        FLIP_Y(false),
        FLIP_Z(false),
        ROTATE_Y(true),
        OFFSET_X(true),
        OFFSET_Y(true),
        OFFSET_Z(true),
        RESET(false),
        COPY_SELECTION(false),
        SAVE(false);

        private final boolean repeatable;

        Action(boolean repeatable) {
            this.repeatable = repeatable;
        }

        public boolean repeatable() {
            return repeatable;
        }

        public String translationKey() {
            return "action.mydimension.realmwright.blueprint." + name().toLowerCase(java.util.Locale.ROOT);
        }

    }

    public enum Phase {
        CLOSED,
        NAVIGATION,
        ADJUSTING
    }

    private static final List<Action> ACTIONS = List.of(Action.values());

    private Phase phase = Phase.CLOSED;
    private int highlighted;
    private Action adjusting;

    public Phase phase() {
        return phase;
    }

    public Action highlighted() {
        return ACTIONS.get(highlighted);
    }

    public int highlightedIndex() {
        return highlighted;
    }

    public Action adjusting() {
        return adjusting;
    }

    public List<Action> actions() {
        return ACTIONS;
    }

    public void openNavigation() {
        if (phase == Phase.CLOSED) {
            phase = Phase.NAVIGATION;
        }
    }

    /** Mirrors the physical Alt state so the wheel opens on key-down, before any scroll event arrives. */
    public void updateVisibility(boolean altDown, boolean available) {
        if (!available) {
            reset();
        } else if (altDown) {
            openNavigation();
        } else if (phase != Phase.CLOSED) {
            closeOnAltRelease();
        }
    }

    public void closeOnAltRelease() {
        if (phase == Phase.ADJUSTING && adjusting != null) {
            BuilderClientServices.send(new BuilderClientCommand.ConfirmBlueprintAdjustment(adjusting));
        }
        phase = Phase.CLOSED;
        adjusting = null;
    }

    public boolean scroll(double delta) {
        int direction = Integer.compare((int) Math.signum(delta), 0);
        if (direction == 0) {
            return false;
        }
        if (phase == Phase.CLOSED) {
            phase = Phase.NAVIGATION;
        }
        if (phase == Phase.ADJUSTING && adjusting != null) {
            BuilderClientServices.send(new BuilderClientCommand.AdjustBlueprint(adjusting, direction));
        } else {
            highlighted = Math.floorMod(highlighted - direction, ACTIONS.size());
        }
        return true;
    }

    public boolean activateOrConfirm() {
        if (phase == Phase.CLOSED) {
            phase = Phase.NAVIGATION;
        }
        if (phase == Phase.ADJUSTING && adjusting != null) {
            BuilderClientServices.send(new BuilderClientCommand.ConfirmBlueprintAdjustment(adjusting));
            adjusting = null;
            phase = Phase.NAVIGATION;
            return true;
        }

        Action action = highlighted();
        if (action.repeatable()) {
            adjusting = action;
            phase = Phase.ADJUSTING;
        } else {
            BuilderClientServices.send(new BuilderClientCommand.ExecuteBlueprintAction(action));
        }
        return true;
    }

    public void reset() {
        phase = Phase.CLOSED;
        adjusting = null;
    }
}
