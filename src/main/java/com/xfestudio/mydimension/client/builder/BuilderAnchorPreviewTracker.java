package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only index of supply anchors in loaded chunks.
 *
 * <p>Chunk events populate/remove whole sections immediately. A small tick-side
 * reconciliation budget catches block-entity additions inside an already loaded
 * chunk without walking world blocks or scanning anything per render frame.</p>
 */
@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class BuilderAnchorPreviewTracker {
    private static final int CHUNKS_RECONCILED_PER_TICK = 64;
    private static final Map<Long, Set<BlockPos>> BY_CHUNK = new HashMap<>();
    private static final Map<Long, LevelChunk> TRACKED_CHUNKS = new HashMap<>();
    private static final Set<BlockPos> ANCHORS = new LinkedHashSet<>();

    private static ClientLevel trackedLevel;
    private static ResourceKey<Level> trackedDimension;
    private static List<BlockPos> snapshot = List.of();
    private static int scanCenterX = Integer.MIN_VALUE;
    private static int scanCenterZ = Integer.MIN_VALUE;
    private static int scanRadius = -1;
    private static int scanIndex;
    private static List<ChunkOffset> scanOffsets = List.of();

    private BuilderAnchorPreviewTracker() {
    }

    static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || !BuilderClientServices.isHoldingRealmwright(minecraft)) {
            clear();
            return;
        }
        if (trackedLevel != level || !level.dimension().equals(trackedDimension)) {
            clear();
            trackedLevel = level;
            trackedDimension = level.dimension();
        }

        int centerX = minecraft.player.chunkPosition().x;
        int centerZ = minecraft.player.chunkPosition().z;
        int radius = Math.min(32, Math.max(2, minecraft.options.renderDistance().get() + 2));
        if (centerX != scanCenterX || centerZ != scanCenterZ || radius != scanRadius) {
            scanCenterX = centerX;
            scanCenterZ = centerZ;
            if (radius != scanRadius) scanOffsets = nearToFarOffsets(radius);
            scanRadius = radius;
            scanIndex = 0;
        }

        int total = scanOffsets.size();
        for (int checked = 0; checked < CHUNKS_RECONCILED_PER_TICK && checked < total; checked++) {
            if (scanIndex >= total) scanIndex = 0;
            ChunkOffset offset = scanOffsets.get(scanIndex++);
            int chunkX = centerX + offset.x();
            int chunkZ = centerZ + offset.z();
            LevelChunk chunk = level.getChunkSource().getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk != null) reconcile(level, chunk);
            else removeChunk(ChunkPos.asLong(chunkX, chunkZ), null);
        }
    }

    static List<BlockPos> positions(ClientLevel level) {
        return trackedLevel == level ? snapshot : List.of();
    }

    static void clear() {
        trackedLevel = null;
        trackedDimension = null;
        BY_CHUNK.clear();
        TRACKED_CHUNKS.clear();
        ANCHORS.clear();
        snapshot = List.of();
        scanCenterX = Integer.MIN_VALUE;
        scanCenterZ = Integer.MIN_VALUE;
        scanRadius = -1;
        scanIndex = 0;
        scanOffsets = List.of();
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level
                && level == trackedLevel && event.getChunk() instanceof LevelChunk chunk) {
            reconcile(level, chunk);
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level && level == trackedLevel) {
            removeChunk(event.getChunk().getPos().toLong(), event.getChunk());
        }
    }

    private static void reconcile(ClientLevel level, LevelChunk chunk) {
        Set<BlockPos> found = new HashSet<>();
        for (BlockPos position : chunk.getBlockEntitiesPos()) {
            if (level.getBlockState(position).is(ModBlocks.RESONANT_SUPPLY_ANCHOR.get())) {
                found.add(position.immutable());
            }
        }

        long key = chunk.getPos().toLong();
        TRACKED_CHUNKS.put(key, chunk);
        Set<BlockPos> previous = BY_CHUNK.get(key);
        if (found.isEmpty()) {
            if (previous != null) removeAnchors(key);
            return;
        }
        if (found.equals(previous)) return;
        if (previous != null) ANCHORS.removeAll(previous);
        BY_CHUNK.put(key, Set.copyOf(found));
        ANCHORS.addAll(found);
        snapshot = List.copyOf(ANCHORS);
    }

    private static void removeChunk(long key, Object expectedChunk) {
        if (expectedChunk != null && TRACKED_CHUNKS.get(key) != expectedChunk) return;
        TRACKED_CHUNKS.remove(key);
        removeAnchors(key);
    }

    private static void removeAnchors(long key) {
        Set<BlockPos> removed = BY_CHUNK.remove(key);
        if (removed == null || removed.isEmpty()) return;
        ANCHORS.removeAll(removed);
        snapshot = List.copyOf(ANCHORS);
    }

    /** Chebyshev rings make the current chunk index zero and expand near-to-far. */
    private static List<ChunkOffset> nearToFarOffsets(int radius) {
        ArrayList<ChunkOffset> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        result.add(new ChunkOffset(0, 0));
        for (int ring = 1; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                result.add(new ChunkOffset(x, -ring));
                result.add(new ChunkOffset(x, ring));
            }
            for (int z = -ring + 1; z < ring; z++) {
                result.add(new ChunkOffset(-ring, z));
                result.add(new ChunkOffset(ring, z));
            }
        }
        return List.copyOf(result);
    }

    private record ChunkOffset(int x, int z) { }
}
