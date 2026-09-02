package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.history.BuilderHistoryData;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.history.WorldDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/** Server-authoritative execution for layer jobs and material-waiting retries. */
public final class BuilderOperationManager {
    /** Conservative upper bound for one ordinary state-only delta plus its small item ledger share. */
    private static final long ORDINARY_HISTORY_ENTRY_BYTES = 4_096L;
    /** Used only when a mod advertises a BE but cannot create a prediction instance safely. */
    private static final long UNKNOWN_BLOCK_ENTITY_BYTES = 256L * 1_024L;
    /**
     * Structural block entities whose first ticker invocation is a bounded, non-processing
     * initialization pass. Keep this deliberately explicit: ticking an arbitrary newly placed
     * furnace or modded machine here could advance recipes, consume fuel, or emit side effects.
     */
    private static final ResourceLocation CREATE_ITEM_VAULT =
            new ResourceLocation("create", "item_vault");

    private BuilderOperationManager() {
    }

    public static Result executeSurface(ServerPlayer player, ItemStack scepter, BlockHitResult hit) {
        return executeSurface(player, scepter, hit, false);
    }

    /** Used by the C2S handler after {@link BuilderReachValidator#validatedHit} already ray-validated it. */
    public static Result executeValidatedSurface(ServerPlayer player, ItemStack scepter, BlockHitResult hit) {
        return executeSurface(player, scepter, hit, true);
    }

    private static Result executeSurface(ServerPlayer player, ItemStack scepter, BlockHitResult hit,
                                         boolean reachAlreadyValidated) {
        if (!BuilderRuntime.settings().enabled()) return Result.disabled();
        if (PendingBuildData.get(player.getServer()).get(player.getUUID()) != null
                || com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(player.getServer())
                .hasActive(player.getUUID())) {
            return Result.rejected("message.mydimension.builder.active_task_exists");
        }
        BuilderMode mode = RealmwrightData.mode(scepter);
        ServerLevel level = player.serverLevel();
        if (!reachAlreadyValidated && !validReachAndSight(player, hit)) {
            return Result.rejected("message.mydimension.builder.out_of_reach");
        }

        int requested = mode == BuilderMode.BUILD
                ? RealmwrightData.buildLimit(scepter, BuilderRuntime.settings().maxBuildLimit())
                : RealmwrightData.demolishLimit(scepter, BuilderRuntime.settings().maxDemolishLimit());
        int hardLimit = mode == BuilderMode.BUILD
                ? BuilderRuntime.settings().maxBuildLimit() : BuilderRuntime.settings().maxDemolishLimit();
        int limit = Math.max(1, Math.min(requested, hardLimit));
        ItemStack offhand = player.getOffhandItem();
        BlockState override = offhand.getItem() instanceof BlockItem blockItem
                ? blockItem.getBlock().defaultBlockState() : null;
        SurfacePlanner.Plan plan = SurfacePlanner.plan(level, hit.getBlockPos(), hit.getDirection(), mode,
                RealmwrightData.matchMode(scepter), limit, override);
        if (plan.candidates().isEmpty()) return Result.rejected("message.mydimension.builder.nothing_to_do");
        List<SurfacePlanner.Candidate> locked = lockSurfaceStates(player, plan.candidates(),
                hit.getDirection(), mode);
        return executeImmediateSurface(player, scepter, mode, locked, hit.getDirection(),
                UUID.randomUUID(), plan.truncated(), RealmwrightData.recordsHistory(scepter), false, false);
    }

    public static Result resumePending(ServerPlayer player, ItemStack scepter) {
        PendingBuildData data = PendingBuildData.get(player.getServer());
        PendingBuildData.Task task = data.get(player.getUUID());
        if (task == null || !task.scepterId().equals(RealmwrightData.id(scepter))) {
            return Result.rejected("message.mydimension.builder.no_pending_task");
        }
        if (!task.dimension().equals(player.level().dimension())) {
            return Result.rejected("message.mydimension.builder.pending_other_dimension");
        }
        if (task.type() == BuilderTransaction.Type.BLUEPRINT) {
            com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.resume(player, scepter, task);
            return new Result(0, task.missing().size(), 0, false, null, true);
        }
        List<SurfacePlanner.Candidate> candidates = task.missing().stream()
                .map(entry -> new SurfacePlanner.Candidate(entry.pos(), entry.pos(), entry.state(), 0)).toList();
        BuilderMode mode = task.type() == BuilderTransaction.Type.DEMOLISH
                ? BuilderMode.DEMOLISH : BuilderMode.BUILD;
        boolean captureHistory = task.resolveRecordHistory(RealmwrightData.recordsHistory(scepter));
        return executeImmediateSurface(player, scepter, mode, candidates, Direction.UP,
                task.transactionId(), false, captureHistory, true, task.soundPlayed());
    }

    /**
     * Executes an entire manually requested surface in this server task.  History
     * capture is an explicit per-scepter opt-in; with it disabled this path never
     * serializes block entities or constructs a transaction ledger.
     */
    private static Result executeImmediateSurface(ServerPlayer player, ItemStack scepter,
                                                  BuilderMode mode,
                                                  List<SurfacePlanner.Candidate> candidates,
                                                  Direction face, UUID transactionId,
                                                  boolean truncated, boolean captureHistory,
                                                  boolean continuation, boolean soundAlreadyPlayed) {
        BuilderTransaction.Type type = mode == BuilderMode.BUILD
                ? BuilderTransaction.Type.BUILD : BuilderTransaction.Type.DEMOLISH;
        if (captureHistory && continuation
                && !validateContinuationPrefix(player, scepter, transactionId)) {
            return new Result(0, 0, 0, truncated,
                    "message.mydimension.builder.continuation_world_conflict", true);
        }
        if (captureHistory && !prepareHistory(player, scepter, transactionId, type,
                candidates, face, false, Map.of())) {
            return new Result(0, 0, 0, truncated,
                    "message.mydimension.builder.history_budget_exceeded", true);
        }

        Execution execution = mode == BuilderMode.BUILD
                ? build(player, scepter, candidates, face, transactionId, false, Map.of(), captureHistory)
                : demolish(player, scepter, candidates, transactionId, captureHistory);
        if (captureHistory) refreshFinalAfterImages(player.serverLevel(), execution);
        boolean committed = !captureHistory
                || recordTransaction(player, scepter, type, transactionId, execution);
        if (!committed) {
            return new Result(execution.changedCount, execution.missing.size(), execution.blocked,
                    truncated, null, true);
        }
        if (shouldRefreshMergedHistory(captureHistory, continuation, committed)) {
            // Appending a continuation can update connection-aware states in the
            // already recorded prefix (fences, walls, redstone, and similar).
            // Refresh the combined transaction, not merely this resume batch.
            BuilderHistoryData.get(player.getServer()).refreshAppliedAfter(player.getUUID(),
                    RealmwrightData.id(scepter), transactionId, player.serverLevel());
        }
        boolean soundPlayed = soundAlreadyPlayed;
        if (!soundPlayed && execution.sound != null) {
            playOperationSound(player, execution.sound);
            soundPlayed = true;
        }

        PendingBuildData pending = PendingBuildData.get(player.getServer());
        PendingBuildData.Task previous = continuation ? pending.get(player.getUUID()) : null;
        boolean matchingContinuation = previous != null
                && previous.transactionId().equals(transactionId)
                && previous.scepterId().equals(RealmwrightData.id(scepter));
        PendingCounts counts = pendingCounts(matchingContinuation,
                matchingContinuation ? previous.completed() : 0,
                matchingContinuation ? previous.total() : 0,
                execution.changedCount, execution.missing.size());
        pending.remove(player.getUUID());
        if (!execution.missing.isEmpty()) {
            pending.put(player.getUUID(), new PendingBuildData.Task(RealmwrightData.id(scepter),
                    transactionId, player.level().dimension(), type, captureHistory, true,
                    soundPlayed, counts.completed(), counts.total(), execution.missing,
                    System.currentTimeMillis()));
        }
        player.displayClientMessage(Component.translatable("message.mydimension.builder.result",
                execution.changedCount, execution.missing.size(), execution.blocked), true);
        // With history disabled and no pending cells, the world and inventories already synchronize
        // through vanilla. Avoid rebuilding/sorting history, resolving every anchor and emitting an
        // empty workflow preview after every ordinary click.
        boolean synchronizeBuilderState = shouldSynchronizeBuilderState(
                captureHistory, execution.missing.size());
        return new Result(execution.changedCount, execution.missing.size(), execution.blocked,
                truncated, null, synchronizeBuilderState);
    }

    static boolean shouldRefreshMergedHistory(boolean captureHistory, boolean continuation,
                                              boolean committed) {
        return captureHistory && continuation && committed;
    }

    static boolean shouldSynchronizeBuilderState(boolean captureHistory, int missingBlocks) {
        return captureHistory || missingBlocks > 0;
    }

    private static boolean validateContinuationPrefix(ServerPlayer player, ItemStack scepter,
                                                      UUID transactionId) {
        BuilderHistoryData history = BuilderHistoryData.get(player.getServer());
        BuilderTransaction prefix = history.peekUndo(player.getUUID(), RealmwrightData.id(scepter));
        if (prefix == null || !prefix.id().equals(transactionId)) return true;
        if (prefix.matchesAppliedAfter(player.serverLevel())) return true;
        history.conflictApplied(player.getUUID(), RealmwrightData.id(scepter), transactionId);
        return false;
    }

    private static int saturatedCount(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, left) + Math.max(0, right));
    }

    static PendingCounts pendingCounts(boolean matchingContinuation, int previousCompleted,
                                       int previousTotal, int changed, int missing) {
        int completed = matchingContinuation
                ? saturatedCount(previousCompleted, changed) : Math.max(0, changed);
        int total = matchingContinuation ? Math.max(0, previousTotal)
                : saturatedCount(changed, missing);
        return new PendingCounts(completed, Math.max(completed, total));
    }

    record PendingCounts(int completed, int total) {
    }

    public static boolean cancelPending(ServerPlayer player, ItemStack scepter) {
        boolean activeBlueprint = com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager
                .get(player.getServer()).cancel(player, scepter);
        PendingBuildData data = PendingBuildData.get(player.getServer());
        PendingBuildData.Task task = data.get(player.getUUID());
        if (task == null || !task.scepterId().equals(RealmwrightData.id(scepter))) {
            return activeBlueprint;
        }
        data.remove(player.getUUID());
        return true;
    }

    /** Locks BlockItem placement semantics once for every target in this click. */
    static List<SurfacePlanner.Candidate> lockSurfaceStates(ServerPlayer player,
                                                            List<SurfacePlanner.Candidate> candidates,
                                                            Direction face, BuilderMode mode) {
        if (mode != BuilderMode.BUILD || !(player.getOffhandItem().getItem() instanceof BlockItem)) {
            return List.copyOf(candidates);
        }
        List<SurfacePlanner.Candidate> locked = new ArrayList<>(candidates.size());
        for (SurfacePlanner.Candidate candidate : candidates) {
            locked.add(new SurfacePlanner.Candidate(candidate.reference(), candidate.target(),
                    desiredState(player, candidate, face), candidate.distance()));
        }
        return List.copyOf(locked);
    }

    private static Execution build(ServerPlayer player, ItemStack scepter,
                                   List<SurfacePlanner.Candidate> candidates, Direction face,
                                   UUID transactionId, boolean allowOffhandOverride,
                                   Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags,
                                   boolean captureHistory) {
        ServerLevel level = player.serverLevel();
        Execution result = new Execution(player.getOffhandItem().copy(), captureHistory,
                OperationSoundKind.PLACE);
        List<BuildAttempt> attempts = new ArrayList<>();
        ItemStack placementSource = player.getOffhandItem().copy();
        boolean free = isFree(player);
        for (SurfacePlanner.Candidate candidate : candidates) {
            try {
                BlockPos pos = candidate.target();
                BlockState desired = allowOffhandOverride ? desiredState(player, candidate, face)
                        : candidate.desiredState();
                BlockState existing = level.getBlockState(pos);
                if (existing.equals(desired)) continue;
                // Keep this pass cheap.  The authoritative collision/envelope check runs exactly
                // once immediately before the edit, after any earlier mod callbacks in this batch.
                if (!validStaticBuildEnvelope(player, pos, desired, existing)) {
                    result.blocked++;
                    continue;
                }
                ItemStack cost = constructionCost(desired);
                if (cost.isEmpty()) {
                    result.blocked++;
                    continue;
                }
                ItemStack placementStack = placementStack(placementSource, desired, cost);
                attempts.add(new BuildAttempt(candidate, desired, cost, placementStack,
                        blockEntityTags.get(pos)));
            } catch (Throwable throwable) {
                result.blocked++;
                MyDimension.LOGGER.warn("Builder rejected a placement candidate after a mod callback failed",
                        throwable);
            }
        }

        Map<BlockPos, WorldDelta.Snapshot> batchBeforeImages = Map.of();
        if (captureHistory) {
            try {
                batchBeforeImages = snapshotBuildBatchArea(level, attempts);
            } catch (Throwable throwable) {
                result.blocked += attempts.size();
                MyDimension.LOGGER.warn("Builder rejected a build batch because its neighbour history "
                        + "could not be captured", throwable);
                return result;
            }
        }

        // Aggregate each ItemKey before touching remote handlers. This is what prevents a large storage
        // network from being scanned once per target block.
        List<SupplyPool> pools = new ArrayList<>();
        if (!free) {
            for (BuildAttempt attempt : attempts) {
                List<Item> fallbacks = BuilderBlockCompatibility.constructionFallbacks(attempt.desired);
                SupplyPool pool = findPool(pools, attempt.cost, fallbacks);
                if (pool == null) {
                    pool = new SupplyPool(attempt.cost.copyWithCount(1), fallbacks);
                    pools.add(pool);
                }
                pool.requested += attempt.cost.getCount();
            }
            // Reserve every target's exact item before requesting any substitution. Thus ordinary dirt
            // targets keep first claim on dirt, while grass targets consume grass blocks whenever present.
            for (SupplyPool pool : pools) {
                extractSupply(player, scepter, pool, pool.template, pool.requested);
            }
            for (SupplyPool pool : pools) {
                for (Item fallback : pool.fallbacks) {
                    int missing = pool.requested - pool.available();
                    if (missing <= 0) break;
                    extractSupply(player, scepter, pool, new ItemStack(fallback), missing);
                }
            }
        }

        List<BuildAttempt> unresolved = processWithSingleRetry(attempts,
                attempt -> placeBuildAttempt(player, pools, free, result, attempt));
        result.blocked += unresolved.size();
        stabilizeBuildBatch(level, result.successfulBuildPositions, captureHistory);
        if (captureHistory && !captureStableBuildHistory(level, batchBeforeImages, result)) {
            restoreSnapshots(level, batchBeforeImages);
            for (SupplyPool pool : pools) pool.resetAvailable();
            result.changed.clear();
            result.missing.clear();
            result.successfulBuildPositions.clear();
            result.sound = null;
            result.blocked += result.changedCount;
            result.changedCount = 0;
        }
        List<ItemStack> unusedSupplies = new ArrayList<>();
        for (SupplyPool pool : pools) {
            unusedSupplies.addAll(pool.unusedStacks());
        }
        if (!unusedSupplies.isEmpty()) {
            dropOverflow(player, transactionId, BuilderMaterials.insert(player, scepter, unusedSupplies));
        }
        result.offhandAfter = player.getOffhandItem().copy();
        return result;
    }

    private static void extractSupply(ServerPlayer player, ItemStack scepter, SupplyPool pool,
                                      ItemStack requested, int amount) {
        if (amount <= 0 || requested.isEmpty()) return;
        try {
            BuilderMaterials.Extraction extraction = BuilderMaterials.extract(player, scepter, requested, amount);
            pool.addReserve(requested, extraction.count());
        } catch (Throwable throwable) {
            MyDimension.LOGGER.warn("Builder material extraction failed; this item is left pending", throwable);
        }
    }

    private static ItemStack placementStack(ItemStack offhand, BlockState desired, ItemStack cost) {
        if (offhand.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == desired.getBlock()) {
            return offhand.copyWithCount(1);
        }
        return cost.copyWithCount(1);
    }

    private static Map<BlockPos, WorldDelta.Snapshot> snapshotBuildBatchArea(
            ServerLevel level, List<BuildAttempt> attempts) {
        List<BlockPos> targets = attempts.stream().map(attempt -> attempt.candidate.target()).toList();
        Map<BlockPos, WorldDelta.Snapshot> snapshots = new java.util.LinkedHashMap<>();
        for (BlockPos pos : buildStabilizationPositions(targets)) {
            if (level.hasChunkAt(pos)) snapshots.put(pos, WorldDelta.snapshot(level, pos));
        }
        return snapshots;
    }

    /** Target and direct-neighbour set used by the post-placement compatibility pass. */
    static List<BlockPos> buildStabilizationPositions(Iterable<BlockPos> targets) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        for (BlockPos target : targets) {
            BlockPos immutable = target.immutable();
            positions.add(immutable);
            for (Direction direction : Direction.values()) {
                positions.add(immutable.relative(direction).immutable());
            }
        }
        return List.copyOf(positions);
    }

    /** Each directed target/source pair appears once even when two successful targets touch. */
    static List<NeighborNotification> buildNeighborNotifications(Iterable<BlockPos> targets) {
        java.util.LinkedHashSet<BlockPos> uniqueTargets = new java.util.LinkedHashSet<>();
        targets.forEach(pos -> uniqueTargets.add(pos.immutable()));
        java.util.LinkedHashSet<NeighborNotification> notifications = new java.util.LinkedHashSet<>();
        for (BlockPos target : uniqueTargets) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = target.relative(direction).immutable();
                notifications.add(new NeighborNotification(target, neighbour));
                notifications.add(new NeighborNotification(neighbour, target));
            }
        }
        return List.copyOf(notifications);
    }

    /**
     * Completes the normal BlockItem neighbour lifecycle once per deduplicated batch area. This is
     * intentionally after all placements so multiblocks such as Create vaults see their complete
     * neighbourhood, while the ordinary per-block protection event remains authoritative.
     */
    static void stabilizeBuildBatch(ServerLevel level, Iterable<BlockPos> successfulTargets,
                                    boolean initializeFreshBlockEntities) {
        java.util.LinkedHashSet<BlockPos> targetSet = new java.util.LinkedHashSet<>();
        successfulTargets.forEach(pos -> targetSet.add(pos.immutable()));
        List<BlockPos> targets = List.copyOf(targetSet);
        if (targets.isEmpty()) return;
        List<BlockPos> affected = buildStabilizationPositions(targets);
        Map<BlockPos, BlockState> beforeStates = new java.util.LinkedHashMap<>();
        Map<BlockPos, BlockEntitySyncState> beforeBlockEntities = new java.util.LinkedHashMap<>();

        for (BlockPos pos : affected) {
            if (!validLoadedPosition(level, pos)) continue;
            beforeStates.put(pos, level.getBlockState(pos));
            // Successful targets are always synchronized exactly once below, so serializing their
            // potentially huge machine/inventory update tags before and after would buy nothing.
            if (requiresBlockEntityComparison(targetSet.contains(pos))) {
                beforeBlockEntities.put(pos, blockEntitySyncState(level, pos));
            }
        }

        for (BlockPos pos : affected) {
            if (!beforeStates.containsKey(pos)) continue;
            try {
                BlockState current = level.getBlockState(pos);
                BlockState shaped = Block.updateFromNeighbourShapes(current, level, pos);
                // Deliver the deduplicated directed callbacks below. UPDATE_KNOWN_SHAPE avoids
                // setBlock recursively emitting the same neighbour relationship a second time.
                if (!shaped.equals(current)) level.setBlock(pos, shaped, Block.UPDATE_KNOWN_SHAPE);
            } catch (Throwable throwable) {
                MyDimension.LOGGER.warn("Builder neighbour-shape stabilization failed at {}", pos, throwable);
            }
        }

        for (NeighborNotification notification : buildNeighborNotifications(targets)) {
            if (!validLoadedPosition(level, notification.target)
                    || !validLoadedPosition(level, notification.source)) continue;
            try {
                Block sourceBlock = level.getBlockState(notification.source).getBlock();
                level.neighborChanged(notification.target, sourceBlock, notification.source);
            } catch (Throwable throwable) {
                MyDimension.LOGGER.warn("Builder neighbour callback stabilization failed for {} <- {}",
                        notification.target, notification.source, throwable);
            }
        }

        if (initializeFreshBlockEntities) {
            initializeDeferredStructuralBlockEntities(level, targets);
        }

        for (BlockPos pos : affected) {
            BlockState beforeState = beforeStates.get(pos);
            if (beforeState == null || !validLoadedPosition(level, pos)) continue;
            try {
                BlockState current = level.getBlockState(pos);
                boolean successfulTarget = targetSet.contains(pos);
                boolean stateChanged = !beforeState.equals(current);
                boolean blockEntityChanged = false;
                if (requiresBlockEntityComparison(successfulTarget)) {
                    BlockEntitySyncState beforeBlockEntity = beforeBlockEntities.get(pos);
                    BlockEntitySyncState afterBlockEntity = blockEntitySyncState(level, pos);
                    blockEntityChanged = beforeBlockEntity == null
                            || !beforeBlockEntity.equivalent(afterBlockEntity);
                }
                if (!successfulTarget && !stateChanged && !blockEntityChanged) continue;
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) blockEntity.setChanged();
                level.sendBlockUpdated(pos, beforeState, current, Block.UPDATE_CLIENTS);
            } catch (Throwable throwable) {
                MyDimension.LOGGER.warn("Builder stabilization sync failed at {}", pos, throwable);
            }
        }
    }

    /**
     * Runs the first server ticker invocation for explicitly vetted structural block entities
     * created by this batch before its transactional after-image is captured. Create item vaults
     * defer both connectivity and their serialized last-known position until this invocation.
     * Capturing the pre-initialization image would make an otherwise untouched material-waiting
     * task look externally modified on resume.
     *
     * <p>This is limited to successful targets, history-enabled construction, and a small explicit
     * allowlist of non-processing structural types. Ordinary and unknown machine block entities are
     * never advanced, and exact after-image validation remains in force once this bounded
     * initialization pass has completed.</p>
     */
    private static void initializeDeferredStructuralBlockEntities(ServerLevel level,
                                                                   Iterable<BlockPos> targets) {
        for (BlockPos pos : targets) {
            if (!validLoadedPosition(level, pos)) continue;
            try {
                BlockState state = level.getBlockState(pos);
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity == null || blockEntity.isRemoved()
                        || !(state.getBlock() instanceof EntityBlock entityBlock)) continue;
                ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                if (!requiresDeferredStructuralFirstTick(typeId)) continue;
                runBlockEntityTicker(entityBlock, level, pos, state, blockEntity);
            } catch (Throwable throwable) {
                // A compatibility initializer must not invalidate already successful placements. If
                // it cannot initialize here, the exact continuation check still fails closed if the
                // ordinary world tick later mutates the recorded image.
                MyDimension.LOGGER.warn("Builder structural first-tick stabilization failed at {}",
                        pos, throwable);
            }
        }
    }

    static boolean requiresDeferredStructuralFirstTick(ResourceLocation blockEntityTypeId) {
        return CREATE_ITEM_VAULT.equals(blockEntityTypeId);
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> void runBlockEntityTicker(EntityBlock block, ServerLevel level,
                                                                      BlockPos pos, BlockState state,
                                                                      BlockEntity blockEntity) {
        BlockEntityType<T> type = (BlockEntityType<T>) blockEntity.getType();
        BlockEntityTicker<T> ticker = block.getTicker(level, state, type);
        if (ticker != null) ticker.tick(level, pos, state, (T) blockEntity);
    }

    static boolean requiresBlockEntityComparison(boolean successfulTarget) {
        return !successfulTarget;
    }

    private static boolean validLoadedPosition(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinBuildHeight() && pos.getY() < level.getMaxBuildHeight()
                && level.hasChunkAt(pos);
    }

    private static BlockEntitySyncState blockEntitySyncState(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return new BlockEntitySyncState(null, null, true);
        try {
            return new BlockEntitySyncState(blockEntity.getType(), blockEntity.getUpdateTag().copy(), true);
        } catch (Throwable throwable) {
            // An opaque modded BE cannot be compared safely. Sync it conservatively only when it is
            // part of the affected area, while still avoiding unconditional setChanged for normal BEs.
            return new BlockEntitySyncState(blockEntity.getType(), null, false);
        }
    }

    private static boolean captureStableBuildHistory(ServerLevel level,
                                                     Map<BlockPos, WorldDelta.Snapshot> beforeImages,
                                                     Execution result) {
        try {
            result.changed.clear();
            for (Map.Entry<BlockPos, WorldDelta.Snapshot> entry : beforeImages.entrySet()) {
                WorldDelta.Snapshot before = entry.getValue();
                WorldDelta.Snapshot after = WorldDelta.snapshot(level, entry.getKey());
                if (before.equals(after)) continue;
                result.changed.add(new WorldDelta(entry.getKey(), before.state(), before.blockEntity(),
                        after.state(), after.blockEntity()));
            }
            return true;
        } catch (Throwable throwable) {
            MyDimension.LOGGER.warn("Builder could not capture stabilized build history", throwable);
            return false;
        }
    }

    /**
     * Makes one placement attempt against the latest world. Returning false is
     * reserved exclusively for a currently-unsatisfied canSurvive dependency,
     * allowing the caller to retry it after later supports in the same batch.
     * Every other result is terminal for this batch and records its own ledger
     * or blocked/missing outcome exactly once.
     */
    private static boolean placeBuildAttempt(ServerPlayer player, List<SupplyPool> pools, boolean free,
                                             Execution result, BuildAttempt attempt) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = attempt.candidate.target();
        BlockState desired = attempt.desired;
        SupplyPool pool = free ? null : findPool(pools, attempt.cost,
                BuilderBlockCompatibility.constructionFallbacks(desired));
        int costCount = attempt.cost.getCount();
        try {
            BlockState existing = level.getBlockState(pos);
            if (existing.equals(desired)) return true;
            if (!validBuildEnvelope(player, pos, desired, existing)) {
                result.blocked++;
                return true;
            }
            if (!desired.canSurvive(level, pos)) return false;
        } catch (Throwable throwable) {
            result.blocked++;
            MyDimension.LOGGER.warn("Builder rejected placement target {} after a dynamic check failed", pos,
                    throwable);
            return true;
        }
        if (!free && pool == null) {
            result.blocked++;
            return true;
        }
        if (pool != null && pool.available() < costCount) {
            result.missing.add(new PendingBuildData.Entry(pos, desired, attempt.blockEntityTag));
            return true;
        }

        BlockState beforeState;
        BlockSnapshot forgeSnapshot;
        try {
            beforeState = level.getBlockState(pos);
            forgeSnapshot = BlockSnapshot.create(level.dimension(), level, pos);
        } catch (Throwable throwable) {
            result.blocked++;
            MyDimension.LOGGER.warn("Builder could not snapshot placement target {} in {}", pos,
                    level.dimension().location(), throwable);
            return true;
        }
        boolean placed = false;
        try {
            placed = level.setBlock(pos, desired, Block.UPDATE_ALL);
            if (placed) {
                BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(forgeSnapshot,
                        beforeState, player);
                if (MinecraftForge.EVENT_BUS.post(event)) {
                    forgeSnapshot.restore(true, false);
                    placed = false;
                }
            }
            if (placed && !attempt.placementStack.isEmpty()
                    && attempt.placementStack.is(desired.getBlock().asItem())) {
                BlockState placedState = level.getBlockState(pos);
                placedState.getBlock().setPlacedBy(level, pos, placedState, player,
                        attempt.placementStack.copy());
            }
            if (placed && attempt.blockEntityTag != null
                    && !applyBlockEntityTag(player, pos, attempt.blockEntityTag)) {
                forgeSnapshot.restore(true, false);
                placed = false;
            }
            // A placement callback may synchronously replace or consume the block (for example a
            // powered TNT). Such side effects are not represented by this transaction, so never debit
            // material or record an air-to-air delta as a successful placement.
            if (placed && !level.getBlockState(pos).is(desired.getBlock())) {
                forgeSnapshot.restore(true, false);
                placed = false;
            }
        } catch (Throwable throwable) {
            try { forgeSnapshot.restore(true, false); } catch (Throwable ignored) { }
            MyDimension.LOGGER.warn("Builder placement failed safely at {} in {}", pos,
                    level.dimension().location(), throwable);
            placed = false;
        }
        if (!placed) {
            result.blocked++;
            return true;
        }
        if (pool != null) {
            List<ItemStack> consumed = pool.consume(costCount);
            if (result.captureHistory) {
                consumed.forEach(stack -> addLedgerStack(result.debits, stack));
            }
        }
        BlockState successfulState = level.getBlockState(pos);
        result.successfulBuildPositions.add(pos.immutable());
        result.captureSound(pos, successfulState);
        result.changedCount++;
        return true;
    }

    /** Runs at most two passes; values returning false after pass two remain unresolved. */
    static <T> List<T> processWithSingleRetry(List<T> values, java.util.function.Predicate<T> process) {
        List<T> deferred = new ArrayList<>();
        for (T value : values) {
            if (!process.test(value)) deferred.add(value);
        }
        if (deferred.isEmpty()) return List.of();
        List<T> unresolved = new ArrayList<>();
        for (T value : deferred) {
            if (!process.test(value)) unresolved.add(value);
        }
        return List.copyOf(unresolved);
    }

    private static Execution demolish(ServerPlayer player, ItemStack scepter,
                                      List<SurfacePlanner.Candidate> candidates, UUID transactionId,
                                      boolean captureHistory) {
        ServerLevel level = player.serverLevel();
        Execution result = new Execution(player.getOffhandItem().copy(), captureHistory,
                OperationSoundKind.BREAK);
        List<PositionedDrop> deferredDrops = captureHistory ? List.of() : new ArrayList<>();
        try {
            for (SurfacePlanner.Candidate candidate : candidates) {
            BlockPos pos = candidate.target();
            BlockState state;
            ItemStack toolBeforeBlock;
            ToolCheck tool;
            List<ItemStack> normalDrops;
            Map<BlockPos, WorldDelta.Snapshot> beforeImages;
            try {
                state = level.getBlockState(pos);
                if (state.isAir() || state.is(BuilderTags.CONSTRUCTION_PROTECTED)
                        || state.is(BuilderTags.TRANSACTION_UNSAFE)) {
                    result.blocked++;
                    continue;
                }
                if (ForgeHooks.onBlockBreakEvent(level, player.gameMode.getGameModeForPlayer(), player, pos) == -1) {
                    result.blocked++;
                    continue;
                }
                toolBeforeBlock = player.getOffhandItem().copy();
                tool = checkTool(state, toolBeforeBlock);
                beforeImages = captureHistory ? snapshotDemolitionArea(level, pos, state) : Map.of();
                BlockEntity blockEntity = level.getBlockEntity(pos);
                normalDrops = tool.canDrop
                        ? Block.getDrops(state, level, pos, blockEntity, player, toolBeforeBlock) : List.of();
            } catch (Throwable throwable) {
                result.blocked++;
                MyDimension.LOGGER.warn("Builder rejected demolition candidate {} after a mod callback failed",
                        pos, throwable);
                continue;
            }

            boolean removed;
            List<ItemStack> capturedDrops;
            try {
                if (tool.canDrop) {
                    BuilderDropCapture.CaptureResult<Boolean> captured = BuilderDropCapture.capture(
                            () -> level.removeBlock(pos, false));
                    removed = captured.value();
                    capturedDrops = captured.drops();
                } else if (tool.recognized) {
                    removed = BuilderDropCapture.discardEntities(
                            () -> level.removeBlock(pos, false));
                    capturedDrops = List.of();
                } else {
                    removed = removeWithoutBreakEffect(level, pos);
                    capturedDrops = List.of();
                }
            } catch (Throwable throwable) {
                if (captureHistory) restoreSnapshots(level, beforeImages);
                result.blocked++;
                MyDimension.LOGGER.warn("Builder rolled back failed demolition at {}", pos, throwable);
                continue;
            }
            if (!removed) {
                if (captureHistory) restoreSnapshots(level, beforeImages);
                result.blocked++;
                continue;
            }

            Map<BlockPos, WorldDelta.Snapshot> afterImages = Map.of();
            if (captureHistory) {
                afterImages = new java.util.LinkedHashMap<>();
                try {
                    for (BlockPos affected : beforeImages.keySet()) {
                        afterImages.put(affected, WorldDelta.snapshot(level, affected));
                    }
                } catch (Throwable throwable) {
                    restoreSnapshots(level, beforeImages);
                    result.blocked++;
                    MyDimension.LOGGER.warn("Builder rolled back demolition at {} because its after-image failed",
                            pos, throwable);
                    continue;
                }
            }
            result.captureSound(pos, state);

            if (tool.canDrop) {
                List<ItemStack> drops = new ArrayList<>(normalDrops.size() + capturedDrops.size());
                normalDrops.forEach(stack -> drops.add(stack.copy()));
                capturedDrops.forEach(stack -> drops.add(stack.copy()));
                if (captureHistory) {
                    drops.forEach(drop -> addLedgerStack(result.credits, drop));
                    dropOverflowAt(level, pos, transactionId,
                            BuilderMaterials.insert(player, scepter, drops));
                } else {
                    // History-off is the low-overhead mode.  Keep each source position, but defer
                    // remote storage until the batch is complete so bound chunks/capabilities are
                    // resolved once instead of once per block and per drop stack.
                    drops.stream().filter(drop -> !drop.isEmpty())
                            .forEach(drop -> deferredDrops.add(new PositionedDrop(pos.immutable(), drop.copy())));
                }
            }
            // Settle the Nth block's drops with the pre-damage tool, then apply exactly one durability point.
            // If this breaks the tool, only subsequent blocks lose harvesting eligibility.
            boolean stopAfterCurrentBlock = false;
            if (tool.recognized && !isFree(player)) {
                try {
                    player.getOffhandItem().hurtAndBreak(1, player,
                            broken -> broken.broadcastBreakEvent(InteractionHand.OFF_HAND));
                } catch (RuntimeException exception) {
                    // Finish accounting this already removed block, but do not let a tool that
                    // refuses durability damage harvest the rest of the batch for free.
                    MyDimension.LOGGER.warn("Builder tool damage callback failed after removing {}", pos,
                            exception);
                    stopAfterCurrentBlock = true;
                }
            }
            if (captureHistory) {
                for (Map.Entry<BlockPos, WorldDelta.Snapshot> entry : beforeImages.entrySet()) {
                    WorldDelta.Snapshot after = afterImages.get(entry.getKey());
                    if (entry.getValue().equals(after)) continue;
                    result.changed.add(new WorldDelta(entry.getKey(), entry.getValue().state(),
                            entry.getValue().blockEntity(), after.state(), after.blockEntity()));
                }
                result.changedCount = result.changed.size();
            } else {
                result.changedCount++;
            }
            if (stopAfterCurrentBlock) break;
            }
        } finally {
            if (!deferredDrops.isEmpty()) {
                settleDemolitionDrops(player, scepter, transactionId, deferredDrops);
            }
        }
        result.offhandAfter = player.getOffhandItem().copy();
        return result;
    }

    /**
     * Stores every demolition output in one remote material session while
     * preserving the original per-stack call semantics and source position.
     */
    private static void settleDemolitionDrops(ServerPlayer player, ItemStack scepter,
                                               UUID transactionId, List<PositionedDrop> drops) {
        List<ItemStack> offered = drops.stream().map(drop -> drop.stack.copy()).toList();
        // Every harvested block was settled before damaging its tool in the original per-block
        // order.  Reserving the offhand here preserves that rule if the tool broke during the batch.
        List<ItemStack> remainders = BuilderMaterials.insertAlignedPreservingOffhand(
                player, scepter, offered);
        ServerLevel level = player.serverLevel();
        for (int index = 0; index < drops.size(); index++) {
            ItemStack remainder = remainders.get(index);
            if (!remainder.isEmpty()) {
                dropOverflowAt(level, drops.get(index).pos, transactionId, List.of(remainder));
            }
        }
    }

    /**
     * Removes a block without ServerLevel#destroyBlock's level-event 2001.
     * Level#removeBlock still restores the contained fluid and runs ordinary
     * setBlock/onRemove/neighbor lifecycle.  Any lifecycle-generated entities
     * are canceled rather than captured because this path is deliberately
     * drop-free.
     */
    static boolean removeWithoutBreakEffect(ServerLevel level, BlockPos pos) {
        return BuilderDropCapture.discardEntities(() -> level.removeBlock(pos, false));
    }

    private static Map<BlockPos, WorldDelta.Snapshot> snapshotDemolitionArea(ServerLevel level, BlockPos pos,
                                                                              BlockState state) {
        Map<BlockPos, WorldDelta.Snapshot> snapshots = new java.util.LinkedHashMap<>();
        for (BlockPos affected : demolitionAffectedPositions(pos, state)) {
            snapshots.put(affected, WorldDelta.snapshot(level, affected));
        }
        return snapshots;
    }

    /** Positions vanilla may synchronously remove when one half of a structural block is broken. */
    private static List<BlockPos> demolitionAffectedPositions(BlockPos pos, BlockState state) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        positions.add(pos.immutable());
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf half = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
            positions.add(pos.relative(half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    ? Direction.UP : Direction.DOWN).immutable());
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.world.level.block.state.properties.BedPart part = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART);
            Direction facing = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            positions.add(pos.relative(part == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                    ? facing : facing.getOpposite()).immutable());
        }
        return List.copyOf(positions);
    }

    private static boolean restoreSnapshots(ServerLevel level, Map<BlockPos, WorldDelta.Snapshot> snapshots) {
        List<Map.Entry<BlockPos, WorldDelta.Snapshot>> entries = new ArrayList<>(snapshots.entrySet());
        boolean restored = true;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Map.Entry<BlockPos, WorldDelta.Snapshot> entry = entries.get(index);
            WorldDelta.Snapshot snapshot = entry.getValue();
            try {
                restored &= new WorldDelta(entry.getKey(), snapshot.state(), snapshot.blockEntity(),
                        snapshot.state(), snapshot.blockEntity()).restoreBefore(level);
            } catch (Throwable throwable) {
                restored = false;
                MyDimension.LOGGER.error("Builder could not restore demolition side effect at {} in {}",
                        entry.getKey(), level.dimension().location(), throwable);
            }
        }
        return restored;
    }

    /** Best-effort immediate compensation when the durable APPLIED record cannot replace PREPARED. */
    private static boolean rollbackUnrecorded(ServerPlayer player, ItemStack scepter,
                                              BuilderTransaction.Type type, UUID transactionId,
                                              Execution execution, ServerLevel level) {
        if (execution.changed.isEmpty()) return true;
        if (type == BuilderTransaction.Type.DEMOLISH) {
            if (!BuilderMaterials.canAndRemove(player, scepter, execution.credits,
                    transactionEntities(level, transactionId, execution.changed))) {
                return false;
            }
            if (!restoreDeltas(level, execution.changed, true)) {
                restoreDeltas(level, execution.changed, false);
                BlockPos dropPos = execution.changed.get(0).pos();
                dropOverflowAt(level, dropPos, transactionId,
                        BuilderMaterials.insertPreservingOffhand(player, scepter, execution.credits));
                return false;
            }
            player.setItemInHand(InteractionHand.OFF_HAND, execution.offhandBefore.copy());
            return true;
        }

        if (!restoreDeltas(level, execution.changed, true)) {
            restoreDeltas(level, execution.changed, false);
            return false;
        }
        BlockPos dropPos = execution.changed.get(0).pos();
        dropOverflowAt(level, dropPos, transactionId,
                BuilderMaterials.insert(player, scepter, execution.debits));
        return true;
    }

    private static boolean restoreDeltas(ServerLevel level, List<WorldDelta> deltas, boolean before) {
        try {
            return BuilderDropCapture.capture(() -> {
                boolean success = true;
                if (before) {
                    for (int index = deltas.size() - 1; index >= 0; index--) {
                        success &= deltas.get(index).restoreBefore(level);
                    }
                } else {
                    for (WorldDelta delta : deltas) success &= delta.restoreAfter(level);
                }
                if (!success) return false;
                for (WorldDelta delta : deltas) {
                    if (before ? !delta.matchesBefore(level) : !delta.matchesAfter(level)) return false;
                }
                return true;
            }).value();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<net.minecraft.world.entity.item.ItemEntity> transactionEntities(
            ServerLevel level, UUID transactionId, List<WorldDelta> deltas) {
        java.util.LinkedHashMap<UUID, net.minecraft.world.entity.item.ItemEntity> values =
                new java.util.LinkedHashMap<>();
        for (WorldDelta delta : deltas) {
            for (net.minecraft.world.entity.item.ItemEntity entity : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(delta.pos()).inflate(2.0D), candidate ->
                            candidate.getPersistentData().hasUUID("mydimension:builder_transaction")
                                    && candidate.getPersistentData().getUUID("mydimension:builder_transaction")
                                    .equals(transactionId))) {
                values.put(entity.getUUID(), entity);
            }
        }
        return List.copyOf(values.values());
    }

    private static BlockState desiredState(ServerPlayer player, SurfacePlanner.Candidate candidate, Direction face) {
        ItemStack offhand = player.getOffhandItem();
        if (!(offhand.getItem() instanceof BlockItem blockItem)) return candidate.desiredState();
        BlockHitResult virtualHit = new BlockHitResult(Vec3.atCenterOf(candidate.reference()), face,
                candidate.reference(), false);
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.OFF_HAND,
                virtualHit));
        BlockState calculated = blockItem.getBlock().getStateForPlacement(context);
        return calculated == null ? blockItem.getBlock().defaultBlockState() : calculated;
    }

    private static boolean validBuildEnvelope(ServerPlayer player, BlockPos pos, BlockState desired,
                                              BlockState existing) {
        if (!validStaticBuildEnvelope(player, pos, desired, existing)) return false;
        // Match normal BlockItem placement semantics: no solid collision shape may intersect any
        // player, mob, vehicle or other collidable entity at the target, not merely the operator.
        return player.serverLevel().isUnobstructed(desired, pos, CollisionContext.empty());
    }

    private static boolean validStaticBuildEnvelope(ServerPlayer player, BlockPos pos, BlockState desired,
                                                     BlockState existing) {
        ServerLevel level = player.serverLevel();
        WorldBorder border = level.getWorldBorder();
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                || !border.isWithinBounds(pos) || !existing.canBeReplaced()
                || desired.is(BuilderTags.CONSTRUCTION_PROTECTED)
                || desired.is(BuilderTags.TRANSACTION_UNSAFE)) return false;
        return true;
    }

    /**
     * Returns the base block-item debit for a state. State properties are part of the blueprint payload,
     * not separate materials: for example, every water-cauldron level maps through vanilla's block/item
     * alias to one cauldron item, and a waterlogged slab still maps to its slab item. Component-count
     * properties remain additive because those states physically represent more than one placed item.
     */
    static ItemStack constructionCost(BlockState state) {
        Block block = state.getBlock();
        ItemStack cost = new ItemStack(block.asItem());
        if (cost.isEmpty()) return ItemStack.EMPTY;
        int count = 1;
        if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            count = 2;
        } else if (block instanceof SnowLayerBlock) {
            count = state.getValue(SnowLayerBlock.LAYERS);
        } else if (block instanceof CandleBlock) {
            count = state.getValue(CandleBlock.CANDLES);
        } else if (block instanceof SeaPickleBlock) {
            count = state.getValue(SeaPickleBlock.PICKLES);
        } else if (block instanceof TurtleEggBlock) {
            count = state.getValue(TurtleEggBlock.EGGS);
        }
        cost.setCount(count);
        return cost;
    }

    private static ToolCheck checkTool(BlockState state, ItemStack tool) {
        if (tool.isEmpty()) return new ToolCheck(false, false);
        ToolAction required = null;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) required = ToolActions.PICKAXE_DIG;
        else if (state.is(BlockTags.MINEABLE_WITH_AXE)) required = ToolActions.AXE_DIG;
        else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) required = ToolActions.SHOVEL_DIG;
        else if (state.is(BlockTags.MINEABLE_WITH_HOE)) required = ToolActions.HOE_DIG;
        boolean matchingType = (required != null && tool.canPerformAction(required))
                || tool.getDestroySpeed(state) > 1.0F;
        boolean tier = matchingType && (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state));
        return new ToolCheck(matchingType, tier);
    }

    private static boolean validReachAndSight(ServerPlayer player, BlockHitResult hit) {
        return BuilderReachValidator.canReachAndSee(player, hit.getBlockPos(), hit.getDirection());
    }

    private static boolean isFree(ServerPlayer player) {
        return player.isCreative() && BuilderRuntime.settings().creativeBypassesCosts();
    }

    /** Executes one part of an already validated blueprint. */
    public static BlueprintBatchResult executeBlueprintBatch(ServerPlayer player, ItemStack scepter,
            List<com.xfestudio.mydimension.builder.blueprint.BlueprintPlacementPlan.PlannedBlock> blocks,
            UUID transactionId, boolean captureHistory) {
        List<SurfacePlanner.Candidate> candidates = new ArrayList<>(blocks.size());
        Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntities = new java.util.HashMap<>();
        for (var block : blocks) {
            candidates.add(new SurfacePlanner.Candidate(block.worldPos(), block.worldPos(), block.state(), 0));
            if (block.blockEntityTag() != null) blockEntities.put(block.worldPos(), block.blockEntityTag());
        }
        if (captureHistory && !prepareHistory(player, scepter, transactionId,
                BuilderTransaction.Type.BLUEPRINT, candidates, Direction.UP, false, blockEntities)) {
            List<PendingBuildData.Entry> deferred = blocks.stream()
                    .map(block -> new PendingBuildData.Entry(block.worldPos(), block.state(), block.blockEntityTag()))
                    .toList();
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.history_budget_exceeded"), true);
            return new BlueprintBatchResult(0, deferred, 0, false, null);
        }
        Execution execution = build(player, scepter, candidates, Direction.UP, transactionId, false,
                blockEntities, captureHistory);
        boolean committed = true;
        if (captureHistory) {
            refreshFinalAfterImages(player.serverLevel(), execution);
            committed = recordTransaction(player, scepter, BuilderTransaction.Type.BLUEPRINT,
                    transactionId, execution);
        }
        return new BlueprintBatchResult(execution.changedCount, List.copyOf(execution.missing), execution.blocked,
                committed, execution.sound);
    }

    private static boolean recordTransaction(ServerPlayer player, ItemStack scepter, BuilderTransaction.Type type,
                                             UUID transactionId, Execution execution) {
        return recordTransaction(player, scepter, type, transactionId, execution, player.serverLevel());
    }

    private static boolean recordTransaction(ServerPlayer player, ItemStack scepter, BuilderTransaction.Type type,
                                             UUID transactionId, Execution execution,
                                             ServerLevel executionLevel) {
        return recordTransaction(player, scepter, type, transactionId, execution, executionLevel, true);
    }

    private static boolean recordTransaction(ServerPlayer player, ItemStack scepter, BuilderTransaction.Type type,
                                             UUID transactionId, Execution execution,
                                             ServerLevel executionLevel, boolean rollbackOnFailure) {
        BuilderHistoryData history = BuilderHistoryData.get(player.getServer());
        if (execution.changed.isEmpty()) {
            history.abortPrepared(player.getUUID(), RealmwrightData.id(scepter), transactionId);
            return true;
        }
        BuilderTransaction transaction = new BuilderTransaction(transactionId, RealmwrightData.id(scepter),
                executionLevel.dimension(), type, System.currentTimeMillis(), execution.changed,
                execution.debits, execution.credits, execution.offhandBefore, execution.offhandAfter,
                BuilderTransaction.State.APPLIED);
        boolean committed;
        try {
            committed = history.commitPrepared(player.getUUID(), RealmwrightData.id(scepter), transaction,
                    BuilderRuntime.settings().undoDepth());
        } catch (RuntimeException exception) {
            history.conflictPrepared(player.getUUID(), RealmwrightData.id(scepter), transactionId);
            committed = false;
            MyDimension.LOGGER.error("Builder transaction {} could not commit its history record",
                    transactionId, exception);
        }
        if (!committed) {
            // Never compensate a task after its recorded after-image stopped matching the world:
            // doing so could overwrite another player's/mod's intervening change. The durable
            // conflict marker is the only safe result in that case.
            boolean rolledBack = rollbackOnFailure && rollbackUnrecorded(player, scepter, type,
                    transactionId, execution, executionLevel);
            if (rolledBack) {
                history.discardConflictMarker(player.getUUID(), RealmwrightData.id(scepter), transactionId);
                execution.changed.clear();
                execution.debits.clear();
                execution.credits.clear();
                execution.changedCount = 0;
            }
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.history_commit_failed"), true);
            return false;
        }
        return true;
    }

    /** Captures neighbour-aware final states only when undo recording was explicitly enabled. */
    private static void refreshFinalAfterImages(ServerLevel level, Execution execution) {
        if (execution.changed.isEmpty()) return;
        List<WorldDelta> refreshed = new ArrayList<>(execution.changed.size());
        try {
            for (WorldDelta delta : execution.changed) {
                WorldDelta.Snapshot after = WorldDelta.snapshot(level, delta.pos());
                refreshed.add(new WorldDelta(delta.pos(), delta.beforeState(), delta.beforeBlockEntity(),
                        after.state(), after.blockEntity()));
            }
        } catch (Throwable throwable) {
            // Each edit already owns a valid immediate after-image. Keep that safe
            // fallback rather than stranding a durable PREPARED entry.
            MyDimension.LOGGER.warn("Builder could not refresh final neighbour-aware history images", throwable);
            return;
        }
        execution.changed.clear();
        execution.changed.addAll(refreshed);
    }

    private static boolean prepareHistory(ServerPlayer player, ItemStack scepter, UUID transactionId,
                                          BuilderTransaction.Type type,
                                          List<SurfacePlanner.Candidate> candidates, Direction face,
                                          boolean allowOffhandOverride,
                                          Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags) {
        long estimate;
        try {
            estimate = estimateHistoryBytes(player, type, candidates, face, allowOffhandOverride,
                    blockEntityTags);
        } catch (RuntimeException exception) {
            MyDimension.LOGGER.warn("Unable to estimate builder transaction {} before execution", transactionId,
                    exception);
            return false;
        }
        return BuilderHistoryData.get(player.getServer()).prepare(player.getUUID(), RealmwrightData.id(scepter),
                transactionId, player.level().dimension(), type, player.getOffhandItem(), estimate)
                == BuilderHistoryData.PrepareResult.PREPARED;
    }

    /** Conservative retained-NBT estimate; evaluated before any material extraction or world edit. */
    private static long estimateHistoryBytes(ServerPlayer player, BuilderTransaction.Type type,
                                             List<SurfacePlanner.Candidate> candidates, Direction face,
                                             boolean allowOffhandOverride,
                                             Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags) {
        ServerLevel level = player.serverLevel();
        long estimate = 4_096L + 2L * stackBytes(player.getOffhandItem());
        java.util.LinkedHashSet<BlockPos> buildTargets = new java.util.LinkedHashSet<>();
        java.util.HashSet<BlockPos> estimatedDemolitionPositions = new java.util.HashSet<>();
        for (SurfacePlanner.Candidate candidate : candidates) {
            if (type == BuilderTransaction.Type.DEMOLISH) {
                BlockPos target = candidate.target();
                BlockState targetState = level.getBlockState(target);
                for (BlockPos pos : demolitionAffectedPositions(target, targetState)) {
                    if (!estimatedDemolitionPositions.add(pos)) continue;
                    BlockEntity existingBlockEntity = level.getBlockEntity(pos);
                    if (existingBlockEntity == null) {
                        estimate = saturatedAdd(estimate, ordinaryHistoryEntryBytes(type));
                        continue;
                    }
                    WorldDelta.Snapshot before = WorldDelta.snapshot(level, pos);
                    WorldDelta predicted = new WorldDelta(pos, before.state(), before.blockEntity(),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null);
                    long entry = 512L + Integer.toUnsignedLong(predicted.save().sizeInBytes());
                    // Container contents can appear once in the before-image and again in the exact drop ledger.
                    entry = saturatedAdd(entry, 2L * tagBytes(before.blockEntity()));
                    estimate = saturatedAdd(estimate, entry);
                }
                continue;
            }

            BlockPos pos = candidate.target();
            buildTargets.add(pos.immutable());
            BlockState beforeState = level.getBlockState(pos);
            BlockState afterState = allowOffhandOverride ? desiredState(player, candidate, face)
                    : candidate.desiredState();
            net.minecraft.nbt.CompoundTag afterBlockEntity = blockEntityTags.get(pos);
            if (afterBlockEntity == null && afterState.hasBlockEntity()) {
                afterBlockEntity = predictBlockEntityTag(pos, afterState);
                if (afterBlockEntity == null) {
                    estimate = saturatedAdd(estimate, saturatedAdd(ordinaryHistoryEntryBytes(type),
                            UNKNOWN_BLOCK_ENTITY_BYTES));
                    continue;
                }
            }
            BlockEntity existingBlockEntity = level.getBlockEntity(pos);
            if (existingBlockEntity == null && afterBlockEntity == null) {
                estimate = saturatedAdd(estimate, ordinaryHistoryEntryBytes(type));
                continue;
            }
            WorldDelta.Snapshot before = existingBlockEntity == null
                    ? new WorldDelta.Snapshot(beforeState, null) : WorldDelta.snapshot(level, pos);
            WorldDelta predicted = new WorldDelta(pos, before.state(), before.blockEntity(), afterState,
                    afterBlockEntity);
            long entry = 512L + Integer.toUnsignedLong(predicted.save().sizeInBytes());
            entry = saturatedAdd(entry, stackBytes(constructionCost(afterState)) + 128L);
            entry = saturatedAdd(entry, tagBytes(afterBlockEntity));
            estimate = saturatedAdd(estimate, entry);
        }
        if (type != BuilderTransaction.Type.DEMOLISH && !buildTargets.isEmpty()) {
            for (BlockPos neighbour : buildStabilizationPositions(buildTargets)) {
                if (buildTargets.contains(neighbour) || !level.hasChunkAt(neighbour)) continue;
                BlockState state = level.getBlockState(neighbour);
                BlockEntity blockEntity = level.getBlockEntity(neighbour);
                if (blockEntity == null) {
                    estimate = saturatedAdd(estimate, ordinaryHistoryEntryBytes(type));
                    continue;
                }
                WorldDelta.Snapshot snapshot = WorldDelta.snapshot(level, neighbour);
                WorldDelta predicted = new WorldDelta(neighbour, state, snapshot.blockEntity(),
                        state, snapshot.blockEntity());
                estimate = saturatedAdd(estimate,
                        512L + Integer.toUnsignedLong(predicted.save().sizeInBytes()));
            }
        }
        return estimate;
    }

    static long ordinaryHistoryEntryBytes(BuilderTransaction.Type type) {
        return ORDINARY_HISTORY_ENTRY_BYTES;
    }

    private static net.minecraft.nbt.CompoundTag predictBlockEntityTag(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) return null;
        try {
            BlockEntity predicted = entityBlock.newBlockEntity(pos, state);
            return predicted == null ? null : predicted.saveWithFullMetadata();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long stackBytes(ItemStack stack) {
        return stack.isEmpty() ? 0L
                : Integer.toUnsignedLong(stack.save(new net.minecraft.nbt.CompoundTag()).sizeInBytes());
    }

    private static long tagBytes(net.minecraft.nbt.CompoundTag tag) {
        return tag == null ? 0L : Integer.toUnsignedLong(tag.sizeInBytes());
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    /** Coalesces equivalent ledger entries without ever exceeding an item's normal stack size. */
    private static void addLedgerStack(List<ItemStack> ledger, ItemStack value) {
        if (value.isEmpty()) return;
        int remaining = value.getCount();
        int maximum = Math.max(1, value.getMaxStackSize());
        for (ItemStack existing : ledger) {
            if (remaining <= 0) break;
            if (!ItemStack.isSameItemSameTags(existing, value) || existing.getCount() >= maximum) continue;
            int moved = Math.min(remaining, maximum - existing.getCount());
            existing.grow(moved);
            remaining -= moved;
        }
        while (remaining > 0) {
            int moved = Math.min(remaining, maximum);
            ledger.add(value.copyWithCount(moved));
            remaining -= moved;
        }
    }

    private static boolean applyBlockEntityTag(ServerPlayer player, BlockPos pos,
                                                net.minecraft.nbt.CompoundTag supplied) {
        BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
        if (blockEntity == null || !supplied.contains("id", net.minecraft.nbt.Tag.TAG_STRING)) return false;
        net.minecraft.resources.ResourceLocation suppliedId = net.minecraft.resources.ResourceLocation.tryParse(
                supplied.getString("id"));
        net.minecraft.resources.ResourceLocation actualId =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        if (suppliedId == null || !suppliedId.equals(actualId)
                || (!player.isCreative() && !player.hasPermissions(2))
                || (blockEntity.onlyOpCanSetNbt() && !player.canUseGameMasterBlocks())) return false;
        try {
            net.minecraft.nbt.CompoundTag copy = supplied.copy();
            copy.putInt("x", pos.getX());
            copy.putInt("y", pos.getY());
            copy.putInt("z", pos.getZ());
            blockEntity.load(copy);
            blockEntity.setChanged();
            player.serverLevel().sendBlockUpdated(pos, player.serverLevel().getBlockState(pos),
                    player.serverLevel().getBlockState(pos), Block.UPDATE_ALL);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void dropOverflow(ServerPlayer player, UUID transactionId, List<ItemStack> values) {
        dropOverflowAt(player.serverLevel(), player.blockPosition(), transactionId, values);
    }

    private static void dropOverflowAt(ServerLevel level, BlockPos pos, UUID transactionId,
                                       List<ItemStack> values) {
        for (ItemStack stack : values) {
            net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(level,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack.copy());
            entity.getPersistentData().putUUID("mydimension:builder_transaction", transactionId);
            level.addFreshEntity(entity);
        }
    }

    public static void playOperationSound(ServerPlayer player,
                                          @javax.annotation.Nullable OperationSound operationSound) {
        if (operationSound == null) return;
        try {
            var soundType = operationSound.state.getSoundType(
                    player.serverLevel(), operationSound.pos, player);
            player.serverLevel().playSound(null, operationSound.pos,
                    selectOperationSound(soundType.getPlaceSound(), soundType.getBreakSound(),
                            operationSound.kind),
                    SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) * 0.5F,
                    soundType.getPitch() * 0.8F);
        } catch (Throwable throwable) {
            MyDimension.LOGGER.warn("Builder could not play its batch sound at {}",
                    operationSound.pos, throwable);
        }
    }

    static <T> T selectOperationSound(T placeSound, T breakSound, OperationSoundKind kind) {
        return switch (kind) {
            case PLACE -> placeSound;
            case BREAK -> breakSound;
        };
    }

    public record Result(int changed, int missing, int blocked, boolean truncated, String rejectionKey,
                         boolean shouldSynchronize) {
        public static Result disabled() { return rejected("message.mydimension.builder.disabled"); }
        public static Result rejected(String key) { return new Result(0, 0, 0, false, key, false); }
        public boolean accepted() { return rejectionKey == null; }
    }

    private record ToolCheck(boolean recognized, boolean canDrop) {
    }

    private static final class Execution {
        private final List<WorldDelta> changed = new ArrayList<>();
        private final List<PendingBuildData.Entry> missing = new ArrayList<>();
        private final List<ItemStack> debits = new ArrayList<>();
        private final List<ItemStack> credits = new ArrayList<>();
        private final java.util.LinkedHashSet<BlockPos> successfulBuildPositions =
                new java.util.LinkedHashSet<>();
        private final ItemStack offhandBefore;
        private final boolean captureHistory;
        private final OperationSoundKind soundKind;
        private ItemStack offhandAfter = ItemStack.EMPTY;
        private int changedCount;
        private int blocked;
        @javax.annotation.Nullable private OperationSound sound;

        private Execution(ItemStack offhandBefore, boolean captureHistory, OperationSoundKind soundKind) {
            this.offhandBefore = offhandBefore;
            this.captureHistory = captureHistory;
            this.soundKind = soundKind;
        }

        private void captureSound(BlockPos pos, BlockState state) {
            if (sound == null) sound = new OperationSound(pos, state, soundKind);
        }
    }

    public record BlueprintBatchResult(int changed, List<PendingBuildData.Entry> missing, int blocked,
                                       boolean committed, @javax.annotation.Nullable OperationSound sound) {
    }

    public record OperationSound(BlockPos pos, BlockState state, OperationSoundKind kind) {
        public OperationSound {
            pos = pos.immutable();
        }
    }

    public enum OperationSoundKind {
        PLACE,
        BREAK
    }

    record NeighborNotification(BlockPos target, BlockPos source) {
        NeighborNotification {
            target = target.immutable();
            source = source.immutable();
        }
    }

    private record BlockEntitySyncState(
            @javax.annotation.Nullable net.minecraft.world.level.block.entity.BlockEntityType<?> type,
            @javax.annotation.Nullable net.minecraft.nbt.CompoundTag updateTag,
            boolean reliable) {
        private boolean equivalent(@javax.annotation.Nullable BlockEntitySyncState other) {
            return other != null && reliable && other.reliable && type == other.type
                    && java.util.Objects.equals(updateTag, other.updateTag);
        }
    }

    private record BuildAttempt(SurfacePlanner.Candidate candidate, BlockState desired, ItemStack cost,
                                ItemStack placementStack,
                                net.minecraft.nbt.CompoundTag blockEntityTag) {
    }

    private record PositionedDrop(BlockPos pos, ItemStack stack) {
    }

    private static final class SupplyPool {
        private final ItemStack template;
        private final List<Item> fallbacks;
        private final List<SupplyReserve> reserves = new ArrayList<>();
        private int requested;

        private SupplyPool(ItemStack template, List<Item> fallbacks) {
            this.template = template;
            this.fallbacks = List.copyOf(fallbacks);
        }

        private void addReserve(ItemStack material, int count) {
            if (count <= 0 || material.isEmpty()) return;
            for (SupplyReserve reserve : reserves) {
                if (!ItemStack.isSameItemSameTags(reserve.template, material)) continue;
                reserve.available += count;
                reserve.extracted += count;
                return;
            }
            reserves.add(new SupplyReserve(material.copyWithCount(1), count));
        }

        private int available() {
            return reserves.stream().mapToInt(reserve -> reserve.available).sum();
        }

        /** Exact material is stored first, followed by fallbacks in policy order. */
        private List<ItemStack> consume(int count) {
            if (count <= 0 || available() < count) return List.of();
            int remaining = count;
            List<ItemStack> consumed = new ArrayList<>();
            for (SupplyReserve reserve : reserves) {
                if (remaining <= 0) break;
                int taken = Math.min(remaining, reserve.available);
                if (taken <= 0) continue;
                reserve.available -= taken;
                consumed.add(reserve.template.copyWithCount(taken));
                remaining -= taken;
            }
            return List.copyOf(consumed);
        }

        private void resetAvailable() {
            reserves.forEach(reserve -> reserve.available = reserve.extracted);
        }

        private List<ItemStack> unusedStacks() {
            return reserves.stream().filter(reserve -> reserve.available > 0)
                    .map(reserve -> reserve.template.copyWithCount(reserve.available)).toList();
        }
    }

    private static final class SupplyReserve {
        private final ItemStack template;
        private int available;
        private int extracted;

        private SupplyReserve(ItemStack template, int extracted) {
            this.template = template;
            this.available = extracted;
            this.extracted = extracted;
        }
    }

    private static SupplyPool findPool(List<SupplyPool> pools, ItemStack stack, List<Item> fallbacks) {
        for (SupplyPool pool : pools) {
            if (ItemStack.isSameItemSameTags(pool.template, stack)
                    && pool.fallbacks.equals(fallbacks)) return pool;
        }
        return null;
    }
}
