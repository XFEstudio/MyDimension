package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.client.RiftClient;
import com.xfestudio.mydimension.world.MindInstances;
import com.xfestudio.mydimension.world.ModDimensions;
import com.xfestudio.mydimension.world.SoaringMindChunkGenerator;
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
import net.minecraft.world.level.block.entity.BlockEntity;
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
    private static final int COPY_CHUNK_RADIUS = 1;
    private static final String SELECTED_ACTION_TAG = "SelectedAction";
    private static final String RETURN_POINT_TAG = "ReturnPoint";
    private static final String MIND_POINTS_TAG = "MindPoints";
    private static final String PLAYER_ANCHORS_TAG = "mydimension.RiftAnchors";
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
            sendToMind(serverPlayer, target, stack, action);
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
        if (action == RiftAction.SET_ANCHOR) {
            setAnchor(player);
            return;
        }

        if (action.copiesSharedDimension()) {
            copySharedDimension(player, action);
            return;
        }

        if (action.sendsMob()) {
            LivingEntity target = findLookedAtLivingEntity(player);
            if (target == null || !sendToMind(player, target, stack, action)) {
                player.displayClientMessage(Component.translatable("message.mydimension.no_mob_target"), true);
            }
            return;
        }

        teleport(player, stack, action);
    }

    private static void teleport(ServerPlayer player, ItemStack stack, RiftAction action) {
        ResourceKey<Level> selectedDimension = action.targetDimension();
        ServerLevel currentLevel = player.serverLevel();
        ResourceKey<Level> currentDimension = currentLevel.dimension();
        ResourceKey<Level> currentMindBase = ModDimensions.baseMindDimension(currentDimension);
        ResourceKey<Level> actualTargetDimension = action.usesSharedDimension() ? selectedDimension : MindInstances.dimensionFor(player, selectedDimension);
        boolean returningFromSelectedMind = currentDimension.equals(actualTargetDimension) && selectedDimension.equals(currentMindBase);
        ReturnPoint returnPoint = returningFromSelectedMind ? readPoint(stack, RETURN_POINT_TAG) : null;
        ReturnPoint mindPoint = returningFromSelectedMind ? null : readMindPoint(stack, actualTargetDimension);
        ServerLevel targetLevel = returningFromSelectedMind ? getReturnLevel(player, returnPoint) : player.getServer().getLevel(actualTargetDimension);

        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        double x = player.getX();
        double z = player.getZ();
        EntryPoint entryPoint = returningFromSelectedMind ? new EntryPoint(x, overworldSpawnY(targetLevel), z) : safeEntryPoint(targetLevel, actualTargetDimension, x, z);
        x = entryPoint.x();
        double y = entryPoint.y();
        z = entryPoint.z();
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

            if (ModDimensions.SOARING_MIND.equals(ModDimensions.baseMindDimension(actualTargetDimension))) {
                EntryPoint safe = safeEntryPoint(targetLevel, actualTargetDimension, x, z);
                x = safe.x();
                y = safe.y();
                z = safe.z();
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
        return safeEntryPoint(level, dimension, x, z).y();
    }

    private static EntryPoint safeEntryPoint(ServerLevel level, ResourceKey<Level> dimension, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        boolean soaringMind = ModDimensions.SOARING_MIND.equals(ModDimensions.baseMindDimension(dimension));
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (height > level.getMinBuildHeight()) {
            return new EntryPoint(x, soaringMind ? height : height + 1.0D, z);
        }

        if (soaringMind) {
            EntryPoint nearby = findNearbySurface(level, blockX, blockZ);
            if (nearby != null) {
                return nearby;
            }
        }

        return new EntryPoint(x, ModDimensions.entryHeight(dimension), z);
    }

    private static EntryPoint findNearbySurface(ServerLevel level, int centerX, int centerZ) {
        BlockPos surface = SoaringMindChunkGenerator.findNearestSurface(centerX, centerZ, 768);
        if (surface == null) {
            return null;
        }

        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surface.getX(), surface.getZ());
        double y = height > level.getMinBuildHeight() ? height : surface.getY();
        return new EntryPoint(surface.getX() + 0.5D, y, surface.getZ() + 0.5D);
    }

    public static boolean sendToMind(ServerPlayer player, LivingEntity target, ItemStack stack, RiftAction action) {
        ResourceKey<Level> targetDimension = action.targetDimension();
        ResourceKey<Level> actualTargetDimension = action.usesSharedDimension() ? targetDimension : MindInstances.dimensionFor(player, targetDimension);
        ServerLevel targetLevel = player.getServer().getLevel(actualTargetDimension);
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return false;
        }

        target.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
        target.stopRiding();
        target.ejectPassengers();

        AnchorPoint anchor = readAnchor(player, actualTargetDimension);
        EntryPoint targetEntryPoint = safeEntryPoint(targetLevel, actualTargetDimension, player.getX(), player.getZ());
        double targetX = anchor != null ? anchor.x() : targetEntryPoint.x();
        double targetY = anchor != null ? anchor.y() : targetEntryPoint.y();
        double targetZ = anchor != null ? anchor.z() : targetEntryPoint.z();

        if (target.level().dimension().equals(actualTargetDimension)) {
            player.displayClientMessage(Component.translatable("message.mydimension.already_in_target_mind"), true);
            return false;
        }

        Entity moved = target.changeDimension(targetLevel, new EtherealMindTeleporter(targetX, targetY, targetZ));
        if (moved != null) {
            moved.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
            targetLevel.playSound(null, BlockPos.containing(moved.position()), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            player.getCooldowns().addCooldown(stack.getItem(), 20);
            return true;
        }

        return false;
    }

    private static void copySharedDimension(ServerPlayer player, RiftAction action) {
        if (!player.isCreative()) {
            player.displayClientMessage(Component.translatable("message.mydimension.copy_creative_only"), true);
            return;
        }

        ResourceKey<Level> sourceDimension = action.targetDimension();
        ResourceKey<Level> targetDimension = MindInstances.dimensionFor(player, sourceDimension);
        if (sourceDimension.equals(targetDimension)) {
            player.displayClientMessage(Component.translatable("message.mydimension.copy_private_unavailable"), true);
            return;
        }

        ServerLevel sourceLevel = player.getServer().getLevel(sourceDimension);
        ServerLevel targetLevel = player.getServer().getLevel(targetDimension);
        if (sourceLevel == null || targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        int copiedBlocks = copyNearbyBlocks(sourceLevel, targetLevel, player.blockPosition());
        player.displayClientMessage(Component.translatable("message.mydimension.copy_done", copiedBlocks), true);
    }

    private static int copyNearbyBlocks(ServerLevel sourceLevel, ServerLevel targetLevel, BlockPos center) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int minY = Math.max(sourceLevel.getMinBuildHeight(), targetLevel.getMinBuildHeight());
        int maxY = Math.min(sourceLevel.getMaxBuildHeight(), targetLevel.getMaxBuildHeight());
        BlockPos.MutableBlockPos sourcePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
        int copied = 0;

        for (int chunkX = centerChunkX - COPY_CHUNK_RADIUS; chunkX <= centerChunkX + COPY_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = centerChunkZ - COPY_CHUNK_RADIUS; chunkZ <= centerChunkZ + COPY_CHUNK_RADIUS; chunkZ++) {
                sourceLevel.getChunk(chunkX, chunkZ);
                targetLevel.getChunk(chunkX, chunkZ);

                int minX = chunkX << 4;
                int minZ = chunkZ << 4;
                for (int x = minX; x < minX + 16; x++) {
                    for (int z = minZ; z < minZ + 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            sourcePos.set(x, y, z);
                            targetPos.set(x, y, z);
                            var state = sourceLevel.getBlockState(sourcePos);
                            targetLevel.setBlock(targetPos, state, 2);
                            copyBlockEntity(sourceLevel, targetLevel, sourcePos, targetPos);
                            copied++;
                        }
                    }
                }
            }
        }

        return copied;
    }

    private static void copyBlockEntity(ServerLevel sourceLevel, ServerLevel targetLevel, BlockPos sourcePos, BlockPos targetPos) {
        BlockEntity sourceBlockEntity = sourceLevel.getBlockEntity(sourcePos);
        if (sourceBlockEntity == null) {
            return;
        }

        BlockEntity targetBlockEntity = targetLevel.getBlockEntity(targetPos);
        if (targetBlockEntity == null) {
            return;
        }

        CompoundTag tag = sourceBlockEntity.saveWithFullMetadata();
        tag.putInt("x", targetPos.getX());
        tag.putInt("y", targetPos.getY());
        tag.putInt("z", targetPos.getZ());
        targetBlockEntity.load(tag);
        targetBlockEntity.setChanged();
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

    private static void setAnchor(ServerPlayer player) {
        ResourceKey<Level> dimension = player.level().dimension();
        if (!ModDimensions.isMindDimension(dimension)) {
            player.displayClientMessage(Component.translatable("message.mydimension.anchor_only_mind"), true);
            return;
        }

        CompoundTag anchors = player.getPersistentData().getCompound(PLAYER_ANCHORS_TAG);
        CompoundTag anchor = new CompoundTag();
        anchor.putString(RETURN_DIMENSION_TAG, dimension.location().toString());
        anchor.putDouble(RETURN_X_TAG, player.getX());
        anchor.putDouble(RETURN_Y_TAG, player.getY());
        anchor.putDouble(RETURN_Z_TAG, player.getZ());
        anchors.put(dimension.location().toString(), anchor);
        player.getPersistentData().put(PLAYER_ANCHORS_TAG, anchors);
        player.displayClientMessage(Component.translatable("message.mydimension.anchor_set"), true);
    }

    public static AnchorPoint readAnchor(ServerPlayer player, ResourceKey<Level> dimension) {
        CompoundTag anchors = player.getPersistentData().getCompound(PLAYER_ANCHORS_TAG);
        String tagName = dimension.location().toString();
        if (!anchors.contains(tagName, CompoundTag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag anchor = anchors.getCompound(tagName);
        ResourceLocation location = ResourceLocation.tryParse(anchor.getString(RETURN_DIMENSION_TAG));
        if (location == null) {
            return null;
        }

        return new AnchorPoint(
                ResourceKey.create(Registries.DIMENSION, location),
                anchor.getDouble(RETURN_X_TAG),
                anchor.getDouble(RETURN_Y_TAG),
                anchor.getDouble(RETURN_Z_TAG)
        );
    }

    public static java.util.List<AnchorPoint> readAnchors(ServerPlayer player) {
        java.util.List<AnchorPoint> anchors = new java.util.ArrayList<>();
        CompoundTag tag = player.getPersistentData().getCompound(PLAYER_ANCHORS_TAG);
        for (String key : tag.getAllKeys()) {
            ResourceLocation location = ResourceLocation.tryParse(key);
            if (location != null && tag.contains(key, CompoundTag.TAG_COMPOUND)) {
                CompoundTag anchor = tag.getCompound(key);
                anchors.add(new AnchorPoint(
                        ResourceKey.create(Registries.DIMENSION, location),
                        anchor.getDouble(RETURN_X_TAG),
                        anchor.getDouble(RETURN_Y_TAG),
                        anchor.getDouble(RETURN_Z_TAG)
                ));
            }
        }
        return anchors;
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

    private record EntryPoint(double x, double y, double z) {
    }

    public record AnchorPoint(ResourceKey<Level> dimension, double x, double y, double z) {
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
