package com.xfestudio.mydimension.builder.blueprint;

import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, authoritative placement plan. Resource consumption, protection hooks, undo recording and
 * actual world changes are deliberately delegated to the builder transaction engine.
 */
public final class BlueprintPlacementPlan {
    public record PlannedBlock(BlockPos relativePos, BlockPos worldPos, BlockState state,
                               @Nullable CompoundTag blockEntityTag) {
        public PlannedBlock {
            relativePos = relativePos.immutable();
            worldPos = worldPos.immutable();
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        @Override
        public CompoundTag blockEntityTag() {
            return blockEntityTag == null ? null : blockEntityTag.copy();
        }
    }

    @FunctionalInterface
    public interface PlacementHook {
        PlacementDecision apply(ServerPlayer player, PlannedBlock block);
    }

    public enum PlacementDecision {
        PLACE,
        SKIP_ALREADY_MATCHING,
        SKIP_PROTECTED,
        SKIP_UNSUPPORTED,
        WAIT_FOR_RESOURCES
    }

    private final BlueprintData blueprint;
    private final BlueprintTransform transform;
    private final BlockPos targetAnchor;
    private final BlockPos origin;
    private final List<PlannedBlock> blocks;

    private BlueprintPlacementPlan(BlueprintData blueprint, BlueprintTransform transform,
                                   BlockPos targetAnchor, BlockPos origin, List<PlannedBlock> blocks) {
        this.blueprint = blueprint;
        this.transform = transform;
        this.targetAnchor = targetAnchor.immutable();
        this.origin = origin.immutable();
        this.blocks = List.copyOf(blocks);
    }

    public static BlueprintPlacementPlan create(BlueprintData blueprint, BlueprintTransform transform,
                                                BlockPos targetAnchor) {
        BlockPos transformedAnchor = transform.transform(blueprint.anchor(), blueprint.sizeX(),
                blueprint.sizeY(), blueprint.sizeZ());
        BlockPos origin = targetAnchor.subtract(transformedAnchor);
        List<PlannedBlock> result = new ArrayList<>(blueprint.blocks().size());
        for (BlueprintData.BlockEntry entry : blueprint.blocks()) {
            BlockPos relative = transform.transform(entry.pos(), blueprint.sizeX(), blueprint.sizeY(),
                    blueprint.sizeZ());
            result.add(new PlannedBlock(relative, origin.offset(relative), transform.transform(blueprint.state(entry)),
                    entry.blockEntityTag()));
        }
        // Keep every target chunk contiguous.  BlueprintTaskManager can then lease an
        // off-screen chunk once and execute one material/history batch for it instead
        // of bouncing between chunks once per row.  Y remains the primary order inside
        // a chunk so ordinary foundations are still constructed before upper blocks.
        result.sort(BlueprintPlacementPlan::compareForExecution);
        return new BlueprintPlacementPlan(blueprint, transform, targetAnchor, origin, result);
    }

    static int compareForExecution(PlannedBlock left, PlannedBlock right) {
        BlockPos a = left.worldPos();
        BlockPos b = right.worldPos();
        int result = Integer.compare(a.getZ() >> 4, b.getZ() >> 4);
        if (result != 0) return result;
        result = Integer.compare(a.getX() >> 4, b.getX() >> 4);
        if (result != 0) return result;
        result = Integer.compare(a.getY(), b.getY());
        if (result != 0) return result;
        result = Integer.compare(a.getZ(), b.getZ());
        return result != 0 ? result : Integer.compare(a.getX(), b.getX());
    }

    public BlueprintData blueprint() { return blueprint; }
    public BlueprintTransform transform() { return transform; }
    public BlockPos targetAnchor() { return targetAnchor; }
    public BlockPos origin() { return origin; }
    public List<PlannedBlock> blocks() { return blocks; }

    public boolean fullDataAllowedFor(ServerPlayer player) {
        if (blueprint.saveMode() != BlueprintSaveMode.FULL) return true;
        return switch (BuilderConfig.fullBlockEntityPolicy()) {
            case NEVER -> false;
            case CREATIVE_ONLY -> player.isCreative();
            case OP_ONLY -> player.hasPermissions(2);
            case CREATIVE_OR_OP -> player.isCreative() || player.hasPermissions(2);
        };
    }
}
