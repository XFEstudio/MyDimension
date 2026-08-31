package com.xfestudio.mydimension.builder.blueprint.client;

import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintLimits;
import com.xfestudio.mydimension.builder.blueprint.BlueprintNetworkHooks;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransferAssembler;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.blueprint.BlueprintUploadBeginPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintUploadChunkPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintUploadEndPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintTransferCancelPacket;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Install once from client setup, then consume completed captures from the blueprint menu. */
public final class ClientBlueprintTransfers implements BlueprintNetworkHooks.ClientReceiver {
    public record Download(BlueprintData blueprint, UUID cacheToken) { }
    public record Result(boolean success, UUID cacheToken, String message) { }

    private static final int MAX_PENDING_TRANSFERS = 4;
    private static final int MAX_RETAINED_RESULTS = 16;
    private final Map<UUID, Incoming> incoming = new HashMap<>();
    private final Map<UUID, Outgoing> outgoing = new LinkedHashMap<>();
    private final Map<UUID, Download> completed = new LinkedHashMap<>();
    private final Map<UUID, Result> results = new LinkedHashMap<>();
    private int logicalTick;

    public void install() {
        BlueprintNetworkHooks.installClientReceiver(this);
    }

    public UUID upload(BlueprintData blueprint) throws IOException {
        UUID transferId = UUID.randomUUID();
        // Compression runs away from the render thread; chunks are then emitted gradually from tick().
        // Keep at most one queued successor because the server accepts one upload per connection.
        if (outgoing.size() >= 2) {
            putResult(transferId, new Result(false, null, "Another blueprint upload is still in progress"));
            return transferId;
        }
        outgoing.put(transferId, new Outgoing(transferId, CompletableFuture.supplyAsync(() -> {
            try {
                return BlueprintIo.encode(blueprint);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        })));
        return transferId;
    }

    public Optional<Download> takeDownload(UUID transferId) {
        return Optional.ofNullable(completed.remove(transferId));
    }

    public Optional<Result> takeResult(UUID requestId) {
        return Optional.ofNullable(results.remove(requestId));
    }

    public void tick() {
        logicalTick++;
        pumpOutgoing();
        ArrayList<UUID> timedOut = new ArrayList<>();
        incoming.forEach((id, value) -> {
            if (value.assembler.timedOut(logicalTick)) timedOut.add(id);
        });
        timedOut.forEach(id -> {
            incoming.remove(id);
            putResult(id, new Result(false, null, "Blueprint transfer timed out"));
        });
    }

    public void clear() {
        incoming.clear();
        for (Outgoing transfer : outgoing.values()) {
            if (transfer.begun) {
                try {
                    ModNetwork.CHANNEL.sendToServer(new BlueprintTransferCancelPacket(transfer.id));
                } catch (RuntimeException ignored) {
                    // The connection may already be closing; the server logout hook also clears sessions.
                }
            }
        }
        outgoing.clear();
        completed.clear();
        results.clear();
    }

    @Override
    public void begin(UUID transferId, UUID cacheToken, int byteLength, int chunkCount, byte[] sha256) {
        try {
            long allocated = incoming.values().stream().mapToLong(Incoming::byteLength).sum();
            if (!incoming.containsKey(transferId) && (incoming.size() >= MAX_PENDING_TRANSFERS
                    || allocated + byteLength > BlueprintLimits.MAX_CACHE_BYTES_PER_PLAYER)) {
                throw new IllegalArgumentException("Too many simultaneous blueprint downloads");
            }
            incoming.put(transferId, new Incoming(cacheToken,
                    new BlueprintTransferAssembler(transferId, byteLength, chunkCount, sha256, logicalTick),
                    byteLength));
        } catch (IllegalArgumentException exception) {
            putResult(transferId, new Result(false, null, exception.getMessage()));
        }
    }

    @Override
    public void chunk(UUID transferId, int sequence, byte[] data) {
        Incoming value = incoming.get(transferId);
        if (value == null) return;
        try {
            value.assembler.accept(sequence, data, logicalTick);
        } catch (IOException exception) {
            incoming.remove(transferId);
            putResult(transferId, new Result(false, null, exception.getMessage()));
        }
    }

    @Override
    public void end(UUID transferId) {
        Incoming value = incoming.remove(transferId);
        if (value == null) return;
        try {
            BlueprintData blueprint = BlueprintIo.decode(value.assembler.finish(logicalTick));
            putBounded(completed, transferId, new Download(blueprint, value.cacheToken),
                    MAX_PENDING_TRANSFERS);
        } catch (IOException exception) {
            putResult(transferId, new Result(false, null, exception.getMessage()));
        }
    }

    @Override
    public void result(UUID requestId, boolean success, UUID cacheToken, String message) {
        putResult(requestId, new Result(success, cacheToken, message));
    }

    private void putResult(UUID id, Result result) {
        putBounded(results, id, result, MAX_RETAINED_RESULTS);
    }

    private void pumpOutgoing() {
        Iterator<Outgoing> iterator = outgoing.values().iterator();
        if (!iterator.hasNext()) return;
        Outgoing transfer = iterator.next();
        if (transfer.bytes == null) {
            if (!transfer.encoded.isDone()) return;
            try {
                transfer.bytes = transfer.encoded.join();
                transfer.chunkCount = (transfer.bytes.length + BlueprintLimits.TRANSFER_CHUNK_BYTES - 1)
                        / BlueprintLimits.TRANSFER_CHUNK_BYTES;
            } catch (CompletionException exception) {
                iterator.remove();
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                putResult(transfer.id, new Result(false, null,
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                return;
            }
        }
        if (!transfer.begun) {
            ModNetwork.CHANNEL.sendToServer(new BlueprintUploadBeginPacket(transfer.id, transfer.bytes.length,
                    transfer.chunkCount, BlueprintIo.sha256(transfer.bytes)));
            transfer.begun = true;
        }
        int sent = 0;
        while (transfer.nextSequence < transfer.chunkCount && sent++ < 4) {
            int offset = transfer.nextSequence * BlueprintLimits.TRANSFER_CHUNK_BYTES;
            int length = Math.min(BlueprintLimits.TRANSFER_CHUNK_BYTES, transfer.bytes.length - offset);
            ModNetwork.CHANNEL.sendToServer(new BlueprintUploadChunkPacket(transfer.id, transfer.nextSequence,
                    Arrays.copyOfRange(transfer.bytes, offset, offset + length)));
            transfer.nextSequence++;
        }
        if (transfer.nextSequence == transfer.chunkCount) {
            ModNetwork.CHANNEL.sendToServer(new BlueprintUploadEndPacket(transfer.id));
            iterator.remove();
        }
    }

    private static <T> void putBounded(Map<UUID, T> map, UUID id, T value, int maximum) {
        map.put(id, value);
        while (map.size() > maximum) {
            UUID oldest = map.keySet().iterator().next();
            map.remove(oldest);
        }
    }

    private record Incoming(UUID cacheToken, BlueprintTransferAssembler assembler, int byteLength) { }

    private static final class Outgoing {
        private final UUID id;
        private final CompletableFuture<byte[]> encoded;
        private byte[] bytes;
        private int chunkCount;
        private int nextSequence;
        private boolean begun;

        private Outgoing(UUID id, CompletableFuture<byte[]> encoded) {
            this.id = id;
            this.encoded = encoded;
        }
    }
}
