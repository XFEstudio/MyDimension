package com.xfestudio.mydimension.builder;

import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Cost-aware admission control for manually clicked surface operations.
 *
 * <p>The first click is always admitted immediately.  A successful admission
 * then reserves enough future ticks to keep repeated clicks near the configured
 * edit budget.  Requests are deliberately not queued: a queued hit can become
 * stale as earlier layers change the world, and an unbounded click backlog would
 * merely move the lag spike into later ticks.</p>
 */
public final class BuilderSurfaceRateLimiter {
    private static final Map<MinecraftServer, PlayerWindows> WINDOWS = new WeakHashMap<>();

    private BuilderSurfaceRateLimiter() {
    }

    public static synchronized boolean isCoolingDown(MinecraftServer server, UUID playerId,
                                                     UUID scepterId, BuilderMode mode, long now) {
        PlayerWindows serverWindows = WINDOWS.get(server);
        if (serverWindows == null) return false;
        return serverWindows.isCoolingDown(playerId, scepterId, mode, now);
    }

    public static synchronized boolean tryAcquire(MinecraftServer server, UUID playerId,
                                                  UUID scepterId, BuilderMode mode, long now,
                                                  int candidateCount, int editsPerTick) {
        PlayerWindows serverWindows = WINDOWS.computeIfAbsent(server, ignored -> new PlayerWindows());
        return serverWindows.tryAcquire(playerId, scepterId, mode, now, candidateCount, editsPerTick);
    }

    public static synchronized void removePlayer(MinecraftServer server, UUID playerId) {
        PlayerWindows serverWindows = WINDOWS.get(server);
        if (serverWindows == null) return;
        serverWindows.remove(playerId);
        if (serverWindows.isEmpty()) WINDOWS.remove(server);
    }

    public static synchronized void shutdown(MinecraftServer server) {
        WINDOWS.remove(server);
    }

    static int delayTicks(int candidateCount, int editsPerTick) {
        return delayTicks(BuilderMode.BUILD, candidateCount, editsPerTick);
    }

    static int delayTicks(BuilderMode mode, int candidateCount, int editsPerTick) {
        long candidates = Math.max(1L, BuilderSurfaceTaskManager.budgetCost(mode, candidateCount));
        long budget = Math.max(1L, editsPerTick);
        return (int) Math.min(Integer.MAX_VALUE, (candidates + budget - 1L) / budget);
    }

    static final class PermitWindow {
        private long nextEligibleTick = Long.MIN_VALUE;

        boolean isCoolingDown(long now) {
            return now < nextEligibleTick;
        }

        boolean tryAcquire(long now, int candidateCount, int editsPerTick) {
            return tryAcquire(now, BuilderMode.BUILD, candidateCount, editsPerTick);
        }

        boolean tryAcquire(long now, BuilderMode mode, int candidateCount, int editsPerTick) {
            if (isCoolingDown(now)) return false;
            int delay = delayTicks(mode, candidateCount, editsPerTick);
            nextEligibleTick = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
            return true;
        }

        long nextEligibleTick() {
            return nextEligibleTick;
        }
    }

    /**
     * One admission window per player. Scepter and mode remain explicit parameters so callers cannot
     * accidentally reintroduce an independent budget when the execution context changes.
     */
    static final class PlayerWindows {
        private final Map<UUID, PermitWindow> players = new HashMap<>();

        boolean isCoolingDown(UUID playerId, UUID scepterId, BuilderMode mode, long now) {
            PermitWindow window = players.get(playerId);
            return window != null && window.isCoolingDown(now);
        }

        boolean tryAcquire(UUID playerId, UUID scepterId, BuilderMode mode, long now,
                           int candidateCount, int editsPerTick) {
            return players.computeIfAbsent(playerId, ignored -> new PermitWindow())
                    .tryAcquire(now, mode, candidateCount, editsPerTick);
        }

        void remove(UUID playerId) {
            players.remove(playerId);
        }

        boolean isEmpty() {
            return players.isEmpty();
        }
    }
}
