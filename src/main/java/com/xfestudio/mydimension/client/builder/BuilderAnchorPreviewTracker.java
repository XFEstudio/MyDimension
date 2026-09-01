package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.RealmwrightData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-only purple-outline view of the anchors bound to the current main-hand scepter.
 *
 * <p>The ordered UUID list on that exact item is the whitelist. Locations come only from the
 * server's lightweight builder snapshot, which in turn resolves the UUIDs through
 * {@code AnchorIndexSavedData}. Nothing here scans chunks, looks through block entities, or asks
 * the client/server to load a remote anchor chunk merely for rendering.</p>
 */
public final class BuilderAnchorPreviewTracker {
    private static final int SNAPSHOT_RETRY_TICKS = 20;

    private static ClientLevel trackedLevel;
    private static List<UUID> trackedBindings = List.of();
    private static List<BuilderClientSnapshot.AnchorView> trackedAnchorViews = List.of();
    private static List<BlockPos> snapshot = List.of();
    private static int snapshotRequestCooldown;

    private BuilderAnchorPreviewTracker() {
    }

    static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null
                || !BuilderClientServices.isHoldingRealmwright(minecraft)) {
            clear();
            return;
        }

        if (trackedLevel != level) {
            clear();
            trackedLevel = level;
        }

        ItemStack scepter = minecraft.player.getMainHandItem();
        List<UUID> bindings = RealmwrightData.anchors(scepter);
        boolean bindingsChanged = !bindings.equals(trackedBindings);
        if (bindingsChanged) {
            // Clear the retry gate on an item/NBT change so switching or unbinding is reflected
            // immediately instead of waiting for a request made for the previous scepter.
            trackedBindings = bindings;
            snapshotRequestCooldown = 0;
        }

        BuilderClientSnapshot serverSnapshot = BuilderClientServices.snapshot();
        List<BuilderClientSnapshot.AnchorView> anchorViews = serverSnapshot.anchors();
        if (bindingsChanged || !anchorViews.equals(trackedAnchorViews)) {
            trackedAnchorViews = anchorViews;
            snapshot = filterPositions(level.dimension().location(), bindings, anchorViews);
        }

        // The item whitelist updates locally as soon as its inventory stack synchronizes. If its
        // UUID order and the cached server metadata disagree, request only the lightweight
        // snapshot and retry at a bounded rate. This request never acquires anchor chunk tickets.
        if (!matchesBindings(bindings, anchorViews)) {
            if (snapshotRequestCooldown <= 0) {
                BuilderClientServices.bridge().requestSnapshot();
                snapshotRequestCooldown = SNAPSHOT_RETRY_TICKS;
            }
        } else {
            snapshotRequestCooldown = 0;
        }
        if (snapshotRequestCooldown > 0) snapshotRequestCooldown--;
    }

    static List<BlockPos> positions(ClientLevel level) {
        return trackedLevel == level ? snapshot : List.of();
    }

    static void clear() {
        trackedLevel = null;
        trackedBindings = List.of();
        trackedAnchorViews = List.of();
        snapshot = List.of();
        snapshotRequestCooldown = 0;
    }

    /**
     * Intersects server-resolved locations with the exact ordered binding list from the held item.
     * The returned order therefore remains the supply priority order of that scepter.
     */
    static List<BlockPos> filterPositions(ResourceLocation currentDimension,
                                          List<UUID> orderedBindings,
                                          List<BuilderClientSnapshot.AnchorView> resolvedAnchors) {
        if (orderedBindings.isEmpty() || resolvedAnchors.isEmpty()) return List.of();

        Map<UUID, BuilderClientSnapshot.AnchorView> byId = new HashMap<>(resolvedAnchors.size());
        for (BuilderClientSnapshot.AnchorView anchor : resolvedAnchors) {
            byId.putIfAbsent(anchor.id(), anchor);
        }

        ArrayList<BlockPos> result = new ArrayList<>(Math.min(orderedBindings.size(), byId.size()));
        for (UUID id : orderedBindings) {
            BuilderClientSnapshot.AnchorView anchor = byId.get(id);
            if (anchor == null || !currentDimension.equals(anchor.dimension())
                    || !hasResolvedLocation(anchor.status())) continue;
            result.add(BlockPos.of(anchor.packedPos()));
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    static boolean matchesBindings(List<UUID> orderedBindings,
                                   List<BuilderClientSnapshot.AnchorView> resolvedAnchors) {
        if (orderedBindings.size() != resolvedAnchors.size()) return false;
        for (int index = 0; index < orderedBindings.size(); index++) {
            if (!orderedBindings.get(index).equals(resolvedAnchors.get(index).id())) return false;
        }
        return true;
    }

    private static boolean hasResolvedLocation(BuilderClientSnapshot.AnchorStatus status) {
        return status == BuilderClientSnapshot.AnchorStatus.AVAILABLE
                || status == BuilderClientSnapshot.AnchorStatus.UNLOADED
                || status == BuilderClientSnapshot.AnchorStatus.FORBIDDEN;
    }
}
