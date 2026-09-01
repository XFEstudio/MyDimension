package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xfestudio.mydimension.MyDimension;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent builder-preview geometry grouped into 16x16x16 render sections.
 *
 * <p>Every section owns three static VBOs: shader-pack-safe quad outlines,
 * translucent block models, and the animated six-face rift veil. A new
 * snapshot reuses unchanged sections instead of discarding all GPU geometry;
 * this is important while a large blueprint is shrinking every server tick.</p>
 */
final class BuilderPreviewSectionMeshCache {
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;
    private static final double FRUSTUM_GUARD = 1.5D;
    private static final double OUTLINE_HALF_WIDTH = 0.010D;
    private static final int WAVE_CELLS_PER_UPLOAD = 256;
    private static final int MODEL_CELLS_PER_UPLOAD = 128;
    /** Prevents adjacent projected models from owning two exactly coplanar faces. */
    private static final float GHOST_MODEL_INSET = 0.0025F;
    private static final Set<ResourceLocation> WARNED_MODEL_TYPES = new HashSet<>();

    @Nullable
    private BuilderPreviewState.Snapshot source;
    private Map<SectionKey, SectionMesh> sections = new LinkedHashMap<>();

    /** Synchronizes lightweight cell groups; expensive geometry stays render-budgeted. */
    void advance(@Nullable BuilderPreviewState.Snapshot snapshot, int ignoredCellBudget) {
        synchronize(snapshot);
    }

    /** Returns in-range, frustum-visible sections nearest-first. */
    List<SectionMesh> visibleSections(net.minecraft.client.renderer.culling.Frustum frustum,
                                      Vec3 camera, double maximumDistanceSqr) {
        List<SectionMesh> visible = new ArrayList<>();
        for (SectionMesh section : sections.values()) {
            if (section.closestDistanceToSqr(camera) > maximumDistanceSqr) continue;
            if (frustum.isVisible(section.bounds().inflate(FRUSTUM_GUARD))) visible.add(section);
        }
        visible.sort(Comparator.comparingDouble(section -> section.distanceToSqr(camera)));
        return visible;
    }

    /** Uploads one dirty section, then continues only while both budgets allow. */
    void prepareVisibleSections(Minecraft minecraft, List<SectionMesh> visible, Vec3 camera,
                                double modelDistanceSqr, int uploadBudget, long deadlineNanos) {
        if (uploadBudget <= 0) return;
        int uploaded = 0;
        for (SectionMesh section : visible) {
            if (uploaded >= uploadBudget) break;
            if (uploaded > 0 && System.nanoTime() >= deadlineNanos) break;
            boolean buildModels = section.closestDistanceToSqr(camera) <= modelDistanceSqr;
            if (section.uploadNext(minecraft, buildModels)) uploaded++;
        }
    }

    void clear() {
        source = null;
        sections.values().forEach(SectionMesh::close);
        sections.clear();
    }

    private void synchronize(@Nullable BuilderPreviewState.Snapshot snapshot) {
        if (source == snapshot) return;
        if (snapshot == null) {
            clear();
            return;
        }
        if (source != null
                && source.dimension().equals(snapshot.dimension())
                && source.cells().equals(snapshot.cells())
                && source.blueprintPreview() == snapshot.blueprintPreview()) {
            source = snapshot;
            return;
        }

        boolean includeBuildGhosts = snapshot.blueprintPreview();

        Map<SectionKey, List<BuilderPreviewState.Cell>> grouped = new LinkedHashMap<>();
        LongSet missingGhostPositions = new LongOpenHashSet();
        for (BuilderPreviewState.Cell cell : snapshot.cells()) {
            grouped.computeIfAbsent(SectionKey.of(cell), ignored -> new ArrayList<>()).add(cell);
            if (isWaveCell(cell)) missingGhostPositions.add(cell.pos().asLong());
        }

        Map<SectionKey, SectionMesh> previous = sections;
        Map<SectionKey, SectionMesh> replacement = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<SectionKey, List<BuilderPreviewState.Cell>> entry : grouped.entrySet()) {
            SectionMesh existing = previous.remove(entry.getKey());
            if (existing != null && existing.matches(
                    entry.getValue(), missingGhostPositions, includeBuildGhosts)) {
                replacement.put(entry.getKey(), existing);
            } else {
                if (existing != null) existing.close();
                replacement.put(entry.getKey(), new SectionMesh(
                        entry.getKey(), entry.getValue(), missingGhostPositions,
                        includeBuildGhosts));
            }
        }
        previous.values().forEach(SectionMesh::close);
        previous.clear();
        sections = replacement;
        source = snapshot;
    }

    static final class SectionMesh {
        private final int originX;
        private final int originY;
        private final int originZ;
        private final AABB bounds;
        private final List<BuilderPreviewState.Cell> cells;
        private final List<WaveCell> waveCells;
        private final boolean includeBuildGhosts;
        private final boolean hasGhosts;

        @Nullable private VertexBuffer outlineBuffer;
        private final List<VertexBuffer> ghostBuffers = new ArrayList<>();
        private final List<VertexBuffer> waveBuffers = new ArrayList<>();
        private int ghostCursor;
        private int waveCursor;

        private SectionMesh(SectionKey key, List<BuilderPreviewState.Cell> cells,
                            LongSet missingGhostPositions, boolean includeBuildGhosts) {
            originX = key.x() * SECTION_SIZE;
            originY = key.y() * SECTION_SIZE;
            originZ = key.z() * SECTION_SIZE;
            bounds = new AABB(originX, originY, originZ,
                    originX + SECTION_SIZE, originY + SECTION_SIZE, originZ + SECTION_SIZE)
                    .inflate(0.02D);
            this.cells = List.copyOf(cells);
            this.includeBuildGhosts = includeBuildGhosts;
            waveCells = createWaveCells(cells, missingGhostPositions, originX, originY, originZ);
            // A manual surface BUILD is deliberately only a green wireframe. Blueprint placement
            // uses the same BUILD kind, but must retain the concrete block projection so the player
            // can inspect the copied palette before committing it.
            hasGhosts = cells.stream().anyMatch(cell -> isGhostCell(cell, includeBuildGhosts));
        }

        private boolean matches(List<BuilderPreviewState.Cell> replacement,
                                LongSet missingGhostPositions, boolean replacementBuildGhosts) {
            return includeBuildGhosts == replacementBuildGhosts
                    && cells.equals(replacement)
                    && waveCells.equals(createWaveCells(
                    replacement, missingGhostPositions, originX, originY, originZ));
        }

        private boolean uploadNext(Minecraft minecraft, boolean buildModels) {
            RenderSystem.assertOnRenderThread();
            if (outlineBuffer == null) {
                outlineBuffer = uploadOutlineBuffer();
                return true;
            }
            if (waveCursor < waveCells.size()) {
                int end = Math.min(waveCells.size(), waveCursor + WAVE_CELLS_PER_UPLOAD);
                VertexBuffer wave = uploadWaveBuffer(waveCursor, end);
                waveCursor = end;
                if (wave != null) waveBuffers.add(wave);
                return true;
            }
            if (hasGhosts && buildModels && ghostCursor < cells.size()) {
                int end = Math.min(cells.size(), ghostCursor + MODEL_CELLS_PER_UPLOAD);
                VertexBuffer ghost = uploadGhostBuffer(minecraft, ghostCursor, end);
                ghostCursor = end;
                if (ghost != null) ghostBuffers.add(ghost);
                return true;
            }
            return false;
        }

        @Nullable
        private VertexBuffer uploadOutlineBuffer() {
            Set<EdgeKey> edges = new HashSet<>(Math.max(16, cells.size() * 4));
            for (BuilderPreviewState.Cell cell : cells) {
                int x = cell.pos().getX() - originX;
                int y = cell.pos().getY() - originY;
                int z = cell.pos().getZ() - originZ;
                addCellEdges(edges, x, y, z, cell.kind());
            }
            BufferBuilder builder = new BufferBuilder(Math.max(512, edges.size() * 160));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            PoseStack.Pose identity = new PoseStack().last();
            for (EdgeKey edge : edges) {
                double x2 = edge.x() + (edge.axis() == Axis.X ? 1.0D : 0.0D);
                double y2 = edge.y() + (edge.axis() == Axis.Y ? 1.0D : 0.0D);
                double z2 = edge.z() + (edge.axis() == Axis.Z ? 1.0D : 0.0D);
                if (edge.kind() == BuilderPreviewState.Kind.INVALID) {
                    emitDashedEdge(identity, builder, edge.x(), edge.y(), edge.z(),
                            x2, y2, z2, edge.kind());
                } else {
                    BuilderPreviewGeometry.emitEdge(identity, builder,
                            edge.x(), edge.y(), edge.z(), x2, y2, z2,
                            edge.kind(), OUTLINE_HALF_WIDTH, 1.0F);
                }
            }
            return upload(builder);
        }

        @Nullable
        private VertexBuffer uploadGhostBuffer(Minecraft minecraft, int start, int end) {
            BufferBuilder builder = new BufferBuilder(Math.max(4096, (end - start) * 1024));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            PoseStack pose = new PoseStack();
            for (int index = start; index < end; index++) {
                BuilderPreviewState.Cell cell = cells.get(index);
                if (!isGhostCell(cell, includeBuildGhosts)) continue;
                pose.pushPose();
                pose.translate(cell.pos().getX() - originX + GHOST_MODEL_INSET,
                        cell.pos().getY() - originY + GHOST_MODEL_INSET,
                        cell.pos().getZ() - originZ + GHOST_MODEL_INSET);
                float modelScale = 1.0F - GHOST_MODEL_INSET * 2.0F;
                pose.scale(modelScale, modelScale, modelScale);
                VertexConsumer tinted = new GhostVertexConsumer(builder, cell.kind());
                MultiBufferSource singleBuffer = ignored -> tinted;
                try {
                    minecraft.getBlockRenderer().renderSingleBlock(cell.state(), pose, singleBuffer,
                            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY,
                            BuilderPreviewRenderTypes.ghostModel());
                } catch (RuntimeException exception) {
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(cell.state().getBlock());
                    if (blockId != null && WARNED_MODEL_TYPES.add(blockId)) {
                        MyDimension.LOGGER.warn("Skipping incompatible projected block model {}",
                                blockId, exception);
                    }
                }
                pose.popPose();
            }
            return upload(builder);
        }

        @Nullable
        private VertexBuffer uploadWaveBuffer(int start, int end) {
            BufferBuilder builder = new BufferBuilder(Math.max(2048, (end - start) * 640));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            PoseStack.Pose identity = new PoseStack().last();
            for (int index = start; index < end; index++) {
                WaveCell cell = waveCells.get(index);
                BuilderPreviewGeometry.emitWaveCube(identity, builder,
                        cell.x(), cell.y(), cell.z(), cell.faceMask());
            }
            return upload(builder);
        }

        AABB bounds() { return bounds; }
        @Nullable VertexBuffer outlineBuffer() { return outlineBuffer; }
        List<VertexBuffer> ghostBuffers() { return ghostBuffers; }
        List<VertexBuffer> waveBuffers() { return waveBuffers; }
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
            close(outlineBuffer);
            ghostBuffers.forEach(SectionMesh::close);
            waveBuffers.forEach(SectionMesh::close);
            outlineBuffer = null;
            ghostBuffers.clear();
            waveBuffers.clear();
        }

        private static void close(@Nullable VertexBuffer buffer) {
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

    @Nullable
    private static VertexBuffer upload(BufferBuilder builder) {
        BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
        if (rendered == null) return null;
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(rendered);
        VertexBuffer.unbind();
        return buffer;
    }

    private static void addCellEdges(Set<EdgeKey> edges, int x, int y, int z,
                                     BuilderPreviewState.Kind kind) {
        edges.add(new EdgeKey(x, y, z, Axis.X, kind));
        edges.add(new EdgeKey(x, y + 1, z, Axis.X, kind));
        edges.add(new EdgeKey(x, y, z + 1, Axis.X, kind));
        edges.add(new EdgeKey(x, y + 1, z + 1, Axis.X, kind));
        edges.add(new EdgeKey(x, y, z, Axis.Y, kind));
        edges.add(new EdgeKey(x + 1, y, z, Axis.Y, kind));
        edges.add(new EdgeKey(x, y, z + 1, Axis.Y, kind));
        edges.add(new EdgeKey(x + 1, y, z + 1, Axis.Y, kind));
        edges.add(new EdgeKey(x, y, z, Axis.Z, kind));
        edges.add(new EdgeKey(x + 1, y, z, Axis.Z, kind));
        edges.add(new EdgeKey(x, y + 1, z, Axis.Z, kind));
        edges.add(new EdgeKey(x + 1, y + 1, z, Axis.Z, kind));
    }

    private static void emitDashedEdge(PoseStack.Pose pose, VertexConsumer consumer,
                                       double x1, double y1, double z1,
                                       double x2, double y2, double z2,
                                       BuilderPreviewState.Kind kind) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        for (double start = 0.0D; start < length; start += 0.235D) {
            double end = Math.min(length, start + 0.15D);
            double first = start / length;
            double second = end / length;
            BuilderPreviewGeometry.emitEdge(pose, consumer,
                    x1 + dx * first, y1 + dy * first, z1 + dz * first,
                    x1 + dx * second, y1 + dy * second, z1 + dz * second,
                    kind, OUTLINE_HALF_WIDTH, 1.0F);
        }
    }

    private enum Axis { X, Y, Z }

    private record WaveCell(int x, int y, int z, int faceMask) { }

    private record EdgeKey(int x, int y, int z, Axis axis,
                           BuilderPreviewState.Kind kind) { }

    private record SectionKey(int x, int y, int z) {
        private static SectionKey of(BuilderPreviewState.Cell cell) {
            return new SectionKey(cell.pos().getX() >> SECTION_SHIFT,
                    cell.pos().getY() >> SECTION_SHIFT,
                    cell.pos().getZ() >> SECTION_SHIFT);
        }
    }

    private static boolean isWaveCell(BuilderPreviewState.Cell cell) {
        return cell.ghost() && cell.kind() == BuilderPreviewState.Kind.MISSING
                && !cell.state().isAir();
    }

    /** BUILD models are concrete only inside blueprint copy/deployment snapshots. */
    static boolean isGhostCell(BuilderPreviewState.Cell cell, boolean blueprintPreview) {
        return cell.ghost()
                && permitsGhostKind(cell.kind(), blueprintPreview)
                && !cell.state().isAir()
                && cell.state().getRenderShape() != RenderShape.INVISIBLE;
    }

    static boolean permitsGhostKind(BuilderPreviewState.Kind kind, boolean blueprintPreview) {
        return kind != BuilderPreviewState.Kind.BUILD || blueprintPreview;
    }

    private static List<WaveCell> createWaveCells(List<BuilderPreviewState.Cell> cells,
                                                   LongSet missingGhostPositions,
                                                   int originX, int originY, int originZ) {
        List<WaveCell> result = new ArrayList<>();
        for (BuilderPreviewState.Cell cell : cells) {
            if (!isWaveCell(cell)) continue;
            long packed = cell.pos().asLong();
            int faceMask = 0;
            for (Direction direction : Direction.values()) {
                if (!missingGhostPositions.contains(BlockPos.offset(packed, direction))) {
                    faceMask |= 1 << direction.ordinal();
                }
            }
            if (faceMask != 0) {
                result.add(new WaveCell(cell.pos().getX() - originX,
                        cell.pos().getY() - originY,
                        cell.pos().getZ() - originZ, faceMask));
            }
        }
        return List.copyOf(result);
    }

    /** Applies stable projection tint and alpha while retaining the block atlas UVs. */
    private static final class GhostVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        private GhostVertexConsumer(VertexConsumer delegate, BuilderPreviewState.Kind kind) {
            this.delegate = delegate;
            float mix = 0.18F;
            red = 1.0F - mix + kind.red() * mix;
            green = 1.0F - mix + kind.green() * mix;
            blue = 1.0F - mix + kind.blue() * mix;
            // Keep the material recognizable at a glance without making it indistinguishable
            // from a real placed block. These values remain translucent and shader-pack safe.
            alpha = kind == BuilderPreviewState.Kind.MISSING ? 0.72F : 0.62F;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(scale(r, red), scale(g, green), scale(b, blue), scale(a, alpha));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(scale(r, red), scale(g, green), scale(b, blue), scale(a, alpha));
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }

        private static int scale(int channel, float multiplier) {
            return Math.max(0, Math.min(255, Math.round(channel * multiplier)));
        }
    }
}
