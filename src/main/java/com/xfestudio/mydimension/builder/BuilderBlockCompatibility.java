package com.xfestudio.mydimension.builder;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit compatibility rules shared by surface discovery and construction material selection.
 * Rules deliberately name concrete blocks/items rather than broad tags: a dirt-like mod block,
 * mycelium or podzol must never become an implicit substitute merely because it shares a tag.
 */
public final class BuilderBlockCompatibility {
    private static final List<Rule> RULES = List.of(
            new Rule(Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT),
                    Map.of(Blocks.GRASS_BLOCK, List.of(Items.DIRT)))
    );

    private BuilderBlockCompatibility() {
    }

    /** Same-block matching with the small, explicit equivalence groups above. */
    public static boolean sameSurfaceType(BlockState first, BlockState second) {
        Block firstBlock = first.getBlock();
        Block secondBlock = second.getBlock();
        if (firstBlock == secondBlock) return true;
        for (Rule rule : RULES) {
            if (rule.surfaceBlocks.contains(firstBlock) && rule.surfaceBlocks.contains(secondBlock)) return true;
        }
        return false;
    }

    /** Ordered fallbacks after the target block's own item has been tried. */
    public static List<Item> constructionFallbacks(BlockState target) {
        for (Rule rule : RULES) {
            List<Item> fallbacks = rule.fallbacks.get(target.getBlock());
            if (fallbacks != null) return fallbacks;
        }
        return List.of();
    }

    private record Rule(Set<Block> surfaceBlocks, Map<Block, List<Item>> fallbacks) {
        private Rule {
            surfaceBlocks = Set.copyOf(surfaceBlocks);
            fallbacks = Map.copyOf(fallbacks);
        }
    }
}
