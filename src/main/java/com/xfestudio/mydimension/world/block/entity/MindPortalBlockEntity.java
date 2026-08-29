package com.xfestudio.mydimension.world.block.entity;

import com.xfestudio.mydimension.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public class MindPortalBlockEntity extends BlockEntity {
    private static final String LINK_ID_TAG = "LinkId";
    private static final String SOURCE_SIDE_TAG = "SourceSide";

    private UUID linkId;
    private boolean sourceSide;

    public MindPortalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MIND_PORTAL.get(), pos, state);
    }

    public void configure(UUID linkId, boolean sourceSide) {
        this.linkId = linkId;
        this.sourceSide = sourceSide;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public UUID linkId() {
        return linkId;
    }

    public boolean sourceSide() {
        return sourceSide;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (linkId != null) {
            tag.putUUID(LINK_ID_TAG, linkId);
        }
        tag.putBoolean(SOURCE_SIDE_TAG, sourceSide);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        linkId = tag.hasUUID(LINK_ID_TAG) ? tag.getUUID(LINK_ID_TAG) : null;
        sourceSide = tag.getBoolean(SOURCE_SIDE_TAG);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition);
    }
}
