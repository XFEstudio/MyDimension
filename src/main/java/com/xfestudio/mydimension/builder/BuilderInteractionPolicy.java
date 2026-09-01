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

import java.lang.reflect.Method;
import java.util.Arrays;

/** Shared client/server policy for giving right-clickable blocks priority over the scepter. */
public final class BuilderInteractionPolicy {
    private static final Class<?>[] USE_PARAMETERS = {
            BlockState.class,
            Level.class,
            BlockPos.class,
            Player.class,
            InteractionHand.class,
            BlockHitResult.class
    };

    /**
     * Cache by implementation class so the render-tick preview check never repeatedly scans methods.
     * Matching by signature, rather than a reflected method name, also remains valid after production
     * obfuscation. Modded blocks with a normal {@code use} override are covered automatically.
     */
    private static final ClassValue<Boolean> CUSTOM_USE_HANDLER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Method method : type.getMethods()) {
                if (method.getReturnType() != InteractionResult.class
                        || !Arrays.equals(method.getParameterTypes(), USE_PARAMETERS)) {
                    continue;
                }
                Class<?> declaration = method.getDeclaringClass();
                // StairBlock only forwards the call to its source block, while the vanilla
                // fletching table's placeholder handler always returns PASS. Neither advertises
                // a real interaction and both must remain valid construction surfaces unless a
                // subclass adds its own handler.
                return declaration != BlockBehaviour.class
                        && declaration != Block.class
                        && declaration != StairBlock.class
                        && declaration != FletchingTableBlock.class;
            }
            return false;
        }
    };

    private BuilderInteractionPolicy() {
    }

    /**
     * Treat block entities (including supply anchors and modded machines) and blocks declaring a
     * right-click handler as interaction-priority targets. This deliberately errs on the safe side:
     * holding the scepter must not modify a block that advertises a normal interaction path.
     */
    public static boolean prioritizesBlock(BlockState state) {
        return prioritizesBlock(state.hasBlockEntity(), state.getBlock().getClass());
    }

    /** Shift is the explicit request to let the scepter override an interaction-priority block. */
    public static boolean permitsScepter(boolean shiftDown, BlockState state) {
        return permitsScepter(shiftDown, prioritizesBlock(state));
    }

    static boolean prioritizesBlock(boolean hasBlockEntity, Class<?> blockType) {
        return hasBlockEntity || CUSTOM_USE_HANDLER.get(blockType);
    }

    static boolean permitsScepter(boolean shiftDown, boolean interactionPriority) {
        return shiftDown || !interactionPriority;
    }
}
