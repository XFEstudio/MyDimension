package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Progressive VBO cache for the one focused missing-material connected group. */
final class BuilderFocusedOutlineCache {
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;
    private static final int EDGES_PER_UPLOAD = 1024;
    private static final int UPLOADS_PER_FRAME = 2;
    private static final long UPLOAD_BUDGET_NANOS = 1_500_000L;
    private static final float FOCUS_ANIMATION_SECONDS = 0.16F;
    private static final Axis[] AXES = Axis.values();

    @Nullable private BuilderPreviewState.MissingGroup source;
    private final Map<SectionKey, FocusSection> sections = new LinkedHashMap<>();
    private List<FocusSection> visible = List.of();
    private boolean focusTarget;
    private float focusAmount;
    private long lastAnimationNanos;

    /**
     * Changes focus without destroying the previous VBO immediately. The maximum-width outline
     * fades over the always-present normal outline, giving even a 65k-cell group a smooth grow and
     * shrink transition without rebuilding all of its geometry every frame.
     */
    void synchronize(@Nullable BuilderPreviewState.MissingGroup group, long nowNanos) {
        if (group != null && source != group) {
            if (sameGroup(source, group)) {
                // Snapshot refreshes rebuild the immutable group object even when its cells did
                // not change. Keep the finished VBOs instead of flashing back to a thin outline.
                source = group;
            } else {
                clearGeometry();
                source = group;
                focusAmount = 0.0F;
                populate(group);
            }
        }
        focusTarget = group != null;
        advanceAnimation(nowNanos);
        if (!focusTarget && focusAmount <= 0.0F && source != null) {
            clearGeometry();
            source = null;
        }
    }

    private static boolean sameGroup(@Nullable BuilderPreviewState.MissingGroup first,
                                     BuilderPreviewState.MissingGroup second) {
        return first != null && first.bounds().equals(second.bounds())
                && first.cells().equals(second.cells());
    }

    private void populate(BuilderPreviewState.MissingGroup group) {
        if (group.cells().isEmpty()) return;

        // Group only lightweight cell references here. Edge expansion is deferred until a
        // section is actually visible, so moving the crosshair into a 65k-cell group cannot
        // allocate hundreds of thousands of edge objects in one frame.
        Map<SectionKey, List<BuilderPreviewState.Cell>> grouped = new LinkedHashMap<>();
        for (BuilderPreviewState.Cell cell : group.cells()) {
            SectionKey key = SectionKey.of(cell);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cell);
        }
        grouped.forEach((key, cells) -> sections.put(key,
                new FocusSection(key, List.copyOf(cells))));
    }

    private void advanceAnimation(long nowNanos) {
        if (lastAnimationNanos == 0L) {
            lastAnimationNanos = nowNanos;
            return;
        }
        float elapsed = Math.min(0.05F,
                Math.max(0.0F, (nowNanos - lastAnimationNanos) / 1_000_000_000.0F));
        lastAnimationNanos = nowNanos;
        float step = elapsed / FOCUS_ANIMATION_SECONDS;
        focusAmount = focusTarget
                ? Math.min(1.0F, focusAmount + step)
                : Math.max(0.0F, focusAmount - step);
    }

    void prepare(Frustum frustum, Vec3 camera, double maximumDistanceSqr) {
        List<FocusSection> current = new ArrayList<>();
        for (FocusSection section : sections.values()) {
            if (section.closestDistanceToSqr(camera) <= maximumDistanceSqr
                    && frustum.isVisible(section.bounds.inflate(1.5D))) {
                current.add(section);
            }
        }
        current.sort(Comparator.comparingDouble(section -> section.distanceToSqr(camera)));
        visible = List.copyOf(current);

        if (!focusTarget) return;
        int uploads = 0;
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        for (FocusSection section : current) {
            if (uploads >= UPLOADS_PER_FRAME) break;
            if (uploads > 0 && System.nanoTime() >= deadline) break;
            if (section.uploadNext()) uploads++;
        }
    }

    void draw(PoseStack poseStack, Matrix4f projection, RenderType renderType, float alpha) {
        float animatedAlpha = alpha * smoothFocusAmount();
        if (visible.isEmpty() || animatedAlpha <= 0.001F) return;
        renderType.setupRenderState();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, animatedAlpha);
        try {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return;
            for (FocusSection section : visible) {
                poseStack.pushPose();
                poseStack.translate(section.originX, section.originY, section.originZ);
                for (VertexBuffer buffer : section.buffers) {
                    if (buffer.isInvalid()) continue;
                    buffer.bind();
                    buffer.drawWithShader(poseStack.last().pose(), projection, shader);
                }
                poseStack.popPose();
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            renderType.clearRenderState();
        }
    }

    void clear() {
        clearGeometry();
        source = null;
        focusTarget = false;
        focusAmount = 0.0F;
        lastAnimationNanos = 0L;
    }

    private void clearGeometry() {
        visible = List.of();
        sections.values().forEach(FocusSection::close);
        sections.clear();
    }

    private float smoothFocusAmount() {
        return focusAmount * focusAmount * (3.0F - 2.0F * focusAmount);
    }

    private static final class FocusSection {
        private final int originX;
        private final int originY;
        private final int originZ;
        private final AABB bounds;
        private List<BuilderPreviewState.Cell> cells;
        @Nullable private LongList edges;
        private final List<VertexBuffer> buffers = new ArrayList<>();
        private int cursor;

        private FocusSection(SectionKey key, List<BuilderPreviewState.Cell> cells) {
            originX = key.x() * SECTION_SIZE;
            originY = key.y() * SECTION_SIZE;
            originZ = key.z() * SECTION_SIZE;
            bounds = new AABB(originX, originY, originZ,
                    originX + SECTION_SIZE, originY + SECTION_SIZE, originZ + SECTION_SIZE);
            this.cells = cells;
        }

        private boolean uploadNext() {
            ensureEdges();
            LongList currentEdges = edges;
            if (currentEdges == null) return false;
            if (cursor >= currentEdges.size()) return false;
            int end = Math.min(currentEdges.size(), cursor + EDGES_PER_UPLOAD);
            BufferBuilder builder = new BufferBuilder(Math.max(1024, (end - cursor) * 272));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            PoseStack.Pose identity = new PoseStack().last();
            for (int index = cursor; index < end; index++) {
                long packedEdge = currentEdges.getLong(index);
                int x = edgeX(packedEdge);
                int y = edgeY(packedEdge);
                int z = edgeZ(packedEdge);
                Axis axis = edgeAxis(packedEdge);
                double x2 = x + (axis == Axis.X ? 1.0D : 0.0D);
                double y2 = y + (axis == Axis.Y ? 1.0D : 0.0D);
                double z2 = z + (axis == Axis.Z ? 1.0D : 0.0D);
                BuilderPreviewGeometry.emitEdge(identity, builder,
                        x, y, z, x2, y2, z2,
                        BuilderPreviewState.Kind.MISSING,
                        BuilderPreviewRenderer.focusedOutlineWidth(), 1.0F);
            }
            cursor = end;
            BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
            if (rendered != null) {
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(rendered);
                VertexBuffer.unbind();
                buffers.add(buffer);
            }
            return true;
        }

        private void ensureEdges() {
            if (edges != null) return;
            LongOpenHashSet unique = new LongOpenHashSet(Math.max(16, cells.size() * 4));
            for (BuilderPreviewState.Cell cell : cells) {
                addCellEdges(unique, cell.pos().getX() - originX,
                        cell.pos().getY() - originY, cell.pos().getZ() - originZ);
            }
            edges = new LongArrayList(unique);
            cells = List.of();
        }

        private double closestDistanceToSqr(Vec3 point) {
            double dx = axisDistance(point.x, bounds.minX, bounds.maxX);
            double dy = axisDistance(point.y, bounds.minY, bounds.maxY);
            double dz = axisDistance(point.z, bounds.minZ, bounds.maxZ);
            return dx * dx + dy * dy + dz * dz;
        }

        private double distanceToSqr(Vec3 point) {
            double x = (bounds.minX + bounds.maxX) * 0.5D - point.x;
            double y = (bounds.minY + bounds.maxY) * 0.5D - point.y;
            double z = (bounds.minZ + bounds.maxZ) * 0.5D - point.z;
            return x * x + y * y + z * z;
        }

        private void close() {
            for (VertexBuffer buffer : buffers) {
                if (buffer.isInvalid()) continue;
                if (RenderSystem.isOnRenderThread()) buffer.close();
                else RenderSystem.recordRenderCall(buffer::close);
            }
            buffers.clear();
        }
    }

    private static void addCellEdges(LongOpenHashSet edges, int x, int y, int z) {
        edges.add(packEdge(x, y, z, Axis.X));
        edges.add(packEdge(x, y + 1, z, Axis.X));
        edges.add(packEdge(x, y, z + 1, Axis.X));
        edges.add(packEdge(x, y + 1, z + 1, Axis.X));
        edges.add(packEdge(x, y, z, Axis.Y));
        edges.add(packEdge(x + 1, y, z, Axis.Y));
        edges.add(packEdge(x, y, z + 1, Axis.Y));
        edges.add(packEdge(x + 1, y, z + 1, Axis.Y));
        edges.add(packEdge(x, y, z, Axis.Z));
        edges.add(packEdge(x + 1, y, z, Axis.Z));
        edges.add(packEdge(x, y + 1, z, Axis.Z));
        edges.add(packEdge(x + 1, y + 1, z, Axis.Z));
    }

    private static double axisDistance(double value, double minimum, double maximum) {
        if (value < minimum) return minimum - value;
        if (value > maximum) return value - maximum;
        return 0.0D;
    }

    private static long packEdge(int x, int y, int z, Axis axis) {
        return (long) x | (long) y << 5 | (long) z << 10 | (long) axis.ordinal() << 15;
    }

    private static int edgeX(long edge) { return (int) (edge & 31L); }
    private static int edgeY(long edge) { return (int) (edge >> 5 & 31L); }
    private static int edgeZ(long edge) { return (int) (edge >> 10 & 31L); }
    private static Axis edgeAxis(long edge) { return AXES[(int) (edge >> 15 & 3L)]; }

    private enum Axis { X, Y, Z }
    private record SectionKey(int x, int y, int z) {
        private static SectionKey of(BuilderPreviewState.Cell cell) {
            return new SectionKey(cell.pos().getX() >> SECTION_SHIFT,
                    cell.pos().getY() >> SECTION_SHIFT,
                    cell.pos().getZ() >> SECTION_SHIFT);
        }
    }
}
