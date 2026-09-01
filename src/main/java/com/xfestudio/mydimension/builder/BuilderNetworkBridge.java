package com.xfestudio.mydimension.builder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.function.Consumer;

/** Avoids loading packet/client classes from the item implementation. */
public final class BuilderNetworkBridge {
    private static volatile Consumer<ServerPlayer> openMenu = player -> { };
    private static volatile Consumer<ServerPlayer> sync = player -> { };

    private BuilderNetworkBridge() {
    }

    public static void install(Consumer<ServerPlayer> menuSender, Consumer<ServerPlayer> syncSender) {
        openMenu = Objects.requireNonNull(menuSender);
        sync = Objects.requireNonNull(syncSender);
    }

    public static void openMenu(ServerPlayer player) {
        openMenu.accept(player);
    }

    public static void sync(ServerPlayer player) {
        sync.accept(player);
    }
}
