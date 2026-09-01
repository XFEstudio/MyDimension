package com.xfestudio.mydimension.builder;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Implemented by a supply-anchor block entity so the scepter can bind without coupling to its storage code. */
public interface ResonantAnchorTarget {
    UUID anchorId();

    boolean mayUse(ServerPlayer player);
}
