package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class RiftItem extends Item {
    public static final double ETHEREAL_SURFACE_Y = 66.0D;
    public static final String IMPORTED_TO_ETHEREAL_MIND = "mydimension_imported_to_ethereal_mind";

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

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            sendToEtherealMind(serverPlayer, target, stack);
        }

        return InteractionResult.CONSUME;
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

    private static void sendToEtherealMind(ServerPlayer player, LivingEntity target, ItemStack stack) {
        ServerLevel targetLevel = player.getServer().getLevel(ModDimensions.ETHEREAL_MIND);
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        if (target.level().dimension().equals(ModDimensions.ETHEREAL_MIND)) {
            player.displayClientMessage(Component.translatable("message.mydimension.already_in_ethereal_mind"), true);
            return;
        }

        target.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
        Entity moved = target.changeDimension(targetLevel);
        if (moved != null) {
            moved.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
            moved.moveTo(player.getX(), ETHEREAL_SURFACE_Y, player.getZ(), moved.getYRot(), moved.getXRot());
            targetLevel.playSound(null, BlockPos.containing(moved.position()), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            player.getCooldowns().addCooldown(stack.getItem(), 20);
        }
    }
}
