package com.xfestudio.mydimension.client.builder;

import net.minecraft.world.item.ItemStack;

/**
 * Integration seam between client-only UX and the authoritative builder
 * networking implementation.
 */
public interface BuilderClientBridge {
    BuilderClientBridge NOOP = new BuilderClientBridge() {
    };

    default boolean isRealmwright(ItemStack stack) {
        return BuilderClientServices.isRealmwrightByRegistry(stack);
    }

    default BuilderClientSnapshot snapshot() {
        return BuilderClientSnapshot.EMPTY;
    }

    default void requestSnapshot() {
    }

    default void send(BuilderClientCommand command) {
    }
}
