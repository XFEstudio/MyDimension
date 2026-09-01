package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event-lifetime storage for server-validated blueprint corners.
 *
 * <p>Selections deliberately have no clock or tick based expiry. A completed
 * selection is tiny and remains reusable until the player replaces it, cancels
 * it, changes worlds, disconnects, or the server stops.</p>
 */
final class BlueprintSelectionStore {
    private final Map<UUID, Selection> selections = new HashMap<>();

    void begin(UUID playerId, BlockPos first, ResourceKey<Level> dimension, UUID scepterId) {
        selections.put(playerId, new Selection(first.immutable(), null, dimension, scepterId));
    }

    @Nullable
    Selection get(UUID playerId) {
        return selections.get(playerId);
    }

    void complete(UUID playerId, Selection expected, BlockPos second) {
        selections.replace(playerId, expected, expected.complete(second));
    }

    void clear(UUID playerId) {
        selections.remove(playerId);
    }

    void clearAll() {
        selections.clear();
    }

    int size() {
        return selections.size();
    }

    record Selection(BlockPos first, @Nullable BlockPos second, ResourceKey<Level> dimension, UUID scepterId) {
        private Selection complete(BlockPos completedSecond) {
            return new Selection(first, completedSecond.immutable(), dimension, scepterId);
        }
    }
}
