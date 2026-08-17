package com.xfestudio.mydimension.world.portal;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModBlocks;
import com.xfestudio.mydimension.world.MindTeamAccess;
import com.xfestudio.mydimension.world.ModDimensions;
import com.xfestudio.mydimension.world.SoaringMindChunkGenerator;
import com.xfestudio.mydimension.world.block.MindPortalBlock;
import com.xfestudio.mydimension.world.block.entity.MindPortalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MindPortalManager {
    public static final int INNER_WIDTH = 3;
    public static final int INNER_HEIGHT = 4;
    private static final int PORTAL_CHARGE_TICKS = 40;
    private static final int PORTAL_COOLDOWN_TICKS = 80;
    private static final String DATA_NAME = "mydimension_mind_portals";
    private static final String LINKS_TAG = "Links";
    private static final String PORTAL_ID_TAG = "mydimension.PortalId";
    private static final String PORTAL_TICKS_TAG = "mydimension.PortalTicks";
    private static final String PORTAL_LAST_TICK_TAG = "mydimension.PortalLastTick";
    private static final String PORTAL_COOLDOWN_TAG = "mydimension.PortalCooldown";

    private MindPortalManager() {
    }

    public static InteractionResult useRiftOnPortal(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            player.displayClientMessage(Component.translatable("message.mydimension.portal.overworld_only"), true);
            return InteractionResult.CONSUME;
        }

        PortalFrame frame = findFrame(level, context.getClickedPos());
        if (frame == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.portal.invalid_frame"), true);
            return InteractionResult.CONSUME;
        }

        RiftItem.PortalTarget target = RiftItem.resolvePortalTarget(player, context.getItemInHand());
        if (target == null) {
            return InteractionResult.CONSUME;
        }

        ServerLevel targetLevel = player.getServer().getLevel(target.dimension());
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return InteractionResult.CONSUME;
        }

        PortalData portalData = data(player.getServer());
        MindPortalBlockEntity existingCore = coreEntity(level, frame.core());
        PortalLink existingLink = existingCore == null || existingCore.linkId() == null
                ? null
                : portalData.links.get(existingCore.linkId());

        if (existingLink != null && !existingLink.owner().equals(player.getUUID()) && !player.isCreative()) {
            player.displayClientMessage(Component.translatable("message.mydimension.portal.owner_only"), true);
            return InteractionResult.CONSUME;
        }

        PortalEndpoint destination = findDestination(targetLevel, frame, target.baseDimension());
        if (destination == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.portal.no_space"), true);
            return InteractionResult.CONSUME;
        }

        UUID linkId = existingLink == null ? UUID.randomUUID() : existingLink.id();
        PortalEndpoint source = new PortalEndpoint(level.dimension(), frame.core(), frame.axis());
        if (existingLink != null) {
            clearEndpoint(player.getServer(), existingLink.destination(), true);
        }

        buildGeneratedFrame(targetLevel, destination);
        fillPortal(targetLevel, destination, linkId, false);
        fillPortal(level, source, linkId, true);
        PortalLink link = new PortalLink(linkId, player.getUUID(), source, destination,
                target.baseDimension(), target.dimension(), target.owner());
        portalData.links.put(linkId, link);
        portalData.setDirty();

        playActivation(level, source.core());
        playActivation(targetLevel, destination.core());
        player.displayClientMessage(Component.translatable(
                existingLink == null ? "message.mydimension.portal.activated" : "message.mydimension.portal.updated",
                target.displayName()), true);
        return InteractionResult.CONSUME;
    }

    public static void frameBroken(Level level, BlockPos framePos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        PortalFrame frame = findFrame(serverLevel, framePos);
        if (frame == null) {
            return;
        }
        MindPortalBlockEntity core = coreEntity(serverLevel, frame.core());
        if (core == null || core.linkId() == null) {
            return;
        }
        destroyLink(serverLevel.getServer(), core.linkId());
    }

    public static void touchPortal(ServerPlayer player, BlockPos portalPos, Direction.Axis axis) {
        MindPortalBlockEntity core = findCoreEntity(player.serverLevel(), portalPos, axis);
        if (core == null || core.linkId() == null) {
            return;
        }

        PortalLink link = data(player.getServer()).links.get(core.linkId());
        if (link == null) {
            return;
        }

        CompoundTag persistentData = player.getPersistentData();
        long now = player.level().getGameTime();
        if (persistentData.getLong(PORTAL_COOLDOWN_TAG) > now) {
            return;
        }

        UUID previousId = persistentData.hasUUID(PORTAL_ID_TAG) ? persistentData.getUUID(PORTAL_ID_TAG) : null;
        long previousTick = persistentData.getLong(PORTAL_LAST_TICK_TAG);
        if (core.linkId().equals(previousId) && previousTick == now) {
            return;
        }

        int charge = core.linkId().equals(previousId) && previousTick == now - 1
                ? persistentData.getInt(PORTAL_TICKS_TAG) + 1
                : 1;
        persistentData.putUUID(PORTAL_ID_TAG, core.linkId());
        persistentData.putLong(PORTAL_LAST_TICK_TAG, now);
        persistentData.putInt(PORTAL_TICKS_TAG, charge);

        if (charge == 1) {
            player.playNotifySound(SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.45F, 1.15F);
        }
        if (charge < PORTAL_CHARGE_TICKS) {
            return;
        }

        persistentData.remove(PORTAL_TICKS_TAG);
        boolean sourceSide = core.sourceSide();
        if (sourceSide && link.targetOwner() != null
                && !MindTeamAccess.hasAccess(player.getServer(), link.targetOwner(), player.getUUID())) {
            persistentData.putLong(PORTAL_COOLDOWN_TAG, now + 40L);
            player.displayClientMessage(Component.translatable("message.mydimension.portal.access_denied"), true);
            return;
        }

        PortalEndpoint destination = sourceSide ? link.destination() : link.source();
        ServerLevel destinationLevel = player.getServer().getLevel(destination.dimension());
        if (destinationLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            return;
        }

        Vec3 exit = safeExit(destinationLevel, destination);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL,
                SoundSource.PLAYERS, 0.8F, 1.0F);
        player.teleportTo(destinationLevel, exit.x(), exit.y(), exit.z(), player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setPortalCooldown();
        player.getPersistentData().putLong(PORTAL_COOLDOWN_TAG, destinationLevel.getGameTime() + PORTAL_COOLDOWN_TICKS);
        destinationLevel.playSound(null, BlockPos.containing(exit), SoundEvents.PORTAL_TRAVEL,
                SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    private static PortalFrame findFrame(ServerLevel level, BlockPos clickedPos) {
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
                for (int vertical = -1; vertical <= INNER_HEIGHT; vertical++) {
                    BlockPos core = offset(clickedPos, axis, -horizontal, -vertical);
                    PortalFrame candidate = new PortalFrame(core, axis);
                    if (isValidFrame(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isValidFrame(ServerLevel level, PortalFrame frame) {
        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int vertical = -1; vertical <= INNER_HEIGHT; vertical++) {
                BlockPos pos = offset(frame.core(), frame.axis(), horizontal, vertical);
                boolean boundary = horizontal == -1 || horizontal == INNER_WIDTH
                        || vertical == -1 || vertical == INNER_HEIGHT;
                BlockState state = level.getBlockState(pos);
                if (boundary) {
                    if (!state.is(ModBlocks.MIND_PORTAL_FRAME.get())) {
                        return false;
                    }
                } else if (!state.isAir() && !state.canBeReplaced() && !state.is(ModBlocks.MIND_PORTAL.get())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static PortalEndpoint findDestination(ServerLevel level, PortalFrame source, ResourceKey<Level> baseDimension) {
        int centerX = source.core().getX() + (source.axis() == Direction.Axis.X ? 1 : 0);
        int centerZ = source.core().getZ() + (source.axis() == Direction.Axis.Z ? 1 : 0);
        if (ModDimensions.SOARING_MIND.equals(baseDimension)) {
            BlockPos nearest = SoaringMindChunkGenerator.findNearestSurface(centerX, centerZ, 768);
            if (nearest != null) {
                centerX = nearest.getX();
                centerZ = nearest.getZ();
            }
        }

        for (int radius = 0; radius <= 24; radius += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= level.getMinBuildHeight()) {
                        y = (int) Math.floor(ModDimensions.entryHeight(level.dimension()));
                    }
                    BlockPos core = source.axis() == Direction.Axis.X
                            ? new BlockPos(x - 1, y, z)
                            : new BlockPos(x, y, z - 1);
                    PortalEndpoint endpoint = new PortalEndpoint(level.dimension(), core, source.axis());
                    if (canBuildDestination(level, endpoint)) {
                        return endpoint;
                    }
                }
            }
        }
        return null;
    }

    private static boolean canBuildDestination(ServerLevel level, PortalEndpoint endpoint) {
        if (endpoint.core().getY() - 1 < level.getMinBuildHeight()
                || endpoint.core().getY() + INNER_HEIGHT >= level.getMaxBuildHeight()) {
            return false;
        }

        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int vertical = 0; vertical <= INNER_HEIGHT; vertical++) {
                BlockPos pos = offset(endpoint.core(), endpoint.axis(), horizontal, vertical);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !state.canBeReplaced()) {
                    return false;
                }
            }
        }

        Direction.Axis normalAxis = endpoint.axis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int normal = -2; normal <= 2; normal++) {
                for (int vertical = 0; vertical <= 2; vertical++) {
                    BlockPos pos = offset(offset(endpoint.core(), endpoint.axis(), horizontal, vertical),
                            normalAxis, normal, 0);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void buildGeneratedFrame(ServerLevel level, PortalEndpoint endpoint) {
        Direction.Axis normalAxis = endpoint.axis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int normal = -2; normal <= 2; normal++) {
                BlockPos floor = offset(offset(endpoint.core(), endpoint.axis(), horizontal, -1), normalAxis, normal, 0);
                level.setBlock(floor, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), 3);
            }
        }

        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int vertical = -1; vertical <= INNER_HEIGHT; vertical++) {
                if (horizontal == -1 || horizontal == INNER_WIDTH || vertical == -1 || vertical == INNER_HEIGHT) {
                    level.setBlock(offset(endpoint.core(), endpoint.axis(), horizontal, vertical),
                            ModBlocks.MIND_PORTAL_FRAME.get().defaultBlockState(), 3);
                }
            }
        }
    }

    private static void fillPortal(ServerLevel level, PortalEndpoint endpoint, UUID linkId, boolean sourceSide) {
        BlockState portalState = ModBlocks.MIND_PORTAL.get().defaultBlockState().setValue(MindPortalBlock.AXIS, endpoint.axis());
        for (int horizontal = 0; horizontal < INNER_WIDTH; horizontal++) {
            for (int vertical = 0; vertical < INNER_HEIGHT; vertical++) {
                BlockPos pos = offset(endpoint.core(), endpoint.axis(), horizontal, vertical);
                boolean core = horizontal == 0 && vertical == 0;
                level.setBlock(pos, portalState.setValue(MindPortalBlock.CORE, core), 3);
                if (core && level.getBlockEntity(pos) instanceof MindPortalBlockEntity blockEntity) {
                    blockEntity.configure(linkId, sourceSide);
                }
            }
        }
    }

    private static void destroyLink(MinecraftServer server, UUID linkId) {
        PortalData portalData = data(server);
        PortalLink link = portalData.links.remove(linkId);
        if (link == null) {
            return;
        }
        portalData.setDirty();
        clearEndpoint(server, link.source(), false);
        clearEndpoint(server, link.destination(), true);
    }

    private static void clearEndpoint(MinecraftServer server, PortalEndpoint endpoint, boolean removeFrame) {
        ServerLevel level = server.getLevel(endpoint.dimension());
        if (level == null) {
            return;
        }
        for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
            for (int vertical = -1; vertical <= INNER_HEIGHT; vertical++) {
                BlockPos pos = offset(endpoint.core(), endpoint.axis(), horizontal, vertical);
                BlockState state = level.getBlockState(pos);
                if (state.is(ModBlocks.MIND_PORTAL.get())
                        || (removeFrame && state.is(ModBlocks.MIND_PORTAL_FRAME.get()))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.playSound(null, endpoint.core(), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                SoundSource.BLOCKS, 0.8F, 0.8F);
    }

    private static MindPortalBlockEntity findCoreEntity(ServerLevel level, BlockPos portalPos, Direction.Axis axis) {
        for (int horizontal = 0; horizontal < INNER_WIDTH; horizontal++) {
            for (int vertical = 0; vertical < INNER_HEIGHT; vertical++) {
                BlockPos candidate = offset(portalPos, axis, -horizontal, -vertical);
                MindPortalBlockEntity core = coreEntity(level, candidate);
                if (core != null && level.getBlockState(candidate).getValue(MindPortalBlock.CORE)) {
                    return core;
                }
            }
        }
        return null;
    }

    private static MindPortalBlockEntity coreEntity(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof MindPortalBlockEntity portal ? portal : null;
    }

    private static Vec3 safeExit(ServerLevel level, PortalEndpoint endpoint) {
        double centerX = endpoint.core().getX() + (endpoint.axis() == Direction.Axis.X ? 1.5D : 0.5D);
        double centerZ = endpoint.core().getZ() + (endpoint.axis() == Direction.Axis.Z ? 1.5D : 0.5D);
        for (double direction : new double[] {1.25D, -1.25D}) {
            double x = centerX + (endpoint.axis() == Direction.Axis.Z ? direction : 0.0D);
            double z = centerZ + (endpoint.axis() == Direction.Axis.X ? direction : 0.0D);
            BlockPos feet = BlockPos.containing(x, endpoint.core().getY(), z);
            if (level.getBlockState(feet.below()).isSolidRender(level, feet.below())
                    && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir()) {
                return new Vec3(x, endpoint.core().getY() + 0.05D, z);
            }
        }
        return new Vec3(centerX, endpoint.core().getY() + 0.05D, centerZ);
    }

    private static void playActivation(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.BLOCKS, 1.0F, 0.75F);
        BlockState state = level.getBlockState(pos);
        Direction.Axis axis = state.is(ModBlocks.MIND_PORTAL.get())
                ? state.getValue(MindPortalBlock.AXIS)
                : Direction.Axis.X;
        double centerX = pos.getX() + (axis == Direction.Axis.X ? 1.5D : 0.5D);
        double centerZ = pos.getZ() + (axis == Direction.Axis.Z ? 1.5D : 0.5D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                centerX, pos.getY() + 2.0D, centerZ,
                90, 1.3D, 1.8D, 0.3D, 0.12D);
    }

    private static BlockPos offset(BlockPos origin, Direction.Axis axis, int horizontal, int vertical) {
        return axis == Direction.Axis.X
                ? origin.offset(horizontal, vertical, 0)
                : origin.offset(0, vertical, horizontal);
    }

    private static PortalData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PortalData::load, PortalData::new, DATA_NAME);
    }

    private record PortalFrame(BlockPos core, Direction.Axis axis) {
    }

    public record PortalEndpoint(ResourceKey<Level> dimension, BlockPos core, Direction.Axis axis) {
    }

    private record PortalLink(UUID id, UUID owner, PortalEndpoint source, PortalEndpoint destination,
                              ResourceKey<Level> baseDimension, ResourceKey<Level> targetDimension,
                              UUID targetOwner) {
    }

    private static class PortalData extends SavedData {
        private final Map<UUID, PortalLink> links = new HashMap<>();

        private static PortalData load(CompoundTag tag) {
            PortalData data = new PortalData();
            ListTag linksTag = tag.getList(LINKS_TAG, Tag.TAG_COMPOUND);
            for (Tag value : linksTag) {
                CompoundTag linkTag = (CompoundTag) value;
                try {
                    UUID id = linkTag.getUUID("Id");
                    UUID owner = linkTag.getUUID("Owner");
                    PortalEndpoint source = readEndpoint(linkTag.getCompound("Source"));
                    PortalEndpoint destination = readEndpoint(linkTag.getCompound("Destination"));
                    ResourceKey<Level> base = readDimension(linkTag.getString("BaseDimension"));
                    ResourceKey<Level> target = readDimension(linkTag.getString("TargetDimension"));
                    UUID targetOwner = linkTag.hasUUID("TargetOwner") ? linkTag.getUUID("TargetOwner") : null;
                    if (source != null && destination != null && base != null && target != null) {
                        data.links.put(id, new PortalLink(id, owner, source, destination, base, target, targetOwner));
                    }
                } catch (IllegalArgumentException ignored) {
                    MyDimension.LOGGER.warn("Ignored invalid saved mind portal link");
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag linksTag = new ListTag();
            for (PortalLink link : links.values()) {
                CompoundTag linkTag = new CompoundTag();
                linkTag.putUUID("Id", link.id());
                linkTag.putUUID("Owner", link.owner());
                linkTag.put("Source", writeEndpoint(link.source()));
                linkTag.put("Destination", writeEndpoint(link.destination()));
                linkTag.putString("BaseDimension", link.baseDimension().location().toString());
                linkTag.putString("TargetDimension", link.targetDimension().location().toString());
                if (link.targetOwner() != null) {
                    linkTag.putUUID("TargetOwner", link.targetOwner());
                }
                linksTag.add(linkTag);
            }
            tag.put(LINKS_TAG, linksTag);
            return tag;
        }

        private static CompoundTag writeEndpoint(PortalEndpoint endpoint) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", endpoint.dimension().location().toString());
            tag.putLong("Pos", endpoint.core().asLong());
            tag.putString("Axis", endpoint.axis().getName());
            return tag;
        }

        private static PortalEndpoint readEndpoint(CompoundTag tag) {
            ResourceKey<Level> dimension = readDimension(tag.getString("Dimension"));
            Direction.Axis axis = Direction.Axis.byName(tag.getString("Axis"));
            if (dimension == null || axis == null || axis == Direction.Axis.Y) {
                return null;
            }
            return new PortalEndpoint(dimension, BlockPos.of(tag.getLong("Pos")), axis);
        }

        private static ResourceKey<Level> readDimension(String value) {
            ResourceLocation location = ResourceLocation.tryParse(value);
            return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
        }
    }
}
