package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.history.BuilderHistoryData;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.history.WorldDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    private BuilderOperationManager() {
    }

    public static Result executeSurface(ServerPlayer player, ItemStack scepter, BlockHitResult hit) {
        if (!BuilderRuntime.settings().enabled()) return Result.disabled();
        BuilderSurfaceTaskManager surfaceTasks = BuilderSurfaceTaskManager.get(player.getServer());
        // Reject repeated click packets before ray validation, BFS planning, history snapshots or
        // remote inventory access. One player never accumulates a surface-operation backlog.
        if (surfaceTasks.hasActive(player.getUUID())) return Result.throttled();
        if (PendingBuildData.get(player.getServer()).get(player.getUUID()) != null
                || com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(player.getServer())
                .hasActive(player.getUUID())) {
            return Result.rejected("message.mydimension.builder.active_task_exists");
        }
        BuilderMode mode = RealmwrightData.mode(scepter);
        UUID scepterId = RealmwrightData.id(scepter);
        long now = player.getServer().overworld().getGameTime();
        // Repeated click packets are rejected before ray checks, BFS planning, inventory scans,
        // history sizing and world snapshots.  The first request has no delay.
        if (BuilderSurfaceRateLimiter.isCoolingDown(player.getServer(), player.getUUID(), scepterId,
                mode, now)) {
            return Result.throttled();
        }
        ServerLevel level = player.serverLevel();
        if (!validReachAndSight(player, hit)) return Result.rejected("message.mydimension.builder.out_of_reach");

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
        if (!BuilderSurfaceRateLimiter.tryAcquire(player.getServer(), player.getUUID(), scepterId, mode, now,
                plan.candidates().size(), BuilderRuntime.settings().editsPerTick())) {
            return Result.throttled();
        }

        List<SurfacePlanner.Candidate> locked = lockSurfaceStates(player, plan.candidates(),
                hit.getDirection(), mode);
        if (!surfaceTasks.start(player, scepter, mode, locked, hit.getDirection())) {
            return Result.throttled();
        }
        return new Result(0, 0, 0, plan.truncated(), null, true);
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
        BuilderSurfaceTaskManager manager = BuilderSurfaceTaskManager.get(player.getServer());
        if (!manager.start(player, scepter, task.transactionId(), mode, candidates, Direction.UP)) {
            return Result.throttled();
        }
        data.remove(player.getUUID());
        return new Result(0, task.missing().size(), 0, false, null, true);
    }

    public static boolean cancelPending(ServerPlayer player, ItemStack scepter) {
        boolean activeSurface = BuilderSurfaceTaskManager.get(player.getServer()).cancel(player, scepter);
        boolean activeBlueprint = com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager
                .get(player.getServer()).cancel(player, scepter);
        PendingBuildData data = PendingBuildData.get(player.getServer());
        PendingBuildData.Task task = data.get(player.getUUID());
        if (task == null || !task.scepterId().equals(RealmwrightData.id(scepter))) {
            return activeSurface || activeBlueprint;
        }
        data.remove(player.getUUID());
        return true;
    }

    /** Locks BlockItem placement semantics at click time before a surface task is split across ticks. */
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
                                   Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags) {
        ServerLevel level = player.serverLevel();
        Execution result = new Execution(player.getOffhandItem().copy());
        List<BuildAttempt> attempts = new ArrayList<>();
        boolean free = isFree(player);
        for (SurfacePlanner.Candidate candidate : candidates) {
            try {
                BlockPos pos = candidate.target();
                BlockState desired = allowOffhandOverride ? desiredState(player, candidate, face)
                        : candidate.desiredState();
                BlockState existing = level.getBlockState(pos);
                if (existing.equals(desired)) continue;
                if (!validBuildEnvelope(player, pos, desired, existing)) {
                    result.blocked++;
                    continue;
                }
                ItemStack cost = constructionCost(desired);
                if (cost.isEmpty()) {
                    result.blocked++;
                    continue;
                }
                attempts.add(new BuildAttempt(candidate, desired, cost, blockEntityTags.get(pos)));
            } catch (Throwable throwable) {
                result.blocked++;
                MyDimension.LOGGER.warn("Builder rejected a placement candidate after a mod callback failed",
                        throwable);
            }
        }

        // Aggregate each ItemKey before touching remote handlers. This is what prevents a large storage
        // network from being scanned once per target block.
        List<SupplyPool> pools = new ArrayList<>();
        if (!free) {
            for (BuildAttempt attempt : attempts) {
                SupplyPool pool = findPool(pools, attempt.cost);
                if (pool == null) {
                    pool = new SupplyPool(attempt.cost.copyWithCount(1));
                    pools.add(pool);
                }
                pool.requested += attempt.cost.getCount();
            }
            for (SupplyPool pool : pools) {
                try {
                    BuilderMaterials.Extraction extraction = BuilderMaterials.extract(player, scepter,
                            pool.template, pool.requested);
                    pool.available = extraction.count();
                } catch (Throwable throwable) {
                    pool.available = 0;
                    MyDimension.LOGGER.warn("Builder material extraction failed; this item is left pending",
                            throwable);
                }
            }
        }

        List<BuildAttempt> unresolved = processWithSingleRetry(attempts,
                attempt -> placeBuildAttempt(player, pools, free, result, attempt));
        result.blocked += unresolved.size();
        for (SupplyPool pool : pools) {
            if (pool.available > 0) {
                dropOverflow(player, transactionId, BuilderMaterials.insert(player, scepter,
                        List.of(pool.template.copyWithCount(pool.available))));
            }
        }
        result.offhandAfter = player.getOffhandItem().copy();
        return result;
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
        SupplyPool pool = free ? null : findPool(pools, attempt.cost);
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
        if (pool != null && pool.available < costCount) {
            result.missing.add(new PendingBuildData.Entry(pos, desired, attempt.blockEntityTag));
            return true;
        }

        WorldDelta.Snapshot before;
        BlockSnapshot forgeSnapshot;
        try {
            before = WorldDelta.snapshot(level, pos);
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
                        before.state(), player);
                if (MinecraftForge.EVENT_BUS.post(event)) {
                    forgeSnapshot.restore(true, false);
                    placed = false;
                }
            }
            if (placed && attempt.blockEntityTag != null
                    && !applyBlockEntityTag(player, pos, attempt.blockEntityTag)) {
                forgeSnapshot.restore(true, false);
                placed = false;
            }
            // A placement callback may synchronously replace or consume the block (for example a
            // powered TNT). Such side effects are not represented by this transaction, so never debit
            // material or record an air-to-air delta as a successful placement.
            if (placed && !level.getBlockState(pos).equals(desired)) {
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
        WorldDelta.Snapshot after;
        try {
            after = WorldDelta.snapshot(level, pos);
        } catch (Throwable throwable) {
            try { forgeSnapshot.restore(true, false); } catch (Throwable ignored) { }
            result.blocked++;
            MyDimension.LOGGER.warn("Builder rolled back placement at {} because its after-image failed",
                    pos, throwable);
            return true;
        }
        if (pool != null) {
            pool.available -= costCount;
            addLedgerStack(result.debits, pool.template.copyWithCount(costCount));
        }
        result.changed.add(new WorldDelta(pos, before.state(), before.blockEntity(),
                after.state(), after.blockEntity()));
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
                                      List<SurfacePlanner.Candidate> candidates, UUID transactionId) {
        ServerLevel level = player.serverLevel();
        Execution result = new Execution(player.getOffhandItem().copy());
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
                beforeImages = snapshotDemolitionArea(level, pos, state);
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
                            () -> level.destroyBlock(pos, false, player));
                    removed = captured.value();
                    capturedDrops = captured.drops();
                } else if (tool.recognized) {
                    removed = BuilderDropCapture.discardEntities(
                            () -> level.destroyBlock(pos, false, player));
                    capturedDrops = List.of();
                } else {
                    removed = removeWithoutBreakEffect(level, pos);
                    capturedDrops = List.of();
                }
            } catch (Throwable throwable) {
                restoreSnapshots(level, beforeImages);
                result.blocked++;
                MyDimension.LOGGER.warn("Builder rolled back failed demolition at {}", pos, throwable);
                continue;
            }
            if (!removed) {
                restoreSnapshots(level, beforeImages);
                result.blocked++;
                continue;
            }

            Map<BlockPos, WorldDelta.Snapshot> afterImages = new java.util.LinkedHashMap<>();
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

            if (tool.canDrop) {
                List<ItemStack> drops = new ArrayList<>(normalDrops.size() + capturedDrops.size());
                normalDrops.forEach(stack -> drops.add(stack.copy()));
                capturedDrops.forEach(stack -> drops.add(stack.copy()));
                drops.forEach(drop -> addLedgerStack(result.credits, drop));
                dropOverflowAt(level, pos, transactionId, BuilderMaterials.insert(player, scepter, drops));
            }
            // Settle the Nth block's drops with the pre-damage tool, then apply exactly one durability point.
            // If this breaks the tool, only subsequent blocks lose harvesting eligibility.
            if (tool.recognized && !isFree(player)) {
                player.getOffhandItem().hurtAndBreak(1, player,
                        broken -> broken.broadcastBreakEvent(InteractionHand.OFF_HAND));
            }
            for (Map.Entry<BlockPos, WorldDelta.Snapshot> entry : beforeImages.entrySet()) {
                WorldDelta.Snapshot after = afterImages.get(entry.getKey());
                if (entry.getValue().equals(after)) continue;
                result.changed.add(new WorldDelta(entry.getKey(), entry.getValue().state(),
                        entry.getValue().blockEntity(), after.state(), after.blockEntity()));
            }
        }
        result.offhandAfter = player.getOffhandItem().copy();
        return result;
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
        ServerLevel level = player.serverLevel();
        WorldBorder border = level.getWorldBorder();
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                || !border.isWithinBounds(pos) || !existing.canBeReplaced()
                || desired.is(BuilderTags.CONSTRUCTION_PROTECTED)
                || desired.is(BuilderTags.TRANSACTION_UNSAFE)) return false;
        // Match normal BlockItem placement semantics: no solid collision shape may intersect any
        // player, mob, vehicle or other collidable entity at the target, not merely the operator.
        return level.isUnobstructed(desired, pos, CollisionContext.empty());
    }

    /**
     * Returns the exact item debit for one state, or an empty stack when a state cannot be reproduced
     * without inventing hidden fluid/content. This closes imported-blueprint shortcuts such as one item
     * becoming a double slab, eight snow layers, a full composter or a charged respawn anchor.
     */
    private static ItemStack constructionCost(BlockState state) {
        if (!state.getFluidState().isEmpty()) return ItemStack.EMPTY;
        Block block = state.getBlock();
        if (block instanceof ComposterBlock && state.getValue(ComposterBlock.LEVEL) > 0) return ItemStack.EMPTY;
        if (block instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) > 0) return ItemStack.EMPTY;
        if (block instanceof RespawnAnchorBlock && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
            return ItemStack.EMPTY;
        }
        if (block instanceof LayeredCauldronBlock && state.getValue(LayeredCauldronBlock.LEVEL) > 0) {
            return ItemStack.EMPTY;
        }

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

    static SurfaceAccumulator beginSurfaceExecution(ServerPlayer player) {
        return new SurfaceAccumulator(new Execution(player.getOffhandItem().copy()));
    }

    /** Executes one bounded slice while the whole-task PREPARED reservation remains durable. */
    static SurfaceBatchResult executeSurfaceBatch(ServerPlayer player, ItemStack scepter,
            BuilderMode mode, List<SurfacePlanner.Candidate> candidates, Direction face,
            UUID transactionId, SurfaceAccumulator accumulator) {
        Execution execution = mode == BuilderMode.BUILD
                ? build(player, scepter, candidates, face, transactionId, false, Map.of())
                : demolish(player, scepter, candidates, transactionId);
        accumulator.merge(execution);
        // Later candidates in this same slice may legitimately update the state of an earlier
        // connected block (fences, walls, redstone shapes, and similar neighbour-aware blocks).
        // Capture that final in-slice image immediately. The task manager validates it at the
        // beginning of the next tick, so changes made between slices are treated as conflicts
        // instead of being silently absorbed into the transaction.
        accumulator.refreshAfter(player.serverLevel());
        return new SurfaceBatchResult(execution.changed.size(), List.copyOf(execution.missing),
                execution.blocked);
    }

    /** Reserves enough history space for the complete surface before any batch changes the world. */
    static boolean prepareSurfaceTask(ServerPlayer player, ItemStack scepter, UUID transactionId,
                                      BuilderTransaction.Type type,
                                      List<SurfacePlanner.Candidate> candidates, Direction face) {
        return prepareHistory(player, scepter, transactionId, type, candidates, face, false, Map.of());
    }

    static boolean commitSurfaceTask(ServerPlayer player, ItemStack scepter, BuilderTransaction.Type type,
                                     UUID transactionId, SurfaceAccumulator accumulator,
                                     ServerLevel executionLevel, boolean worldConflict) {
        return recordTransaction(player, scepter, type, transactionId, accumulator.execution,
                executionLevel, !worldConflict);
    }

    /** Executes one rate-limited part of an already validated blueprint. */
    public static BlueprintBatchResult executeBlueprintBatch(ServerPlayer player, ItemStack scepter,
            List<com.xfestudio.mydimension.builder.blueprint.BlueprintPlacementPlan.PlannedBlock> blocks,
            UUID transactionId) {
        List<SurfacePlanner.Candidate> candidates = new ArrayList<>(blocks.size());
        Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntities = new java.util.HashMap<>();
        for (var block : blocks) {
            candidates.add(new SurfacePlanner.Candidate(block.worldPos(), block.worldPos(), block.state(), 0));
            if (block.blockEntityTag() != null) blockEntities.put(block.worldPos(), block.blockEntityTag());
        }
        if (!prepareHistory(player, scepter, transactionId, BuilderTransaction.Type.BLUEPRINT, candidates,
                Direction.UP, false, blockEntities)) {
            List<PendingBuildData.Entry> deferred = blocks.stream()
                    .map(block -> new PendingBuildData.Entry(block.worldPos(), block.state(), block.blockEntityTag()))
                    .toList();
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.history_budget_exceeded"), true);
            return new BlueprintBatchResult(0, deferred, 0, false);
        }
        Execution execution = build(player, scepter, candidates, Direction.UP, transactionId, false, blockEntities);
        boolean committed = recordTransaction(player, scepter, BuilderTransaction.Type.BLUEPRINT, transactionId,
                execution);
        return new BlueprintBatchResult(execution.changed.size(), List.copyOf(execution.missing), execution.blocked,
                committed);
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
            }
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.history_commit_failed"), true);
            return false;
        }
        return true;
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

    public record Result(int changed, int missing, int blocked, boolean truncated, String rejectionKey,
                         boolean shouldSynchronize) {
        public static Result disabled() { return rejected("message.mydimension.builder.disabled"); }
        public static Result rejected(String key) { return new Result(0, 0, 0, false, key, false); }
        public static Result throttled() { return new Result(0, 0, 0, false, null, false); }
        public boolean accepted() { return rejectionKey == null; }
    }

    private record ToolCheck(boolean recognized, boolean canDrop) {
    }

    private static final class Execution {
        private final List<WorldDelta> changed = new ArrayList<>();
        private final List<PendingBuildData.Entry> missing = new ArrayList<>();
        private final List<ItemStack> debits = new ArrayList<>();
        private final List<ItemStack> credits = new ArrayList<>();
        private final ItemStack offhandBefore;
        private ItemStack offhandAfter = ItemStack.EMPTY;
        private int blocked;

        private Execution(ItemStack offhandBefore) {
            this.offhandBefore = offhandBefore;
        }
    }

    public record BlueprintBatchResult(int changed, List<PendingBuildData.Entry> missing, int blocked,
                                       boolean committed) {
    }

    public record SurfaceBatchResult(int changed, List<PendingBuildData.Entry> missing, int blocked) {
    }

    static final class SurfaceAccumulator {
        private final Execution execution;

        private SurfaceAccumulator(Execution execution) {
            this.execution = execution;
        }

        ItemStack initialOffhand() {
            return execution.offhandBefore.copy();
        }

        boolean matchesAfter(ServerLevel level) {
            try {
                for (WorldDelta delta : execution.changed) {
                    if (!delta.matchesAfter(level)) return false;
                }
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }

        void refreshAfter(ServerLevel level) {
            if (execution.changed.isEmpty()) return;
            List<WorldDelta> refreshed = new ArrayList<>(execution.changed.size());
            for (WorldDelta delta : execution.changed) {
                WorldDelta.Snapshot after = WorldDelta.snapshot(level, delta.pos());
                refreshed.add(new WorldDelta(delta.pos(), delta.beforeState(), delta.beforeBlockEntity(),
                        after.state(), after.blockEntity()));
            }
            execution.changed.clear();
            execution.changed.addAll(refreshed);
        }

        private void merge(Execution batch) {
            execution.changed.addAll(batch.changed);
            execution.missing.addAll(batch.missing);
            batch.debits.forEach(stack -> addLedgerStack(execution.debits, stack));
            batch.credits.forEach(stack -> addLedgerStack(execution.credits, stack));
            execution.offhandAfter = batch.offhandAfter.copy();
            execution.blocked += batch.blocked;
        }
    }

    private record BuildAttempt(SurfacePlanner.Candidate candidate, BlockState desired, ItemStack cost,
                                net.minecraft.nbt.CompoundTag blockEntityTag) {
    }

    private static final class SupplyPool {
        private final ItemStack template;
        private int requested;
        private int available;

        private SupplyPool(ItemStack template) {
            this.template = template;
        }
    }

    private static SupplyPool findPool(List<SupplyPool> pools, ItemStack stack) {
        for (SupplyPool pool : pools) {
            if (ItemStack.isSameItemSameTags(pool.template, stack)) return pool;
        }
        return null;
    }
}
