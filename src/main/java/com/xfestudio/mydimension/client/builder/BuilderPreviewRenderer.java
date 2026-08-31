package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.util.List;

/** Renders selection frames, operation boxes, and translucent ghost blocks. */
public final class BuilderPreviewRenderer {
    private static final int MAX_GHOSTS_PER_KIND = 128;
    private static final int MAX_GHOSTS_PER_FRAME = 256;
    private static final double MAX_GHOST_DISTANCE_SQR = 64.0D * 64.0D;
    private static final long GHOST_RENDER_BUDGET_NANOS = 1_500_000L;
    private static final int SECTION_UPLOADS_PER_FRAME = 1;
    private static final int CELLS_PREPARED_PER_TICK = 2048;
    private static final int CELLS_PREPARED_PER_FRAME = 768;
    private static final BuilderPreviewSectionMeshCache SECTION_CACHE =
            new BuilderPreviewSectionMeshCache();

    private BuilderPreviewRenderer() {
    }

    /** Performs the larger half of progressive cache construction once per client tick. */
    public static void tick(Minecraft minecraft) {
        BuilderPreviewState.Snapshot snapshot = BuilderPreviewState.get().snapshot();
        if (!BuilderClientServices.isHoldingRealmwright(minecraft)
                || snapshot == null || minecraft.level == null
                || !minecraft.level.dimension().equals(snapshot.dimension())) {
            SECTION_CACHE.clear();
            return;
        }
        SECTION_CACHE.advance(snapshot, CELLS_PREPARED_PER_TICK);
    }

    /** Drops all section meshes, including on disconnect and resource reload. */
    public static void clearCache() {
        SECTION_CACHE.clear();
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!BuilderClientServices.isHoldingRealmwright(minecraft) || minecraft.level == null) {
            SECTION_CACHE.clear();
            return;
        }
        BuilderClientEvents.updateControlTargetForRender(minecraft);
        BuilderPreviewState.get().updateHoveredTarget(minecraft, event.getPartialTick(),
                Math.max(1, BuilderClientServices.snapshot().reach()));
        BuilderPreviewState.Snapshot snapshot = BuilderPreviewState.get().snapshot();
        if (snapshot != null && !minecraft.level.dimension().equals(snapshot.dimension())) {
            SECTION_CACHE.clear();
            snapshot = null;
        }
        BuilderPreviewState.Candidate candidate = BuilderPreviewState.get().controlCandidate();
        if (candidate != null && !minecraft.level.dimension().equals(candidate.dimension())) candidate = null;
        if (snapshot == null && candidate == null) return;

        if (snapshot != null) SECTION_CACHE.advance(snapshot, CELLS_PREPARED_PER_FRAME);
        Vec3 camera = event.getCamera().getPosition();
        List<BuilderPreviewSectionMeshCache.SectionMesh> visibleSections = snapshot == null
                ? List.of() : SECTION_CACHE.visibleSections(event.getFrustum(), camera);
        SECTION_CACHE.prepareVisibleSections(visibleSections, SECTION_UPLOADS_PER_FRAME);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        try {
            if (snapshot != null) {
                GhostBudget ghostBudget = new GhostBudget(System.nanoTime() + GHOST_RENDER_BUDGET_NANOS,
                        MAX_GHOSTS_PER_FRAME);
                renderGhosts(minecraft, poseStack, visibleSections,
                        BuilderPreviewState.Kind.MISSING, 0.45F, camera, ghostBudget);
                renderGhosts(minecraft, poseStack, visibleSections,
                        BuilderPreviewState.Kind.BLUEPRINT, 0.35F, camera, ghostBudget);
            }
            renderLines(minecraft, poseStack, event, snapshot, candidate, visibleSections);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            poseStack.popPose();
        }
    }

    private static void renderGhosts(Minecraft minecraft, PoseStack poseStack,
                                     List<BuilderPreviewSectionMeshCache.SectionMesh> visibleSections,
                                     BuilderPreviewState.Kind kind, float alpha, Vec3 camera,
                                     GhostBudget budget) {
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        RenderSystem.setShaderColor(kind.red(), kind.green(), kind.blue(), alpha);
        int rendered = 0;
        outer:
        for (BuilderPreviewSectionMeshCache.SectionMesh section : visibleSections) {
            if (budget.exhausted() || rendered >= MAX_GHOSTS_PER_KIND) break;
            if (section.closestDistanceToSqr(camera) > MAX_GHOST_DISTANCE_SQR) continue;
            for (BuilderPreviewState.Cell cell : section.ghosts(kind)) {
                if (cell.state().isAir()) continue;
                BlockPos pos = cell.pos();
                double dx = pos.getX() + 0.5D - camera.x;
                double dy = pos.getY() + 0.5D - camera.y;
                double dz = pos.getZ() + 0.5D - camera.z;
                if (dx * dx + dy * dy + dz * dz > MAX_GHOST_DISTANCE_SQR) continue;
                if (rendered >= MAX_GHOSTS_PER_KIND || !budget.tryConsume()) break outer;
                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                minecraft.getBlockRenderer().renderSingleBlock(cell.state(), poseStack, buffers,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY,
                        RenderType.translucent());
                poseStack.popPose();
                rendered++;
            }
        }
        buffers.endBatch(RenderType.translucent());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void renderLines(Minecraft minecraft, PoseStack poseStack,
                                    RenderLevelStageEvent event,
                                    @Nullable BuilderPreviewState.Snapshot snapshot,
                                    @Nullable BuilderPreviewState.Candidate candidate,
                                    List<BuilderPreviewSectionMeshCache.SectionMesh> visibleSections) {
        renderCachedOutlines(poseStack, event, visibleSections);

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        BuilderPreviewState.Selection selection = snapshot == null ? null : snapshot.selection();
        if (selection != null && selection.active()) {
            if (selection.first() != null
                    && event.getFrustum().isVisible(new AABB(selection.first()).inflate(0.006D))) {
                renderSelectionBox(poseStack, lines, selection.first(), 0.18F, 0.95F, 0.35F);
            }
            if (selection.second() != null
                    && event.getFrustum().isVisible(new AABB(selection.second()).inflate(0.006D))) {
                renderSelectionBox(poseStack, lines, selection.second(), 0.96F, 0.20F, 0.20F);
            }
            AABB bounds = selection.bounds();
            if (bounds != null && event.getFrustum().isVisible(bounds)) {
                LevelRenderer.renderLineBox(poseStack, lines, bounds.inflate(0.004D),
                        0.20F, 0.55F, 1.00F, 1.00F);
                if (BuilderPreviewState.get().isSelectionBoundaryHovered()) {
                    renderEmphasizedBounds(poseStack, lines, bounds,
                            BuilderPreviewState.Kind.BLUEPRINT);
                }
            }
        }
        AABB blueprintBounds = BuilderPreviewState.get().blueprintBounds();
        if (blueprintBounds != null && event.getFrustum().isVisible(blueprintBounds.inflate(0.008D))) {
            LevelRenderer.renderLineBox(poseStack, lines, blueprintBounds.inflate(0.004D),
                    BuilderPreviewState.Kind.BLUEPRINT.red(),
                    BuilderPreviewState.Kind.BLUEPRINT.green(),
                    BuilderPreviewState.Kind.BLUEPRINT.blue(), 1.0F);
            if (BuilderPreviewState.get().isBlueprintBoundaryHovered()) {
                renderEmphasizedBounds(poseStack, lines, blueprintBounds,
                        BuilderPreviewState.Kind.BLUEPRINT);
            }
        }
        if (candidate != null
                && event.getFrustum().isVisible(new AABB(candidate.pos()).inflate(0.008D))) {
            renderSelectionBox(poseStack, lines, candidate.pos(), 0.20F, 0.55F, 1.00F);
        }
        BuilderPreviewState.Cell hovered = BuilderPreviewState.get().hoveredTarget();
        if (hovered != null && event.getFrustum().isVisible(hovered.bounds().inflate(0.016D))) {
            renderEmphasizedBox(poseStack, lines, hovered);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** Draws each stable section outline with one persistent GPU buffer call. */
    private static void renderCachedOutlines(PoseStack poseStack, RenderLevelStageEvent event,
                                             List<BuilderPreviewSectionMeshCache.SectionMesh> sections) {
        ShaderInstance shader = GameRenderer.getRendertypeLinesShader();
        if (shader == null || sections.isEmpty()) return;
        RenderType lines = RenderType.lines();
        lines.setupRenderState();
        try {
            for (BuilderPreviewSectionMeshCache.SectionMesh section : sections) {
                VertexBuffer buffer = section.outlineBuffer();
                if (buffer == null || buffer.isInvalid()) continue;
                poseStack.pushPose();
                poseStack.translate(section.originX(), section.originY(), section.originZ());
                buffer.bind();
                buffer.drawWithShader(poseStack.last().pose(), event.getProjectionMatrix(), shader);
                poseStack.popPose();
            }
        } finally {
            VertexBuffer.unbind();
            lines.clearRenderState();
        }
    }

    private static void renderSelectionBox(PoseStack poseStack, VertexConsumer lines, BlockPos pos,
                                           float red, float green, float blue) {
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(0.006D),
                red, green, blue, 1.0F);
    }

    /** Multiple close shells make the crosshair-selected virtual frame visibly heavier. */
    private static void renderEmphasizedBox(PoseStack poseStack, VertexConsumer lines,
                                            BuilderPreviewState.Cell cell) {
        renderEmphasizedBounds(poseStack, lines, cell.bounds(), cell.kind());
    }

    private static void renderEmphasizedBounds(PoseStack poseStack, VertexConsumer lines,
                                               AABB box, BuilderPreviewState.Kind kind) {
        for (double inflation : new double[] { 0.004D, 0.009D, 0.014D }) {
            LevelRenderer.renderLineBox(poseStack, lines, box.inflate(inflation),
                    kind.red(), kind.green(), kind.blue(), 1.0F);
        }
    }

    private static final class GhostBudget {
        private final long deadlineNanos;
        private int remaining;

        private GhostBudget(long deadlineNanos, int remaining) {
            this.deadlineNanos = deadlineNanos;
            this.remaining = remaining;
        }

        private boolean tryConsume() {
            if (exhausted()) return false;
            remaining--;
            return true;
        }

        private boolean exhausted() {
            return remaining <= 0 || System.nanoTime() >= deadlineNanos;
        }
    }
}
