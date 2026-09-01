package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Client intents emitted by the builder UI. The networking layer deliberately
 * lives outside this package and translates these immutable commands to C2S
 * packets; the server remains authoritative for every world change.
 */
public sealed interface BuilderClientCommand {
    record Target(BlockPos blockPos, Direction face, Vec3 location, boolean inside, boolean direct) {
        public Target {
            blockPos = blockPos.immutable();
        }

        public Target(BlockPos blockPos, Direction face, Vec3 location, boolean inside) {
            this(blockPos, face, location, inside, false);
        }
    }

    record OpenMenu() implements BuilderClientCommand {
    }

    record SetMode(BuilderMode mode) implements BuilderClientCommand {
    }

    record SetSurfaceMatch(SurfaceMatchMode mode) implements BuilderClientCommand {
    }

    record SetLimits(int buildLimit, int demolishLimit) implements BuilderClientCommand {
    }

    record SetHistoryRecording(boolean enabled) implements BuilderClientCommand {
    }

    record SelectBlueprintPoint(Target target) implements BuilderClientCommand {
    }

    record UseTarget(Target target, UseKind kind, UUID activeJobId,
                     boolean interactionOverride) implements BuilderClientCommand {
        public UseTarget(Target target, UseKind kind, UUID activeJobId) {
            this(target, kind, activeJobId, false);
        }
    }

    record CancelActive(UUID activeJobId) implements BuilderClientCommand {
    }

    /** Cancels only queued/running blueprint placement, never a material-waiting surface task. */
    record CancelBlueprint() implements BuilderClientCommand {
    }

    record Undo() implements BuilderClientCommand {
    }

    record Redo() implements BuilderClientCommand {
    }

    record UnbindAnchor(UUID anchorId) implements BuilderClientCommand {
    }

    record MoveAnchor(UUID anchorId, int delta) implements BuilderClientCommand {
    }

    record SetAnchorPublic(UUID anchorId, boolean publicAccess) implements BuilderClientCommand {
    }

    record SetAnchorPlayer(UUID anchorId, String playerName, boolean authorize) implements BuilderClientCommand {
        public SetAnchorPlayer {
            playerName = playerName == null ? "" : playerName.strip();
        }
    }

    record SelectBlueprint(UUID blueprintId) implements BuilderClientCommand {
    }

    record ExecuteBlueprintAction(BlueprintAltActionController.Action action) implements BuilderClientCommand {
    }

    record AdjustBlueprint(BlueprintAltActionController.Action action, int direction) implements BuilderClientCommand {
        public AdjustBlueprint {
            direction = Integer.compare(direction, 0);
        }
    }

    record ConfirmBlueprintAdjustment(BlueprintAltActionController.Action action) implements BuilderClientCommand {
    }

    enum UseKind {
        AUTO,
        RESUME_MISSING,
        PLACE_BLUEPRINT
    }
}
