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
}
