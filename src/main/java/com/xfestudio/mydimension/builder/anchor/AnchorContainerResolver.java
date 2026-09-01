package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves the broadest safe item interface on the one container face touched by an anchor. */
public final class AnchorContainerResolver {
    private static final long LOG_INTERVAL_MILLIS = 30_000L;
    private static final int MAX_LOG_KEYS = 1024;
    private static final Map<EndpointKey, Long> LAST_ERROR_LOG = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<EndpointKey, Long> eldest) {
            return size() > MAX_LOG_KEYS;
        }
    };

    private AnchorContainerResolver() {
    }

    public static Optional<ResolvedContainer> resolve(Level level, BlockPos anchorPosition, Direction anchorFacing) {
        if (!BuilderConfig.isEnabled()) {
            return Optional.empty();
        }
        BlockPos targetPosition = anchorPosition.relative(anchorFacing);
        Direction targetSide = anchorFacing.getOpposite();
        return resolveTarget(level, targetPosition, targetSide);
    }

    public static Optional<ResolvedContainer> resolveTarget(Level level, BlockPos targetPosition, Direction targetSide) {
        if (!BuilderConfig.isEnabled() || !level.isLoaded(targetPosition)) {
            return Optional.empty();
        }

        BlockEntity blockEntity = level.getBlockEntity(targetPosition);
        if (blockEntity == null) {
            return Optional.empty();
        }

        EndpointKey key = new EndpointKey(level.dimension().location().toString(), targetPosition.immutable(), targetSide);
        try {
            // Prefer the exact face exported by Forge/modded storage blocks.
            Optional<IItemHandler> sided = resolveCapability(blockEntity, targetSide);
            if (sided.isPresent()) {
                return Optional.of(new ResolvedContainer(targetPosition.immutable(), targetSide,
                        sided.get(), Source.SIDED_CAPABILITY));
            }

            // Vanilla sided rules remain authoritative when no capability exists.
            if (blockEntity instanceof WorldlyContainer worldlyContainer) {
                return Optional.of(new ResolvedContainer(targetPosition.immutable(), targetSide,
                        new SidedInvWrapper(worldlyContainer, targetSide), Source.WORLDLY_CONTAINER));
            }

            if (blockEntity instanceof Container container) {
                return Optional.of(new ResolvedContainer(targetPosition.immutable(), targetSide,
                        new InvWrapper(container), Source.CONTAINER));
            }

            // Some storage networks intentionally expose only a directionless capability.
            if (BuilderConfig.ALLOW_UNSIDED_ITEM_HANDLER_FALLBACK.get()) {
                Optional<IItemHandler> unsided = resolveCapability(blockEntity, null);
                if (unsided.isPresent()) {
                    return Optional.of(new ResolvedContainer(targetPosition.immutable(), targetSide,
                            unsided.get(), Source.UNSIDED_CAPABILITY));
                }
            }
        } catch (RuntimeException throwable) {
            // A broken third-party capability must not abort the complete construction operation.
            logCapabilityFailure(key, throwable);
        }
        return Optional.empty();
    }

    public static boolean hasCompatibleContainer(Level level, BlockPos anchorPosition, Direction anchorFacing) {
        return resolve(level, anchorPosition, anchorFacing).isPresent();
    }

    public static void reportHandlerFailure(ResourceKey<Level> dimension, BlockPos position,
                                            Direction side, RuntimeException exception) {
        logCapabilityFailure(new EndpointKey(dimension.location().toString(), position.immutable(), side), exception);
    }

    private static Optional<IItemHandler> resolveCapability(BlockEntity blockEntity, Direction side) {
        LazyOptional<IItemHandler> capability = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
        // Resolve the optional but never retain the LazyOptional across this operation.
        return capability.resolve();
    }

    private static void logCapabilityFailure(EndpointKey key, Throwable throwable) {
        long now = System.currentTimeMillis();
        synchronized (LAST_ERROR_LOG) {
            long previous = LAST_ERROR_LOG.getOrDefault(key, 0L);
            if (now - previous < LOG_INTERVAL_MILLIS) {
                return;
            }
            LAST_ERROR_LOG.put(key, now);
        }
        MyDimension.LOGGER.warn("Supply anchor skipped failing item handler at {} {} side {}",
                key.dimension(), key.position(), key.side(), throwable);
    }

    public record ResolvedContainer(BlockPos position, Direction side, IItemHandler handler, Source source) {
    }

    public enum Source {
        SIDED_CAPABILITY,
        WORLDLY_CONTAINER,
        CONTAINER,
        UNSIDED_CAPABILITY
    }

    private record EndpointKey(String dimension, BlockPos position, Direction side) {
    }
}
