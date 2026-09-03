package com.xfestudio.mydimension.builder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RealmwrightData {
    private static final String ROOT = "Realmwright";
    private static final String VERSION = "Version";
    private static final String ID = "Id";
    private static final String MODE = "Mode";
    private static final String MATCH = "Match";
    private static final String BUILD_LIMIT = "BuildLimit";
    private static final String DEMOLISH_LIMIT = "DemolishLimit";
    private static final String RECORD_HISTORY = "RecordHistory";
    private static final String ALLOW_REPLACEMENT = "AllowReplace";
    private static final String ANCHORS = "Anchors";
    private static final int DATA_VERSION = 3;

    public static final int DEFAULT_BUILD_LIMIT = 256;
    public static final int DEFAULT_DEMOLISH_LIMIT = 64;

    private RealmwrightData() {
    }

    public static UUID ensureId(ItemStack stack) {
        CompoundTag tag = root(stack);
        if (!tag.hasUUID(ID)) {
            tag.putUUID(ID, UUID.randomUUID());
            writeRoot(stack, tag);
        }
        return tag.getUUID(ID);
    }

    public static UUID id(ItemStack stack) {
        CompoundTag tag = readRoot(stack);
        return tag != null && tag.hasUUID(ID) ? tag.getUUID(ID) : ensureId(stack);
    }

    public static BuilderMode mode(ItemStack stack) {
        CompoundTag tag = readRoot(stack);
        return tag == null ? BuilderMode.BUILD : BuilderMode.byName(tag.getString(MODE));
    }

    public static void setMode(ItemStack stack, BuilderMode mode) {
        CompoundTag tag = root(stack);
        tag.putString(MODE, mode.name());
        writeRoot(stack, tag);
    }

    public static SurfaceMatchMode matchMode(ItemStack stack) {
        CompoundTag tag = readRoot(stack);
        return tag == null ? SurfaceMatchMode.SAME_BLOCK : SurfaceMatchMode.byName(tag.getString(MATCH));
    }

    public static void setMatchMode(ItemStack stack, SurfaceMatchMode mode) {
        CompoundTag tag = root(stack);
        tag.putString(MATCH, mode.name());
        writeRoot(stack, tag);
    }

    public static int buildLimit(ItemStack stack, int serverMaximum) {
        CompoundTag tag = readRoot(stack);
        int value = tag != null && tag.contains(BUILD_LIMIT, Tag.TAG_INT)
                ? tag.getInt(BUILD_LIMIT) : DEFAULT_BUILD_LIMIT;
        return clamp(value, 1, serverMaximum);
    }

    public static int demolishLimit(ItemStack stack, int serverMaximum) {
        CompoundTag tag = readRoot(stack);
        int value = tag != null && tag.contains(DEMOLISH_LIMIT, Tag.TAG_INT)
                ? tag.getInt(DEMOLISH_LIMIT) : DEFAULT_DEMOLISH_LIMIT;
        return clamp(value, 1, serverMaximum);
    }

    public static void setBuildLimit(ItemStack stack, int value, int serverMaximum) {
        CompoundTag tag = root(stack);
        tag.putInt(BUILD_LIMIT, clamp(value, 1, serverMaximum));
        writeRoot(stack, tag);
    }

    public static void setDemolishLimit(ItemStack stack, int value, int serverMaximum) {
        CompoundTag tag = root(stack);
        tag.putInt(DEMOLISH_LIMIT, clamp(value, 1, serverMaximum));
        writeRoot(stack, tag);
    }

    /**
     * Whether new operations performed by this individual scepter should retain
     * undo data.  Absence deliberately means false so existing scepters receive
     * the low-overhead behaviour after upgrading.
     */
    public static boolean recordsHistory(ItemStack stack) {
        return recordsHistory(readRoot(stack));
    }

    static boolean recordsHistory(CompoundTag tag) {
        return tag != null && tag.contains(RECORD_HISTORY, Tag.TAG_BYTE)
                && tag.getBoolean(RECORD_HISTORY);
    }

    public static void setRecordsHistory(ItemStack stack, boolean value) {
        CompoundTag tag = root(stack);
        tag.putBoolean(RECORD_HISTORY, value);
        writeRoot(stack, tag);
    }

    /**
     * Whether builds made with this individual scepter may replace obstructing
     * blocks. Absence deliberately means false so existing scepters remain
     * non-destructive after upgrading.
     */
    public static boolean allowsReplacement(ItemStack stack) {
        return allowsReplacement(readRoot(stack));
    }

    static boolean allowsReplacement(CompoundTag tag) {
        return tag != null && tag.contains(ALLOW_REPLACEMENT, Tag.TAG_BYTE)
                && tag.getBoolean(ALLOW_REPLACEMENT);
    }

    public static void setAllowsReplacement(ItemStack stack, boolean value) {
        CompoundTag tag = root(stack);
        tag.putBoolean(ALLOW_REPLACEMENT, value);
        writeRoot(stack, tag);
    }

    public static List<UUID> anchors(ItemStack stack) {
        CompoundTag tag = readRoot(stack);
        if (tag == null || !tag.contains(ANCHORS, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = tag.getList(ANCHORS, Tag.TAG_INT_ARRAY);
        List<UUID> result = new ArrayList<>(list.size());
        for (Tag entry : list) {
            try {
                result.add(net.minecraft.nbt.NbtUtils.loadUUID(entry));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return List.copyOf(result);
    }

    public static boolean bind(ItemStack stack, UUID anchorId, int maximum) {
        List<UUID> values = new ArrayList<>(anchors(stack));
        if (values.contains(anchorId)) {
            return false;
        }
        if (values.size() >= maximum) {
            return false;
        }
        values.add(anchorId);
        setAnchors(stack, values);
        return true;
    }

    public static boolean unbind(ItemStack stack, UUID anchorId) {
        List<UUID> values = new ArrayList<>(anchors(stack));
        if (!values.remove(anchorId)) {
            return false;
        }
        setAnchors(stack, values);
        return true;
    }

    public static void setAnchors(ItemStack stack, List<UUID> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(net.minecraft.nbt.NbtUtils.createUUID(value)));
        CompoundTag tag = root(stack);
        tag.put(ANCHORS, list);
        writeRoot(stack, tag);
    }

    private static CompoundTag root(ItemStack stack) {
        CompoundTag existing = readRoot(stack);
        CompoundTag tag = existing == null ? new CompoundTag() : existing.copy();
        tag.putInt(VERSION, DATA_VERSION);
        return tag;
    }

    private static CompoundTag readRoot(ItemStack stack) {
        CompoundTag itemTag = stack.getTag();
        return itemTag != null && itemTag.contains(ROOT, Tag.TAG_COMPOUND)
                ? itemTag.getCompound(ROOT) : null;
    }

    private static void writeRoot(ItemStack stack, CompoundTag value) {
        stack.getOrCreateTag().put(ROOT, value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
}
