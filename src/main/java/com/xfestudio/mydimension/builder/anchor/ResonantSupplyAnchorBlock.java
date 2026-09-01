package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/** Six-way attached remote inventory endpoint for Realmwright construction. */
public final class ResonantSupplyAnchorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final VoxelShape SHAPE_X = Shapes.or(
            Block.box(2.0D, 2.0D, 2.0D, 14.0D, 14.0D, 14.0D),
            Block.box(0.0D, 4.0D, 4.0D, 16.0D, 12.0D, 12.0D));
    private static final VoxelShape SHAPE_Y = Shapes.or(
            Block.box(2.0D, 2.0D, 2.0D, 14.0D, 14.0D, 14.0D),
            Block.box(4.0D, 0.0D, 4.0D, 12.0D, 16.0D, 12.0D));
    private static final VoxelShape SHAPE_Z = Shapes.or(
            Block.box(2.0D, 2.0D, 2.0D, 14.0D, 14.0D, 14.0D),
            Block.box(4.0D, 4.0D, 0.0D, 12.0D, 12.0D, 16.0D));

    public ResonantSupplyAnchorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (!BuilderConfig.isEnabled() || player == null || !player.isShiftKeyDown()) {
            return null;
        }

        // BlockPlaceContext#getClickedPos is already the intended placement position when
        // the clicked container is not replaceable. Requiring a separate placement cell
        // prevents replacing an unusual, replaceable block entity instead of attaching to it.
        if (context.replacingClickedOnBlock()) {
            return null;
        }

        BlockPos placementPosition = context.getClickedPos();
        Direction containerDirection = context.getClickedFace().getOpposite();
        return attachmentIsValid(context.getLevel(), placementPosition, containerDirection)
                ? defaultBlockState().setValue(FACING, containerDirection)
                : null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos position) {
        if (!(level instanceof Level concreteLevel)) {
            return true;
        }
        // A server disabling the feature must not destroy already placed anchors or their saved UUID/ACL.
        if (!BuilderConfig.isEnabled()) return true;
        return attachmentIsValid(concreteLevel, position, state.getValue(FACING));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction changedDirection, BlockState changedState,
                                  LevelAccessor level, BlockPos position, BlockPos changedPosition) {
        if (changedDirection == state.getValue(FACING)
                && !level.isClientSide()
                && BuilderConfig.isEnabled()) {
            // Returning AIR here can remove a freshly placed block before BlockItem finishes
            // applying BlockEntityTag/ownership, which also makes its item appear consumed.
            // A scheduled server check keeps the block entity alive for the loot table and
            // coalesces duplicate neighbour notifications into one destruction/drop.
            level.scheduleTick(position, this, 1);
        }
        return super.updateShape(state, changedDirection, changedState, level, position, changedPosition);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos position, Block changedBlock,
                                BlockPos changedPosition, boolean moving) {
        super.neighborChanged(state, level, position, changedBlock, changedPosition, moving);
        if (!level.isClientSide()
                && BuilderConfig.isEnabled()
                && changedPosition.equals(containerPosition(position, state.getValue(FACING)))) {
            level.scheduleTick(position, this, 1);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos position, BlockState previousState, boolean moving) {
        super.onPlace(state, level, position, previousState, moving);
        if (!level.isClientSide() && BuilderConfig.isEnabled() && !state.is(previousState.getBlock())) {
            // Covers commands, blueprints and third-party placement paths that bypass
            // getStateForPlacement. Invalid anchors return themselves on the next tick.
            level.scheduleTick(position, this, 1);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
        BlockPos targetPosition = containerPosition(position, state.getValue(FACING));
        // A neighbouring chunk unloading is not proof that the physical support vanished.
        // Keep the anchor until the target can be authoritatively inspected again.
        if (!level.isLoaded(targetPosition)) {
            return;
        }
        if (BuilderConfig.isEnabled()
                && level.getBlockState(position).is(this)
                && !attachmentIsValid(level, position, state.getValue(FACING))) {
            // destroyBlock(true) evaluates the anchor loot table while its block entity is
            // still present, preserving stable UUID/ACL NBT and returning exactly one item.
            level.destroyBlock(position, true);
        }
    }

    /** The facing points from the anchor into the container face it reads. */
    public static BlockPos containerPosition(BlockPos anchorPosition, Direction containerDirection) {
        return anchorPosition.relative(containerDirection);
    }

    /**
     * Six-way attachment validation shared by placement and lifecycle checks.
     *
     * <p>The server always resolves the real sided Forge/container interface. A client may
     * not receive a third-party block entity's capability implementation, so it only rejects
     * surfaces that are provably impossible (no block entity at all). The server remains
     * authoritative and a rejected placement never consumes the stack.</p>
     */
    public static boolean attachmentIsValid(Level level, BlockPos anchorPosition, Direction containerDirection) {
        if (level.isClientSide()) {
            BlockPos targetPosition = containerPosition(anchorPosition, containerDirection);
            return level.isLoaded(targetPosition) && level.getBlockState(targetPosition).hasBlockEntity();
        }
        return AnchorContainerResolver.hasCompatibleContainer(level, anchorPosition, containerDirection);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos position, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (placer instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(position) instanceof ResonantSupplyAnchorBlockEntity anchor) {
            anchor.placedBy(serverPlayer);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos position, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(position) instanceof ResonantSupplyAnchorBlockEntity anchor) {
            anchor.unregisterFromIndex();
        }
        super.onRemove(state, level, position, newState, moving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new ResonantSupplyAnchorBlockEntity(position, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return switch (state.getValue(FACING).getAxis()) {
            case X -> SHAPE_X;
            case Y -> SHAPE_Y;
            case Z -> SHAPE_Z;
        };
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos position) {
        // This is an inset model. A full occlusion cube culls the faces of
        // neighbouring blocks and exposes the void through the model's gaps.
        return Shapes.empty();
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
