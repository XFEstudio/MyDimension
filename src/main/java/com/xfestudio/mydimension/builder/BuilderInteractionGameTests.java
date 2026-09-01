package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** End-to-end checks for the ordinary Item#useOn fallback after block interaction dispatch. */
@GameTestHolder(MyDimension.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuilderInteractionGameTests {
    private BuilderInteractionGameTests() {
    }

    @GameTest(template = "empty")
    public static void unshiftedItemEntryCannotBypassInteractiveBlocks(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos button = helper.absolutePos(new BlockPos(3, 2, 1));
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(button.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(button, Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR), Block.UPDATE_ALL);

        Player player = helper.makeMockPlayer();
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);

        InteractionResult chestResult = useOn(player, scepter, chest);
        InteractionResult buttonResult = useOn(player, scepter, button);
        player.setShiftKeyDown(true);
        InteractionResult shiftedDirectResult = useOn(player, scepter, chest);

        helper.assertTrue(chestResult == InteractionResult.PASS,
                "An unshifted direct item call bypassed chest interaction priority");
        helper.assertTrue(buttonResult == InteractionResult.PASS,
                "An unshifted direct item call bypassed button interaction priority");
        helper.assertTrue(shiftedDirectResult == InteractionResult.PASS,
                "The vanilla item packet bypassed interaction priority without an explicit override intent");
        helper.assertTrue(helper.getLevel().getBlockState(chest).is(Blocks.CHEST)
                        && helper.getLevel().getBlockState(button).is(Blocks.STONE_BUTTON),
                "The rejected scepter calls changed an interaction-priority block");
        helper.succeed();
    }

    private static InteractionResult useOn(Player player, ItemStack stack, BlockPos position) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position),
                Direction.UP, position, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }
}
