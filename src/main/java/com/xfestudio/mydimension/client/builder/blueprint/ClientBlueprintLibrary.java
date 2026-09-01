package com.xfestudio.mydimension.client.builder.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintLimits;
import com.xfestudio.mydimension.builder.blueprint.BlueprintNames;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.GZIPInputStream;

/**
 * Client-global .mindbp library. File parsing and writes happen off-thread;
 * callers may observe immutable entry snapshots from the render thread.
 */
public final class ClientBlueprintLibrary {
    public static final String EXTENSION = ".mindbp";

    public enum ConflictPolicy {
        FAIL,
        REPLACE,
        KEEP_BOTH
    }

    public record Entry(UUID id, String name, String author, int sizeX, int sizeY, int sizeZ,
                        int blocks, BlueprintSaveMode saveMode, Path path, long modifiedAt,
                        boolean valid, String error) {
        public Entry {
            name = name == null ? "" : name;
            author = author == null ? "" : author;
            error = error == null ? "" : error;
        }
    }

    private record ScanResult(List<Entry> entries, Map<UUID, BlueprintData> data) {
    }

    private record ConflictResolution(BlueprintData blueprint, Entry supersededName) {
    }

    public static final class NameConflictException extends IOException {
        private final UUID existingId;
        private final String existingName;

        private NameConflictException(UUID existingId, String existingName) {
            super("Blueprint name is already used: " + existingName);
            this.existingId = existingId;
            this.existingName = existingName;
        }

        public UUID existingId() {
            return existingId;
        }

        public String existingName() {
            return existingName;
        }
    }

    private static final ClientBlueprintLibrary INSTANCE = new ClientBlueprintLibrary(ForkJoinPool.commonPool());

    private final Executor executor;
    private final Object mutationLock = new Object();
    private volatile List<Entry> entries = List.of();
    private volatile Map<UUID, BlueprintData> cache = Map.of();
    private volatile boolean refreshing;
    private volatile String lastError = "";

    private ClientBlueprintLibrary(Executor executor) {
        this.executor = executor;
    }

    public static ClientBlueprintLibrary get() {
        return INSTANCE;
    }

    public Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("mydimension").resolve("blueprints").toAbsolutePath().normalize();
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean refreshing() {
        return refreshing;
    }

    public String lastError() {
        return lastError;
    }

    public Optional<BlueprintData> blueprint(UUID id) {
        return Optional.ofNullable(cache.get(id));
    }

    public CompletableFuture<List<Entry>> refreshAsync() {
        if (refreshing) {
            return CompletableFuture.completedFuture(entries);
        }
        refreshing = true;
        return CompletableFuture.supplyAsync(() -> {
                    synchronized (mutationLock) {
                        ScanResult result = scan();
                        publishScan(result);
                        return result.entries();
                    }
                }, executor)
                .handle((result, failure) -> {
                    refreshing = false;
                    if (failure != null) {
                        lastError = rootMessage(failure);
                        return entries;
                    }
                    return result;
                });
    }

    public CompletableFuture<Entry> saveAsync(BlueprintData blueprint, ConflictPolicy policy) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (mutationLock) {
                try {
                    // Saving from the Alt wheel is valid before the library tab has ever been
                    // opened. Always resolve names against a fresh on-disk snapshot while holding
                    // the same lock as the commit, so two local saves/imports cannot both pass a
                    // stale empty-cache check.
                    publishScan(scan());
                    ConflictResolution resolution = resolveConflict(blueprint, policy);
                    BlueprintData value = resolution.blueprint();
                    Path output = writeAtomically(value);
                    deleteSupersededAfterCommit(resolution.supersededName(), output);
                    Entry saved = entry(value, output);
                    publishSave(saved, value, resolution.supersededName());
                    return saved;
                } catch (IOException | RuntimeException exception) {
                    throw new CompletionException(exception);
                }
            }
        }, executor);
    }

    public CompletableFuture<Entry> importAsync(Path source, ConflictPolicy policy) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path normalized = validateExternalFile(source);
                BlueprintData data = read(normalized);
                synchronized (mutationLock) {
                    publishScan(scan());
                    ConflictResolution resolution = resolveConflict(data, policy);
                    BlueprintData value = resolution.blueprint();
                    Path output = writeAtomically(value);
                    deleteSupersededAfterCommit(resolution.supersededName(), output);
                    Entry imported = entry(value, output);
                    publishSave(imported, value, resolution.supersededName());
                    return imported;
                }
            } catch (IOException | RuntimeException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    /**
     * Renames one library entry without changing either its blueprint UUID or its physical UUID filename.
     * A conflicting entry is removed only after the renamed file has been atomically committed.
     */
    public CompletableFuture<Entry> renameAsync(UUID id, String newName, ConflictPolicy policy) {
        return CompletableFuture.supplyAsync(() -> {
            if (policy == ConflictPolicy.KEEP_BOTH) {
                throw new CompletionException(new IllegalArgumentException(
                        "Rename requires an explicit fail or replace conflict policy"));
            }
            synchronized (mutationLock) {
                try {
                    publishScan(scan());
                    Entry source = entries.stream().filter(Entry::valid)
                            .filter(entry -> entry.id().equals(id)).findFirst()
                            .orElseThrow(() -> new IOException("Blueprint is no longer present in the local library"));
                    BlueprintData original = cache.get(id);
                    if (original == null) original = read(source.path());

                    BlueprintData renamed = original.withIdentity(original.id(), newName);
                    Entry conflict = entries.stream().filter(Entry::valid)
                            .filter(entry -> !entry.id().equals(id))
                            .filter(entry -> BlueprintNames.collisionKey(entry.name())
                                    .equals(BlueprintNames.collisionKey(renamed.name())))
                            .findFirst().orElse(null);
                    if (conflict != null && policy == ConflictPolicy.FAIL) {
                        throw new NameConflictException(conflict.id(), conflict.name());
                    }

                    Path output = validateLibraryEntryPath(source.path());
                    writeAtomicallyStrict(renamed, output);
                    deleteSupersededAfterCommit(conflict, output);

                    Entry result = entry(renamed, output);
                    publishRename(result, renamed, conflict);
                    return result;
                } catch (IOException | RuntimeException exception) {
                    throw new CompletionException(exception);
                }
            }
        }, executor);
    }

    public CompletableFuture<Path> exportAsync(UUID id, Path destination, boolean replace) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BlueprintData data = cache.get(id);
                if (data == null) {
                    throw new IOException("Blueprint is no longer present in the local library");
                }
                Path output = ensureExtension(destination.toAbsolutePath().normalize());
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !replace) {
                    throw new IOException("Export destination already exists");
                }
                if (Files.isSymbolicLink(output)) {
                    throw new IOException("Refusing to replace a symbolic link");
                }
                Path parent = output.getParent();
                if (parent != null) Files.createDirectories(parent);
                writeAtomically(data, output);
                return output;
            } catch (IOException | RuntimeException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public CompletableFuture<Boolean> deleteAsync(UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (mutationLock) {
                try {
                    publishScan(scan());
                    Entry entry = entries.stream().filter(value -> value.valid() && value.id().equals(id))
                            .findFirst().orElse(null);
                    if (entry == null) return false;
                    Path library = directory().toRealPath(LinkOption.NOFOLLOW_LINKS);
                    Path target = entry.path().toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!target.startsWith(library) || Files.isSymbolicLink(entry.path())) {
                        throw new IOException("Blueprint path escaped the library directory");
                    }
                    boolean deleted = Files.deleteIfExists(target);
                    if (deleted) {
                        List<Entry> nextEntries = new ArrayList<>(entries);
                        nextEntries.removeIf(value -> value.id().equals(id));
                        Map<UUID, BlueprintData> nextCache = new HashMap<>(cache);
                        nextCache.remove(id);
                        entries = List.copyOf(nextEntries);
                        cache = Map.copyOf(nextCache);
                    }
                    return deleted;
                } catch (IOException | RuntimeException exception) {
                    throw new CompletionException(exception);
                }
            }
        }, executor);
    }

    public Optional<Path> chooseImportFile() {
        String selected = TinyFileDialogs.tinyfd_openFileDialog(
                "Import Mind Blueprint", directory().toString(),
                null, null, false);
        return selected == null ? Optional.empty() : Optional.of(Path.of(selected));
    }

    public Optional<Path> chooseExportFile(String suggestedName) {
        String safeName;
        try {
            safeName = BlueprintNames.normalize(suggestedName);
        } catch (IllegalArgumentException ignored) {
            safeName = "blueprint";
        }
        String selected = TinyFileDialogs.tinyfd_saveFileDialog(
                "Export Mind Blueprint", safeName + EXTENSION,
                null, null);
        return selected == null ? Optional.empty() : Optional.of(Path.of(selected));
    }

    private ScanResult scan() {
        Path root = directory();
        List<Entry> found = new ArrayList<>();
        Map<UUID, BlueprintData> loaded = new HashMap<>();
        try {
            Files.createDirectories(root);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root,
                    path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION))) {
                for (Path path : stream) {
                    try {
                        BlueprintData value = read(path);
                        loaded.putIfAbsent(value.id(), value);
                        found.add(entry(value, path));
                    } catch (Exception exception) {
                        found.add(new Entry(UUID.nameUUIDFromBytes(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                path.getFileName().toString(), "", 0, 0, 0, 0,
                                BlueprintSaveMode.BLOCKS_ONLY, path, modified(path), false,
                                exception.getMessage()));
                    }
                }
            }
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
        found.sort(Comparator.comparing(Entry::valid).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Entry::id));
        return new ScanResult(List.copyOf(found), loaded);
    }

    private ConflictResolution resolveConflict(BlueprintData input, ConflictPolicy policy) throws IOException {
        Entry idConflict = entries.stream().filter(Entry::valid)
                .filter(entry -> entry.id().equals(input.id())).findFirst().orElse(null);
        String key = BlueprintNames.collisionKey(input.name());
        Entry nameConflict = entries.stream().filter(Entry::valid)
                .filter(entry -> BlueprintNames.collisionKey(entry.name()).equals(key))
                .filter(entry -> !entry.id().equals(input.id())).findFirst().orElse(null);
        if (idConflict == null && nameConflict == null) {
            return new ConflictResolution(input, null);
        }
        if (policy == ConflictPolicy.FAIL) {
            Entry conflict = nameConflict != null ? nameConflict : idConflict;
            throw new NameConflictException(conflict.id(), conflict.name());
        }
        if (policy == ConflictPolicy.REPLACE) {
            // Keep the old file until the replacement has been encoded, fsynced by the filesystem and
            // atomically moved into place. A failed save must never destroy the only valid copy.
            return new ConflictResolution(input, nameConflict);
        }

        String base = input.name();
        int suffix = 2;
        String candidate = base;
        while (containsName(candidate)) {
            candidate = base + " (" + suffix++ + ")";
        }
        return new ConflictResolution(new BlueprintData(UUID.randomUUID(), candidate, input.author(), input.authorUuid(),
                System.currentTimeMillis(), input.saveMode(), input.sizeX(), input.sizeY(), input.sizeZ(),
                input.anchor(), input.palette(), input.blocks()), null);
    }

    private static void deleteSupersededAfterCommit(Entry superseded, Path committed) {
        if (superseded == null || superseded.path().toAbsolutePath().normalize()
                .equals(committed.toAbsolutePath().normalize())) return;
        try {
            Files.deleteIfExists(superseded.path());
        } catch (IOException ignored) {
            // The new blueprint is already durable. Retaining a duplicate is safer than rolling back by
            // deleting the just-written file or risking loss of both copies.
        }
    }

    private boolean containsName(String name) {
        String key = BlueprintNames.collisionKey(name);
        return entries.stream().filter(Entry::valid)
                .anyMatch(entry -> BlueprintNames.collisionKey(entry.name()).equals(key));
    }

    private BlueprintData read(Path path) throws IOException {
        long compressed = Files.size(path);
        if (compressed < 1 || compressed > BlueprintLimits.MAX_COMPRESSED_BYTES) {
            throw new IOException("Blueprint compressed size is outside the allowed range");
        }
        try (InputStream file = Files.newInputStream(path, StandardOpenOption.READ);
             InputStream gzip = new GZIPInputStream(file);
             InputStream limited = new LimitedInputStream(gzip, BlueprintLimits.MAX_UNCOMPRESSED_BYTES);
             DataInputStream input = new DataInputStream(new BufferedInputStream(limited))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(BlueprintLimits.MAX_UNCOMPRESSED_BYTES));
            return BlueprintData.fromTag(tag);
        } catch (RuntimeException exception) {
            throw new IOException("Blueprint NBT exceeds its safety budget", exception);
        }
    }

    private Path writeAtomically(BlueprintData data) throws IOException {
        Path root = directory();
        Files.createDirectories(root);
        return writeAtomically(data, root.resolve(data.id() + EXTENSION));
    }

    private Path writeAtomically(BlueprintData data, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("Blueprint output has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".mindbp-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream stream = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                NbtIo.writeCompressed(data.toTag(), stream);
            }
            if (Files.size(temporary) > BlueprintLimits.MAX_COMPRESSED_BYTES) {
                throw new IOException("Compressed blueprint exceeds the hard limit");
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return output;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private Path writeAtomicallyStrict(BlueprintData data, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("Blueprint output has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".mindbp-rename-", ".tmp");
        boolean moved = false;
        try {
            try (OutputStream stream = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                NbtIo.writeCompressed(data.toTag(), stream);
            }
            if (Files.size(temporary) > BlueprintLimits.MAX_COMPRESSED_BYTES) {
                throw new IOException("Compressed blueprint exceeds the hard limit");
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            return output;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private Path validateLibraryEntryPath(Path path) throws IOException {
        Path root = directory();
        Files.createDirectories(root);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Blueprint path escaped the library directory");
        }
        return normalized;
    }

    private void publishRename(Entry renamedEntry, BlueprintData renamed, Entry superseded) {
        List<Entry> nextEntries = new ArrayList<>(entries);
        nextEntries.removeIf(entry -> entry.id().equals(renamedEntry.id())
                || superseded != null && entry.id().equals(superseded.id()));
        nextEntries.add(renamedEntry);
        nextEntries.sort(Comparator.comparing(Entry::valid).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Entry::id));

        Map<UUID, BlueprintData> nextCache = new HashMap<>(cache);
        nextCache.put(renamed.id(), renamed);
        if (superseded != null) nextCache.remove(superseded.id());
        entries = List.copyOf(nextEntries);
        cache = Map.copyOf(nextCache);
        lastError = "";
    }

    private void publishScan(ScanResult result) {
        entries = result.entries();
        cache = Map.copyOf(result.data());
        lastError = "";
    }

    private void publishSave(Entry savedEntry, BlueprintData saved, Entry superseded) {
        List<Entry> nextEntries = new ArrayList<>(entries);
        nextEntries.removeIf(entry -> entry.id().equals(savedEntry.id())
                || superseded != null && entry.id().equals(superseded.id()));
        nextEntries.add(savedEntry);
        nextEntries.sort(Comparator.comparing(Entry::valid).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Entry::id));

        Map<UUID, BlueprintData> nextCache = new HashMap<>(cache);
        nextCache.put(saved.id(), saved);
        if (superseded != null) nextCache.remove(superseded.id());
        entries = List.copyOf(nextEntries);
        cache = Map.copyOf(nextCache);
        lastError = "";
    }

    private Entry entry(BlueprintData data, Path path) {
        return new Entry(data.id(), data.name(), data.author(), data.sizeX(), data.sizeY(), data.sizeZ(),
                data.blocks().size(), data.saveMode(), path, modified(path), true, "");
    }

    private static Path validateExternalFile(Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !normalized.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION)) {
            throw new IOException("Selected file is not a regular " + EXTENSION + " blueprint");
        }
        return normalized;
    }

    private static Path ensureExtension(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION)
                ? path : path.resolveSibling(name + EXTENSION);
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
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

        private void account(long bytes) throws IOException {
            consumed += bytes;
            if (consumed > limit) throw new IOException("Blueprint decompressed size exceeds the hard limit");
        }
    }
}
