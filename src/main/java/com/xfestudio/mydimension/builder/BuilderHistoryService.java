package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.history.BuilderHistoryData;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.history.WorldDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/** Strict, whole-transaction undo/redo. Any observable mismatch rejects before world mutation. */
public final class BuilderHistoryService {
    private BuilderHistoryService() {
    }

    public static boolean undo(ServerPlayer player, ItemStack scepter) {
        if (!BuilderRuntime.settings().enabled()) return false;
        com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager taskManager =
                com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(player.getServer());
        boolean stoppedWorkflow = taskManager.cancel(player, scepter)
                | com.xfestudio.mydimension.builder.blueprint.BlueprintServerService.get(player.getServer())
                .cancelQueuedPlacement(player);
        BuilderHistoryData history = BuilderHistoryData.get(player.getServer());
        BuilderTransaction transaction = history.peekUndo(player.getUUID(), RealmwrightData.id(scepter));
        PendingBuildData.Task initialPending = PendingBuildData.get(player.getServer()).get(player.getUUID());
        if (transaction == null) {
            if (initialPending != null && initialPending.scepterId().equals(RealmwrightData.id(scepter))) {
                PendingBuildData.get(player.getServer()).remove(player.getUUID());
                stoppedWorkflow = true;
            }
            return feedback(player, stoppedWorkflow, stoppedWorkflow
                    ? "message.mydimension.builder.undo_stopped_task"
                    : "message.mydimension.builder.nothing_to_undo");
        }
        ServerLevel level = player.getServer().getLevel(transaction.dimension());
        if (level == null || transaction.state() != BuilderTransaction.State.APPLIED
                || transaction.worldDeltas().stream().anyMatch(delta -> !delta.matchesAfter(level))) {
            return conflict(player, history, transaction, "message.mydimension.builder.undo_world_conflict");
        }
        // Protection is intentionally checked for the complete transaction before reclaiming drops or
        // changing any block.  This matters when land was claimed after the original operation: an exact
        // world-state match alone must not turn undo into a claim bypass.
        if (!mayRestore(player, level, transaction.worldDeltas(), true)) {
            return conflict(player, history, transaction, "message.mydimension.builder.undo_world_conflict");
        }
        boolean demolition = transaction.type() == BuilderTransaction.Type.DEMOLISH;
        if (demolition && !ItemStack.matches(player.getOffhandItem(), transaction.offhandAfter())) {
            return conflict(player, history, transaction, "message.mydimension.builder.undo_tool_conflict");
        }
        if (!transaction.dropCredits().isEmpty()) {
            List<ItemEntity> entities = transactionEntities(level, transaction);
            boolean reclaimed = demolition
                    ? BuilderMaterials.canAndRemove(player, scepter, transaction.dropCredits(), entities)
                    : BuilderMaterials.canAndRemoveIncludingOffhand(
                    player, scepter, transaction.dropCredits(), entities);
            if (!reclaimed) {
                return conflict(player, history, transaction, "message.mydimension.builder.undo_items_conflict");
            }
        }

        if (!restoreAll(level, transaction.worldDeltas(), true)) {
            restoreAll(level, transaction.worldDeltas(), false);
            if (!transaction.dropCredits().isEmpty()) {
                BlockPos pos = transaction.worldDeltas().isEmpty() ? player.blockPosition()
                        : transaction.worldDeltas().get(0).pos();
                List<ItemStack> overflow = demolition
                        ? BuilderMaterials.insertPreservingOffhand(player, scepter,
                        transaction.dropCredits())
                        : BuilderMaterials.insert(player, scepter, transaction.dropCredits());
                spawnOverflow(player, overflow, transaction.id(), pos);
            }
            return conflict(player, history, transaction, "message.mydimension.builder.undo_restore_conflict");
        }
        if (demolition) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, transaction.offhandBefore());
        } else {
            spawnOverflow(player, BuilderMaterials.insert(player, scepter, transaction.materialDebits()));
        }
        PendingBuildData.Task pending = PendingBuildData.get(player.getServer()).get(player.getUUID());
        if (pending != null && pending.transactionId().equals(transaction.id())) {
            PendingBuildData.get(player.getServer()).remove(player.getUUID());
        }
        history.markUndone(player.getUUID(), RealmwrightData.id(scepter));
        return feedback(player, true, "message.mydimension.builder.undo_ok");
    }

    public static boolean redo(ServerPlayer player, ItemStack scepter) {
        if (!BuilderRuntime.settings().enabled()) return false;
        BuilderHistoryData history = BuilderHistoryData.get(player.getServer());
        BuilderTransaction transaction = history.peekRedo(player.getUUID(), RealmwrightData.id(scepter));
        if (transaction == null) return feedback(player, false, "message.mydimension.builder.nothing_to_redo");
        ServerLevel level = player.getServer().getLevel(transaction.dimension());
        if (level == null || transaction.state() != BuilderTransaction.State.UNDONE
                || transaction.worldDeltas().stream().anyMatch(delta -> !delta.matchesBefore(level))) {
            return conflict(player, history, transaction, "message.mydimension.builder.redo_world_conflict");
        }
        // Run the complete permission preflight before material extraction.  A canceled event therefore
        // leaves both the world and every material source untouched.
        if (!mayRestore(player, level, transaction.worldDeltas(), false)) {
            return conflict(player, history, transaction, "message.mydimension.builder.redo_world_conflict");
        }
        if (transaction.type() == BuilderTransaction.Type.DEMOLISH
                && !ItemStack.matches(player.getOffhandItem(), transaction.offhandBefore())) {
            return conflict(player, history, transaction, "message.mydimension.builder.redo_tool_conflict");
        }

        List<ItemStack> consumed = new ArrayList<>();
        if (transaction.type() != BuilderTransaction.Type.DEMOLISH
                && !(player.isCreative() && BuilderRuntime.settings().creativeBypassesCosts())) {
            for (ItemStack debit : transaction.materialDebits()) {
                BuilderMaterials.Extraction extraction = BuilderMaterials.extract(player, scepter, debit,
                        debit.getCount());
                consumed.addAll(extraction.stacks());
                if (extraction.count() != debit.getCount()) {
                    spawnOverflow(player, BuilderMaterials.insert(player, scepter, consumed));
                    return conflict(player, history, transaction, "message.mydimension.builder.redo_items_conflict");
                }
            }
        }

        if (!restoreAll(level, transaction.worldDeltas(), false)) {
            restoreAll(level, transaction.worldDeltas(), true);
            if (!consumed.isEmpty()) {
                spawnOverflow(player, BuilderMaterials.insert(player, scepter, consumed));
            }
            return conflict(player, history, transaction, "message.mydimension.builder.redo_restore_conflict");
        }
        if (transaction.type() == BuilderTransaction.Type.DEMOLISH) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, transaction.offhandAfter());
        }
        if (!transaction.dropCredits().isEmpty()) {
            BlockPos dropPos = transaction.worldDeltas().isEmpty() ? player.blockPosition()
                    : transaction.worldDeltas().get(0).pos();
            List<ItemStack> overflow = transaction.type() == BuilderTransaction.Type.DEMOLISH
                    ? BuilderMaterials.insertPreservingOffhand(player, scepter,
                    transaction.dropCredits())
                    : BuilderMaterials.insert(player, scepter, transaction.dropCredits());
            spawnOverflow(player, overflow, transaction.id(), dropPos);
        }
        history.markRedone(player.getUUID(), RealmwrightData.id(scepter));
        return feedback(player, true, "message.mydimension.builder.redo_ok");
    }

    private static boolean conflict(ServerPlayer player, BuilderHistoryData history,
                                    BuilderTransaction transaction, String key) {
        transaction.setState(BuilderTransaction.State.CONFLICTED);
        history.setDirty();
        return feedback(player, false, key);
    }

    private static boolean feedback(ServerPlayer player, boolean success, String key) {
        player.displayClientMessage(Component.translatable(key), true);
        return success;
    }

    /**
     * Posts the Forge protection events that the equivalent remove/place operation would expose, without
     * first mutating the level.  Every delta is authorized before the caller touches inventory or invokes
     * {@link #restoreAll}; a single cancellation rejects the complete transaction.
     *
     * <p>{@link BlockEvent.EntityPlaceEvent} is normally constructed after the new state is present in the
     * world.  Mutating the level merely to ask permission could itself trigger neighbour or block-entity
     * side effects, so the history path uses a target-aware event subclass.  It preserves Forge's ordinary
     * snapshot, position and actor while exposing the intended state through both state accessors used by
     * protection integrations.</p>
     */
    private static boolean mayRestore(ServerPlayer player, ServerLevel level, List<WorldDelta> deltas,
                                      boolean restoreBefore) {
        if (!player.mayBuild() || player.isSpectator()) return false;
        try {
            for (WorldDelta delta : deltas) {
                BlockState current = level.getBlockState(delta.pos());
                BlockState target = restoreBefore ? delta.beforeState() : delta.afterState();
                CompoundTag targetBlockEntity = restoreBefore
                        ? delta.beforeBlockEntity() : delta.afterBlockEntity();

                if (!level.mayInteract(player, delta.pos())
                        || player.blockActionRestricted(level, delta.pos(),
                        player.gameMode.getGameModeForPlayer())) {
                    return false;
                }
                if (!player.canUseGameMasterBlocks()
                        && (requiresGameMasterAccess(current, level.getBlockEntity(delta.pos()), null,
                        delta.pos())
                        || requiresGameMasterAccess(target, null, targetBlockEntity, delta.pos()))) {
                    return false;
                }

                // Replacements need both permissions: authorization to remove the current block and to
                // place the recorded target.  This is deliberately strict for same-block NBT rewrites too.
                if (!current.isAir()) {
                    if (!current.equals(target)
                            && !BuilderReplacementPolicy.canReplaceTarget(current, level, delta.pos(),
                            true, player.isCreative())) {
                        return false;
                    }
                    BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(level, delta.pos(), current, player);
                    if (MinecraftForge.EVENT_BUS.post(breakEvent)) return false;
                }
                if (!target.isAir()) {
                    BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, delta.pos());
                    BlockEvent.EntityPlaceEvent placeEvent = new HistoryPlaceEvent(snapshot, current, player, target);
                    if (MinecraftForge.EVENT_BUS.post(placeEvent)) return false;
                }
            }

            // Event listeners are expected to be observers, but a defensive final check prevents a
            // listener-side world mutation from being silently overwritten by the restore phase.
            for (WorldDelta delta : deltas) {
                if (restoreBefore ? !delta.matchesAfter(level) : !delta.matchesBefore(level)) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean requiresGameMasterAccess(BlockState state, BlockEntity liveBlockEntity,
                                                     CompoundTag savedBlockEntity, BlockPos pos) {
        if (state.getBlock() instanceof GameMasterBlock) return true;
        if (liveBlockEntity != null && liveBlockEntity.onlyOpCanSetNbt()) return true;
        if (savedBlockEntity == null) return false;
        BlockEntity restored = BlockEntity.loadStatic(pos, state, savedBlockEntity.copy());
        return restored != null && restored.onlyOpCanSetNbt();
    }

    /** Place-event view whose target is available without a speculative world write. */
    private static final class HistoryPlaceEvent extends BlockEvent.EntityPlaceEvent {
        private final BlockState target;

        private HistoryPlaceEvent(BlockSnapshot snapshot, BlockState placedAgainst, ServerPlayer player,
                                  BlockState target) {
            super(snapshot, placedAgainst, player);
            this.target = target;
        }

        @Override
        public BlockState getState() {
            return target;
        }

        @Override
        public BlockState getPlacedBlock() {
            return target;
        }
    }

    /** Applies all deltas and then verifies the complete final set, not merely each intermediate write. */
    private static boolean restoreAll(ServerLevel level, List<WorldDelta> deltas, boolean before) {
        try {
            return BuilderDropCapture.capture(() -> {
                boolean restored = true;
                if (before) {
                    for (int index = deltas.size() - 1; index >= 0; index--) {
                        restored &= deltas.get(index).restoreBefore(level);
                    }
                } else {
                    for (WorldDelta delta : deltas) restored &= delta.restoreAfter(level);
                }
                if (!restored) return false;
                for (WorldDelta delta : deltas) {
                    if (before ? !delta.matchesBefore(level) : !delta.matchesAfter(level)) return false;
                }
                return true;
            }).value();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void spawnOverflow(ServerPlayer player, List<ItemStack> values) {
        BlockPos pos = player.blockPosition();
        for (ItemStack stack : values) {
            ItemEntity entity = new ItemEntity(player.serverLevel(), pos.getX() + .5D, pos.getY() + .5D,
                    pos.getZ() + .5D, stack.copy());
            player.serverLevel().addFreshEntity(entity);
        }
    }

    private static void spawnOverflow(ServerPlayer player, List<ItemStack> values,
                                      java.util.UUID transactionId, BlockPos pos) {
        for (ItemStack stack : values) {
            ItemEntity entity = new ItemEntity(player.serverLevel(), pos.getX() + .5D, pos.getY() + .5D,
                    pos.getZ() + .5D, stack.copy());
            entity.getPersistentData().putUUID("mydimension:builder_transaction", transactionId);
            player.serverLevel().addFreshEntity(entity);
        }
    }

    private static List<ItemEntity> transactionEntities(ServerLevel level, BuilderTransaction transaction) {
        java.util.LinkedHashMap<java.util.UUID, ItemEntity> result = new java.util.LinkedHashMap<>();
        for (WorldDelta delta : transaction.worldDeltas()) {
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class,
                    new net.minecraft.world.phys.AABB(delta.pos()).inflate(2.0D), candidate ->
                            candidate.getPersistentData().hasUUID("mydimension:builder_transaction")
                                    && candidate.getPersistentData().getUUID("mydimension:builder_transaction")
                                    .equals(transaction.id()))) {
                result.put(entity.getUUID(), entity);
            }
        }
        return List.copyOf(result.values());
    }
}
