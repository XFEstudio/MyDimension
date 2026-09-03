package com.xfestudio.mydimension.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Shared coarse replacement policy; server-side protection events remain authoritative. */
public final class BuilderReplacementPolicy {
    private BuilderReplacementPolicy() {
    }

    /** Replaceable states follow ordinary placement semantics and do not need to be broken first. */
    public static boolean requiresBreaking(BlockState existing) {
        return !existing.canBeReplaced();
    }

    /**
     * Rejects survival-unbreakable obstacles before any inventory or world mutation. Creative mode
     * deliberately bypasses only hardness; claims and Forge break/place hooks are still checked by
     * the authoritative execution path.
     */
    public static boolean canReplaceTarget(BlockState existing, BlockGetter level, BlockPos pos,
                                           boolean replacementEnabled, boolean creative) {
        if (!requiresBreaking(existing)) return true;
        return allowsDestructiveReplacement(replacementEnabled, creative,
                existing.getDestroySpeed(level, pos));
    }

    static boolean allowsDestructiveReplacement(boolean replacementEnabled, boolean creative,
                                                float destroySpeed) {
        return replacementEnabled && (creative || destroySpeed >= 0.0F);
    }
}
