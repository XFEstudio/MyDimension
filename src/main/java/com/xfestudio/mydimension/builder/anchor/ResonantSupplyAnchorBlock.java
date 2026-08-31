package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.Blocks;
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

        Direction facing = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing);
        if (!context.getLevel().isClientSide()
                && !AnchorContainerResolver.hasCompatibleContainer(context.getLevel(),
                        context.getClickedPos(), facing)) {
            return null;
        }
        return state;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos position) {
        if (!(level instanceof Level concreteLevel) || concreteLevel.isClientSide()) {
            return true;
        }
        // A server disabling the feature must not destroy already placed anchors or their saved UUID/ACL.
        if (!BuilderConfig.isEnabled()) return true;
        return AnchorContainerResolver.hasCompatibleContainer(concreteLevel, position, state.getValue(FACING));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction changedDirection, BlockState changedState,
                                  LevelAccessor level, BlockPos position, BlockPos changedPosition) {
        if (changedDirection == state.getValue(FACING)
                && level instanceof Level concreteLevel
                && !concreteLevel.isClientSide()
                && !canSurvive(state, concreteLevel, position)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, changedDirection, changedState, level, position, changedPosition);
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
