package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.anchor.AnchorContainerResolver;
import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
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
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(chest).inflate(2.0D)).isEmpty(),
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

    private record TestPlacement(BlockPos pos, BlockState state) {
    }
}
