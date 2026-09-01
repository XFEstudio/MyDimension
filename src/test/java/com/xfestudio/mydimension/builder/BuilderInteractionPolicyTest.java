package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FletchingTableBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderInteractionPolicyTest {
    @Test
    void blockEntitiesAlwaysReceiveOrdinaryRightClickPriority() {
        assertTrue(BuilderInteractionPolicy.prioritizesBlock(true, PassiveBlock.class));
    }

    @Test
    void customUseHandlerReceivesPriorityButPlainBlockDoesNot() {
        assertTrue(BuilderInteractionPolicy.prioritizesBlock(false, InteractiveBlock.class));
        assertFalse(BuilderInteractionPolicy.prioritizesBlock(false, PassiveBlock.class));
    }

    @Test
    void vanillaStairDelegationIsNotMistakenForAnInteraction() {
        assertFalse(BuilderInteractionPolicy.prioritizesBlock(false, StairBlock.class));
        assertFalse(BuilderInteractionPolicy.prioritizesBlock(false, FletchingTableBlock.class));
        assertFalse(BuilderInteractionPolicy.prioritizesBlock(false, Block.class));
        assertFalse(BuilderInteractionPolicy.prioritizesBlock(false, BlockBehaviour.class));
    }

    @Test
    void shiftExplicitlyOverridesInteractionPriority() {
        assertFalse(BuilderInteractionPolicy.permitsScepter(false, true));
        assertTrue(BuilderInteractionPolicy.permitsScepter(true, true));
        assertTrue(BuilderInteractionPolicy.permitsScepter(false, false));
    }

    private static final class PassiveBlock extends Block {
        private PassiveBlock(Properties properties) {
            super(properties);
        }
    }

    private static final class InteractiveBlock extends Block {
        private InteractiveBlock(Properties properties) {
            super(properties);
        }

        @Override
        @SuppressWarnings("deprecation")
        public InteractionResult use(BlockState state, Level level, BlockPos position,
                                     Player player, InteractionHand hand, BlockHitResult hit) {
            return InteractionResult.SUCCESS;
        }
    }
}
