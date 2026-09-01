package com.xfestudio.mydimension.client.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderClientInteractionPolicyTest {
    @Test
    void vanillaFallbackRequiresTheSameBlockFaceAndInsideFlag() {
        BlockHitResult requested = hit(new BlockPos(1, 2, 3), Direction.NORTH, false);

        assertTrue(BuilderClientEvents.sameVanillaBlockTarget(
                hit(new BlockPos(1, 2, 3), Direction.NORTH, false), requested));
        assertFalse(BuilderClientEvents.sameVanillaBlockTarget(
                hit(new BlockPos(2, 2, 3), Direction.NORTH, false), requested));
        assertFalse(BuilderClientEvents.sameVanillaBlockTarget(
                hit(new BlockPos(1, 2, 3), Direction.SOUTH, false), requested));
        assertFalse(BuilderClientEvents.sameVanillaBlockTarget(
                hit(new BlockPos(1, 2, 3), Direction.NORTH, true), requested));
        assertFalse(BuilderClientEvents.sameVanillaBlockTarget(
                BlockHitResult.miss(Vec3.ZERO, Direction.NORTH, BlockPos.ZERO), requested));
    }

    private static BlockHitResult hit(BlockPos position, Direction face, boolean inside) {
        return new BlockHitResult(Vec3.atCenterOf(position), face, position, inside);
    }
}
