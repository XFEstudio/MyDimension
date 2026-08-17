package com.xfestudio.mydimension.world.block;

import com.xfestudio.mydimension.world.portal.MindPortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class MindPortalFrameBlock extends Block {
    public MindPortalFrameBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            MindPortalManager.frameBroken(level, pos);
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
