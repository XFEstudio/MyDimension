package com.xfestudio.mydimension.builder.blueprint;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BlueprintData {
    public record BlockEntry(BlockPos pos, int stateIndex, @Nullable CompoundTag blockEntityTag) {
        public BlockEntry {
            pos = pos.immutable();
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        @Override
        public CompoundTag blockEntityTag() {
            return blockEntityTag == null ? null : blockEntityTag.copy();
        }
    }

    private final UUID id;
    private final String name;
    private final String author;
    @Nullable
    private final UUID authorUuid;
    private final long createdAt;
    private final BlueprintSaveMode saveMode;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockPos anchor;
    private final List<BlockState> palette;
    private final List<BlockEntry> blocks;

    public BlueprintData(UUID id, String name, String author, @Nullable UUID authorUuid, long createdAt,
                         BlueprintSaveMode saveMode, int sizeX, int sizeY, int sizeZ, BlockPos anchor,
                         List<BlockState> palette, List<BlockEntry> blocks) {
        this.id = id;
        this.name = BlueprintNames.normalize(name);
        this.author = author == null ? "" : author;
        this.authorUuid = authorUuid;
        this.createdAt = createdAt;
        this.saveMode = saveMode;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.anchor = anchor.immutable();
        this.palette = List.copyOf(palette);
        this.blocks = blocks.stream()
                .sorted(Comparator.comparingInt((BlockEntry entry) -> entry.pos().getY())
                        .thenComparingInt(entry -> entry.pos().getZ())
                        .thenComparingInt(entry -> entry.pos().getX()))
                .toList();
        validate();
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String author() { return author; }
    @Nullable public UUID authorUuid() { return authorUuid; }
    public long createdAt() { return createdAt; }
    public BlueprintSaveMode saveMode() { return saveMode; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public BlockPos anchor() { return anchor; }
    public List<BlockState> palette() { return palette; }
    public List<BlockEntry> blocks() { return blocks; }

    public BlockState state(BlockEntry entry) {
        return palette.get(entry.stateIndex());
    }

    public BlueprintData withIdentity(UUID newId, String newName) {
        return new BlueprintData(newId, newName, author, authorUuid, createdAt, saveMode,
                sizeX, sizeY, sizeZ, anchor, palette, blocks);
    }

    public long volume() {
        try {
            return Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        } catch (ArithmeticException ignored) {
            // Dimensions are stored as positive ints, but their mathematical product can exceed a long.
            // Saturating keeps metadata/reporting well-defined without imposing a geometry limit.
            return Long.MAX_VALUE;
        }
    }

    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("FormatVersion", BlueprintLimits.FORMAT_VERSION);
        root.putUUID("BlueprintId", id);
        root.putString("Name", name);
        root.putString("Author", author);
        if (authorUuid != null) root.putUUID("AuthorUuid", authorUuid);
        root.putLong("CreatedAt", createdAt);
        root.putString("SaveMode", saveMode.serializedName());
        root.put("Anchor", new IntArrayTag(new int[]{anchor.getX(), anchor.getY(), anchor.getZ()}));

        CompoundTag structure = new CompoundTag();
        structure.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        structure.put("size", intList(sizeX, sizeY, sizeZ));
        ListTag paletteTag = new ListTag();
        palette.forEach(state -> paletteTag.add(NbtUtils.writeBlockState(state)));
        structure.put("palette", paletteTag);

        ListTag blocksTag = new ListTag();
        for (BlockEntry entry : blocks) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.put("pos", intList(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()));
            blockTag.putInt("state", entry.stateIndex());
            CompoundTag beTag = entry.blockEntityTag();
            if (beTag != null) blockTag.put("nbt", beTag);
            blocksTag.add(blockTag);
        }
        structure.put("blocks", blocksTag);
        structure.put("entities", new ListTag());
        root.put("Structure", structure);
        return root;
    }

    public static BlueprintData fromTag(CompoundTag root) throws IOException {
        if (root.getInt("FormatVersion") != BlueprintLimits.FORMAT_VERSION) {
            throw new IOException("Unsupported blueprint format version: " + root.getInt("FormatVersion"));
        }
        if (!root.hasUUID("BlueprintId") || !root.contains("Structure", Tag.TAG_COMPOUND)) {
            throw new IOException("Blueprint header is incomplete");
        }
        CompoundTag structure = root.getCompound("Structure");
        int currentDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int savedDataVersion = structure.getInt("DataVersion");
        if (savedDataVersion > currentDataVersion) {
            throw new IOException("Blueprint was created by a newer Minecraft version");
        }
        if (savedDataVersion < currentDataVersion) {
            structure = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(),
                    structure.copy(), savedDataVersion);
        }
        ListTag size = structure.getList("size", Tag.TAG_INT);
        if (size.size() != 3) throw new IOException("Blueprint size must contain three integers");
        int sizeX = size.getInt(0);
        int sizeY = size.getInt(1);
        int sizeZ = size.getInt(2);
        int[] anchorArray = root.getIntArray("Anchor");
        if (anchorArray.length != 3) throw new IOException("Blueprint anchor must contain three integers");

        ListTag paletteTag = structure.getList("palette", Tag.TAG_COMPOUND);
        if (paletteTag.size() > BlueprintLimits.MAX_PALETTE) throw new IOException("Blueprint palette is too large");
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (Tag tag : paletteTag) {
            palette.add(decodeState((CompoundTag) tag));
        }

        ListTag blocksTag = structure.getList("blocks", Tag.TAG_COMPOUND);
        List<BlockEntry> blocks = new ArrayList<>(blocksTag.size());
        for (Tag tag : blocksTag) {
            CompoundTag blockTag = (CompoundTag) tag;
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            if (posTag.size() != 3) throw new IOException("Blueprint block position is invalid");
            CompoundTag beTag = blockTag.contains("nbt", Tag.TAG_COMPOUND)
                    ? blockTag.getCompound("nbt").copy() : null;
            blocks.add(new BlockEntry(new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                    blockTag.getInt("state"), beTag));
        }
        BlueprintSaveMode mode;
        try {
            mode = BlueprintSaveMode.parse(root.getString("SaveMode"));
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        try {
            return new BlueprintData(root.getUUID("BlueprintId"), root.getString("Name"), root.getString("Author"),
                    root.hasUUID("AuthorUuid") ? root.getUUID("AuthorUuid") : null,
                    root.getLong("CreatedAt"), mode, sizeX, sizeY, sizeZ,
                    new BlockPos(anchorArray[0], anchorArray[1], anchorArray[2]), palette, blocks);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid blueprint: " + exception.getMessage(), exception);
        }
    }

    private void validate() {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("Blueprint dimensions must be positive");
        }
        if (anchor.getX() < 0 || anchor.getY() < 0 || anchor.getZ() < 0
                || anchor.getX() >= sizeX || anchor.getY() >= sizeY || anchor.getZ() >= sizeZ) {
            throw new IllegalArgumentException("Blueprint anchor is outside its bounds");
        }
        if (palette.size() > BlueprintLimits.MAX_PALETTE) {
            throw new IllegalArgumentException("Blueprint palette exceeds the allocation-safety limit");
        }
        Set<BlockPos> occupied = new HashSet<>();
        int blockEntityCount = 0;
        int blockEntityBytes = 0;
        for (BlockEntry entry : blocks) {
            BlockPos pos = entry.pos();
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                    || pos.getX() >= sizeX || pos.getY() >= sizeY || pos.getZ() >= sizeZ) {
                throw new IllegalArgumentException("Blueprint contains an out-of-bounds block");
            }
            if (!occupied.add(pos)) throw new IllegalArgumentException("Blueprint contains a duplicate block position");
            if (entry.stateIndex() < 0 || entry.stateIndex() >= palette.size()) {
                throw new IllegalArgumentException("Blueprint contains an invalid palette index");
            }
            CompoundTag beTag = entry.blockEntityTag();
            if (beTag != null) {
                blockEntityCount++;
                int encoded = encodedBytes(beTag);
                if (encoded > BlueprintLimits.MAX_BLOCK_ENTITY_BYTES) {
                    throw new IllegalArgumentException("A block entity exceeds the hard limit");
                }
                blockEntityBytes = Math.addExact(blockEntityBytes, encoded);
            }
        }
        if (blockEntityCount > BlueprintLimits.MAX_BLOCK_ENTITIES
                || blockEntityBytes > BlueprintLimits.MAX_BLOCK_ENTITY_TOTAL_BYTES) {
            throw new IllegalArgumentException("Blueprint block entity data exceeds the hard limit");
        }
        if (saveMode == BlueprintSaveMode.BLOCKS_ONLY && blockEntityCount != 0) {
            throw new IllegalArgumentException("Blocks-only blueprint contains block entity data");
        }
    }

    private static int encodedBytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(output));
            return output.size();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to size block entity NBT", exception);
        }
    }

    private static BlockState decodeState(CompoundTag tag) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Name"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IOException("Missing or invalid block in blueprint palette: " + tag.getString("Name"));
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = block.defaultBlockState();
        if (!tag.contains("Properties", Tag.TAG_COMPOUND)) return state;
        CompoundTag properties = tag.getCompound("Properties");
        for (String propertyName : properties.getAllKeys()) {
            Property<?> property = block.getStateDefinition().getProperty(propertyName);
            if (property == null) throw new IOException("Unknown property " + propertyName + " for " + id);
            state = setProperty(state, property, properties.getString(propertyName), id);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setProperty(BlockState state, Property property, String value,
                                          ResourceLocation blockId) throws IOException {
        java.util.Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new IOException("Invalid value " + value + " for property " + property.getName()
                    + " on " + blockId);
        }
        return state.setValue(property, (Comparable) parsed.get());
    }

    private static ListTag intList(int... values) {
        ListTag result = new ListTag();
        for (int value : values) result.add(IntTag.valueOf(value));
        return result;
    }
}
