package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative bridge from an ordered scepter UUID to a live item handler. */
public final class AnchorAccess {
    private AnchorAccess() {
    }

    public static <T> AccessResult<T> withContainer(ServerPlayer player, UUID anchorId,
                                                     ContainerOperation<T> operation) {
        if (!BuilderConfig.isEnabled()) {
            return AccessResult.failure(anchorId, Status.DISABLED, null);
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return AccessResult.failure(anchorId, Status.DIMENSION_UNAVAILABLE, null);
        }

        AnchorIndexSavedData index = AnchorIndexSavedData.get(server);
        Optional<AnchorIndexSavedData.AnchorLocation> indexed = index.find(anchorId);
        if (indexed.isEmpty()) {
            return AccessResult.failure(anchorId, Status.NOT_FOUND, null);
        }
        AnchorIndexSavedData.AnchorLocation location = indexed.get();

        TemporaryAnchorChunkLeases.Acquisition acquisition = TemporaryAnchorChunkLeases.acquire(player, location);
        if (!acquisition.acquired()) {
            return AccessResult.failure(anchorId, Status.LEASE_UNAVAILABLE, acquisition.status());
        }

        try (TemporaryAnchorChunkLeases.Lease lease = acquisition.lease()) {
            ServerLevel anchorLevel = server.getLevel(location.dimension());
            if (anchorLevel == null) {
                return AccessResult.failure(anchorId, Status.DIMENSION_UNAVAILABLE, acquisition.status());
            }
            if (!(anchorLevel.getBlockEntity(location.position()) instanceof ResonantSupplyAnchorBlockEntity anchor)
                    || !anchor.anchorId().equals(anchorId)) {
                index.unregister(anchorId, location);
                return AccessResult.failure(anchorId, Status.STALE_INDEX, acquisition.status());
            }
            if (!anchor.canUse(player)) {
                return AccessResult.failure(anchorId, Status.NOT_AUTHORIZED, acquisition.status());
            }

            AnchorContainerResolver.ResolvedContainer container = AnchorContainerResolver
                    .resolve(anchorLevel, location.position(), location.facing())
                    .orElse(null);
            if (container == null) {
                return AccessResult.failure(anchorId, Status.NO_CONTAINER, acquisition.status());
            }

            try {
                T value = operation.run(new ContainerContext(anchorId, location, anchor, container, lease));
                return AccessResult.success(anchorId, value, acquisition.status());
            } catch (Exception throwable) {
                MyDimension.LOGGER.warn("Supply anchor {} skipped a failing container operation at {} in {}",
                        anchorId, location.position(), location.dimension().location(), throwable);
                return AccessResult.failure(anchorId, Status.HANDLER_FAILED, acquisition.status());
            }
        }
    }

    /** Resolves bindings in their stored priority order without rescanning the world. */
    public static <T> List<AccessResult<T>> visitInOrder(ItemStack scepter, ServerPlayer player,
                                                         ContainerOperation<T> operation) {
        List<AccessResult<T>> results = new ArrayList<>();
        for (UUID anchorId : AnchorBindings.read(scepter)) {
            results.add(withContainer(player, anchorId, operation));
        }
        return List.copyOf(results);
    }

    @FunctionalInterface
    public interface ContainerOperation<T> {
        T run(ContainerContext context) throws Exception;
    }

    public record ContainerContext(UUID anchorId,
                                   AnchorIndexSavedData.AnchorLocation location,
                                   ResonantSupplyAnchorBlockEntity anchor,
                                   AnchorContainerResolver.ResolvedContainer container,
                                   TemporaryAnchorChunkLeases.Lease lease) {
    }

    public record AccessResult<T>(UUID anchorId, Status status, T value,
                                  TemporaryAnchorChunkLeases.AcquireStatus leaseStatus) {
        private static <T> AccessResult<T> success(UUID anchorId, T value,
                                                   TemporaryAnchorChunkLeases.AcquireStatus leaseStatus) {
            return new AccessResult<>(anchorId, Status.AVAILABLE, value, leaseStatus);
        }

        private static <T> AccessResult<T> failure(UUID anchorId, Status status,
                                                   TemporaryAnchorChunkLeases.AcquireStatus leaseStatus) {
            return new AccessResult<>(anchorId, status, null, leaseStatus);
        }

        public boolean available() {
            return status == Status.AVAILABLE;
        }

        public Optional<T> optionalValue() {
            return Optional.ofNullable(value);
        }
    }

    public enum Status {
        AVAILABLE,
        DISABLED,
        NOT_FOUND,
        DIMENSION_UNAVAILABLE,
        LEASE_UNAVAILABLE,
        STALE_INDEX,
        NOT_AUTHORIZED,
        NO_CONTAINER,
        HANDLER_FAILED
    }
}
