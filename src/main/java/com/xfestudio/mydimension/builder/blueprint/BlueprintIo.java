package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BlueprintIo {
    public static final String EXTENSION = ".mindbp";

    private BlueprintIo() {
    }

    public static byte[] encode(BlueprintData blueprint) throws IOException {
        return encode(blueprint, BlueprintLimits.MAX_UNCOMPRESSED_BYTES,
                BlueprintLimits.MAX_COMPRESSED_BYTES);
    }

    public static byte[] encode(BlueprintData blueprint, int requestedUncompressedLimit,
                                int requestedCompressedLimit) throws IOException {
        int uncompressedLimit = Math.min(BlueprintLimits.MAX_UNCOMPRESSED_BYTES,
                Math.max(1, requestedUncompressedLimit));
        int compressedLimit = Math.min(BlueprintLimits.MAX_COMPRESSED_BYTES,
                Math.max(1, requestedCompressedLimit));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            NbtIo.write(blueprint.toTag(), output);
        }
        if (raw.size() > uncompressedLimit) {
            throw new IOException("Blueprint exceeds the uncompressed size limit");
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            raw.writeTo(gzip);
        }
        if (compressed.size() > compressedLimit) {
            throw new IOException("Blueprint exceeds the compressed size limit");
        }
        return compressed.toByteArray();
    }

    public static BlueprintData decode(byte[] compressed) throws IOException {
        return decode(compressed, BlueprintLimits.MAX_COMPRESSED_BYTES,
                BlueprintLimits.MAX_UNCOMPRESSED_BYTES);
    }

    public static BlueprintData decode(byte[] compressed, int requestedCompressedLimit,
                                       int requestedUncompressedLimit) throws IOException {
        int compressedLimit = Math.min(BlueprintLimits.MAX_COMPRESSED_BYTES,
                Math.max(1, requestedCompressedLimit));
        int uncompressedLimit = Math.min(BlueprintLimits.MAX_UNCOMPRESSED_BYTES,
                Math.max(1, requestedUncompressedLimit));
        if (compressed.length > compressedLimit) {
            throw new IOException("Blueprint exceeds the compressed size limit");
        }
        try (InputStream bytes = new ByteArrayInputStream(compressed);
             GZIPInputStream gzip = new GZIPInputStream(bytes);
             LimitedInputStream limited = new LimitedInputStream(gzip, uncompressedLimit);
             DataInputStream input = new DataInputStream(limited)) {
            CompoundTag root = NbtIo.read(input, new NbtAccounter(uncompressedLimit));
            return BlueprintData.fromTag(root);
        } catch (RuntimeException exception) {
            throw new IOException("Blueprint NBT exceeded its allocation limit or is malformed", exception);
        }
    }

    public static BlueprintData read(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            throw new IOException("Not a " + EXTENSION + " blueprint");
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Blueprint path is not a regular file");
        }
        long size = Files.size(normalized);
        if (size < 1 || size > BlueprintLimits.MAX_COMPRESSED_BYTES) {
            throw new IOException("Blueprint file size is outside the allowed range");
        }
        return decode(Files.readAllBytes(normalized));
    }

    public static void writeAtomic(Path path, BlueprintData blueprint, boolean replace) throws IOException {
        byte[] bytes = encode(blueprint);
        Path normalized = ensureExtension(path.toAbsolutePath().normalize());
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Blueprint path has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".mindbp-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            StandardCopyOption[] options = replace
                    ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                    : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
            try {
                Files.move(temporary, normalized, options);
            } catch (AtomicMoveNotSupportedException exception) {
                if (replace) {
                    Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, normalized);
                }
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public static Path ensureExtension(Path path) {
        String name = path.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) return path;
        return path.resolveSibling(name + EXTENSION);
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private LimitedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) account(read);
            return read;
        }

        private void account(int amount) throws IOException {
            count += amount;
            if (count > limit) throw new IOException("Blueprint exceeds the decompressed size limit");
        }
    }
}
