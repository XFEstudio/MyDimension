package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Ordered, compact UUID references stored on a Realmwright's Scepter. */
public final class AnchorBindings {
    /** Legacy pre-integration root, retained only for one-way migration. */
    @Deprecated(forRemoval = false)
    public static final String ROOT_TAG = "RealmwrightAnchors";
    private static final String ALTERNATE_LEGACY_ROOT_TAG = "RealmWrightAnchors";
    private static final String ENTRIES_TAG = "Entries";
    private static final String ID_TAG = "Id";
    private static final int CORRUPT_DATA_SAFETY_LIMIT = 4096;

    private AnchorBindings() {
    }

    public static List<UUID> read(ItemStack stack) {
        migrateLegacy(stack);
        return RealmwrightData.anchors(stack);
    }

    public static BindResult bind(ItemStack stack, UUID anchorId) {
        return bind(stack, anchorId, BuilderConfig.MAX_BOUND_ANCHORS.get());
    }

    public static BindResult bind(ItemStack stack, UUID anchorId, int maximum) {
        Objects.requireNonNull(anchorId, "anchorId");
        List<UUID> entries = new ArrayList<>(read(stack));
        if (entries.contains(anchorId)) {
            return BindResult.ALREADY_BOUND;
        }
        if (entries.size() >= Math.max(0, maximum)) {
            return BindResult.LIMIT_REACHED;
        }
        RealmwrightData.bind(stack, anchorId, Math.max(0, maximum));
        return BindResult.BOUND;
    }

    public static boolean unbind(ItemStack stack, UUID anchorId) {
        List<UUID> entries = new ArrayList<>(read(stack));
        if (!entries.remove(anchorId)) {
            return false;
        }
        RealmwrightData.setAnchors(stack, entries);
        return true;
    }

    /** Removes UUIDs that no longer have any server-side anchor index entry. */
    public static boolean pruneMissing(ItemStack stack, AnchorIndexSavedData index) {
        Objects.requireNonNull(index, "index");
        List<UUID> entries = read(stack);
        List<UUID> retained = entries.stream()
                .filter(anchorId -> index.find(anchorId).isPresent())
                .toList();
        if (retained.size() == entries.size()) {
            return false;
        }
        RealmwrightData.setAnchors(stack, retained);
        return true;
    }

    public static boolean move(ItemStack stack, UUID anchorId, int targetIndex) {
        List<UUID> entries = new ArrayList<>(read(stack));
        int currentIndex = entries.indexOf(anchorId);
        if (currentIndex < 0 || targetIndex < 0 || targetIndex >= entries.size()) {
            return false;
        }
        if (currentIndex == targetIndex) {
            return true;
        }
        entries.remove(currentIndex);
        entries.add(targetIndex, anchorId);
        RealmwrightData.setAnchors(stack, entries);
        return true;
    }

    public static void replace(ItemStack stack, List<UUID> orderedIds) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(orderedIds);
        int maximum = Math.max(0, BuilderConfig.MAX_BOUND_ANCHORS.get());
        List<UUID> normalized = unique.stream().limit(maximum).toList();
        RealmwrightData.setAnchors(stack, normalized);
    }

    private static void migrateLegacy(ItemStack stack) {
        CompoundTag itemTag = stack.getTag();
        if (itemTag == null) {
            return;
        }

        LinkedHashSet<UUID> merged = new LinkedHashSet<>(RealmwrightData.anchors(stack));
        readLegacyRoot(itemTag.getCompound(ROOT_TAG), merged);
        readLegacyRoot(itemTag.getCompound(ALTERNATE_LEGACY_ROOT_TAG), merged);

        if (itemTag.contains(ROOT_TAG, Tag.TAG_COMPOUND)
                || itemTag.contains(ALTERNATE_LEGACY_ROOT_TAG, Tag.TAG_COMPOUND)) {
            int maximum = Math.max(0, BuilderConfig.MAX_BOUND_ANCHORS.get());
            RealmwrightData.setAnchors(stack, merged.stream().limit(maximum).toList());
            CompoundTag updated = stack.getTag();
            if (updated != null) {
                updated.remove(ROOT_TAG);
                updated.remove(ALTERNATE_LEGACY_ROOT_TAG);
            }
        }
    }

    private static void readLegacyRoot(CompoundTag root, LinkedHashSet<UUID> result) {
        ListTag entries = root.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        int count = Math.min(entries.size(), CORRUPT_DATA_SAFETY_LIMIT);
        for (int i = 0; i < count; i++) {
            CompoundTag entry = entries.getCompound(i);
            if (entry.hasUUID(ID_TAG)) {
                result.add(entry.getUUID(ID_TAG));
            }
        }
    }

    public enum BindResult {
        BOUND,
        ALREADY_BOUND,
        LIMIT_REACHED
    }
}
