package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BlueprintServerCache {
    public record Entry(UUID token, BlueprintData blueprint, byte[] compressed, byte[] sha256,
                        int createdTick, int lastAccessTick) {
        public Entry {
            compressed = compressed.clone();
            sha256 = sha256.clone();
        }

        @Override public byte[] compressed() { return compressed.clone(); }
        @Override public byte[] sha256() { return sha256.clone(); }

        private Entry touched(int tick) {
            return new Entry(token, blueprint, compressed, sha256, createdTick, tick);
        }

        private int weight() {
            long retained = compressed.length * 2L
                    + blueprint.blocks().size() * 160L
                    + blueprint.palette().size() * 256L;
            for (BlueprintData.BlockEntry block : blueprint.blocks()) {
                CompoundTag tag = block.blockEntityTag();
                if (tag != null) retained += encodedBytes(tag);
            }
            return (int) Math.min(Integer.MAX_VALUE, retained);
        }

        private static int encodedBytes(CompoundTag tag) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(tag, new DataOutputStream(bytes));
                return bytes.size();
            } catch (java.io.IOException exception) {
                return BlueprintLimits.MAX_BLOCK_ENTITY_BYTES;
            }
        }
    }

    private final Map<UUID, LinkedHashMap<UUID, Entry>> entriesByPlayer = new LinkedHashMap<>();

    public synchronized Entry put(ServerPlayer player, BlueprintData blueprint, byte[] compressed, int tick) {
        LinkedHashMap<UUID, Entry> entries = entriesByPlayer.computeIfAbsent(player.getUUID(), ignored ->
                new LinkedHashMap<>(8, 0.75F, true));
        byte[] digest = BlueprintIo.sha256(compressed);
        for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
            if (java.security.MessageDigest.isEqual(mapEntry.getValue().sha256, digest)) {
                Entry touched = mapEntry.getValue().touched(tick);
                mapEntry.setValue(touched);
                return touched;
            }
        }
        Entry entry = new Entry(UUID.randomUUID(), blueprint, compressed, digest, tick, tick);
        if (entry.weight() > BlueprintLimits.MAX_CACHE_BYTES_PER_PLAYER) {
            throw new IllegalArgumentException("Blueprint exceeds the per-connection cache budget");
        }
        entries.put(entry.token(), entry);
        evictPlayer(entries);
        evictGlobal();
        return entry;
    }

    public synchronized Optional<Entry> resolve(ServerPlayer player, UUID token, int tick) {
        LinkedHashMap<UUID, Entry> entries = entriesByPlayer.get(player.getUUID());
        if (entries == null) return Optional.empty();
        Entry entry = entries.get(token);
        if (entry == null) return Optional.empty();
        Entry touched = entry.touched(tick);
        entries.put(token, touched);
        evictGlobal();
        return Optional.of(touched);
    }

    public synchronized void tick(int tick) {
        Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Entry>>> players = entriesByPlayer.entrySet().iterator();
        while (players.hasNext()) {
            LinkedHashMap<UUID, Entry> entries = players.next().getValue();
            entries.values().removeIf(entry -> tick - entry.lastAccessTick() > BlueprintLimits.CACHE_IDLE_TICKS);
            if (entries.isEmpty()) players.remove();
        }
        evictGlobal();
    }

    public synchronized void removePlayer(UUID playerId) {
        entriesByPlayer.remove(playerId);
    }

    public synchronized void clear() {
        entriesByPlayer.clear();
    }

    private static void evictPlayer(LinkedHashMap<UUID, Entry> entries) {
        while (entries.size() > BlueprintLimits.MAX_CACHE_ENTRIES_PER_PLAYER || weight(entries) > BlueprintLimits.MAX_CACHE_BYTES_PER_PLAYER) {
            Iterator<UUID> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    /** Enforces the server-wide budget across every connection, evicting the least recently used token. */
    private void evictGlobal() {
        while (globalWeight() > BlueprintLimits.MAX_CACHE_BYTES_GLOBAL) {
            UUID oldestPlayer = null;
            UUID oldestToken = null;
            Entry oldest = null;
            for (Map.Entry<UUID, LinkedHashMap<UUID, Entry>> playerEntry : entriesByPlayer.entrySet()) {
                for (Entry candidate : playerEntry.getValue().values()) {
                    if (oldest == null || candidate.lastAccessTick() < oldest.lastAccessTick()
                            || (candidate.lastAccessTick() == oldest.lastAccessTick()
                            && candidate.createdTick() < oldest.createdTick())) {
                        oldestPlayer = playerEntry.getKey();
                        oldestToken = candidate.token();
                        oldest = candidate;
                    }
                }
            }
            if (oldestPlayer == null || oldestToken == null) return;
            LinkedHashMap<UUID, Entry> entries = entriesByPlayer.get(oldestPlayer);
            if (entries != null) {
                entries.remove(oldestToken);
                if (entries.isEmpty()) entriesByPlayer.remove(oldestPlayer);
            }
        }
    }

    private long globalWeight() {
        long total = 0L;
        for (LinkedHashMap<UUID, Entry> entries : entriesByPlayer.values()) {
            total += weight(entries);
        }
        return total;
    }

    private static int weight(LinkedHashMap<UUID, Entry> entries) {
        long total = 0;
        for (Entry entry : entries.values()) total += entry.weight();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
