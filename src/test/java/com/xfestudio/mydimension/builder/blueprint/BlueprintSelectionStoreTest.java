package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlueprintSelectionStoreTest {
    // The store treats dimension keys opaquely; null avoids bootstrapping Minecraft registries in pure JUnit.
    private static final ResourceKey<Level> DIMENSION = null;

    @Test
    void completedSelectionRemainsReusableAfterArbitrarilyLongIdleTime() {
        BlueprintSelectionStore store = new BlueprintSelectionStore();
        UUID player = UUID.randomUUID();
        UUID scepter = UUID.randomUUID();
        BlockPos first = new BlockPos(10, 64, 20);
        BlockPos second = new BlockPos(18, 70, 31);

        store.begin(player, first, DIMENSION, scepter);
        BlueprintSelectionStore.Selection started = store.get(player);
        assertNotNull(started);
        store.complete(player, started, second);
        BlueprintSelectionStore.Selection completed = store.get(player);
        assertNotNull(completed);

        // Saving the same validated cuboid again refreshes its completed value without consuming it.
        store.complete(player, completed, second);
        completed = store.get(player);
        assertNotNull(completed);

        // The store has no clock-driven maintenance: even an idle interval far beyond the former
        // 2,400-tick timeout cannot alter an event-lifetime selection.
        long simulatedIdleTicks = 20L * 60L * 60L * 24L * 30L;
        for (long elapsed = 0; elapsed <= simulatedIdleTicks; elapsed += 100_000L) {
            assertSame(completed, store.get(player));
        }
        assertEquals(first, completed.first());
        assertEquals(second, completed.second());
    }

    @Test
    void lifecycleClearsReleaseOnlyTheirTargetSelection() {
        BlueprintSelectionStore store = new BlueprintSelectionStore();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        BlockPos secondStart = new BlockPos(1, 1, 1);
        store.begin(firstPlayer, BlockPos.ZERO, DIMENSION, UUID.randomUUID());
        store.begin(secondPlayer, secondStart, DIMENSION, UUID.randomUUID());

        store.clear(firstPlayer);

        assertNull(store.get(firstPlayer));
        assertNotNull(store.get(secondPlayer));
        assertEquals(secondStart, store.get(secondPlayer).first());
        assertEquals(1, store.size());

        store.clearAll();
        assertNull(store.get(secondPlayer));
        assertEquals(0, store.size());
    }
}
