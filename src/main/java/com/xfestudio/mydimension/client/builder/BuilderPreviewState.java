package com.xfestudio.mydimension.client.builder;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Client-owned, immutable-at-the-boundary state for world previews. */
public final class BuilderPreviewState {
    public enum Kind {
        BUILD(0.18F, 0.95F, 0.35F, 0.95F),
        DEMOLISH(0.96F, 0.20F, 0.20F, 0.95F),
        MISSING(1.00F, 0.82F, 0.12F, 1.00F),
        INVALID(1.00F, 0.48F, 0.10F, 0.95F),
        BLUEPRINT(0.20F, 0.55F, 1.00F, 0.82F),
        /** Client-only color used for loaded resonant supply anchors. */
        ANCHOR(0.72F, 0.24F, 0.96F, 1.00F);

        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        Kind(float red, float green, float blue, float alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        public float red() { return red; }
        public float green() { return green; }
        public float blue() { return blue; }
        public float alpha() { return alpha; }
    }

    public record Cell(BlockPos pos, BlockState state, Kind kind, boolean ghost) {
        public Cell {
            pos = pos.immutable();
        }

        public AABB bounds() {
            return new AABB(pos).inflate(0.002D);
        }
    }

    public record Selection(ResourceKey<Level> dimension, @Nullable BlockPos first, @Nullable BlockPos second) {
        public Selection {
            first = first == null ? null : first.immutable();
            second = second == null ? null : second.immutable();
        }

        public boolean active() {
            return first != null;
        }

        /** Both validated corners exist, so the cuboid can be saved again. */
        public boolean complete() {
            return first != null && second != null;
        }

        @Nullable
        public AABB bounds() {
            if (first == null || second == null) {
                return null;
            }
            BlockPos min = new BlockPos(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ())
            );
            BlockPos max = new BlockPos(
                    Math.max(first.getX(), second.getX()) + 1,
                    Math.max(first.getY(), second.getY()) + 1,
                    Math.max(first.getZ(), second.getZ()) + 1
            );
            return new AABB(min, max);
        }
    }

    public record Snapshot(ResourceKey<Level> dimension, List<Cell> cells, Selection selection,
                           @Nullable UUID activeJobId, boolean blueprintPreview,
                           boolean cancelable, int revision) {
        public Snapshot {
            cells = List.copyOf(cells);
        }
    }

    public record Candidate(ResourceKey<Level> dimension, BlockPos pos) {
        public Candidate {
            pos = pos.immutable();
        }
    }

    /** Exactly one crosshair focus is selected from all visible preview objects. */
    public enum FocusKind {
        DEPLOYMENT,
        SELECTION,
        MISSING,
        CANDIDATE
    }

    public record Focus(FocusKind kind, AABB bounds, double distance,
                        @Nullable Cell cell, @Nullable MissingGroup missingGroup) {
        public Focus(FocusKind kind, AABB bounds, double distance, @Nullable Cell cell) {
            this(kind, bounds, distance, cell, null);
        }
    }

    /** One 26-neighbour connected component of missing construction cells. */
    public record MissingGroup(List<Cell> cells, AABB bounds) {
        public MissingGroup {
            cells = List.copyOf(cells);
        }
    }

    record MissingTarget(MissingGroup group, Cell cell) { }

    /** Exact action represented by the currently focused cancelable object. */
    public enum CancelTarget {
        DEPLOYMENT,
        SELECTION,
        MISSING
    }

    private static final BuilderPreviewState INSTANCE = new BuilderPreviewState();

    @Nullable
    private volatile PublishedPreview published;
    @Nullable
    private volatile Focus focus;
    @Nullable
    private volatile Candidate controlCandidate;
    @Nullable
    private ResourceKey<Level> layerDimension;
    @Nullable
    private Selection layerSelection;
    @Nullable
    private UUID layerActiveJobId;
    private List<Cell> surfaceCells = List.of();
    private List<Cell> missingCells = List.of();
    private List<Cell> deploymentCells = List.of();
    private boolean deploymentActive;
    private boolean serverCancelable;
    private boolean layersInitialized;
    private int layerRevision;
    private record PublishedPreview(Snapshot snapshot,
                                    Map<BlockPos, MissingTarget> interactiveGroups,
                                    @Nullable AABB blueprintBounds) {
        private PublishedPreview {
            interactiveGroups = Map.copyOf(interactiveGroups);
        }
    }

    BuilderPreviewState() {
    }

    public static BuilderPreviewState get() {
        return INSTANCE;
    }

    @Nullable
    public Snapshot snapshot() {
        PublishedPreview frame = published;
        return frame == null ? null : frame.snapshot();
    }

    /**
     * Replaces every preview layer from one already-composed snapshot. Production callers should
     * prefer the layer-specific update methods below so unrelated workflows cannot erase each
     * other; this entry point remains useful at the immutable boundary and in focused tests.
     */
    public void accept(Snapshot value) {
        resetLayers(value.dimension());
        layerSelection = value.selection();
        layerActiveJobId = value.activeJobId();
        missingCells = value.cells().stream()
                .filter(cell -> cell.kind() == Kind.MISSING).toList();
        List<Cell> ordinary = value.cells().stream()
                .filter(cell -> cell.kind() != Kind.MISSING).toList();
        deploymentActive = value.blueprintPreview();
        deploymentCells = deploymentActive ? ordinary : List.of();
        surfaceCells = deploymentActive ? List.of() : ordinary;
        serverCancelable = value.cancelable() && value.activeJobId() != null;
        layerRevision = value.revision();
        publishComposite();
    }

    /** Replaces only the locally planned build/demolish surface. */
    public void updateSurfacePreview(ResourceKey<Level> dimension, List<Cell> cells) {
        ensureDimension(dimension);
        surfaceCells = List.copyOf(cells);
        advanceRevision(0);
        publishComposite();
    }

    /** Removes only the locally planned surface, retaining selection and material-waiting work. */
    public void clearSurfacePreview() {
        if (!layersInitialized || surfaceCells.isEmpty()) return;
        surfaceCells = List.of();
        advanceRevision(0);
        publishComposite();
    }

    /** Replaces only the copied source cuboid. */
    public void updateSelection(Selection selection) {
        ensureDimension(selection.dimension());
        layerSelection = selection;
        advanceRevision(0);
        publishComposite();
    }

    /**
     * Replaces only the server-owned material-waiting layer. A transient empty packet may be
     * ignored while the lightweight server snapshot still advertises the same active job (the
     * blueprint worker temporarily moves that job out of persistent pending storage while it runs).
     */
    public void updateMissingPreview(ResourceKey<Level> dimension, List<Cell> cells,
                                     @Nullable UUID activeJobId, boolean cancelable,
                                     int revision, boolean preserveTransientEmpty) {
        ensureDimension(dimension);
        if (preserveTransientEmpty && cells.isEmpty() && activeJobId == null
                && layerActiveJobId != null) {
            return;
        }
        missingCells = cells.stream()
                .filter(cell -> cell.kind() == Kind.MISSING).toList();
        layerActiveJobId = activeJobId;
        serverCancelable = cancelable && activeJobId != null;
        advanceRevision(revision);
        publishComposite();
    }

    /** Activates or refreshes the movable deployment without destroying background workflows. */
    public void updateDeploymentPreview(ResourceKey<Level> dimension, List<Cell> cells) {
        ensureDimension(dimension);
        deploymentActive = true;
        deploymentCells = List.copyOf(cells);
        // A surface target is meaningful only at the location from which it was planned. Once a
        // deployment takes control of targeting, discard that stale layer; it is replanned on the
        // first tick after the deployment is cancelled.
        surfaceCells = List.of();
        advanceRevision(0);
        publishComposite();
    }

    private void publishComposite() {
        if (!layersInitialized) {
            published = null;
            focus = null;
            return;
        }
        Selection selection = layerSelection == null
                || !Objects.equals(layerSelection.dimension(), layerDimension)
                ? new Selection(layerDimension, null, null) : layerSelection;
        List<Cell> foreground = deploymentActive ? deploymentCells : surfaceCells;
        Map<BlockPos, Cell> visible = new LinkedHashMap<>(
                Math.max(16, foreground.size() + missingCells.size()));
        for (Cell cell : foreground) visible.put(cell.pos(), cell);
        if (!deploymentActive) {
            // Missing material is actionable and therefore wins a same-position collision with a
            // passive surface projection. While a deployment is active it exclusively owns the
            // world overlay and focus; missing work remains retained in this layer and returns as
            // soon as placement is cancelled.
            for (Cell cell : missingCells) visible.put(cell.pos(), cell);
        }
        boolean cancelable = deploymentActive || selection.active()
                || serverCancelable && layerActiveJobId != null;
        Snapshot snapshot = new Snapshot(layerDimension, List.copyOf(visible.values()), selection,
                layerActiveJobId, deploymentActive, cancelable, layerRevision);
        publishSnapshot(snapshot, deploymentActive ? deploymentCells : List.of());
    }

    private void publishSnapshot(Snapshot value, List<Cell> deploymentBoundsCells) {
        focus = null;
        if (value.cells().isEmpty()) {
            published = new PublishedPreview(value, Map.of(), null);
            return;
        }
        Map<BlockPos, Cell> targets = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Cell cell : value.cells()) {
            if (cell.kind() == Kind.MISSING) {
                targets.put(cell.pos(), cell);
            }
        }
        for (Cell cell : deploymentBoundsCells) {
            minX = Math.min(minX, cell.pos().getX());
            minY = Math.min(minY, cell.pos().getY());
            minZ = Math.min(minZ, cell.pos().getZ());
            maxX = Math.max(maxX, cell.pos().getX());
            maxY = Math.max(maxY, cell.pos().getY());
            maxZ = Math.max(maxZ, cell.pos().getZ());
        }
        Map<BlockPos, MissingTarget> groups = targets.isEmpty()
                ? Map.of() : indexMissingGroups(targets);
        AABB bounds = deploymentActive && minX != Integer.MAX_VALUE
                ? new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D)
                : null;
        // Publish the immutable snapshot and all derived indexes together. Readers can never
        // observe cells from one generation with bounds/groups from another generation.
        published = new PublishedPreview(value, groups, bounds);
    }

    public void clear() {
        focus = null;
        controlCandidate = null;
        published = null;
        clearLayers();
    }

    public void clearLocalWorkflow() {
        if (!layersInitialized) return;
        surfaceCells = List.of();
        missingCells = List.of();
        deploymentCells = List.of();
        deploymentActive = false;
        layerActiveJobId = null;
        serverCancelable = false;
        layerSelection = new Selection(layerDimension, null, null);
        advanceRevision(0);
        publishComposite();
        focus = null;
    }

    /** Removes only the transformed placement, retaining the copied source cuboid. */
    public void clearPlacementPreview() {
        if (!layersInitialized || !deploymentActive) return;
        deploymentActive = false;
        deploymentCells = List.of();
        advanceRevision(0);
        publishComposite();
    }

    /** Removes only the copied source selection, retaining deployment or missing previews. */
    public void clearSelection() {
        if (!layersInitialized || layerSelection == null || !layerSelection.active()) return;
        layerSelection = new Selection(layerDimension, null, null);
        advanceRevision(0);
        publishComposite();
    }

    /** Removes only the yellow task cells after a transaction-specific cancellation. */
    public void clearMissingPreview() {
        if (!layersInitialized || layerActiveJobId == null) return;
        missingCells = List.of();
        layerActiveJobId = null;
        serverCancelable = false;
        advanceRevision(0);
        publishComposite();
    }

    private void ensureDimension(ResourceKey<Level> dimension) {
        if (!layersInitialized || !Objects.equals(layerDimension, dimension)) {
            resetLayers(dimension);
        }
    }

    private void resetLayers(ResourceKey<Level> dimension) {
        clearLayers();
        layerDimension = dimension;
        layerSelection = new Selection(dimension, null, null);
        layersInitialized = true;
    }

    private void clearLayers() {
        layerDimension = null;
        layerSelection = null;
        layerActiveJobId = null;
        surfaceCells = List.of();
        missingCells = List.of();
        deploymentCells = List.of();
        deploymentActive = false;
        serverCancelable = false;
        layersInitialized = false;
        layerRevision = 0;
    }

    private void advanceRevision(int suggestedRevision) {
        int next = layerRevision == Integer.MAX_VALUE ? 1 : layerRevision + 1;
        layerRevision = Math.max(next, suggestedRevision);
    }

    public boolean isBlueprintPreviewActive() {
        Snapshot value = snapshot();
        return value != null && value.blueprintPreview();
    }

    /** Checks the deployment layer itself; composite cells can additionally contain missing work. */
    public boolean hasCompleteDeploymentGeneration(int expectedCells) {
        return deploymentActive && deploymentCells.size() == Math.max(0, expectedCells);
    }

    public boolean hasSaveableSelection() {
        Snapshot value = snapshot();
        return value != null && value.selection() != null && value.selection().complete();
    }

    /** Transform actions need a deployment; SAVE only needs the persistent source cuboid. */
    public boolean hasBlueprintWheelActions() {
        return isBlueprintPreviewActive() || hasSaveableSelection();
    }

    public boolean hasCancelableWorkflow() {
        Snapshot value = snapshot();
        return value != null && value.cancelable();
    }

    public boolean hasFocusedCancelableTarget() {
        return focusedCancelTarget() != null;
    }

    /** Resolves focus and workflow state once so an input event cannot cancel a different object. */
    @Nullable
    public CancelTarget focusedCancelTarget() {
        return cancelTarget(snapshot(), focus);
    }

    @Nullable
    public UUID activeJobId() {
        Snapshot value = snapshot();
        return value == null ? null : value.activeJobId();
    }

    @Nullable
    public Cell virtualTarget() {
        Focus current = focus;
        return current != null && current.kind() == FocusKind.MISSING ? current.cell() : null;
    }

    @Nullable
    public Focus focus() {
        return focus;
    }

    @Nullable
    public Candidate controlCandidate() {
        return controlCandidate;
    }

    @Nullable
    public AABB blueprintBounds() {
        PublishedPreview frame = published;
        return frame == null ? null : frame.blueprintBounds();
    }

    public void setControlCandidate(ResourceKey<Level> dimension, BlockPos position) {
        Candidate current = controlCandidate;
        if (current == null || !current.dimension().equals(dimension)
                || !current.pos().equals(position)) {
            controlCandidate = new Candidate(dimension, position);
        }
    }

    public void clearControlCandidate() {
        controlCandidate = null;
    }

    public void clearHover() {
        focus = null;
    }

    /**
     * Server task previews currently carry no client-owned capture corners. Preserve an active
     * same-dimension selection unless the packet explicitly supplies replacement corners.
     */
    static Selection mergeSelection(ResourceKey<Level> dimension, @Nullable BlockPos first,
                                    @Nullable BlockPos second, @Nullable Snapshot current) {
        if (first != null || second != null) {
            return new Selection(dimension, first, second);
        }
        Selection currentSelection = current == null ? null : current.selection();
        boolean sameDimension = current != null && currentSelection != null
                && current.dimension().equals(dimension)
                && currentSelection.dimension().equals(dimension);
        if (shouldPreserveSelection(first, second, sameDimension, currentSelection)) {
            return current.selection();
        }
        return new Selection(dimension, null, null);
    }

    static boolean shouldPreserveSelection(@Nullable BlockPos first, @Nullable BlockPos second,
                                           boolean sameDimension, @Nullable Selection current) {
        return first == null && second == null && sameDimension
                && current != null && current.active();
    }

    static boolean mergeCancelable(boolean serverCancelable, boolean blueprintPreview,
                                   Selection selection) {
        return serverCancelable || blueprintPreview || selection.active();
    }

    /**
     * A normal surface/selection/deployment preview is client-owned. Server synchronization also
     * sends an empty packet to mean "there is no material-waiting task"; accepting that packet
     * verbatim creates a one-frame blank before the local planner republishes its result. Preserve
     * client-owned content in that case, while an actual task completion still removes its yellow
     * cells immediately.
     */
    static Snapshot mergeServerSnapshot(@Nullable Snapshot current, Snapshot incoming,
                                        boolean emptyServerWorkflow) {
        if (!emptyServerWorkflow || current == null
                || !Objects.equals(current.dimension(), incoming.dimension())) return incoming;

        if (current.activeJobId() != null) {
            List<Cell> remaining = current.cells().stream()
                    .filter(cell -> cell.kind() != Kind.MISSING).toList();
            boolean cancelable = current.blueprintPreview() || incoming.selection().active();
            return new Snapshot(incoming.dimension(), remaining, incoming.selection(), null,
                    current.blueprintPreview(), cancelable, incoming.revision());
        }

        boolean clientOwned = !current.cells().isEmpty() || current.blueprintPreview()
                || current.selection().active();
        return clientOwned ? current : incoming;
    }

    /**
     * Updates the yellow/blue virtual-cell under the crosshair. A voxel DDA
     * visits at most a few hundred positions at 64-block reach and performs
     * O(1) lookups, rather than scanning a 65k-cell blueprint every tick.
     */
    public void updateHoveredTarget(Minecraft minecraft, float partialTick, double maximumDistance) {
        PublishedPreview frame = published;
        Snapshot value = frame == null ? null : frame.snapshot();
        Candidate candidate = controlCandidate;
        if (minecraft.player == null || minecraft.level == null) {
            focus = null;
            return;
        }
        boolean snapshotVisible = value != null
                && minecraft.level.dimension().equals(value.dimension());
        boolean candidateVisible = candidate != null
                && minecraft.level.dimension().equals(candidate.dimension());
        if (!snapshotVisible && !candidateVisible) {
            focus = null;
            return;
        }
        Map<BlockPos, MissingTarget> groups = snapshotVisible
                ? frame.interactiveGroups() : Map.of();
        AABB visibleDeploymentBounds = snapshotVisible && value.blueprintPreview()
                ? frame.blueprintBounds() : null;
        AABB visibleSelectionBounds = snapshotVisible && !value.blueprintPreview()
                && value.selection() != null
                ? value.selection().bounds() : null;
        if (groups.isEmpty() && visibleDeploymentBounds == null
                && visibleSelectionBounds == null && !candidateVisible) {
            focus = null;
            return;
        }

        Vec3 origin = minecraft.player.getEyePosition(partialTick);
        Vec3 direction = minecraft.player.getViewVector(partialTick).normalize();
        double visibleDistance = maximumDistance;
        // Vanilla's cached hit result is limited by the ordinary interaction
        // reach. Re-pick at the server-advertised builder reach so a virtual
        // cell cannot be selected through a real obstruction farther away.
        HitResult hitResult = minecraft.player.pick(maximumDistance, partialTick, false);
        if (hitResult instanceof BlockHitResult blockHit && hitResult.getType() == HitResult.Type.BLOCK) {
            visibleDistance = Math.min(visibleDistance,
                    origin.distanceTo(blockHit.getLocation()) + 0.025D);
        }

        // Deployment is the one intentional type priority. Once the ray enters its full
        // volume, it wins over source-selection and missing-material objects on that ray.
        RayInterval deploymentHit = rayInterval(visibleDeploymentBounds, origin, direction, visibleDistance);
        if (deploymentHit != null) {
            focus = new Focus(FocusKind.DEPLOYMENT, visibleDeploymentBounds,
                    deploymentHit.enter(), null);
            return;
        }

        Focus nearest = null;
        RayInterval selectionHit = rayInterval(visibleSelectionBounds, origin, direction, visibleDistance);
        if (selectionHit != null) {
            nearest = new Focus(FocusKind.SELECTION, visibleSelectionBounds,
                    selectionHit.enter(), null);
        }

        nearest = nearer(nearest, focusMissing(origin, direction, visibleDistance, groups));

        if (candidateVisible) {
            AABB bounds = new AABB(candidate.pos()).inflate(0.006D);
            RayInterval candidateHit = rayInterval(bounds, origin, direction, visibleDistance);
            if (candidateHit != null) {
                nearest = nearer(nearest, new Focus(FocusKind.CANDIDATE, bounds, candidateHit.enter(),
                        new Cell(candidate.pos(), Blocks.AIR.defaultBlockState(),
                                Kind.BLUEPRINT, false)));
            }
        }
        focus = nearest;
    }

    @Nullable
    private static MissingRayHit raycastInteractive(Vec3 origin, Vec3 direction, double maximumDistance,
                                                    Map<BlockPos, MissingTarget> groups) {
        if (groups.isEmpty() || maximumDistance <= 0.0D) return null;
        int x = floor(origin.x);
        int y = floor(origin.y);
        int z = floor(origin.z);
        int stepX = direction.x > 0.0D ? 1 : direction.x < 0.0D ? -1 : 0;
        int stepY = direction.y > 0.0D ? 1 : direction.y < 0.0D ? -1 : 0;
        int stepZ = direction.z > 0.0D ? 1 : direction.z < 0.0D ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / direction.x);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / direction.y);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / direction.z);
        double nextX = boundaryDistance(origin.x, x, stepX, direction.x);
        double nextY = boundaryDistance(origin.y, y, stepY, direction.y);
        double nextZ = boundaryDistance(origin.z, z, stepZ, direction.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double travelled = 0.0D;
        while (travelled <= maximumDistance + 1.0E-5D) {
            cursor.set(x, y, z);
            MissingTarget target = groups.get(cursor);
            if (target != null) return new MissingRayHit(target.group(), target.cell());
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                travelled = nextX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                travelled = nextY;
                nextY += deltaY;
            } else {
                z += stepZ;
                travelled = nextZ;
                nextZ += deltaZ;
            }
        }
        return null;
    }

    @Nullable
    static Focus focusMissing(Vec3 origin, Vec3 direction, double maximumDistance,
                              Map<BlockPos, MissingTarget> groups) {
        MissingRayHit hit = raycastInteractive(origin, direction, maximumDistance, groups);
        if (hit == null) return null;
        // Interaction uses the exact cell volume. Cell#bounds is slightly inflated only to avoid
        // visual z-fighting and must not make a farther group win by a few millimetres.
        RayInterval interval = rayInterval(new AABB(hit.cell().pos()), origin, direction, maximumDistance);
        if (interval == null) return null;
        return new Focus(FocusKind.MISSING, hit.group().bounds(), interval.enter(),
                hit.cell(), hit.group());
    }

    /** Builds stable connected components using face, edge, and corner adjacency. */
    static Map<BlockPos, MissingTarget> indexMissingGroups(Map<BlockPos, Cell> cells) {
        if (cells.isEmpty()) return Map.of();
        Long2ObjectOpenHashMap<Cell> cellsByPosition = new Long2ObjectOpenHashMap<>(cells.size());
        cells.forEach((position, cell) -> cellsByPosition.put(position.asLong(), cell));
        LongOpenHashSet visited = new LongOpenHashSet(cells.size());
        Map<BlockPos, MissingTarget> result = new HashMap<>(Math.max(16, cells.size() * 2));
        LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
        for (Map.Entry<BlockPos, Cell> seed : cells.entrySet()) {
            long seedPosition = seed.getKey().asLong();
            if (!visited.add(seedPosition)) continue;
            List<Cell> members = new ArrayList<>();
            pending.enqueue(seedPosition);
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            while (!pending.isEmpty()) {
                long packedPosition = pending.dequeueLong();
                Cell cell = cellsByPosition.get(packedPosition);
                if (cell == null) continue;
                int x = BlockPos.getX(packedPosition);
                int y = BlockPos.getY(packedPosition);
                int z = BlockPos.getZ(packedPosition);
                members.add(cell);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            long neighbour = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if (cellsByPosition.containsKey(neighbour) && visited.add(neighbour)) {
                                pending.enqueue(neighbour);
                            }
                        }
                    }
                }
            }
            MissingGroup group = new MissingGroup(members,
                    new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D));
            for (Cell member : members) result.put(member.pos(), new MissingTarget(group, member));
        }
        return Collections.unmodifiableMap(result);
    }

    @Nullable
    static CancelTarget cancelTarget(@Nullable Snapshot value, @Nullable Focus current) {
        if (value == null || current == null) return null;
        return switch (current.kind()) {
            case DEPLOYMENT -> value.blueprintPreview() ? CancelTarget.DEPLOYMENT : null;
            case SELECTION -> value.selection().active() ? CancelTarget.SELECTION : null;
            case MISSING -> value.cancelable() && value.activeJobId() != null
                    && current.missingGroup() != null
                    ? CancelTarget.MISSING : null;
            case CANDIDATE -> null;
        };
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double boundaryDistance(double origin, int cell, int step, double direction) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? cell + 1.0D : cell;
        return (boundary - origin) / direction;
    }

    @Nullable
    private static Focus nearer(@Nullable Focus current, @Nullable Focus candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.distance() + 1.0E-6D < current.distance()) {
            return candidate;
        }
        return current;
    }

    /** Slab intersection reports entry into the whole cuboid, not merely its twelve edges. */
    @Nullable
    private static RayInterval rayInterval(@Nullable AABB box, Vec3 origin,
                                           Vec3 direction, double maximumDistance) {
        if (box == null || maximumDistance < 0.0D) return null;
        double enter = 0.0D;
        double exit = maximumDistance;
        double[] origins = { origin.x, origin.y, origin.z };
        double[] directions = { direction.x, direction.y, direction.z };
        double[] minimums = { box.minX, box.minY, box.minZ };
        double[] maximums = { box.maxX, box.maxY, box.maxZ };
        for (int axis = 0; axis < 3; axis++) {
            double component = directions[axis];
            if (Math.abs(component) < 1.0E-9D) {
                if (origins[axis] < minimums[axis] || origins[axis] > maximums[axis]) return null;
                continue;
            }
            double first = (minimums[axis] - origins[axis]) / component;
            double second = (maximums[axis] - origins[axis]) / component;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            enter = Math.max(enter, first);
            exit = Math.min(exit, second);
            if (exit + 1.0E-7D < enter) return null;
        }
        return exit < 0.0D || enter > maximumDistance ? null
                : new RayInterval(Math.max(0.0D, enter), Math.min(maximumDistance, exit));
    }

    private record RayInterval(double enter, double exit) { }

    private record MissingRayHit(MissingGroup group, Cell cell) { }
}
