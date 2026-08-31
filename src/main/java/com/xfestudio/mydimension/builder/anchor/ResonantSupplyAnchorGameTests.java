package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModBlocks;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MyDimension.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResonantSupplyAnchorGameTests {
    private ResonantSupplyAnchorGameTests() {
    }

    @GameTest(template = "empty")
    public static void acceptsEveryContainerFaceAndRejectsPlainSurfaces(GameTestHelper helper) {
        BlockPos container = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlock(container, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

        for (Direction outwardFromContainer : Direction.values()) {
            BlockPos anchorPosition = container.relative(outwardFromContainer);
            Direction containerDirection = outwardFromContainer.getOpposite();
            BlockState anchor = ModBlocks.RESONANT_SUPPLY_ANCHOR.get().defaultBlockState()
                    .setValue(ResonantSupplyAnchorBlock.FACING, containerDirection);
            helper.assertTrue(ResonantSupplyAnchorBlock.containerPosition(anchorPosition, containerDirection)
                            .equals(container),
                    "Anchor/container coordinate semantics changed for " + outwardFromContainer);
            helper.assertTrue(anchor.canSurvive(helper.getLevel(), anchorPosition),
                    "A chest face rejected a six-way anchor on " + outwardFromContainer);
        }

        BlockPos plainSupport = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos invalidAnchor = plainSupport.east();
        helper.getLevel().setBlock(plainSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState invalid = ModBlocks.RESONANT_SUPPLY_ANCHOR.get().defaultBlockState()
                .setValue(ResonantSupplyAnchorBlock.FACING, Direction.WEST);
        helper.assertTrue(!invalid.canSurvive(helper.getLevel(), invalidAnchor),
                "A non-container surface was accepted as a supply endpoint");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidItemPlacementDoesNotConsumeAnchor(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(1, 2, 2));
        BlockPos expectedAnchor = support.east();
        helper.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        Player player = helper.makeMockPlayer();
        player.setShiftKeyDown(true);
        ItemStack stack = new ItemStack(ModItems.RESONANT_SUPPLY_ANCHOR.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0.5D, 0.0D, 0.0D),
                Direction.EAST, support, false);

        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(!result.consumesAction(), "Invalid anchor placement unexpectedly succeeded");
        helper.assertTrue(stack.getCount() == 1, "Invalid anchor placement consumed its item");
        helper.assertTrue(helper.getLevel().getBlockState(expectedAnchor).isAir(),
                "Invalid anchor placement left a predicted/server block behind");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void losingContainerDropsAllSixAnchorsExactlyOnce(GameTestHelper helper) {
        BlockPos container = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlock(container, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        List<BlockPos> anchors = new ArrayList<>();
        Set<UUID> expectedIds = new HashSet<>();
        for (Direction outwardFromContainer : Direction.values()) {
            BlockPos anchorPosition = container.relative(outwardFromContainer);
            Direction containerDirection = outwardFromContainer.getOpposite();
            BlockState anchor = ModBlocks.RESONANT_SUPPLY_ANCHOR.get().defaultBlockState()
                    .setValue(ResonantSupplyAnchorBlock.FACING, containerDirection);
            helper.assertTrue(helper.getLevel().setBlock(anchorPosition, anchor, Block.UPDATE_ALL),
                    "Could not prepare anchor on " + outwardFromContainer);
            if (!(helper.getLevel().getBlockEntity(anchorPosition)
                    instanceof ResonantSupplyAnchorBlockEntity blockEntity)) {
                helper.fail("Anchor block entity was not created on " + outwardFromContainer);
                return;
            }
            expectedIds.add(blockEntity.anchorId());
            anchors.add(anchorPosition);
        }

        helper.getLevel().removeBlock(container, false);
        helper.runAfterDelay(4, () -> {
            for (BlockPos anchorPosition : anchors) {
                helper.assertTrue(helper.getLevel().getBlockState(anchorPosition).isAir(),
                        "Unsupported anchor remained at " + anchorPosition);
            }
            List<ItemStack> returnedStacks = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(container).inflate(4.0D)).stream()
                    .map(ItemEntity::getItem)
                    .filter(stack -> stack.is(ModItems.RESONANT_SUPPLY_ANCHOR.get()))
                    .filter(stack -> expectedIds.contains(droppedAnchorId(stack)))
                    .toList();
            int returned = returnedStacks.stream()
                    .mapToInt(ItemStack::getCount)
                    .sum();
            helper.assertTrue(returned == Direction.values().length,
                    "Expected six returned anchors, got " + returned);
            helper.assertTrue(returnedStacks.stream().allMatch(stack -> {
                        var blockEntityTag = stack.getTagElement("BlockEntityTag");
                        return blockEntityTag != null
                                && blockEntityTag.hasUUID(ResonantSupplyAnchorBlockEntity.ANCHOR_ID_TAG);
                    }),
                    "A detached anchor lost its stable UUID NBT");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void bypassedInvalidPlacementReturnsItsItem(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(1, 2, 2));
        BlockPos anchorPosition = support.east();
        helper.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState anchor = ModBlocks.RESONANT_SUPPLY_ANCHOR.get().defaultBlockState()
                .setValue(ResonantSupplyAnchorBlock.FACING, Direction.WEST);
        helper.getLevel().setBlock(anchorPosition, anchor, Block.UPDATE_ALL);
        if (!(helper.getLevel().getBlockEntity(anchorPosition)
                instanceof ResonantSupplyAnchorBlockEntity blockEntity)) {
            helper.fail("Invalid anchor block entity was not created");
            return;
        }
        UUID expectedId = blockEntity.anchorId();

        helper.runAfterDelay(4, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(anchorPosition).isAir(),
                    "An invalid command/blueprint anchor did not detach");
            int returned = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(anchorPosition).inflate(2.0D)).stream()
                    .map(ItemEntity::getItem)
                    .filter(stack -> stack.is(ModItems.RESONANT_SUPPLY_ANCHOR.get()))
                    .filter(stack -> expectedId.equals(droppedAnchorId(stack)))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            helper.assertTrue(returned == 1, "Invalid detached anchor returned " + returned + " items");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void targetChunkWakeupIndexTracksMovesAndRemoval(GameTestHelper helper) {
        AnchorIndexSavedData index = new AnchorIndexSavedData();
        ChunkPos baseChunk = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int baseX = baseChunk.getMinBlockX();
        int baseZ = baseChunk.getMinBlockZ();
        int y = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
        UUID id = UUID.randomUUID();
        var acrossEastBoundary = new AnchorIndexSavedData.AnchorLocation(helper.getLevel().dimension(),
                new BlockPos(baseX + 15, y, baseZ + 4), Direction.EAST);
        index.claim(id, acrossEastBoundary);

        helper.assertTrue(index.findTargetingChunk(helper.getLevel().dimension(),
                        new ChunkPos(baseChunk.x + 1, baseChunk.z)).equals(List.of(acrossEastBoundary)),
                "Cross-boundary anchor was not indexed by its container chunk");

        var moved = new AnchorIndexSavedData.AnchorLocation(helper.getLevel().dimension(),
                new BlockPos(baseX + 31, y, baseZ + 4), Direction.EAST);
        index.update(id, moved);
        helper.assertTrue(index.findTargetingChunk(helper.getLevel().dimension(),
                        new ChunkPos(baseChunk.x + 1, baseChunk.z)).isEmpty(),
                "Moving an anchor left a stale target-chunk wakeup route");
        helper.assertTrue(index.findTargetingChunk(helper.getLevel().dimension(),
                        new ChunkPos(baseChunk.x + 2, baseChunk.z)).equals(List.of(moved)),
                "Moving an anchor did not create its new wakeup route");

        helper.assertTrue(index.unregister(id, moved), "Could not unregister moved anchor");
        helper.assertTrue(index.findTargetingChunk(helper.getLevel().dimension(),
                        new ChunkPos(baseChunk.x + 2, baseChunk.z)).isEmpty(),
                "Removing an anchor left a target-chunk wakeup route");
        helper.succeed();
    }

    private static UUID droppedAnchorId(ItemStack stack) {
        var blockEntityTag = stack.getTagElement("BlockEntityTag");
        return blockEntityTag != null && blockEntityTag.hasUUID(ResonantSupplyAnchorBlockEntity.ANCHOR_ID_TAG)
                ? blockEntityTag.getUUID(ResonantSupplyAnchorBlockEntity.ANCHOR_ID_TAG)
                : null;
    }
}
