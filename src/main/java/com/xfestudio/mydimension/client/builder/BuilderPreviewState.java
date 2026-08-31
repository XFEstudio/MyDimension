package com.xfestudio.mydimension.client.builder;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-owned, immutable-at-the-boundary state for world previews. */
public final class BuilderPreviewState {
    public enum Kind {
        BUILD(0.18F, 0.95F, 0.35F, 0.95F),
        DEMOLISH(0.96F, 0.20F, 0.20F, 0.95F),
        MISSING(1.00F, 0.82F, 0.12F, 1.00F),
        INVALID(1.00F, 0.48F, 0.10F, 0.95F),
        BLUEPRINT(0.20F, 0.55F, 1.00F, 0.82F);

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

    private static final BuilderPreviewState INSTANCE = new BuilderPreviewState();

    private volatile Snapshot snapshot;
    @Nullable
    private volatile Cell hoveredTarget;
    @Nullable
    private volatile Candidate controlCandidate;
    @Nullable
    private volatile AABB blueprintBounds;
    private volatile boolean hoveredBlueprintBoundary;
    private volatile boolean hoveredSelectionBoundary;
    private volatile Map<BlockPos, Cell> interactiveCells = Map.of();

    private BuilderPreviewState() {
    }

    public static BuilderPreviewState get() {
        return INSTANCE;
    }

    @Nullable
    public Snapshot snapshot() {
        return snapshot;
    }

    public void accept(Snapshot value) {
        snapshot = value;
        hoveredTarget = null;
        hoveredBlueprintBoundary = false;
        hoveredSelectionBoundary = false;
        if (value.cells().isEmpty()) {
            interactiveCells = Map.of();
            blueprintBounds = null;
            return;
        }
        Map<BlockPos, Cell> targets = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Cell cell : value.cells()) {
            if (cell.kind() == Kind.MISSING || cell.kind() == Kind.BLUEPRINT) {
                targets.put(cell.pos(), cell);
            }
            if (value.blueprintPreview()) {
                minX = Math.min(minX, cell.pos().getX());
                minY = Math.min(minY, cell.pos().getY());
                minZ = Math.min(minZ, cell.pos().getZ());
                maxX = Math.max(maxX, cell.pos().getX());
                maxY = Math.max(maxY, cell.pos().getY());
                maxZ = Math.max(maxZ, cell.pos().getZ());
            }
        }
        interactiveCells = targets.isEmpty() ? Map.of() : Collections.unmodifiableMap(targets);
        blueprintBounds = value.blueprintPreview() && minX != Integer.MAX_VALUE
                ? new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D)
                : null;
    }

    public void clear() {
        snapshot = null;
        hoveredTarget = null;
        controlCandidate = null;
        blueprintBounds = null;
        hoveredBlueprintBoundary = false;
        hoveredSelectionBoundary = false;
        interactiveCells = Map.of();
    }

    public void clearLocalWorkflow() {
        Snapshot value = snapshot;
        if (value != null) {
            accept(new Snapshot(value.dimension(), List.of(),
                    new Selection(value.dimension(), null, null), null,
                    false, false, value.revision() + 1));
        }
        hoveredTarget = null;
    }

    public boolean isBlueprintPreviewActive() {
        Snapshot value = snapshot;
        return value != null && value.blueprintPreview();
    }

    public boolean hasCancelableWorkflow() {
        Snapshot value = snapshot;
        return value != null && value.cancelable();
    }

    public boolean hasHoveredCancelableTarget() {
        Cell hovered = hoveredTarget;
        return hasCancelableWorkflow() && (hoveredBlueprintBoundary || hoveredSelectionBoundary
                || hovered != null
                && (hovered.kind() == Kind.MISSING || hovered.kind() == Kind.BLUEPRINT));
    }

    @Nullable
    public UUID activeJobId() {
        Snapshot value = snapshot;
        return value == null ? null : value.activeJobId();
    }

    @Nullable
    public Cell virtualTarget() {
        Cell hovered = hoveredTarget;
        return hovered != null && hovered.kind() == Kind.MISSING ? hovered : null;
    }

    @Nullable
    public Cell hoveredTarget() {
        return hoveredTarget;
    }

    @Nullable
    public Candidate controlCandidate() {
        return controlCandidate;
    }

    @Nullable
    public AABB blueprintBounds() {
        return blueprintBounds;
    }

    public boolean isBlueprintBoundaryHovered() {
        return hoveredBlueprintBoundary;
    }

    public boolean isSelectionBoundaryHovered() {
        return hoveredSelectionBoundary;
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
        hoveredTarget = null;
        hoveredBlueprintBoundary = false;
        hoveredSelectionBoundary = false;
    }

    /**
     * Updates the yellow/blue virtual-cell under the crosshair. A voxel DDA
     * visits at most a few hundred positions at 64-block reach and performs
     * O(1) lookups, rather than scanning a 65k-cell blueprint every tick.
     */
    public void updateHoveredTarget(Minecraft minecraft, float partialTick, double maximumDistance) {
        Snapshot value = snapshot;
        Candidate candidate = controlCandidate;
        if (minecraft.player == null || minecraft.level == null) {
            hoveredTarget = null;
            hoveredBlueprintBoundary = false;
            hoveredSelectionBoundary = false;
            return;
        }
        boolean snapshotVisible = value != null
                && minecraft.level.dimension().equals(value.dimension());
        boolean candidateVisible = candidate != null
                && minecraft.level.dimension().equals(candidate.dimension());
        if (!snapshotVisible && !candidateVisible) {
            hoveredTarget = null;
            hoveredBlueprintBoundary = false;
            hoveredSelectionBoundary = false;
            return;
        }
        Map<BlockPos, Cell> cells = snapshotVisible ? interactiveCells : Map.of();
        AABB visibleBlueprintBounds = snapshotVisible ? blueprintBounds : null;
        AABB visibleSelectionBounds = snapshotVisible && value.selection() != null
                ? value.selection().bounds() : null;
        if (cells.isEmpty() && visibleBlueprintBounds == null
                && visibleSelectionBounds == null && !candidateVisible) {
            hoveredTarget = null;
            hoveredBlueprintBoundary = false;
            hoveredSelectionBoundary = false;
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
        hoveredTarget = raycastInteractive(origin, direction, visibleDistance, cells);
        Vec3 end = origin.add(direction.scale(visibleDistance));
        if (hoveredTarget == null && candidateVisible
                && new AABB(candidate.pos()).inflate(0.006D).clip(origin, end).isPresent()) {
            // The renderer already emphasizes hovered BLUEPRINT cells. A synthetic air cell
            // lets the Ctrl candidate use that exact path without adding it to a workflow.
            hoveredTarget = new Cell(candidate.pos(), Blocks.AIR.defaultBlockState(),
                    Kind.BLUEPRINT, false);
        }
        double selectionDistance = hoveredTarget == null && visibleSelectionBounds != null
                ? frameDistanceSqr(visibleSelectionBounds, origin, end) : Double.POSITIVE_INFINITY;
        double blueprintDistance = hoveredTarget == null && visibleBlueprintBounds != null
                ? frameDistanceSqr(visibleBlueprintBounds, origin, end) : Double.POSITIVE_INFINITY;
        hoveredSelectionBoundary = selectionDistance < Double.POSITIVE_INFINITY
                && selectionDistance <= blueprintDistance;
        hoveredBlueprintBoundary = blueprintDistance < Double.POSITIVE_INFINITY
                && blueprintDistance < selectionDistance;
    }

    @Nullable
    private static Cell raycastInteractive(Vec3 origin, Vec3 direction, double maximumDistance,
                                           Map<BlockPos, Cell> cells) {
        if (cells.isEmpty() || maximumDistance <= 0.0D) return null;
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
            Cell cell = cells.get(cursor);
            if (cell != null) return cell;
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

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double boundaryDistance(double origin, int cell, int step, double direction) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? cell + 1.0D : cell;
        return (boundary - origin) / direction;
    }

    private static double frameDistanceSqr(AABB box, Vec3 origin, Vec3 end) {
        final double radius = 0.045D;
        double nearest = Double.POSITIVE_INFINITY;
        double[] xs = { box.minX, box.maxX };
        double[] ys = { box.minY, box.maxY };
        double[] zs = { box.minZ, box.maxZ };
        for (double y : ys) for (double z : zs) {
            nearest = Math.min(nearest, clipDistanceSqr(new AABB(box.minX, y - radius, z - radius,
                    box.maxX, y + radius, z + radius), origin, end));
        }
        for (double x : xs) for (double z : zs) {
            nearest = Math.min(nearest, clipDistanceSqr(new AABB(x - radius, box.minY, z - radius,
                    x + radius, box.maxY, z + radius), origin, end));
        }
        for (double x : xs) for (double y : ys) {
            nearest = Math.min(nearest, clipDistanceSqr(new AABB(x - radius, y - radius, box.minZ,
                    x + radius, y + radius, box.maxZ), origin, end));
        }
        return nearest;
    }

    private static double clipDistanceSqr(AABB edge, Vec3 origin, Vec3 end) {
        return edge.clip(origin, end).map(origin::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
    }
}
