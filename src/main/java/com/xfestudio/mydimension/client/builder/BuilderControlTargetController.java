package com.xfestudio.mydimension.client.builder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Owns the short-lived Ctrl targeting gesture.
 *
 * <p>A normal Ctrl hold follows the extended block ray. Once the wheel is
 * moved during that same hold, targeting becomes a visible point along the
 * view vector, clamped just before the first obstruction. Releasing Ctrl
 * deliberately throws all of this state away, so an air distance can never
 * leak into the next gesture.</p>
 */
final class BuilderControlTargetController {
    private static final double MIN_AIR_DISTANCE = 0.05D;
    private static final double NORMAL_MIN_AIR_DISTANCE = 1.0D;
    private static final double HIT_FACE_EPSILON = 0.02D;

    private boolean held;
    private boolean airMode;
    private double distance;
    @Nullable private BuilderClientCommand.Target target;
    @Nullable private BlockPos consumedPosition;

    void tick(Minecraft minecraft, double maximumDistance) {
        if (!Screen.hasControlDown() || Screen.hasAltDown() || minecraft.screen != null
                || !BuilderClientServices.isHoldingRealmwright(minecraft)
                || minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }

        if (!held) {
            held = true;
            airMode = false;
            distance = initialDistance(minecraft, maximumDistance);
            consumedPosition = null;
        }
        recompute(minecraft, maximumDistance);
        BlockPos preview = previewPosition();
        if (preview != null && !preview.equals(consumedPosition)) {
            BuilderPreviewState.get().setControlCandidate(minecraft.level.dimension(), preview);
        } else {
            BuilderPreviewState.get().clearControlCandidate();
        }
    }

    boolean scroll(Minecraft minecraft, double delta, double maximumDistance) {
        int direction = (int) Math.signum(delta);
        if (direction == 0 || minecraft.player == null || minecraft.level == null) {
            return false;
        }
        if (!held) {
            held = true;
            distance = initialDistance(minecraft, maximumDistance);
        }
        airMode = true;
        distance = clampAirDistance(distance + direction, visibleAirLimit(minecraft, maximumDistance));
        consumedPosition = null;
        recompute(minecraft, maximumDistance);
        BlockPos preview = previewPosition();
        if (preview != null) {
            BuilderPreviewState.get().setControlCandidate(minecraft.level.dimension(), preview);
        }
        return true;
    }

    boolean isAirMode() {
        return held && airMode;
    }

    @Nullable
    BuilderClientCommand.Target target() {
        return target;
    }

    /** Hides the just-used blue candidate until the ray moves to another voxel. */
    void consumeCurrent() {
        consumedPosition = previewPosition();
        BuilderPreviewState.get().clearControlCandidate();
    }

    void reset() {
        held = false;
        airMode = false;
        distance = 0.0D;
        target = null;
        consumedPosition = null;
        BuilderPreviewState.get().clearControlCandidate();
    }

    private void recompute(Minecraft minecraft, double maximumDistance) {
        if (minecraft.player == null) {
            target = null;
            return;
        }
        if (!airMode) {
            HitResult picked = minecraft.player.pick(Math.max(1.0D, maximumDistance), 1.0F, false);
            if (picked instanceof BlockHitResult blockHit && picked.getType() == HitResult.Type.BLOCK) {
                target = BuilderClientEvents.target(blockHit, false);
            } else {
                target = null;
            }
            return;
        }

        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 view = minecraft.player.getViewVector(1.0F).normalize();
        distance = clampAirDistance(distance, visibleAirLimit(minecraft, maximumDistance));
        BlockPos position = BlockPos.containing(eye.add(view.scale(
                distance)));
        Direction face = Direction.getNearest((float) view.x, (float) view.y, (float) view.z);
        target = new BuilderClientCommand.Target(position, face,
                Vec3.atCenterOf(position), false, true);
    }

    @Nullable
    private BlockPos previewPosition() {
        if (target == null) {
            return null;
        }
        return target.blockPos();
    }

    private static double initialDistance(Minecraft minecraft, double maximumDistance) {
        if (minecraft.player == null) {
            return Math.min(5.0D, maximumDistance);
        }
        HitResult picked = minecraft.player.pick(Math.max(1.0D, maximumDistance), 1.0F, false);
        if (picked.getType() == HitResult.Type.BLOCK) {
            return clamp(minecraft.player.getEyePosition(1.0F).distanceTo(picked.getLocation()),
                    1.0D, Math.max(1.0D, maximumDistance));
        }
        return Math.min(5.0D, Math.max(1.0D, maximumDistance));
    }

    /**
     * Stops an air candidate immediately before the first real collision on
     * the extended client ray.  The epsilon keeps the selected voxel on the
     * player's side of the hit face, so the blue preview never advertises a
     * position behind a wall that the server must reject.
     */
    private static double visibleAirLimit(Minecraft minecraft, double maximumDistance) {
        double configuredLimit = Math.max(MIN_AIR_DISTANCE, maximumDistance);
        if (minecraft.player == null) return configuredLimit;
        HitResult picked = minecraft.player.pick(configuredLimit, 1.0F, false);
        if (picked.getType() != HitResult.Type.BLOCK) return configuredLimit;
        double hitDistance = minecraft.player.getEyePosition(1.0F).distanceTo(picked.getLocation());
        return Math.max(MIN_AIR_DISTANCE,
                Math.min(configuredLimit, hitDistance - HIT_FACE_EPSILON));
    }

    private static double clampAirDistance(double value, double upperLimit) {
        double safeUpper = Math.max(MIN_AIR_DISTANCE, upperLimit);
        double safeLower = Math.min(NORMAL_MIN_AIR_DISTANCE, safeUpper);
        return clamp(value, safeLower, safeUpper);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
