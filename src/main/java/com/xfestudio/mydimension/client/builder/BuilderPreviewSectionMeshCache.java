package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent preview geometry grouped by 16x16x16 render sections.
 *
 * <p>Source cells are still admitted incrementally, but a section's line boxes
 * are expanded only when its static VBO is uploaded. Stable previews therefore
 * cost one draw per visible section instead of one CPU-side box expansion per
 * cell and frame. Ghost cells remain grouped here and are consumed through the
 * renderer's strict distance and frame budgets.</p>
 */
final class BuilderPreviewSectionMeshCache {
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;
    private static final int OUTLINES_PER_INCREMENTAL_UPLOAD = 128;
    private static final double DASH_LENGTH = 0.14D;
    private static final double DASH_GAP = 0.09D;

    @Nullable
    private BuilderPreviewState.Snapshot source;
    private int nextCell;
    private final Map<SectionKey, SectionMesh> sections = new LinkedHashMap<>();

    /** Advances CPU-side cache construction by at most {@code cellBudget} cells. */
    void advance(@Nullable BuilderPreviewState.Snapshot snapshot, int cellBudget) {
        synchronize(snapshot);
        if (source == null || cellBudget <= 0) return;

        List<BuilderPreviewState.Cell> cells = source.cells();
        int end = Math.min(cells.size(), nextCell + cellBudget);
        while (nextCell < end) {
            BuilderPreviewState.Cell cell = cells.get(nextCell++);
            SectionKey key = SectionKey.of(cell);
            sections.computeIfAbsent(key, SectionMesh::new).add(cell);
        }
    }

    /** Returns frustum-visible sections nearest-first. */
    List<SectionMesh> visibleSections(Frustum frustum, Vec3 camera) {
        List<SectionMesh> visible = new ArrayList<>();
        for (SectionMesh section : sections.values()) {
            if (frustum.isVisible(section.bounds())) visible.add(section);
        }
        visible.sort(Comparator.comparingDouble(section -> section.distanceToSqr(camera)));
        return visible;
    }

    /** Uploads at most {@code uploadBudget} dirty visible section VBOs. */
    void prepareVisibleSections(List<SectionMesh> visible, int uploadBudget) {
        if (uploadBudget <= 0) return;
        boolean sourceComplete = source != null && nextCell >= source.cells().size();
        int uploaded = 0;
        for (SectionMesh section : visible) {
            if (uploaded >= uploadBudget) break;
            if (section.uploadIfNeeded(sourceComplete)) uploaded++;
        }
    }

    void clear() {
        source = null;
        nextCell = 0;
        sections.values().forEach(SectionMesh::close);
        sections.clear();
    }

    private void synchronize(@Nullable BuilderPreviewState.Snapshot snapshot) {
        if (source == snapshot) return;
        clear();
        source = snapshot;
    }

    static final class SectionMesh {
        private final int originX;
        private final int originY;
        private final int originZ;
        private final AABB bounds;
        private final List<BuilderPreviewState.Cell> outlines = new ArrayList<>();
        private final EnumMap<BuilderPreviewState.Kind, List<BuilderPreviewState.Cell>> ghosts =
                new EnumMap<>(BuilderPreviewState.Kind.class);

        @Nullable private VertexBuffer outlineBuffer;
        private int uploadedOutlineCount;
        private boolean dirty;

        private SectionMesh(SectionKey key) {
            originX = key.x() * SECTION_SIZE;
            originY = key.y() * SECTION_SIZE;
            originZ = key.z() * SECTION_SIZE;
            bounds = new AABB(originX, originY, originZ,
                    originX + SECTION_SIZE, originY + SECTION_SIZE, originZ + SECTION_SIZE)
                    .inflate(0.01D);
        }

        private void add(BuilderPreviewState.Cell cell) {
            outlines.add(cell);
            dirty = true;
            if (cell.ghost()) {
                ghosts.computeIfAbsent(cell.kind(), ignored -> new ArrayList<>()).add(cell);
            }
        }

        private boolean uploadIfNeeded(boolean sourceComplete) {
            if (!dirty || outlines.isEmpty()) return false;
            int addedSinceUpload = outlines.size() - uploadedOutlineCount;
            if (outlineBuffer != null && !sourceComplete
                    && addedSinceUpload < OUTLINES_PER_INCREMENTAL_UPLOAD) return false;
            RenderSystem.assertOnRenderThread();

            BufferBuilder builder = new BufferBuilder(Math.max(256, outlines.size() * 24));
            builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            PoseStack identity = new PoseStack();
            for (BuilderPreviewState.Cell cell : outlines) {
                AABB local = cell.bounds().move(-originX, -originY, -originZ);
                if (cell.kind() == BuilderPreviewState.Kind.INVALID) {
                    emitDashedBox(builder, local, cell.kind());
                } else {
                    LevelRenderer.renderLineBox(identity, builder, local,
                            cell.kind().red(), cell.kind().green(), cell.kind().blue(), cell.kind().alpha());
                }
            }

            BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
            if (rendered == null) {
                dirty = false;
                uploadedOutlineCount = outlines.size();
                return false;
            }
            if (outlineBuffer == null || outlineBuffer.isInvalid()) {
                outlineBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            outlineBuffer.bind();
            outlineBuffer.upload(rendered);
            VertexBuffer.unbind();
            uploadedOutlineCount = outlines.size();
            dirty = false;
            return true;
        }

        AABB bounds() { return bounds; }

        List<BuilderPreviewState.Cell> ghosts(BuilderPreviewState.Kind kind) {
            return ghosts.getOrDefault(kind, List.of());
        }

        @Nullable VertexBuffer outlineBuffer() { return outlineBuffer; }
        int originX() { return originX; }
        int originY() { return originY; }
        int originZ() { return originZ; }

        double closestDistanceToSqr(Vec3 point) {
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
            VertexBuffer buffer = outlineBuffer;
            outlineBuffer = null;
            if (buffer == null || buffer.isInvalid()) return;
            if (RenderSystem.isOnRenderThread()) buffer.close();
            else RenderSystem.recordRenderCall(buffer::close);
        }

        private static double axisDistance(double value, double minimum, double maximum) {
            if (value < minimum) return minimum - value;
            if (value > maximum) return value - maximum;
            return 0.0D;
        }
    }

    private static void emitDashedBox(BufferBuilder builder, AABB box, BuilderPreviewState.Kind kind) {
        emitDashedEdge(builder, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, kind);
        emitDashedEdge(builder, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, kind);
        emitDashedEdge(builder, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, kind);
        emitDashedEdge(builder, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, kind);
        emitDashedEdge(builder, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, kind);
        emitDashedEdge(builder, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, kind);
        emitDashedEdge(builder, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, kind);
        emitDashedEdge(builder, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, kind);
        emitDashedEdge(builder, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, kind);
        emitDashedEdge(builder, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, kind);
        emitDashedEdge(builder, box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, kind);
        emitDashedEdge(builder, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, kind);
    }

    private static void emitDashedEdge(BufferBuilder builder,
                                       double x1, double y1, double z1,
                                       double x2, double y2, double z2,
                                       BuilderPreviewState.Kind kind) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-6D) return;
        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        for (double start = 0.0D; start < length; start += DASH_LENGTH + DASH_GAP) {
            double end = Math.min(length, start + DASH_LENGTH);
            emitSegment(builder,
                    x1 + nx * start, y1 + ny * start, z1 + nz * start,
                    x1 + nx * end, y1 + ny * end, z1 + nz * end,
                    nx, ny, nz, kind);
        }
    }

    private static void emitSegment(BufferBuilder builder,
                                    double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    float nx, float ny, float nz,
                                    BuilderPreviewState.Kind kind) {
        builder.vertex(x1, y1, z1).color(kind.red(), kind.green(), kind.blue(), kind.alpha())
                .normal(nx, ny, nz).endVertex();
        builder.vertex(x2, y2, z2).color(kind.red(), kind.green(), kind.blue(), kind.alpha())
                .normal(nx, ny, nz).endVertex();
    }

    private record SectionKey(int x, int y, int z) {
        private static SectionKey of(BuilderPreviewState.Cell cell) {
            return new SectionKey(cell.pos().getX() >> SECTION_SHIFT,
                    cell.pos().getY() >> SECTION_SHIFT,
                    cell.pos().getZ() >> SECTION_SHIFT);
        }
    }
}
