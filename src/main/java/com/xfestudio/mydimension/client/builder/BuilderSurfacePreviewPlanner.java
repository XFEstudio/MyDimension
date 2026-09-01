package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.BuilderTags;
import com.xfestudio.mydimension.builder.SurfacePlaneTraversal;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/** Lightweight client prediction; the server still recomputes and authorizes every target. */
public final class BuilderSurfacePreviewPlanner {
    private static final int VALIDATION_CELLS_PER_TICK = 256;
    private static PreviewKey lastKey;
    private static boolean showingSurfacePreview;
    private static BuilderPreviewState.Snapshot lastPlannedSnapshot;
    private static List<BuilderPreviewState.Cell> lastCells = List.of();
    private static int validationCursor;
    private static Direction lastFace;
    private static BlockState lastOverride;

    private BuilderSurfacePreviewPlanner() { }

    public static void update(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return;
        BuilderPreviewState.Snapshot workflow = BuilderPreviewState.get().snapshot();
        if (workflow != null && (workflow.activeJobId() != null || workflow.blueprintPreview()
                || workflow.selection().active())) {
            reset();
            return;
        }
        BuilderClientSnapshot settings = BuilderClientServices.snapshot();
        HitResult picked = minecraft.player.pick(settings.reach(), 1.0F, false);
        if (!(picked instanceof BlockHitResult hit) || picked.getType() != HitResult.Type.BLOCK) {
            if (showingSurfacePreview) BuilderPreviewState.get().clear();
            reset();
            return;
        }
        BlockState override = minecraft.player.getOffhandItem().getItem() instanceof BlockItem blockItem
                ? blockItem.getBlock().defaultBlockState() : null;
        PreviewKey key = new PreviewKey(minecraft.level, hit.getBlockPos().immutable(), hit.getDirection(),
                minecraft.level.getBlockState(hit.getBlockPos()), settings.mode(), settings.surfaceMatch(),
                settings.buildLimit(), settings.demolishLimit(), override,
                minecraft.level.getBlockState(hit.getBlockPos().relative(hit.getDirection())));
        boolean snapshotReplaced = showingSurfacePreview && workflow != lastPlannedSnapshot;
        if (key.equals(lastKey) && !snapshotReplaced
                && !hasPreviewDrift(minecraft.level, key)) return;

        List<BuilderPreviewState.Cell> cells = plan(minecraft.level, hit, settings, override);
        BuilderPreviewState.Selection empty = new BuilderPreviewState.Selection(minecraft.level.dimension(),
                null, null);
        int revision = workflow == null ? 1 : workflow.revision() + 1;
        BuilderPreviewState.Snapshot planned = new BuilderPreviewState.Snapshot(
                minecraft.level.dimension(), cells, empty, null, false, false, revision);
        BuilderPreviewState.get().accept(planned);
        lastKey = key;
        showingSurfacePreview = true;
        lastPlannedSnapshot = planned;
        lastCells = cells;
        validationCursor = 0;
        lastFace = hit.getDirection();
        lastOverride = override;
    }

    public static void reset() {
        lastKey = null;
        showingSurfacePreview = false;
        lastPlannedSnapshot = null;
        lastCells = List.of();
        validationCursor = 0;
        lastFace = null;
        lastOverride = null;
    }

    private static List<BuilderPreviewState.Cell> plan(ClientLevel level, BlockHitResult hit,
                                                        BuilderClientSnapshot settings,
                                                        BlockState override) {
        BlockState seed = level.getBlockState(hit.getBlockPos());
        if (!SurfacePlaneTraversal.isReference(seed)
                || !SurfacePlaneTraversal.hasExposedReferenceFace(
                level, hit.getBlockPos(), hit.getDirection(), seed)) return List.of();
        BuilderMode mode = settings.mode();
        int limit = mode == BuilderMode.BUILD ? settings.buildLimit() : settings.demolishLimit();
        int scanLimit = Math.min(65_536, Math.max(limit, limit * 16));
        SurfacePlaneTraversal.Result traversal = SurfacePlaneTraversal.traverse(
                hit.getBlockPos(), hit.getDirection().getAxis(), scanLimit, limit,
                pos -> {
                    BlockState state = level.getBlockState(pos);
                    return SurfacePlaneTraversal.isReference(state)
                            && (settings.surfaceMatch() != SurfaceMatchMode.SAME_BLOCK
                            || state.getBlock() == seed.getBlock())
                            && SurfacePlaneTraversal.hasExposedReferenceFace(
                            level, pos, hit.getDirection(), state);
                }, ignored -> true);
        List<BuilderPreviewState.Cell> result = new ArrayList<>(traversal.nodes().size());
        for (SurfacePlaneTraversal.Node node : traversal.nodes()) {
            BlockState source = level.getBlockState(node.pos());
            if (mode == BuilderMode.DEMOLISH) {
                BuilderPreviewState.Kind kind = source.is(BuilderTags.CONSTRUCTION_PROTECTED)
                        || source.is(BuilderTags.TRANSACTION_UNSAFE)
                        ? BuilderPreviewState.Kind.INVALID : BuilderPreviewState.Kind.DEMOLISH;
                result.add(new BuilderPreviewState.Cell(node.pos(), source, kind, false));
            } else {
                BlockPos target = node.pos().relative(hit.getDirection());
                BlockState desired = override == null ? source : override;
                BlockState current = level.getBlockState(target);
                BuilderPreviewState.Kind kind = current.equals(desired)
                        ? BuilderPreviewState.Kind.BLUEPRINT
                        : current.canBeReplaced() && desired.canSurvive(level, target)
                        && !desired.is(BuilderTags.CONSTRUCTION_PROTECTED)
                        && !desired.is(BuilderTags.TRANSACTION_UNSAFE)
                        ? BuilderPreviewState.Kind.BUILD : BuilderPreviewState.Kind.INVALID;
                result.add(new BuilderPreviewState.Cell(target, desired, kind,
                        kind == BuilderPreviewState.Kind.BUILD));
            }
        }
        return List.copyOf(result);
    }

    /** Checks a bounded slice of the previous result and only reruns BFS after observable drift. */
    private static boolean hasPreviewDrift(ClientLevel level, PreviewKey key) {
        if (lastCells.isEmpty() || lastFace == null) return false;
        int checks = Math.min(VALIDATION_CELLS_PER_TICK, lastCells.size());
        for (int count = 0; count < checks; count++) {
            if (validationCursor >= lastCells.size()) validationCursor = 0;
            BuilderPreviewState.Cell cell = lastCells.get(validationCursor++);
            BlockState current = level.getBlockState(cell.pos());
            if (key.mode() == BuilderMode.DEMOLISH) {
                if (!SurfacePlaneTraversal.hasExposedReferenceFace(
                        level, cell.pos(), lastFace)) return true;
                BuilderPreviewState.Kind currentKind = current.is(BuilderTags.CONSTRUCTION_PROTECTED)
                        || current.is(BuilderTags.TRANSACTION_UNSAFE)
                        ? BuilderPreviewState.Kind.INVALID : BuilderPreviewState.Kind.DEMOLISH;
                if (!current.equals(cell.state()) || currentKind != cell.kind()) return true;
                continue;
            }

            BuilderPreviewState.Kind currentKind = current.equals(cell.state())
                    ? BuilderPreviewState.Kind.BLUEPRINT
                    : current.canBeReplaced() && cell.state().canSurvive(level, cell.pos())
                    && !cell.state().is(BuilderTags.CONSTRUCTION_PROTECTED)
                    && !cell.state().is(BuilderTags.TRANSACTION_UNSAFE)
                    ? BuilderPreviewState.Kind.BUILD : BuilderPreviewState.Kind.INVALID;
            if (currentKind != cell.kind()) return true;

            BlockState source = level.getBlockState(cell.pos().relative(lastFace.getOpposite()));
            if (!SurfacePlaneTraversal.hasExposedReferenceFace(
                    level, cell.pos().relative(lastFace.getOpposite()), lastFace)) return true;
            if (lastOverride == null) {
                if (!source.equals(cell.state())) return true;
            } else if (!SurfacePlaneTraversal.isReference(source)
                    || key.match() == SurfaceMatchMode.SAME_BLOCK
                    && source.getBlock() != key.seedState().getBlock()) {
                return true;
            }
        }
        return false;
    }

    private record PreviewKey(ClientLevel level, BlockPos seed, Direction face, BlockState seedState,
                              BuilderMode mode, SurfaceMatchMode match, int buildLimit,
                              int demolishLimit, BlockState override, BlockState targetSeedState) { }
}
