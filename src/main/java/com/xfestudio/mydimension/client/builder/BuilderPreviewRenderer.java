package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Renders cached projection models, rift veils, and shader-safe solid outlines. */
public final class BuilderPreviewRenderer {
    private static final int MINIMUM_PREVIEW_DISTANCE = 160;
    private static final int MAXIMUM_PREVIEW_DISTANCE = 512;
    private static final int FULL_MODEL_DISTANCE = 128;
    private static final int EXTRA_RENDER_SECTIONS = 4;
    private static final int SECTION_UPLOADS_PER_FRAME = 3;
    private static final long SECTION_UPLOAD_BUDGET_NANOS = 2_500_000L;
    private static final int CELLS_PREPARED_PER_TICK = 2048;
    private static final int CELLS_PREPARED_PER_FRAME = 768;
    private static final double NORMAL_OUTLINE_WIDTH = 0.012D;
    private static final double FOCUSED_OUTLINE_WIDTH = 0.048D;
    private static final BuilderPreviewSectionMeshCache SECTION_CACHE =
            new BuilderPreviewSectionMeshCache();
    private static final BuilderFocusedOutlineCache FOCUSED_OUTLINE_CACHE =
            new BuilderFocusedOutlineCache();
    private static final FocusOutlineAnimation DYNAMIC_FOCUS_ANIMATION =
            new FocusOutlineAnimation();

    private BuilderPreviewRenderer() {
    }

    public static void tick(Minecraft minecraft) {
        BuilderPreviewState.Snapshot snapshot = BuilderPreviewState.get().snapshot();
        if (!BuilderClientServices.isHoldingRealmwright(minecraft)
                || snapshot == null || minecraft.level == null
                || !minecraft.level.dimension().equals(snapshot.dimension())) {
            SECTION_CACHE.clear();
            FOCUSED_OUTLINE_CACHE.clear();
            DYNAMIC_FOCUS_ANIMATION.clear();
            return;
        }
        SECTION_CACHE.advance(snapshot, CELLS_PREPARED_PER_TICK);
    }

    public static void clearCache() {
        SECTION_CACHE.clear();
        FOCUSED_OUTLINE_CACHE.clear();
        DYNAMIC_FOCUS_ANIMATION.clear();
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        if (!BuilderClientServices.isHoldingRealmwright(minecraft) || minecraft.level == null) {
            SECTION_CACHE.clear();
            FOCUSED_OUTLINE_CACHE.clear();
            DYNAMIC_FOCUS_ANIMATION.clear();
            return;
        }

        BuilderClientEvents.updateControlTargetForRender(minecraft);
        BuilderPreviewState.get().updateHoveredTarget(minecraft, event.getPartialTick(),
                Math.max(1, BuilderClientServices.snapshot().reach()));
        BuilderPreviewState.Snapshot snapshot = BuilderPreviewState.get().snapshot();
        if (snapshot != null && !minecraft.level.dimension().equals(snapshot.dimension())) {
            SECTION_CACHE.clear();
            FOCUSED_OUTLINE_CACHE.clear();
            DYNAMIC_FOCUS_ANIMATION.clear();
            snapshot = null;
        }
        BuilderPreviewState.Candidate candidate = BuilderPreviewState.get().controlCandidate();
        if (candidate != null && !minecraft.level.dimension().equals(candidate.dimension())) {
            candidate = null;
        }
        if (snapshot == null && candidate == null
                && BuilderAnchorPreviewTracker.positions(minecraft.level).isEmpty()) return;

        SECTION_CACHE.advance(snapshot, CELLS_PREPARED_PER_FRAME);
        Vec3 camera = event.getCamera().getPosition();
        int previewDistance = previewDistance(minecraft);
        double previewDistanceSqr = (double) previewDistance * previewDistance;
        long uploadDeadline = System.nanoTime() + SECTION_UPLOAD_BUDGET_NANOS;
        int stagedUploads = SECTION_CACHE.preparePending(minecraft, camera,
                (double) FULL_MODEL_DISTANCE * FULL_MODEL_DISTANCE,
                SECTION_UPLOADS_PER_FRAME, uploadDeadline);
        BuilderPreviewState.Snapshot renderedSnapshot = SECTION_CACHE.renderSnapshot(snapshot);
        BuilderPreviewSectionMeshCache.ModelResidency modelResidency =
                SECTION_CACHE.modelResidency(camera,
                        (double) FULL_MODEL_DISTANCE * FULL_MODEL_DISTANCE);
        List<BuilderPreviewSectionMeshCache.SectionMesh> visibleSections = renderedSnapshot == null
                ? List.of() : SECTION_CACHE.visibleSections(
                        event.getFrustum(), camera, previewDistanceSqr);
        SECTION_CACHE.prepareVisibleSections(minecraft, modelResidency, camera,
                Math.max(0, SECTION_UPLOADS_PER_FRAME - stagedUploads), uploadDeadline);
        Set<BuilderPreviewSectionMeshCache.SectionKey> drawableGhostSections =
                modelResidency.drawableGhostSections();
        BuilderPreviewState.Focus focus = BuilderPreviewState.get().focus();
        if (focus != null && focus.kind() != BuilderPreviewState.FocusKind.CANDIDATE
                && !SECTION_CACHE.represents(snapshot)) {
            // The hit-test indexes already point at the newest immutable state, whereas section
            // VBOs deliberately keep rendering the previous complete generation during upload.
            // Do not draw a thick outline for an object that is not visible in that generation.
            BuilderPreviewState.get().clearHover();
            focus = null;
        }
        BuilderPreviewState.MissingGroup focusedMissingGroup = focus != null
                && focus.kind() == BuilderPreviewState.FocusKind.MISSING
                ? focus.missingGroup() : null;
        long animationTime = System.nanoTime();
        FOCUSED_OUTLINE_CACHE.synchronize(focusedMissingGroup, animationTime);
        DYNAMIC_FOCUS_ANIMATION.update(focus != null
                && focus.kind() != BuilderPreviewState.FocusKind.MISSING ? focus : null,
                animationTime);
        FOCUSED_OUTLINE_CACHE.prepare(event.getFrustum(), camera, previewDistanceSqr);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            drawCachedSections(poseStack, event, visibleSections,
                    BuilderPreviewRenderTypes.ghostModel(),
                    section -> section.ghostBuffers(drawableGhostSections),
                    true, 1.0F);
            drawSpecialGhostSections(poseStack, event, visibleSections,
                    drawableGhostSections);
            BuilderPreviewRenderTypes.updateWaveUniforms();
            drawCachedSections(poseStack, event, visibleSections,
                    BuilderPreviewRenderTypes.projectionWave(),
                    BuilderPreviewSectionMeshCache.SectionMesh::waveBuffers, true, 1.0F);

            // Draw the faint pass only where world geometry occludes the frame. Keeping it
            // off visible pixels avoids two coplanar outline passes under temporal shaders.
            drawCachedSections(poseStack, event, visibleSections,
                    BuilderPreviewRenderTypes.outlineXray(),
                    section -> section.outlineBuffer() == null
                            ? List.of() : List.of(section.outlineBuffer()), false, 0.16F);
            drawCachedSections(poseStack, event, visibleSections,
                    BuilderPreviewRenderTypes.outline(),
                    section -> section.outlineBuffer() == null
                            ? List.of() : List.of(section.outlineBuffer()), false, 1.0F);

            renderDynamicOutlines(minecraft, poseStack, renderedSnapshot, candidate,
                    camera, previewDistanceSqr, BuilderPreviewRenderTypes.outlineXray(), 0.16F);
            renderDynamicOutlines(minecraft, poseStack, renderedSnapshot, candidate,
                    camera, previewDistanceSqr, BuilderPreviewRenderTypes.outline(), 1.0F);
            FOCUSED_OUTLINE_CACHE.draw(poseStack, event.getProjectionMatrix(),
                    BuilderPreviewRenderTypes.outlineXray(), 0.16F);
            FOCUSED_OUTLINE_CACHE.draw(poseStack, event.getProjectionMatrix(),
                    BuilderPreviewRenderTypes.outline(), 1.0F);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            VertexBuffer.unbind();
            poseStack.popPose();
        }
    }

    /**
     * Draws captured block-entity projection geometry with the exact RenderType requested by its
     * renderer. This preserves non-block atlases such as the chest sheet while the explicit blend
     * state keeps the projection tint translucent even for entity-cutout render types.
     */
    private static void drawSpecialGhostSections(
            PoseStack poseStack,
            RenderLevelStageEvent event,
            List<BuilderPreviewSectionMeshCache.SectionMesh> sections,
            Set<BuilderPreviewSectionMeshCache.SectionKey> drawableSections) {
        if (sections.isEmpty()) return;
        try {
            for (int index = sections.size() - 1; index >= 0; index--) {
                BuilderPreviewSectionMeshCache.SectionMesh section = sections.get(index);
                List<BuilderPreviewSectionMeshCache.SpecialGhostBuffer> buffers =
                        section.specialGhostBuffers(drawableSections);
                if (buffers.isEmpty()) continue;
                poseStack.pushPose();
                poseStack.translate(section.originX(), section.originY(), section.originZ());
                try {
                    for (BuilderPreviewSectionMeshCache.SpecialGhostBuffer special : buffers) {
                        VertexBuffer buffer = special.buffer();
                        if (buffer == null || buffer.isInvalid()) continue;
                        RenderType renderType = special.renderType();
                        renderType.setupRenderState();
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        try {
                            ShaderInstance shader = RenderSystem.getShader();
                            if (shader == null) continue;
                            buffer.bind();
                            buffer.drawWithShader(poseStack.last().pose(),
                                    event.getProjectionMatrix(), shader);
                        } finally {
                            VertexBuffer.unbind();
                            renderType.clearRenderState();
                        }
                    }
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    private static void drawCachedSections(PoseStack poseStack, RenderLevelStageEvent event,
                                           List<BuilderPreviewSectionMeshCache.SectionMesh> sections,
                                           RenderType renderType,
                                           Function<BuilderPreviewSectionMeshCache.SectionMesh,
                                                   List<VertexBuffer>> bufferGetter,
                                           boolean farToNear, float alpha) {
        if (sections.isEmpty()) return;
        renderType.setupRenderState();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        try {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return;
            int start = farToNear ? sections.size() - 1 : 0;
            int end = farToNear ? -1 : sections.size();
            int step = farToNear ? -1 : 1;
            for (int index = start; index != end; index += step) {
                BuilderPreviewSectionMeshCache.SectionMesh section = sections.get(index);
                poseStack.pushPose();
                poseStack.translate(section.originX(), section.originY(), section.originZ());
                for (VertexBuffer buffer : bufferGetter.apply(section)) {
                    if (buffer == null || buffer.isInvalid()) continue;
                    buffer.bind();
                    buffer.drawWithShader(poseStack.last().pose(), event.getProjectionMatrix(), shader);
                }
                poseStack.popPose();
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            renderType.clearRenderState();
        }
    }

    private static void renderDynamicOutlines(Minecraft minecraft, PoseStack poseStack,
                                              @Nullable BuilderPreviewState.Snapshot snapshot,
                                              @Nullable BuilderPreviewState.Candidate candidate,
                                              Vec3 camera, double maximumDistanceSqr,
                                              RenderType renderType, float alphaScale) {
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(renderType);
        PoseStack.Pose pose = poseStack.last();

        for (BlockPos anchor : BuilderAnchorPreviewTracker.positions(minecraft.level)) {
            AABB bounds = new AABB(anchor).inflate(0.008D);
            if (withinDistance(bounds, camera, maximumDistanceSqr)) {
                BuilderPreviewGeometry.emitBox(pose, quads, bounds,
                        BuilderPreviewState.Kind.ANCHOR, NORMAL_OUTLINE_WIDTH,
                        alphaScale, false);
            }
        }

        BuilderPreviewState.Selection selection = snapshot == null ? null : snapshot.selection();
        if (selection != null && selection.active()) {
            if (selection.first() != null) {
                AABB bounds = new AABB(selection.first()).inflate(0.006D);
                if (withinDistance(bounds, camera, maximumDistanceSqr)) {
                    BuilderPreviewGeometry.emitBox(pose, quads, bounds,
                            BuilderPreviewState.Kind.BUILD, NORMAL_OUTLINE_WIDTH,
                            alphaScale, false);
                }
            }
            if (selection.second() != null) {
                AABB bounds = new AABB(selection.second()).inflate(0.006D);
                if (withinDistance(bounds, camera, maximumDistanceSqr)) {
                    BuilderPreviewGeometry.emitBox(pose, quads, bounds,
                            BuilderPreviewState.Kind.DEMOLISH, NORMAL_OUTLINE_WIDTH,
                            alphaScale, false);
                }
            }
            AABB bounds = selection.bounds();
            if (bounds != null && withinDistance(bounds, camera, maximumDistanceSqr)) {
                BuilderPreviewGeometry.emitBox(pose, quads, bounds.inflate(0.004D),
                        BuilderPreviewState.Kind.BLUEPRINT, NORMAL_OUTLINE_WIDTH,
                        alphaScale, false);
            }
        }

        AABB blueprintBounds = blueprintBounds(snapshot);
        if (blueprintBounds != null && withinDistance(blueprintBounds, camera, maximumDistanceSqr)) {
            BuilderPreviewGeometry.emitBox(pose, quads, blueprintBounds.inflate(0.004D),
                    BuilderPreviewState.Kind.BLUEPRINT, NORMAL_OUTLINE_WIDTH,
                    alphaScale, false);
        }
        if (candidate != null) {
            AABB bounds = new AABB(candidate.pos()).inflate(0.006D);
            if (withinDistance(bounds, camera, maximumDistanceSqr)) {
                BuilderPreviewGeometry.emitBox(pose, quads, bounds,
                        BuilderPreviewState.Kind.BLUEPRINT, NORMAL_OUTLINE_WIDTH,
                        alphaScale, false);
            }
        }

        BuilderPreviewState.Focus focus = DYNAMIC_FOCUS_ANIMATION.renderedFocus();
        float focusAmount = DYNAMIC_FOCUS_ANIMATION.smoothAmount();
        if (focus != null && focusAmount > 0.001F
                && withinDistance(focus.bounds(), camera, maximumDistanceSqr)) {
            BuilderPreviewState.Kind color = focus.kind() == BuilderPreviewState.FocusKind.MISSING
                    ? BuilderPreviewState.Kind.MISSING : BuilderPreviewState.Kind.BLUEPRINT;
            double animatedWidth = Mth.lerp(focusAmount,
                    NORMAL_OUTLINE_WIDTH, FOCUSED_OUTLINE_WIDTH);
            BuilderPreviewGeometry.emitBox(pose, quads, focus.bounds(),
                    color, animatedWidth, alphaScale * focusAmount, false);
        }
        buffers.endBatch(renderType);
    }

    private static boolean withinDistance(AABB bounds, Vec3 point, double maximumDistanceSqr) {
        double x = Mth.clamp(point.x, bounds.minX, bounds.maxX) - point.x;
        double y = Mth.clamp(point.y, bounds.minY, bounds.maxY) - point.y;
        double z = Mth.clamp(point.z, bounds.minZ, bounds.maxZ) - point.z;
        return x * x + y * y + z * z <= maximumDistanceSqr;
    }

    @Nullable
    private static AABB blueprintBounds(@Nullable BuilderPreviewState.Snapshot snapshot) {
        if (snapshot == null || !snapshot.blueprintPreview() || snapshot.cells().isEmpty()) return null;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BuilderPreviewState.Cell cell : snapshot.cells()) {
            minX = Math.min(minX, cell.pos().getX());
            minY = Math.min(minY, cell.pos().getY());
            minZ = Math.min(minZ, cell.pos().getZ());
            maxX = Math.max(maxX, cell.pos().getX());
            maxY = Math.max(maxY, cell.pos().getY());
            maxZ = Math.max(maxZ, cell.pos().getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    private static int previewDistance(Minecraft minecraft) {
        int renderSections = minecraft.options.getEffectiveRenderDistance() + EXTRA_RENDER_SECTIONS;
        return Mth.clamp(renderSections * 16,
                MINIMUM_PREVIEW_DISTANCE, MAXIMUM_PREVIEW_DISTANCE);
    }

    static double focusedOutlineWidth() {
        return FOCUSED_OUTLINE_WIDTH;
    }

    /** Smoothly interpolates the handful of dynamic selection/deployment focus boxes. */
    private static final class FocusOutlineAnimation {
        private static final float DURATION_SECONDS = 0.16F;

        @Nullable private BuilderPreviewState.Focus renderedFocus;
        private boolean targetFocused;
        private float amount;
        private long lastNanos;

        private void update(@Nullable BuilderPreviewState.Focus target, long nowNanos) {
            if (target != null && !sameTarget(renderedFocus, target)) {
                renderedFocus = target;
                amount = 0.0F;
            }
            targetFocused = target != null;
            if (lastNanos == 0L) {
                lastNanos = nowNanos;
                return;
            }
            float elapsed = Math.min(0.05F,
                    Math.max(0.0F, (nowNanos - lastNanos) / 1_000_000_000.0F));
            lastNanos = nowNanos;
            float step = elapsed / DURATION_SECONDS;
            amount = targetFocused
                    ? Math.min(1.0F, amount + step)
                    : Math.max(0.0F, amount - step);
            if (!targetFocused && amount <= 0.0F) renderedFocus = null;
        }

        private void clear() {
            renderedFocus = null;
            targetFocused = false;
            amount = 0.0F;
            lastNanos = 0L;
        }

        @Nullable
        private BuilderPreviewState.Focus renderedFocus() {
            return renderedFocus;
        }

        private float smoothAmount() {
            return amount * amount * (3.0F - 2.0F * amount);
        }

        private static boolean sameTarget(@Nullable BuilderPreviewState.Focus first,
                                          BuilderPreviewState.Focus second) {
            return first != null && first.kind() == second.kind()
                    && first.bounds().equals(second.bounds());
        }
    }
}
