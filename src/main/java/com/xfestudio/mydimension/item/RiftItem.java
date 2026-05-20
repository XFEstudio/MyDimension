package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.client.RiftClient;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.fml.DistExecutor;

import java.util.function.Function;

public class RiftItem extends Item {
    public static final double ETHEREAL_SURFACE_Y = 66.0D;
    public static final String IMPORTED_TO_ETHEREAL_MIND = "mydimension_imported_to_ethereal_mind";
    private static final String SELECTED_ACTION_TAG = "SelectedAction";
    private static final String RETURN_POINT_TAG = "ReturnPoint";
    private static final String MIND_POINTS_TAG = "MindPoints";
    private static final String RETURN_DIMENSION_TAG = "Dimension";
    private static final String RETURN_X_TAG = "X";
    private static final String RETURN_Y_TAG = "Y";
    private static final String RETURN_Z_TAG = "Z";
    private static final String RETURN_Y_ROT_TAG = "YRot";
    private static final String RETURN_X_ROT_TAG = "XRot";

    public RiftItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> RiftClient::openActionScreen);
                return InteractionResultHolder.sidedSuccess(stack, true);
            }

            return InteractionResultHolder.consume(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            executeSelectedAction(serverPlayer, stack);
            serverPlayer.getCooldowns().addCooldown(this, 40);
        }

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            if (player.level().isClientSide()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> RiftClient::openActionScreen);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.CONSUME;
        }

        RiftAction action = getSelectedAction(stack);
        if (!action.sendsMob()) {
            return InteractionResult.PASS;
        }

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            sendToMind(serverPlayer, target, stack, action.targetDimension());
        }

        return InteractionResult.CONSUME;
    }

    public static void setSelectedAction(ItemStack stack, RiftAction action) {
        stack.getOrCreateTag().putString(SELECTED_ACTION_TAG, action.id());
    }

    public static RiftAction getSelectedAction(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(SELECTED_ACTION_TAG)) {
            return RiftAction.ETHEREAL_MIND;
        }

        return RiftAction.byId(tag.getString(SELECTED_ACTION_TAG));
    }

    private static void executeSelectedAction(ServerPlayer player, ItemStack stack) {
        RiftAction action = getSelectedAction(stack);
        if (action.sendsMob()) {
            LivingEntity target = findLookedAtLivingEntity(player);
            if (target == null || !sendToMind(player, target, stack, action.targetDimension())) {
                player.displayClientMessage(Component.translatable("message.mydimension.no_mob_target"), true);
            }
            return;
        }

        teleport(player, stack, action.targetDimension());
    }

    private static void teleport(ServerPlayer player, ItemStack stack, ResourceKey<Level> selectedDimension) {
        ServerLevel currentLevel = player.serverLevel();
        ResourceKey<Level> currentDimension = currentLevel.dimension();
        boolean returningFromSelectedMind = currentDimension.equals(selectedDimension);
        ReturnPoint returnPoint = returningFromSelectedMind ? readPoint(stack, RETURN_POINT_TAG) : null;
        ReturnPoint mindPoint = returningFromSelectedMind ? null : readMindPoint(stack, selectedDimension);
        ServerLevel targetLevel = returningFromSelectedMind ? getReturnLevel(player, returnPoint) : player.getServer().getLevel(selectedDimension);

        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        double x = player.getX();
        double z = player.getZ();
        double y = returningFromSelectedMind ? overworldSpawnY(targetLevel) : safeEntryY(targetLevel, selectedDimension, x, z);
        float yRot = player.getYRot();
        float xRot = player.getXRot();

        if (returningFromSelectedMind) {
            if (returnPoint != null) {
                x = returnPoint.x();
                y = returnPoint.y();
                z = returnPoint.z();
                yRot = returnPoint.yRot();
                xRot = returnPoint.xRot();
            } else {
                BlockPos spawn = targetLevel.getSharedSpawnPos();
                x = spawn.getX() + 0.5D;
                z = spawn.getZ() + 0.5D;
            }
        } else {
            if (!ModDimensions.isMindDimension(currentDimension)) {
                writePoint(stack, RETURN_POINT_TAG, player);
            } else {
                writeMindPoint(stack, currentDimension, player);
            }

            if (mindPoint != null) {
                x = mindPoint.x();
                y = mindPoint.y();
                z = mindPoint.z();
                yRot = mindPoint.yRot();
                xRot = mindPoint.xRot();
            }
        }

        if (returningFromSelectedMind) {
            writeMindPoint(stack, currentDimension, player);
        }

        targetLevel.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.teleportTo(targetLevel, x, y, z, yRot, xRot);
        targetLevel.playSound(null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static double overworldSpawnY(ServerLevel level) {
        return level.getSharedSpawnPos().getY() + 1.0D;
    }

    private static double safeEntryY(ServerLevel level, ResourceKey<Level> dimension, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (height > level.getMinBuildHeight()) {
            return height + 1.0D;
        }

        return ModDimensions.entryHeight(dimension);
    }

    public static boolean sendToMind(ServerPlayer player, LivingEntity target, ItemStack stack, ResourceKey<Level> targetDimension) {
        ServerLevel targetLevel = player.getServer().getLevel(targetDimension);
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return false;
        }

        if (target.level().dimension().equals(targetDimension)) {
            player.displayClientMessage(Component.translatable("message.mydimension.already_in_target_mind"), true);
            return false;
        }

        target.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
        target.stopRiding();
        target.ejectPassengers();

        Entity moved = target.changeDimension(targetLevel, new EtherealMindTeleporter(player.getX(), safeEntryY(targetLevel, targetDimension, player.getX(), player.getZ()), player.getZ()));
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

    private static void writePoint(ItemStack stack, String tagName, ServerPlayer player) {
        CompoundTag point = new CompoundTag();
        point.putString(RETURN_DIMENSION_TAG, player.level().dimension().location().toString());
        point.putDouble(RETURN_X_TAG, player.getX());
        point.putDouble(RETURN_Y_TAG, player.getY());
        point.putDouble(RETURN_Z_TAG, player.getZ());
        point.putFloat(RETURN_Y_ROT_TAG, player.getYRot());
        point.putFloat(RETURN_X_ROT_TAG, player.getXRot());
        stack.getOrCreateTag().put(tagName, point);
    }

    private static void writeMindPoint(ItemStack stack, ResourceKey<Level> dimension, ServerPlayer player) {
        CompoundTag mindPoints = stack.getOrCreateTag().getCompound(MIND_POINTS_TAG);
        writePointToTag(mindPoints, dimension.location().toString(), player);
        stack.getOrCreateTag().put(MIND_POINTS_TAG, mindPoints);
    }

    private static void writePointToTag(CompoundTag parent, String tagName, ServerPlayer player) {
        CompoundTag point = new CompoundTag();
        point.putString(RETURN_DIMENSION_TAG, player.level().dimension().location().toString());
        point.putDouble(RETURN_X_TAG, player.getX());
        point.putDouble(RETURN_Y_TAG, player.getY());
        point.putDouble(RETURN_Z_TAG, player.getZ());
        point.putFloat(RETURN_Y_ROT_TAG, player.getYRot());
        point.putFloat(RETURN_X_ROT_TAG, player.getXRot());
        parent.put(tagName, point);
    }

    private static ReturnPoint readPoint(ItemStack stack, String tagName) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(tagName, CompoundTag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag point = tag.getCompound(tagName);
        ResourceLocation dimension = ResourceLocation.tryParse(point.getString(RETURN_DIMENSION_TAG));
        if (dimension == null) {
            return null;
        }

        return new ReturnPoint(
                ResourceKey.create(Registries.DIMENSION, dimension),
                point.getDouble(RETURN_X_TAG),
                point.getDouble(RETURN_Y_TAG),
                point.getDouble(RETURN_Z_TAG),
                point.getFloat(RETURN_Y_ROT_TAG),
                point.getFloat(RETURN_X_ROT_TAG)
        );
    }

    private static ReturnPoint readMindPoint(ItemStack stack, ResourceKey<Level> dimension) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(MIND_POINTS_TAG, CompoundTag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag mindPoints = tag.getCompound(MIND_POINTS_TAG);
        String tagName = dimension.location().toString();
        if (!mindPoints.contains(tagName, CompoundTag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag point = mindPoints.getCompound(tagName);
        return new ReturnPoint(
                dimension,
                point.getDouble(RETURN_X_TAG),
                point.getDouble(RETURN_Y_TAG),
                point.getDouble(RETURN_Z_TAG),
                point.getFloat(RETURN_Y_ROT_TAG),
                point.getFloat(RETURN_X_ROT_TAG)
        );
    }

    private static ServerLevel getReturnLevel(ServerPlayer player, ReturnPoint returnPoint) {
        if (returnPoint != null) {
            ServerLevel savedLevel = player.getServer().getLevel(returnPoint.dimension());
            if (savedLevel != null) {
                return savedLevel;
            }
        }

        return player.getServer().getLevel(Level.OVERWORLD);
    }

    private record ReturnPoint(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {
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
