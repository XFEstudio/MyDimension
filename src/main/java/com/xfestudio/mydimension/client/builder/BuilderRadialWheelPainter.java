package com.xfestudio.mydimension.client.builder;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Texture-free, scanline-rasterized radial primitives for the builder HUD. */
final class BuilderRadialWheelPainter {
    private static final int GAP_PER_MILLE = 72;
    private static final Map<GeometryKey, List<Span>> SECTOR_CACHE = new HashMap<>();

    private BuilderRadialWheelPainter() {
    }

    static void drawSectors(GuiGraphics graphics, int centerX, int centerY, int innerRadius,
                            int outerRadius, int sectorCount, IntUnaryOperator colorForSector) {
        GeometryKey key = new GeometryKey(innerRadius, outerRadius, sectorCount, GAP_PER_MILLE);
        for (Span span : SECTOR_CACHE.computeIfAbsent(key, BuilderRadialWheelPainter::build)) {
            graphics.fill(centerX + span.fromX, centerY + span.y,
                    centerX + span.toX + 1, centerY + span.y + 1,
                    colorForSector.applyAsInt(span.sector));
        }
    }

    static void drawSector(GuiGraphics graphics, int centerX, int centerY, int innerRadius,
                           int outerRadius, int sectorCount, int selectedSector, int color) {
        GeometryKey key = new GeometryKey(innerRadius, outerRadius, sectorCount, GAP_PER_MILLE);
        for (Span span : SECTOR_CACHE.computeIfAbsent(key, BuilderRadialWheelPainter::build)) {
            if (span.sector == selectedSector) {
                graphics.fill(centerX + span.fromX, centerY + span.y,
                        centerX + span.toX + 1, centerY + span.y + 1, color);
            }
        }
    }

    static void drawDisc(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        int squaredRadius = radius * radius;
        for (int y = -radius; y <= radius; y++) {
            int x = (int) Math.floor(Math.sqrt(Math.max(0, squaredRadius - y * y)));
            graphics.fill(centerX - x, centerY + y, centerX + x + 1, centerY + y + 1, color);
        }
    }

    static void drawRing(GuiGraphics graphics, int centerX, int centerY, int innerRadius,
                         int outerRadius, int color) {
        GeometryKey key = new GeometryKey(innerRadius, outerRadius, 1, 0);
        for (Span span : SECTOR_CACHE.computeIfAbsent(key, BuilderRadialWheelPainter::build)) {
            graphics.fill(centerX + span.fromX, centerY + span.y,
                    centerX + span.toX + 1, centerY + span.y + 1, color);
        }
    }

    private static List<Span> build(GeometryKey key) {
        List<Span> spans = new ArrayList<>();
        int outerSquared = key.outerRadius * key.outerRadius;
        int innerSquared = key.innerRadius * key.innerRadius;
        for (int y = -key.outerRadius; y <= key.outerRadius; y++) {
            int runSector = -1;
            int runStart = 0;
            for (int x = -key.outerRadius; x <= key.outerRadius + 1; x++) {
                int sector = -1;
                if (x <= key.outerRadius) {
                    double sampleX = x + 0.5D;
                    double sampleY = y + 0.5D;
                    double distanceSquared = sampleX * sampleX + sampleY * sampleY;
                    if (distanceSquared <= outerSquared && distanceSquared >= innerSquared) {
                        sector = sectorAt(sampleX, sampleY, key);
                    }
                }
                if (sector != runSector) {
                    if (runSector >= 0) spans.add(new Span(y, runStart, x - 1, runSector));
                    runSector = sector;
                    runStart = x;
                }
            }
        }
        return List.copyOf(spans);
    }

    private static int sectorAt(double x, double y, GeometryKey key) {
        if (key.sectorCount == 1) return 0;
        double step = Math.PI * 2.0D / key.sectorCount;
        double position = (Math.atan2(y, x) + Math.PI / 2.0D) / step;
        long nearest = Math.round(position);
        double distanceFromCenter = Math.abs(position - nearest);
        double halfVisible = 0.5D - key.gapPerMille / 2_000.0D;
        if (distanceFromCenter > halfVisible) return -1;
        return Math.floorMod((int) nearest, key.sectorCount);
    }

    private record GeometryKey(int innerRadius, int outerRadius, int sectorCount, int gapPerMille) {
    }

    private record Span(int y, int fromX, int toX, int sector) {
    }
}
