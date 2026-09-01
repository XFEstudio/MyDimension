package com.xfestudio.mydimension.builder.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** Strict, sequential bounded assembler for either network direction. */
public final class BlueprintTransferAssembler {
    private final UUID transferId;
    private final int expectedBytes;
    private final int expectedChunks;
    private final byte[] expectedSha256;
    private final ByteArrayOutputStream output;
    private int nextSequence;
    private int lastActivityTick;

    public BlueprintTransferAssembler(UUID transferId, int expectedBytes, int expectedChunks,
                                      byte[] expectedSha256, int currentTick) {
        if (expectedBytes < 1 || expectedBytes > BlueprintLimits.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Invalid blueprint transfer length");
        }
        int calculatedChunks = (expectedBytes + BlueprintLimits.TRANSFER_CHUNK_BYTES - 1)
                / BlueprintLimits.TRANSFER_CHUNK_BYTES;
        if (expectedChunks != calculatedChunks || expectedChunks > BlueprintLimits.MAX_TRANSFER_CHUNKS) {
            throw new IllegalArgumentException("Invalid blueprint transfer chunk count");
        }
        if (expectedSha256.length != 32) throw new IllegalArgumentException("Invalid SHA-256 length");
        this.transferId = transferId;
        this.expectedBytes = expectedBytes;
        this.expectedChunks = expectedChunks;
        this.expectedSha256 = expectedSha256.clone();
        this.output = new ByteArrayOutputStream(expectedBytes);
        this.lastActivityTick = currentTick;
    }

    public UUID transferId() { return transferId; }
    public int nextSequence() { return nextSequence; }

    public void accept(int sequence, byte[] data, int currentTick) throws IOException {
        if (sequence != nextSequence) throw new IOException("Out-of-order blueprint transfer chunk");
        if (data.length < 1 || data.length > BlueprintLimits.TRANSFER_CHUNK_BYTES) {
            throw new IOException("Invalid blueprint transfer chunk size");
        }
        if (nextSequence >= expectedChunks || output.size() + data.length > expectedBytes) {
            throw new IOException("Blueprint transfer exceeds its declared length");
        }
        boolean last = nextSequence == expectedChunks - 1;
        int expectedLength = last
                ? expectedBytes - BlueprintLimits.TRANSFER_CHUNK_BYTES * (expectedChunks - 1)
                : BlueprintLimits.TRANSFER_CHUNK_BYTES;
        if (data.length != expectedLength) throw new IOException("Blueprint transfer chunk has the wrong length");
        output.write(data);
        nextSequence++;
        lastActivityTick = currentTick;
    }

    public byte[] finish(int currentTick) throws IOException {
        lastActivityTick = currentTick;
        if (nextSequence != expectedChunks || output.size() != expectedBytes) {
            throw new IOException("Blueprint transfer is incomplete");
        }
        byte[] result = output.toByteArray();
        if (!MessageDigest.isEqual(BlueprintIo.sha256(result), expectedSha256)) {
            throw new IOException("Blueprint transfer checksum mismatch");
        }
        return result;
    }

    public boolean timedOut(int currentTick) {
        return currentTick - lastActivityTick > BlueprintLimits.TRANSFER_IDLE_TICKS;
    }

    @Override
    public String toString() {
        return "BlueprintTransferAssembler{" + transferId + ", " + nextSequence + "/" + expectedChunks
                + ", sha256=" + Arrays.toString(expectedSha256) + '}';
    }
}
