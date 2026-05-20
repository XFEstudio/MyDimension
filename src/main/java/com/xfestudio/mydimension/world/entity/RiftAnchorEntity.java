package com.xfestudio.mydimension.world.entity;

import com.xfestudio.mydimension.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;

import java.util.UUID;

public class RiftAnchorEntity extends Entity {
    private static final String OWNER_TAG = "Owner";
    private UUID owner;

    public RiftAnchorEntity(EntityType<? extends RiftAnchorEntity> entityType, Level level) {
        super(entityType, level);
        noPhysics = true;
        setNoGravity(true);
        setGlowingTag(true);
    }

    public RiftAnchorEntity(Level level, UUID owner, double x, double y, double z) {
        this(ModEntities.RIFT_ANCHOR.get(), level);
        this.owner = owner;
        setPos(x, y, z);
    }

    @Override
    public void tick() {
        super.tick();
        setGlowingTag(true);
        setDeltaMovement(0.0D, 0.0D, 0.0D);
        if (!level().isClientSide()) {
            placeLight();
        }
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID(OWNER_TAG)) {
            owner = tag.getUUID(OWNER_TAG);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) {
            tag.putUUID(OWNER_TAG, owner);
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        clearLight();
        super.remove(reason);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    public UUID owner() {
        return owner;
    }

    private void placeLight() {
        BlockPos pos = lightPos();
        BlockState state = level().getBlockState(pos);
        if (state.isAir() || state.is(Blocks.LIGHT)) {
            level().setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
        }
    }

    private void clearLight() {
        if (level().isClientSide()) {
            return;
        }

        BlockPos pos = lightPos();
        if (level().getBlockState(pos).is(Blocks.LIGHT)) {
            level().removeBlock(pos, false);
        }
    }

    private BlockPos lightPos() {
        return BlockPos.containing(getX(), getY() + 1.0D, getZ());
    }
}
