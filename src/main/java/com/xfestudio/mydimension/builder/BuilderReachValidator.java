package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Server-side reach validation shared by surface and blueprint operations.
 *
 * <p>It deliberately validates rays towards the requested block instead of
 * replaying {@link net.minecraft.world.entity.player.Player#pick(double, float, boolean)}.
 * Replaying the current server rotation is overly strict when a C2S packet
 * arrives one or two ticks after the client preview was produced.  The target
 * is still bounded by reach and cannot be selected through an earlier wall.</p>
 */
public final class BuilderReachValidator {
    private static final double DISTANCE_GRACE = 2.0D;
    private static final double NEAR_TARGET_GRACE_SQR = 0.75D * 0.75D;
    private static final double SAMPLE_OFFSET = 0.34D;

    private BuilderReachValidator() {
    }

    public static boolean canReach(ServerPlayer player, BlockPos position) {
        Vec3 eye = player.getEyePosition();
        double nearestX = Mth.clamp(eye.x, position.getX(), position.getX() + 1.0D);
        double nearestY = Mth.clamp(eye.y, position.getY(), position.getY() + 1.0D);
        double nearestZ = Mth.clamp(eye.z, position.getZ(), position.getZ() + 1.0D);
        double reach = Math.max(4.5D, BuilderRuntime.settings().blockReach()) + DISTANCE_GRACE;
        return eye.distanceToSqr(nearestX, nearestY, nearestZ) <= reach * reach;
    }

    public static boolean canReachAndSee(ServerPlayer player, BlockPos position,
                                         @Nullable Direction preferredFace) {
        if (!canReach(player, position)) return false;
        Vec3 center = Vec3.atCenterOf(position);

        if (preferredFace != null) {
            Vec3 faceCenter = center.add(preferredFace.getStepX() * 0.501D,
                    preferredFace.getStepY() * 0.501D, preferredFace.getStepZ() * 0.501D);
            if (clearTo(player, position, faceCenter)) return true;

            Direction.Axis firstAxis = preferredFace.getAxis() == Direction.Axis.X
                    ? Direction.Axis.Y : Direction.Axis.X;
            Direction.Axis secondAxis = preferredFace.getAxis() == Direction.Axis.Z
                    ? Direction.Axis.Y : Direction.Axis.Z;
            for (int first : new int[]{-1, 1}) {
                if (clearTo(player, position, offset(faceCenter, firstAxis, first * SAMPLE_OFFSET))) return true;
            }
            for (int second : new int[]{-1, 1}) {
                if (clearTo(player, position, offset(faceCenter, secondAxis, second * SAMPLE_OFFSET))) return true;
            }
        }

        if (clearTo(player, position, center)) return true;
        // Air anchors and selection corners have no preferred face. Sampling
        // the six inset face centres makes edges and partial collision shapes
        // reliable without allowing rays through a wall well before the target.
        for (Direction direction : Direction.values()) {
            Vec3 sample = center.add(direction.getStepX() * 0.49D,
                    direction.getStepY() * 0.49D, direction.getStepZ() * 0.49D);
            if (clearTo(player, position, sample)) return true;
        }
        return false;
    }

    /**
     * Visibility check for a Ctrl-wheel air anchor.  The client chooses a voxel
     * along the view ray, not necessarily its centre, so centre-only replay can
     * falsely reject a cell beside an edge.  Sample the point of the voxel that
     * faces the player's eye first, then retain the ordinary centre/face checks.
     * Every sample still uses the world's collision clip, so an earlier wall is
     * never skipped.
     */
    public static boolean canReachAirAnchor(ServerPlayer player, BlockPos position) {
        if (!canReach(player, position)) return false;
        Vec3 eye = player.getEyePosition();
        double inset = 0.08D;
        Vec3 eyeFacingPoint = new Vec3(
                Mth.clamp(eye.x, position.getX() + inset, position.getX() + 1.0D - inset),
                Mth.clamp(eye.y, position.getY() + inset, position.getY() + 1.0D - inset),
                Mth.clamp(eye.z, position.getZ() + inset, position.getZ() + 1.0D - inset));
        if (clearTo(player, position, eyeFacingPoint)) return true;
        return canReachAndSee(player, position, null);
    }

    public static BlockHitResult validatedHit(ServerPlayer player, BlockPos position,
                                               Direction face, boolean inside) {
        if (!canReachAndSee(player, position, face)) return null;
        Vec3 center = Vec3.atCenterOf(position);
        Vec3 location = center.add(face.getStepX() * 0.501D,
                face.getStepY() * 0.501D, face.getStepZ() * 0.501D);
        return new BlockHitResult(location, face, position, inside);
    }

    private static boolean clearTo(ServerPlayer player, BlockPos requested, Vec3 target) {
        BlockHitResult actual = player.serverLevel().clip(new ClipContext(player.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (actual.getType() == HitResult.Type.MISS) return true;
        if (actual.getBlockPos().equals(requested)
                || actual.getBlockPos().relative(actual.getDirection()).equals(requested)) {
            return true;
        }
        return actual.getLocation().distanceToSqr(target) <= NEAR_TARGET_GRACE_SQR;
    }

    private static Vec3 offset(Vec3 value, Direction.Axis axis, double amount) {
        return switch (axis) {
            case X -> value.add(amount, 0.0D, 0.0D);
            case Y -> value.add(0.0D, amount, 0.0D);
            case Z -> value.add(0.0D, 0.0D, amount);
        };
    }
}
