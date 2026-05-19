package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class RiftItem extends Item {
    public static final double ETHEREAL_SURFACE_Y = 66.0D;

    public RiftItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            teleport(serverPlayer);
            serverPlayer.getCooldowns().addCooldown(this, 40);
        }

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static void teleport(ServerPlayer player) {
        ServerLevel currentLevel = player.serverLevel();
        boolean inEtherealMind = currentLevel.dimension().equals(ModDimensions.ETHEREAL_MIND);
        ServerLevel targetLevel = player.getServer().getLevel(inEtherealMind ? Level.OVERWORLD : ModDimensions.ETHEREAL_MIND);

        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        double x = player.getX();
        double z = player.getZ();
        double y = inEtherealMind ? overworldSpawnY(targetLevel) : ETHEREAL_SURFACE_Y;

        if (inEtherealMind) {
            BlockPos spawn = targetLevel.getSharedSpawnPos();
            x = spawn.getX() + 0.5D;
            z = spawn.getZ() + 0.5D;
        }

        targetLevel.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.teleportTo(targetLevel, x, y, z, player.getYRot(), player.getXRot());
        targetLevel.playSound(null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static double overworldSpawnY(ServerLevel level) {
        return level.getSharedSpawnPos().getY() + 1.0D;
    }
}
