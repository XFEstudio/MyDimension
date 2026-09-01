package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Small, allocation-free emitters shared by cached and dynamic previews. */
final class BuilderPreviewGeometry {
    private static final double DASH_LENGTH = 0.15D;
    private static final double DASH_GAP = 0.085D;
    private static final double WAVE_NORMAL_OFFSET = 0.006D;
    // Leaves a visible gap even around the 0.048-wide focused prism.
    private static final double WAVE_EDGE_INSET = 0.060D;

    private BuilderPreviewGeometry() {
    }

    static void emitBox(PoseStack.Pose pose, VertexConsumer consumer, AABB box,
                        BuilderPreviewState.Kind kind, double halfWidth, float alphaScale,
                        boolean dashed) {
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.minY, box.minZ,
                box.maxX, box.minY, box.minZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.maxY, box.minZ,
                box.maxX, box.maxY, box.minZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.minY, box.maxZ,
                box.maxX, box.minY, box.maxZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.maxY, box.maxZ,
                box.maxX, box.maxY, box.maxZ, kind, halfWidth, alphaScale, dashed);

        emitEdgeMaybeDashed(pose, consumer, box.minX, box.minY, box.minZ,
                box.minX, box.maxY, box.minZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.maxX, box.minY, box.minZ,
                box.maxX, box.maxY, box.minZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.minY, box.maxZ,
                box.minX, box.maxY, box.maxZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.maxX, box.minY, box.maxZ,
                box.maxX, box.maxY, box.maxZ, kind, halfWidth, alphaScale, dashed);

        emitEdgeMaybeDashed(pose, consumer, box.minX, box.minY, box.minZ,
                box.minX, box.minY, box.maxZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.maxX, box.minY, box.minZ,
                box.maxX, box.minY, box.maxZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.minX, box.maxY, box.minZ,
                box.minX, box.maxY, box.maxZ, kind, halfWidth, alphaScale, dashed);
        emitEdgeMaybeDashed(pose, consumer, box.maxX, box.maxY, box.minZ,
                box.maxX, box.maxY, box.maxZ, kind, halfWidth, alphaScale, dashed);
    }

    static void emitEdge(PoseStack.Pose pose, VertexConsumer consumer,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         BuilderPreviewState.Kind kind, double halfWidth, float alphaScale) {
        float alpha = kind.alpha() * alphaScale;
        if (x1 != x2) {
            halfWidth = separatedHalfWidth(halfWidth, 0, kind);
            // Four side faces form a real rectangular prism. The previous pair of
            // intersecting ribbons produced shader-pack TAA moire while moving.
            emitQuad(pose, consumer,
                    x1, y1 - halfWidth, z1 - halfWidth,
                    x2, y2 - halfWidth, z2 - halfWidth,
                    x2, y2 - halfWidth, z2 + halfWidth,
                    x1, y1 - halfWidth, z1 + halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1, y1 + halfWidth, z1 + halfWidth,
                    x2, y2 + halfWidth, z2 + halfWidth,
                    x2, y2 + halfWidth, z2 - halfWidth,
                    x1, y1 + halfWidth, z1 - halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1, y1 - halfWidth, z1 - halfWidth,
                    x1, y1 + halfWidth, z1 - halfWidth,
                    x2, y2 + halfWidth, z2 - halfWidth,
                    x2, y2 - halfWidth, z2 - halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1, y1 - halfWidth, z1 + halfWidth,
                    x2, y2 - halfWidth, z2 + halfWidth,
                    x2, y2 + halfWidth, z2 + halfWidth,
                    x1, y1 + halfWidth, z1 + halfWidth, kind, alpha);
        } else if (y1 != y2) {
            halfWidth = separatedHalfWidth(halfWidth, 1, kind);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1, z1 - halfWidth,
                    x1 - halfWidth, y2, z2 - halfWidth,
                    x1 - halfWidth, y2, z2 + halfWidth,
                    x1 - halfWidth, y1, z1 + halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1 + halfWidth, y1, z1 + halfWidth,
                    x1 + halfWidth, y2, z2 + halfWidth,
                    x1 + halfWidth, y2, z2 - halfWidth,
                    x1 + halfWidth, y1, z1 - halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1, z1 - halfWidth,
                    x1 + halfWidth, y1, z1 - halfWidth,
                    x1 + halfWidth, y2, z2 - halfWidth,
                    x1 - halfWidth, y2, z2 - halfWidth, kind, alpha);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1, z1 + halfWidth,
                    x1 - halfWidth, y2, z2 + halfWidth,
                    x1 + halfWidth, y2, z2 + halfWidth,
                    x1 + halfWidth, y1, z1 + halfWidth, kind, alpha);
        } else if (z1 != z2) {
            halfWidth = separatedHalfWidth(halfWidth, 2, kind);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1 - halfWidth, z1,
                    x1 - halfWidth, y1 - halfWidth, z2,
                    x1 - halfWidth, y1 + halfWidth, z2,
                    x1 - halfWidth, y1 + halfWidth, z1, kind, alpha);
            emitQuad(pose, consumer,
                    x1 + halfWidth, y1 + halfWidth, z1,
                    x1 + halfWidth, y1 + halfWidth, z2,
                    x1 + halfWidth, y1 - halfWidth, z2,
                    x1 + halfWidth, y1 - halfWidth, z1, kind, alpha);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1 - halfWidth, z1,
                    x1 + halfWidth, y1 - halfWidth, z1,
                    x1 + halfWidth, y1 - halfWidth, z2,
                    x1 - halfWidth, y1 - halfWidth, z2, kind, alpha);
            emitQuad(pose, consumer,
                    x1 - halfWidth, y1 + halfWidth, z1,
                    x1 - halfWidth, y1 + halfWidth, z2,
                    x1 + halfWidth, y1 + halfWidth, z2,
                    x1 + halfWidth, y1 + halfWidth, z1, kind, alpha);
        }
    }

    /**
     * Gives perpendicular/independently-coloured prisms a deterministic sub-pixel nesting order.
     * Their faces therefore meet instead of occupying the exact same depth at a corner, avoiding
     * the remaining junction shimmer without changing the visible nominal line weight.
     */
    private static double separatedHalfWidth(double nominal, int axis,
                                             BuilderPreviewState.Kind kind) {
        double axisStep = Math.min(0.00075D, nominal * 0.04D);
        double colorStep = Math.min(0.00020D, nominal * 0.004D);
        return nominal + axis * axisStep + kind.ordinal() * colorStep;
    }

    static void emitWaveCube(PoseStack.Pose pose, VertexConsumer consumer,
                             double x, double y, double z, int faceMask) {
        double maxX = x + 1.0D;
        double maxY = y + 1.0D;
        double maxZ = z + 1.0D;
        double innerMinX = x + WAVE_EDGE_INSET;
        double innerMinY = y + WAVE_EDGE_INSET;
        double innerMinZ = z + WAVE_EDGE_INSET;
        double innerMaxX = maxX - WAVE_EDGE_INSET;
        double innerMaxY = maxY - WAVE_EDGE_INSET;
        double innerMaxZ = maxZ - WAVE_EDGE_INSET;
        int alpha = 224;

        // Offset only along each face normal. Expanding the tangential axes made
        // neighboring coplanar faces overlap, which shimmered as the camera moved.
        if (hasFace(faceMask, Direction.NORTH)) {
            double plane = z - WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, innerMaxX, innerMinY, plane,
                    innerMinX, innerMinY, plane, innerMinX, innerMaxY, plane,
                    innerMaxX, innerMaxY, plane, alpha);
        }
        if (hasFace(faceMask, Direction.SOUTH)) {
            double plane = maxZ + WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, innerMinX, innerMinY, plane,
                    innerMaxX, innerMinY, plane, innerMaxX, innerMaxY, plane,
                    innerMinX, innerMaxY, plane, alpha);
        }
        if (hasFace(faceMask, Direction.WEST)) {
            double plane = x - WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, plane, innerMinY, innerMinZ,
                    plane, innerMinY, innerMaxZ, plane, innerMaxY, innerMaxZ,
                    plane, innerMaxY, innerMinZ, alpha);
        }
        if (hasFace(faceMask, Direction.EAST)) {
            double plane = maxX + WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, plane, innerMinY, innerMaxZ,
                    plane, innerMinY, innerMinZ, plane, innerMaxY, innerMinZ,
                    plane, innerMaxY, innerMaxZ, alpha);
        }
        if (hasFace(faceMask, Direction.DOWN)) {
            double plane = y - WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, innerMinX, plane, innerMinZ,
                    innerMaxX, plane, innerMinZ, innerMaxX, plane, innerMaxZ,
                    innerMinX, plane, innerMaxZ, alpha);
        }
        if (hasFace(faceMask, Direction.UP)) {
            double plane = maxY + WAVE_NORMAL_OFFSET;
            emitWaveQuad(pose, consumer, innerMinX, plane, innerMaxZ,
                    innerMaxX, plane, innerMaxZ, innerMaxX, plane, innerMinZ,
                    innerMinX, plane, innerMinZ, alpha);
        }
    }

    private static void emitEdgeMaybeDashed(PoseStack.Pose pose, VertexConsumer consumer,
                                            double x1, double y1, double z1,
                                            double x2, double y2, double z2,
                                            BuilderPreviewState.Kind kind, double halfWidth,
                                            float alphaScale, boolean dashed) {
        if (!dashed) {
            emitEdge(pose, consumer, x1, y1, z1, x2, y2, z2,
                    kind, halfWidth, alphaScale);
            return;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-7D) return;
        for (double start = 0.0D; start < length; start += DASH_LENGTH + DASH_GAP) {
            double end = Math.min(length, start + DASH_LENGTH);
            double first = start / length;
            double second = end / length;
            emitEdge(pose, consumer,
                    x1 + dx * first, y1 + dy * first, z1 + dz * first,
                    x1 + dx * second, y1 + dy * second, z1 + dz * second,
                    kind, halfWidth, alphaScale);
        }
    }

    private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 double x3, double y3, double z3,
                                 double x4, double y4, double z4,
                                 BuilderPreviewState.Kind kind, float alpha) {
        colorVertex(pose, consumer, x1, y1, z1, kind, alpha);
        colorVertex(pose, consumer, x2, y2, z2, kind, alpha);
        colorVertex(pose, consumer, x3, y3, z3, kind, alpha);
        colorVertex(pose, consumer, x4, y4, z4, kind, alpha);
    }

    private static void colorVertex(PoseStack.Pose pose, VertexConsumer consumer,
                                    double x, double y, double z,
                                    BuilderPreviewState.Kind kind, float alpha) {
        consumer.vertex(pose.pose(), (float) x, (float) y, (float) z)
                .color(kind.red(), kind.green(), kind.blue(), alpha)
                .endVertex();
    }

    private static void emitWaveQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     double x3, double y3, double z3,
                                     double x4, double y4, double z4,
                                     int alpha) {
        waveVertex(pose, consumer, x1, y1, z1, 0.0F, 0.0F, alpha);
        waveVertex(pose, consumer, x2, y2, z2, 1.0F, 0.0F, alpha);
        waveVertex(pose, consumer, x3, y3, z3, 1.0F, 1.0F, alpha);
        waveVertex(pose, consumer, x4, y4, z4, 0.0F, 1.0F, alpha);
    }

    private static void waveVertex(PoseStack.Pose pose, VertexConsumer consumer,
                                   double x, double y, double z, float u, float v, int alpha) {
        consumer.vertex(pose.pose(), (float) x, (float) y, (float) z)
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .endVertex();
    }

    private static boolean hasFace(int faceMask, Direction direction) {
        return (faceMask & 1 << direction.ordinal()) != 0;
    }
}
