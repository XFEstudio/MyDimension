package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reference-counted, non-ticking Forge chunk tickets scoped to one immediate
 * builder operation. Callers must use try-with-resources.
 */
public final class TemporaryAnchorChunkLeases {
    private static final int WATCHDOG_TICKS = 100;
    private static final Map<LeaseKey, TicketState> ACTIVE = new HashMap<>();
    private static final Map<UUID, Integer> LAST_COLD_ANCHOR_TICK = new HashMap<>();

    private TemporaryAnchorChunkLeases() {
    }

    public static Acquisition acquire(ServerPlayer player, AnchorIndexSavedData.AnchorLocation location) {
        if (!BuilderConfig.isEnabled()) {
            return Acquisition.failure(AcquireStatus.DISABLED);
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return Acquisition.failure(AcquireStatus.DIMENSION_UNAVAILABLE);
        }
        if (!BuilderConfig.ALLOW_CROSS_DIMENSION_ANCHORS.get()
                && !player.level().dimension().equals(location.dimension())) {
            return Acquisition.failure(AcquireStatus.CROSS_DIMENSION_DISABLED);
        }

        ServerLevel targetLevel = server.getLevel(location.dimension());
        if (targetLevel == null) {
            return Acquisition.failure(AcquireStatus.DIMENSION_UNAVAILABLE);
        }

        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        chunks.add(new ChunkPos(location.position()));
        chunks.add(new ChunkPos(location.containerPosition()));

        return acquireChunks(player, targetLevel, chunks, true);
    }

    /**
     * Acquires the same bounded temporary lease used by supply anchors for one blueprint target chunk.
     * Blueprint targets are deliberately restricted to the player's current level; queued tasks validate
     * that dimension independently before reaching this method.
     */
    public static Acquisition acquireTargetChunk(ServerPlayer player, ServerLevel targetLevel, ChunkPos chunk) {
        if (!BuilderConfig.isEnabled()) {
            return Acquisition.failure(AcquireStatus.DISABLED);
        }

        MinecraftServer server = player.getServer();
        if (server == null || targetLevel.getServer() != server
                || !player.level().dimension().equals(targetLevel.dimension())) {
            return Acquisition.failure(AcquireStatus.DIMENSION_UNAVAILABLE);
        }

        return acquireChunks(player, targetLevel, List.of(chunk), false);
    }

    private static Acquisition acquireChunks(ServerPlayer player, ServerLevel targetLevel,
                                             Iterable<ChunkPos> requestedChunks,
                                             boolean rateLimitColdLoad) {
        MinecraftServer server = player.getServer();
        if (server == null || targetLevel.getServer() != server) {
            return Acquisition.failure(AcquireStatus.DIMENSION_UNAVAILABLE);
        }

        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        requestedChunks.forEach(chunks::add);

        boolean cold = chunks.stream().anyMatch(chunk -> !targetLevel.hasChunk(chunk.x, chunk.z));
        if (!cold) {
            return Acquisition.success(Lease.noop());
        }
        if (!BuilderConfig.TEMPORARILY_LOAD_ANCHOR_CHUNKS.get()) {
            return Acquisition.failure(AcquireStatus.CHUNK_UNLOADED);
        }

        int tick = server.getTickCount();
        UUID playerId = player.getUUID();
        synchronized (ACTIVE) {
            if (rateLimitColdLoad
                    && LAST_COLD_ANCHOR_TICK.getOrDefault(playerId, Integer.MIN_VALUE) == tick) {
                return Acquisition.failure(AcquireStatus.COLD_LOAD_RATE_LIMITED);
            }

            Set<LeaseKey> playerKeys = new HashSet<>();
            for (LeaseKey activeKey : ACTIVE.keySet()) {
                if (activeKey.playerId().equals(playerId)) {
                    playerKeys.add(activeKey);
                }
            }
            int additions = 0;
            List<LeaseKey> requested = new ArrayList<>();
            for (ChunkPos chunk : chunks) {
                LeaseKey key = new LeaseKey(targetLevel.dimension(), playerId, chunk.toLong());
                requested.add(key);
                if (!playerKeys.contains(key)) {
                    additions++;
                }
            }
            if (playerKeys.size() + additions > BuilderConfig.MAX_TEMPORARY_CHUNKS_PER_PLAYER.get()) {
                return Acquisition.failure(AcquireStatus.PLAYER_CHUNK_LIMIT_REACHED);
            }

            List<LeaseKey> acquired = new ArrayList<>();
            for (LeaseKey key : requested) {
                TicketState existing = ACTIVE.get(key);
                if (existing != null) {
                    existing.references++;
                    existing.lastTouchedTick = tick;
                    acquired.add(key);
                    continue;
                }

                ChunkPos chunk = new ChunkPos(key.chunkPosition());
                boolean added = ForgeChunkManager.forceChunk(targetLevel, MyDimension.MOD_ID, playerId,
                        chunk.x, chunk.z, true, false);
                if (!added) {
                    releaseKeys(server, acquired);
                    return Acquisition.failure(AcquireStatus.TICKET_REJECTED);
                }
                ACTIVE.put(key, new TicketState(1, tick));
                acquired.add(key);
            }
            if (rateLimitColdLoad) {
                LAST_COLD_ANCHOR_TICK.put(playerId, tick);
            }
            return Acquisition.success(new Lease(server, acquired));
        }
    }

    private static void releaseKeys(MinecraftServer server, List<LeaseKey> keys) {
        for (LeaseKey key : keys) {
            TicketState state = ACTIVE.get(key);
            if (state == null) {
                continue;
            }
            state.references--;
            if (state.references <= 0) {
                ACTIVE.remove(key);
                releaseTicket(server, key);
            }
        }
    }

    private static void releaseTicket(MinecraftServer server, LeaseKey key) {
        ServerLevel level = server.getLevel(key.dimension());
        if (level == null) {
            return;
        }
        ChunkPos chunk = new ChunkPos(key.chunkPosition());
        ForgeChunkManager.forceChunk(level, MyDimension.MOD_ID, key.playerId(),
                chunk.x, chunk.z, false, false);
    }

    private static void releasePlayer(MinecraftServer server, UUID playerId) {
        synchronized (ACTIVE) {
            List<LeaseKey> keys = ACTIVE.keySet().stream()
                    .filter(key -> key.playerId().equals(playerId))
                    .toList();
            for (LeaseKey key : keys) {
                ACTIVE.remove(key);
                releaseTicket(server, key);
            }
            LAST_COLD_ANCHOR_TICK.remove(playerId);
        }
    }

    private static void watchdog(MinecraftServer server) {
        int now = server.getTickCount();
        synchronized (ACTIVE) {
            List<LeaseKey> expired = ACTIVE.entrySet().stream()
                    .filter(entry -> now - entry.getValue().lastTouchedTick >= WATCHDOG_TICKS)
                    .map(Map.Entry::getKey)
                    .toList();
            for (LeaseKey key : expired) {
                ACTIVE.remove(key);
                releaseTicket(server, key);
                MyDimension.LOGGER.warn("Released stale temporary builder chunk lease for {} in {}",
                        new ChunkPos(key.chunkPosition()), key.dimension().location());
            }
        }
    }

    private static void releaseAll(MinecraftServer server) {
        synchronized (ACTIVE) {
            List<LeaseKey> keys = List.copyOf(ACTIVE.keySet());
            ACTIVE.clear();
            LAST_COLD_ANCHOR_TICK.clear();
            for (LeaseKey key : keys) {
                releaseTicket(server, key);
            }
        }
    }

    public enum AcquireStatus {
        ACQUIRED,
        DISABLED,
        DIMENSION_UNAVAILABLE,
        CROSS_DIMENSION_DISABLED,
        CHUNK_UNLOADED,
        COLD_LOAD_RATE_LIMITED,
        PLAYER_CHUNK_LIMIT_REACHED,
        TICKET_REJECTED
    }

    public record Acquisition(AcquireStatus status, Lease lease) {
        private static Acquisition success(Lease lease) {
            return new Acquisition(AcquireStatus.ACQUIRED, lease);
        }

        private static Acquisition failure(AcquireStatus status) {
            return new Acquisition(status, null);
        }

        public boolean acquired() {
            return status == AcquireStatus.ACQUIRED;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final MinecraftServer server;
        private final List<LeaseKey> keys;
        private boolean closed;

        private Lease(MinecraftServer server, List<LeaseKey> keys) {
            this.server = server;
            this.keys = List.copyOf(keys);
        }

        private static Lease noop() {
            return new Lease(null, List.of());
        }

        public void touch() {
            if (server == null || closed) {
                return;
            }
            int now = server.getTickCount();
            synchronized (ACTIVE) {
                for (LeaseKey key : keys) {
                    TicketState state = ACTIVE.get(key);
                    if (state != null) {
                        state.lastTouchedTick = now;
                    }
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (server == null) {
                return;
            }
            synchronized (ACTIVE) {
                releaseKeys(server, keys);
            }
        }
    }

    private record LeaseKey(ResourceKey<Level> dimension, UUID playerId, long chunkPosition) {
    }

    private static final class TicketState {
        private int references;
        private int lastTouchedTick;

        private TicketState(int references, int lastTouchedTick) {
            this.references = references;
            this.lastTouchedTick = lastTouchedTick;
        }
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> ForgeChunkManager.setForcedChunkLoadingCallback(MyDimension.MOD_ID,
                    (level, helper) -> List.copyOf(helper.getEntityTickets().keySet())
                            .forEach(helper::removeAllTickets)));
        }
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void serverTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                watchdog(event.getServer());
            }
        }

        @SubscribeEvent
        public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                releasePlayer(player.getServer(), player.getUUID());
            }
        }

        @SubscribeEvent
        public static void serverStopping(ServerStoppingEvent event) {
            releaseAll(event.getServer());
        }
    }
}
