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
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

import java.util.function.Function;

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
            if (level.isClientSide()) {
                return InteractionResultHolder.sidedSuccess(stack, true);
            }

            if (player instanceof ServerPlayer serverPlayer) {
                LivingEntity target = findLookedAtLivingEntity(serverPlayer);
                if (target != null && sendToEtherealMind(serverPlayer, target, stack)) {
                    return InteractionResultHolder.consume(stack);
                }
            }

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

    public static boolean sendToEtherealMind(ServerPlayer player, LivingEntity target, ItemStack stack) {
        ServerLevel targetLevel = player.getServer().getLevel(ModDimensions.ETHEREAL_MIND);
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return false;
        }

        if (target.level().dimension().equals(ModDimensions.ETHEREAL_MIND)) {
            player.displayClientMessage(Component.translatable("message.mydimension.already_in_ethereal_mind"), true);
            return false;
        }

        target.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
        target.stopRiding();
        target.ejectPassengers();

        Entity moved = target.changeDimension(targetLevel, new EtherealMindTeleporter(player.getX(), ETHEREAL_SURFACE_Y, player.getZ()));
        if (moved != null) {
            moved.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
            targetLevel.playSound(null, BlockPos.containing(moved.position()), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            player.getCooldowns().addCooldown(stack.getItem(), 20);
            return true;
        }

        return false;
    }

    private static LivingEntity findLookedAtLivingEntity(ServerPlayer player) {
        double reach = 6.0D;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        AABB searchBox = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player.level(),
                player,
                eye,
                end,
                searchBox,
                entity -> entity instanceof LivingEntity && entity.isAlive() && entity.isPickable() && entity != player,
                0.3F
        );

        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    private static class EtherealMindTeleporter implements ITeleporter {
        private final double x;
        private final double y;
        private final double z;

        private EtherealMindTeleporter(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destinationLevel, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(new Vec3(x, y, z), Vec3.ZERO, entity.getYRot(), entity.getXRot());
        }

        @Override
        public Entity placeEntity(Entity entity, ServerLevel currentLevel, ServerLevel destinationLevel, float yaw, Function<Boolean, Entity> repositionEntity) {
            Entity moved = repositionEntity.apply(false);
            if (moved != null) {
                moved.moveTo(x, y, z, moved.getYRot(), moved.getXRot());
                moved.setDeltaMovement(Vec3.ZERO);
                moved.setPortalCooldown();
            }
            return moved;
        }
    }
}
