package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.server.level.ServerPlayer;

/**
 * Tick-time handoff to the main builder engine. The blueprint service retains a plan while the
 * handler returns RETRY; ACCEPTED means the engine owns the full queued plan from that point on.
 */
public final class BlueprintPlacementBridge {
    public enum Result { ACCEPTED, RETRY, REJECTED }

    @FunctionalInterface
    public interface Handler {
        Result enqueue(ServerPlayer player, BlueprintPlacementPlan plan);
    }

    private static volatile Handler handler;

    private BlueprintPlacementBridge() {
    }

    public static void install(Handler value) {
        handler = value;
    }

    public static boolean isInstalled() {
        return handler != null;
    }

    public static Result dispatch(ServerPlayer player, BlueprintPlacementPlan plan) {
        Handler current = handler;
        return current == null ? Result.RETRY : current.enqueue(player, plan);
    }
}
