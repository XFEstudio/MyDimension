package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.builder.ResonantAnchorTarget;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.registry.ModBlockEntities;
import com.xfestudio.mydimension.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Non-ticking identity and ACL state for a Resonant Supply Anchor.
 */
public final class ResonantSupplyAnchorBlockEntity extends BlockEntity implements ResonantAnchorTarget {
    public static final String ANCHOR_ID_TAG = "AnchorId";
    public static final String OWNER_TAG = "Owner";
    public static final String AUTHORIZED_TAG = "Authorized";
    public static final String PUBLIC_TAG = "Public";

    private static final String AUTHORIZED_ID_TAG = "Id";
    private static final int CORRUPT_ACL_SAFETY_LIMIT = 4096;

    private UUID anchorId = UUID.randomUUID();
    @Nullable
    private UUID ownerId;
    private final LinkedHashSet<UUID> authorizedPlayers = new LinkedHashSet<>();
    private boolean publicAccess;

    /** The ID currently present in SavedData; kept separate while BlockEntityTag is applied. */
    @Nullable
    private UUID registeredId;
    @Nullable
    private AnchorIndexSavedData.AnchorLocation registeredLocation;

    public ResonantSupplyAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESONANT_SUPPLY_ANCHOR.get(), pos, state);
    }

    public UUID anchorId() {
        return anchorId;
    }

    public Optional<UUID> ownerId() {
        return Optional.ofNullable(ownerId);
    }

    public Set<UUID> authorizedPlayers() {
        return Collections.unmodifiableSet(authorizedPlayers);
    }

    public boolean publicAccess() {
        return publicAccess;
    }

    public boolean canUse(ServerPlayer player) {
        UUID playerId = player.getUUID();
        return player.hasPermissions(2)
                || publicAccess
                || playerId.equals(ownerId)
                || authorizedPlayers.contains(playerId);
    }

    @Override
    public boolean mayUse(ServerPlayer player) {
        return canUse(player);
    }

    public boolean canManage(ServerPlayer player) {
        return player.hasPermissions(2) || player.getUUID().equals(ownerId);
    }

    public AclResult authorize(ServerPlayer actor, UUID playerId) {
        if (!canManage(actor)) {
            return AclResult.NOT_AUTHORIZED;
        }
        if (playerId.equals(ownerId) || authorizedPlayers.contains(playerId)) {
            return AclResult.UNCHANGED;
        }
        if (authorizedPlayers.size() >= BuilderConfig.MAX_AUTHORIZED_PLAYERS.get()) {
            return AclResult.LIMIT_REACHED;
        }
        authorizedPlayers.add(playerId);
        setChangedAndSync();
        return AclResult.CHANGED;
    }

    public AclResult revoke(ServerPlayer actor, UUID playerId) {
        if (!canManage(actor)) {
            return AclResult.NOT_AUTHORIZED;
        }
        if (!authorizedPlayers.remove(playerId)) {
            return AclResult.UNCHANGED;
        }
        setChangedAndSync();
        return AclResult.CHANGED;
    }

    public AclResult setPublicAccess(ServerPlayer actor, boolean publicAccess) {
        if (!canManage(actor)) {
            return AclResult.NOT_AUTHORIZED;
        }
        if (this.publicAccess == publicAccess) {
            return AclResult.UNCHANGED;
        }
        this.publicAccess = publicAccess;
        setChangedAndSync();
        return AclResult.CHANGED;
    }

    /** Called after BlockItem has applied any copied BlockEntityTag. */
    public void placedBy(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        AnchorIndexSavedData index = AnchorIndexSavedData.get(serverLevel.getServer());
        if (registeredId != null) {
            index.unregister(registeredId, registeredLocation == null ? location() : registeredLocation);
            registeredId = null;
            registeredLocation = null;
        }

        if (ownerId == null) {
            ownerId = player.getUUID();
        } else if (!ownerId.equals(player.getUUID())) {
            // A dropped/cloned anchor only retains its stable identity for its owner.
            anchorId = UUID.randomUUID();
            ownerId = player.getUUID();
            authorizedPlayers.clear();
            publicAccess = false;
        }

        registerInIndex();
        setChangedAndSync();
    }

    /** Removes the index entry only for an actual block replacement, never a chunk unload. */
    public void unregisterFromIndex() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID indexed = registeredId == null ? anchorId : registeredId;
        AnchorIndexSavedData.get(serverLevel.getServer()).unregister(indexed,
                registeredLocation == null ? location() : registeredLocation);
        registeredId = null;
        registeredLocation = null;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemoved() && level instanceof ServerLevel serverLevel) {
            registerInIndex();
            if (BuilderConfig.isEnabled()
                    && getBlockState().is(ModBlocks.RESONANT_SUPPLY_ANCHOR.get())) {
                // One-shot lifecycle validation. Cross-chunk targets that are not loaded yet
                // are woken precisely by ResonantSupplyAnchorLifecycle when their chunk loads.
                serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        BlockState previous = getBlockState();
        super.setBlockState(state);
        if (level instanceof ServerLevel
                && previous.hasProperty(ResonantSupplyAnchorBlock.FACING)
                && state.hasProperty(ResonantSupplyAnchorBlock.FACING)
                && previous.getValue(ResonantSupplyAnchorBlock.FACING)
                != state.getValue(ResonantSupplyAnchorBlock.FACING)) {
            registerInIndex();
        }
    }

    public void synchronizeIndex() {
        if (level instanceof ServerLevel) {
            registerInIndex();
        }
    }

    private void registerInIndex() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        AnchorIndexSavedData index = AnchorIndexSavedData.get(serverLevel.getServer());
        AnchorIndexSavedData.AnchorLocation currentLocation = location();
        if (registeredId != null && (!registeredId.equals(anchorId)
                || registeredLocation == null || !registeredLocation.equals(currentLocation))) {
            index.unregister(registeredId, registeredLocation == null ? currentLocation : registeredLocation);
        }
        UUID claimed = index.claim(anchorId, currentLocation);
        if (!claimed.equals(anchorId)) {
            anchorId = claimed;
            setChanged();
        }
        registeredId = anchorId;
        registeredLocation = currentLocation;
    }

    private AnchorIndexSavedData.AnchorLocation location() {
        return new AnchorIndexSavedData.AnchorLocation(
                level.dimension(),
                worldPosition,
                getBlockState().getValue(ResonantSupplyAnchorBlock.FACING)
        );
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID(ANCHOR_ID_TAG, anchorId);
        if (ownerId != null) {
            tag.putUUID(OWNER_TAG, ownerId);
        }
        tag.putBoolean(PUBLIC_TAG, publicAccess);

        ListTag authorized = new ListTag();
        for (UUID playerId : authorizedPlayers) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(AUTHORIZED_ID_TAG, playerId);
            authorized.add(entry);
        }
        tag.put(AUTHORIZED_TAG, authorized);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        anchorId = tag.hasUUID(ANCHOR_ID_TAG) ? tag.getUUID(ANCHOR_ID_TAG) : UUID.randomUUID();
        ownerId = tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
        publicAccess = tag.getBoolean(PUBLIC_TAG);

        authorizedPlayers.clear();
        ListTag authorized = tag.getList(AUTHORIZED_TAG, Tag.TAG_COMPOUND);
        int count = Math.min(authorized.size(), CORRUPT_ACL_SAFETY_LIMIT);
        for (int i = 0; i < count; i++) {
            CompoundTag entry = authorized.getCompound(i);
            if (entry.hasUUID(AUTHORIZED_ID_TAG)) {
                authorizedPlayers.add(entry.getUUID(AUTHORIZED_ID_TAG));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public enum AclResult {
        CHANGED,
        UNCHANGED,
        NOT_AUTHORIZED,
        LIMIT_REACHED
    }
}
