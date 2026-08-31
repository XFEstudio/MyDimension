package com.xfestudio.mydimension.builder;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Captures only entities spawned synchronously by the active server-thread block lifecycle. */
public final class BuilderDropCapture {
    private static final Collector DISCARDING = new Collector(null);
    private static final ThreadLocal<Collector> ACTIVE = new ThreadLocal<>();

    public static <T> CaptureResult<T> capture(Supplier<T> operation) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested builder drop capture");
        Collector collector = new Collector(new ArrayList<>());
        ACTIVE.set(collector);
        try {
            return new CaptureResult<>(operation.get(), List.copyOf(collector.items));
        } finally {
            ACTIVE.remove();
        }
    }

    /**
     * Cancels synchronous item/experience entities without retaining copies.
     * Used by no-tool demolition, where block removal lifecycle must run but
     * no drops are allowed and allocating a captured-drop list per block would
     * be wasted work.
     */
    public static <T> T discardEntities(Supplier<T> operation) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested builder drop suppression");
        ACTIVE.set(DISCARDING);
        try {
            return operation.get();
        } finally {
            ACTIVE.remove();
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Collector collector = ACTIVE.get();
        if (collector == null || event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (entity instanceof ExperienceOrb) {
            event.setCanceled(true);
        } else if (entity instanceof ItemEntity itemEntity) {
            if (collector.items != null) collector.items.add(itemEntity.getItem().copy());
            event.setCanceled(true);
        }
    }

    public record CaptureResult<T>(T value, List<ItemStack> drops) {
    }

    private static final class Collector {
        private final List<ItemStack> items;

        private Collector(List<ItemStack> items) {
            this.items = items;
        }
    }
}
