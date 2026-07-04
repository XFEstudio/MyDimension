package com.xfestudio.mydimension.world;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MindInstances {
    public static final int MAX_PLAYER_SLOTS = 64;
    public static final int BUILT_IN_PLAYER_SLOTS = 2;
    private static final String DATA_NAME = "mydimension_mind_instances";
    private static final String NEXT_SLOT_TAG = "NextSlot";
    private static final String REQUESTED_SLOTS_TAG = "RequestedSlots";
    private static final String SLOTS_TAG = "Slots";
    private static final String GENERATED_DATAPACK_NAME = "mydimension_dynamic_player_minds";

    public static ResourceKey<Level> dimensionFor(ServerPlayer player, ResourceKey<Level> baseDimension) {
        int slot = slotFor(player);
        if (slot < 0 || slot >= MAX_PLAYER_SLOTS) {
            return baseDimension;
        }

        MinecraftServer server = player.getServer();
        ResourceKey<Level> privateDimension = ModDimensions.playerDimension(baseDimension, slot);
        if (server.getLevel(privateDimension) != null) {
            return privateDimension;
        }

        data(server).requestSlots(server, slot + 1);
        return baseDimension;
    }

    public static int slotFor(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        MindInstanceData data = data(server);
        int slot = data.slotFor(player.getUUID());
        if (slot >= BUILT_IN_PLAYER_SLOTS) {
            data.requestSlots(server, slot + 1);
        }
        return slot;
    }

    private static MindInstanceData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MindInstanceData::load, MindInstanceData::new, DATA_NAME);
    }

    public static class MindInstanceData extends SavedData {
        private final Map<UUID, Integer> slots = new HashMap<>();
        private int nextSlot;
        private int requestedSlots = BUILT_IN_PLAYER_SLOTS;

        public static MindInstanceData load(CompoundTag tag) {
            MindInstanceData data = new MindInstanceData();
            data.nextSlot = tag.getInt(NEXT_SLOT_TAG);
            data.requestedSlots = Math.max(BUILT_IN_PLAYER_SLOTS, tag.contains(REQUESTED_SLOTS_TAG) ? tag.getInt(REQUESTED_SLOTS_TAG) : data.nextSlot);
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
            tag.putInt(REQUESTED_SLOTS_TAG, requestedSlots);
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

            if (nextSlot >= MAX_PLAYER_SLOTS) {
                return -1;
            }

            int slot = nextSlot++;
            slots.put(owner, slot);
            setDirty();
            return slot;
        }

        private void requestSlots(MinecraftServer server, int slotsNeeded) {
            int clampedSlots = Math.min(Math.max(slotsNeeded, BUILT_IN_PLAYER_SLOTS), MAX_PLAYER_SLOTS);
            if (clampedSlots > requestedSlots) {
                requestedSlots = clampedSlots;
                setDirty();
            }

            if (clampedSlots > BUILT_IN_PLAYER_SLOTS) {
                writeGeneratedDatapack(server, clampedSlots);
            }
        }
    }

    private static void writeGeneratedDatapack(MinecraftServer server, int slotsNeeded) {
        Path datapackRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(GENERATED_DATAPACK_NAME);
        Path dimensionDir = datapackRoot.resolve("data").resolve(MyDimension.MOD_ID).resolve("dimension");
        try {
            deleteDirectory(dimensionDir);
            Files.createDirectories(dimensionDir);
            Files.writeString(datapackRoot.resolve("pack.mcmeta"), """
                    {
                      "pack": {
                        "description": "My Dimension dynamic private mind dimensions",
                        "pack_format": 15
                      }
                    }
                    """, StandardCharsets.UTF_8);

            for (int slot = BUILT_IN_PLAYER_SLOTS; slot < slotsNeeded; slot++) {
                writeSlotDimensions(dimensionDir, slot);
            }
        } catch (IOException exception) {
            MyDimension.LOGGER.warn("Failed to write dynamic player mind dimension datapack", exception);
        }
    }

    private static void writeSlotDimensions(Path dimensionDir, int slot) throws IOException {
        Files.createDirectories(dimensionDir);
        Files.writeString(dimensionDir.resolve("p" + slot + "e.json"), etherealMindJson(), StandardCharsets.UTF_8);
        Files.writeString(dimensionDir.resolve("p" + slot + "m.json"), mirrorMindJson(), StandardCharsets.UTF_8);
        Files.writeString(dimensionDir.resolve("p" + slot + "w.json"), waterMindJson(), StandardCharsets.UTF_8);
        Files.writeString(dimensionDir.resolve("p" + slot + "n.json"), natureMindJson(), StandardCharsets.UTF_8);
        Files.writeString(dimensionDir.resolve("p" + slot + "s.json"), soaringMindJson(), StandardCharsets.UTF_8);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String etherealMindJson() {
        return """
                {
                  "type": "mydimension:ethereal_mind",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "biome": "mydimension:ethereal_mind",
                      "features": false,
                      "lakes": false,
                      "layers": [
                        { "block": "minecraft:stone", "height": 60 },
                        { "block": "minecraft:dirt", "height": 3 },
                        { "block": "minecraft:quartz_block", "height": 3 }
                      ],
                      "structure_overrides": []
                    }
                  }
                }
                """;
    }

    private static String mirrorMindJson() {
        return """
                {
                  "type": "mydimension:mirror_mind",
                  "generator": {
                    "type": "mydimension:no_structure_noise",
                    "biome_source": {
                      "type": "minecraft:multi_noise",
                      "preset": "minecraft:overworld"
                    },
                    "settings": "minecraft:overworld"
                  }
                }
                """;
    }

    private static String waterMindJson() {
        return """
                {
                  "type": "mydimension:water_mind",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "biome": "mydimension:water_mind",
                      "features": false,
                      "lakes": false,
                      "layers": [
                        { "block": "minecraft:stone", "height": 20 },
                        { "block": "minecraft:gravel", "height": 5 },
                        { "block": "minecraft:water", "height": 50 }
                      ],
                      "structure_overrides": []
                    }
                  }
                }
                """;
    }

    private static String natureMindJson() {
        return """
                {
                  "type": "mydimension:nature_mind",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "biome": "mydimension:nature_mind",
                      "features": true,
                      "lakes": false,
                      "layers": [
                        { "block": "minecraft:stone", "height": 50 },
                        { "block": "minecraft:dirt", "height": 9 },
                        { "block": "minecraft:grass_block", "height": 1 }
                      ],
                      "structure_overrides": []
                    }
                  }
                }
                """;
    }

    private static String soaringMindJson() {
        return """
                {
                  "type": "mydimension:soaring_mind",
                  "generator": {
                    "type": "mydimension:soaring_islands",
                    "biome_source": {
                      "type": "minecraft:checkerboard",
                      "biomes": [
                        "minecraft:plains",
                        "minecraft:forest",
                        "minecraft:flower_forest",
                        "minecraft:birch_forest",
                        "minecraft:meadow",
                        "minecraft:cherry_grove",
                        "minecraft:snowy_plains",
                        "minecraft:grove",
                        "minecraft:desert",
                        "minecraft:savanna",
                        "minecraft:jungle",
                        "minecraft:windswept_hills"
                      ],
                      "scale": 3
                    }
                  }
                }
                """;
    }
}
