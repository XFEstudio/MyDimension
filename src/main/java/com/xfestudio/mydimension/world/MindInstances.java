package com.xfestudio.mydimension.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MindInstances {
    public static final int MAX_PLAYER_SLOTS = 32;
    private static final String DATA_NAME = "mydimension_mind_instances";
    private static final String NEXT_SLOT_TAG = "NextSlot";
    private static final String SLOTS_TAG = "Slots";

    public static ResourceKey<Level> dimensionFor(ServerPlayer player, ResourceKey<Level> baseDimension) {
        return ModDimensions.playerDimension(baseDimension, slotFor(player));
    }

    public static int slotFor(ServerPlayer player) {
        return data(player.getServer()).slotFor(player.getUUID());
    }

    private static MindInstanceData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MindInstanceData::load, MindInstanceData::new, DATA_NAME);
    }

    public static class MindInstanceData extends SavedData {
        private final Map<UUID, Integer> slots = new HashMap<>();
        private int nextSlot;

        public static MindInstanceData load(CompoundTag tag) {
            MindInstanceData data = new MindInstanceData();
            data.nextSlot = tag.getInt(NEXT_SLOT_TAG);
            CompoundTag slotsTag = tag.getCompound(SLOTS_TAG);
            for (String key : slotsTag.getAllKeys()) {
                try {
                    data.slots.put(UUID.fromString(key), slotsTag.getInt(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt(NEXT_SLOT_TAG, nextSlot);
            CompoundTag slotsTag = new CompoundTag();
            for (Map.Entry<UUID, Integer> entry : slots.entrySet()) {
                slotsTag.putInt(entry.getKey().toString(), entry.getValue());
            }
            tag.put(SLOTS_TAG, slotsTag);
            return tag;
        }

        private int slotFor(UUID owner) {
            Integer existing = slots.get(owner);
            if (existing != null) {
                return existing;
            }

            int slot = nextSlot++;
            slots.put(owner, slot);
            setDirty();
            return slot;
        }
    }
}
