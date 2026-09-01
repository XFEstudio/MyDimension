package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.anchor.AnchorContainerResolver;
import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.history.WorldDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MyDimension.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuilderGameTests {
    private BuilderGameTests() { }

    @GameTest(template = "empty")
    public static void diagonalEightNeighborSurface(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos diagonal = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(first, Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(diagonal, Blocks.STONE.defaultBlockState(), 3);
        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), first, Direction.UP,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 16, null);
        helper.assertTrue(plan.candidates().size() == 2,
                "Eight-neighbor traversal must include a diagonal block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void verticalWallUsesOnlyExposedSelectedFace(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos upper = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos diagonal = helper.absolutePos(new BlockPos(3, 3, 3));
        helper.getLevel().setBlock(lower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(upper, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(diagonal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), lower, Direction.NORTH,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(plan.candidates().size() == 3,
                "An exposed vertical wall must retain four-way and diagonal connectivity");
        helper.assertTrue(plan.candidates().stream()
                        .allMatch(candidate -> candidate.reference().getZ() == lower.getZ()),
                "Vertical face traversal left its selected reference plane");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groundEdgeDoesNotIncludeLowerTerrace(GameTestHelper helper) {
        BlockPos top = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos topEdge = helper.absolutePos(new BlockPos(2, 3, 1));
        BlockPos lowerTerrace = helper.absolutePos(new BlockPos(3, 2, 1));
        helper.getLevel().setBlock(top, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(topEdge, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(lowerTerrace, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), top, Direction.UP,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(plan.candidates().size() == 2,
                "A horizontal selected face must stop at the ground edge");
        helper.assertTrue(plan.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(lowerTerrace)),
                "Horizontal face traversal wrapped down onto a lower terrace");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void solidGroundBackingCutsOffBuriedWallForBothModes(GameTestHelper helper) {
        BlockPos visibleLower = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos visibleUpper = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos buried = helper.absolutePos(new BlockPos(2, 1, 3));
        BlockPos solidBacking = buried.north();
        helper.getLevel().setBlock(visibleLower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(visibleUpper, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(buried, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(solidBacking, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan build = SurfacePlanner.plan(helper.getLevel(), visibleLower, Direction.NORTH,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 16, null);
        SurfacePlanner.Plan demolish = SurfacePlanner.plan(helper.getLevel(), visibleLower, Direction.NORTH,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(build.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(buried)),
                "Build planning crossed a solid ground backing into the buried wall");
        helper.assertTrue(demolish.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(buried)),
                "Demolish planning crossed a solid ground backing into the buried wall");
        helper.assertTrue(build.candidates().size() == 2 && demolish.candidates().size() == 2,
                "Visible wall cells were lost while excluding the buried continuation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void vanillaContainerFallbackResolves(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        helper.assertTrue(AnchorContainerResolver.resolveTarget(helper.getLevel(), chest, Direction.UP).isPresent(),
                "A vanilla chest must resolve through capability/container fallback");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mindbpRoundTrip(GameTestHelper helper) {
        try {
            BlueprintData source = new BlueprintData(UUID.randomUUID(), "GameTest", "server", null,
                    System.currentTimeMillis(), BlueprintSaveMode.BLOCKS_ONLY, 1, 1, 1, BlockPos.ZERO,
                    List.of(Blocks.STONE.defaultBlockState()),
                    List.of(new BlueprintData.BlockEntry(BlockPos.ZERO, 0, null)));
            BlueprintData decoded = BlueprintIo.decode(BlueprintIo.encode(source));
            helper.assertTrue(decoded.blocks().size() == 1 && decoded.state(decoded.blocks().get(0)).is(Blocks.STONE),
                    "mindbp GZIP NBT round trip changed its palette");
            helper.succeed();
        } catch (Exception exception) {
            helper.fail(exception.getMessage());
        }
    }

    @GameTest(template = "empty")
    public static void dropFreeRemovalSuppressesContainerContentsAndRestoresFluid(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (!(helper.getLevel().getBlockEntity(chest) instanceof Container container)) {
            helper.fail("Test chest did not create a container block entity");
            return;
        }
        container.setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.assertTrue(BuilderOperationManager.removeWithoutBreakEffect(helper.getLevel(), chest),
                "Drop-free removal rejected a valid chest");
        helper.assertTrue(helper.getLevel().getBlockState(chest).isAir(),
                "Drop-free removal did not clear the chest");
        // Other GameTests in the same parallel batch intentionally drop anchor
        // items. Assert only against the chest payload so test layout changes
        // cannot create a false positive from a neighbouring structure.
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(chest).inflate(2.0D)).stream()
                        .noneMatch(entity -> entity.getItem().is(Items.DIAMOND)),
                "Drop-free removal leaked container item entities");

        BlockPos waterlogged = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlock(waterlogged, Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        helper.assertTrue(BuilderOperationManager.removeWithoutBreakEffect(helper.getLevel(), waterlogged),
                "Drop-free removal rejected a waterlogged block");
        helper.assertTrue(helper.getLevel().getFluidState(waterlogged).is(Fluids.WATER),
                "Drop-free removal did not restore the contained fluid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameBatchSupportDependencyPlacesOnSecondPass(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos dependent = support.above();
        List<TestPlacement> reversed = List.of(
                new TestPlacement(dependent, Blocks.TORCH.defaultBlockState()),
                new TestPlacement(support, Blocks.STONE.defaultBlockState()));

        List<TestPlacement> unresolved = BuilderOperationManager.processWithSingleRetry(reversed, attempt -> {
            if (!attempt.state().canSurvive(helper.getLevel(), attempt.pos())) return false;
            return helper.getLevel().setBlock(attempt.pos(), attempt.state(), Block.UPDATE_ALL);
        });

        helper.assertTrue(unresolved.isEmpty(), "Support-dependent placement remained unresolved");
        helper.assertTrue(helper.getLevel().getBlockState(support).is(Blocks.STONE),
                "The lower support was not placed");
        helper.assertTrue(helper.getLevel().getBlockState(dependent).is(Blocks.TORCH),
                "The upper dependent block was not placed on its bounded retry");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void continuationPrefixRejectsExternalWorldChanges(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState before = helper.getLevel().getBlockState(position);
        helper.getLevel().setBlock(position, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BuilderTransaction transaction = new BuilderTransaction(UUID.randomUUID(), UUID.randomUUID(),
                helper.getLevel().dimension(), BuilderTransaction.Type.BUILD, System.currentTimeMillis(),
                List.of(new WorldDelta(position, before, null,
                        Blocks.STONE.defaultBlockState(), null)),
                List.of(), List.of(), ItemStack.EMPTY, ItemStack.EMPTY,
                BuilderTransaction.State.APPLIED);

        helper.assertTrue(transaction.matchesAppliedAfter(helper.getLevel()),
                "Unmodified applied transaction should match its continuation prefix");
        helper.getLevel().setBlock(position, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!transaction.matchesAppliedAfter(helper.getLevel()),
                "External edits must not be absorbed into a resumed transaction");
        helper.succeed();
    }

    private record TestPlacement(BlockPos pos, BlockState state) {
    }
}
