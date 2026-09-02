package com.xfestudio.mydimension.client.builder;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Texture-free pictograms used by the blueprint action wheel.
 *
 * <p>The icons deliberately avoid font glyphs: they remain legible with every
 * language/font pack and communicate the action directly.  Coordinates use a
 * small 13 by 13 design grid centred on the origin; the HUD may scale the grid
 * for the selected badge and for the larger centre preview.</p>
 */
final class BuilderWheelIconPainter {
    private BuilderWheelIconPainter() {
    }

    static void draw(GuiGraphics graphics, BlueprintAltActionController.Action action,
                     int centerX, int centerY, float scale, int color, int accentColor) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        switch (action) {
            case FLIP_X -> drawFlip(graphics, Axis.X, color, accentColor);
            case FLIP_Y -> drawFlip(graphics, Axis.Y, color, accentColor);
            case FLIP_Z -> drawFlip(graphics, Axis.Z, color, accentColor);
            case ROTATE_Y -> drawRotateY(graphics, color, accentColor);
            case OFFSET_X -> drawOffset(graphics, Axis.X, color, accentColor);
            case OFFSET_Y -> drawOffset(graphics, Axis.Y, color, accentColor);
            case OFFSET_Z -> drawOffset(graphics, Axis.Z, color, accentColor);
            case RESET -> drawReset(graphics, color, accentColor);
            case COPY_SELECTION -> drawCopySelection(graphics, color, accentColor);
            case SAVE -> drawSave(graphics, color, accentColor);
        }
        graphics.pose().popPose();
    }

    /** Two axis marks reflected around a clearly visible symmetry line. */
    private static void drawFlip(GuiGraphics graphics, Axis axis, int color, int accentColor) {
        drawAxisGlyph(graphics, axis, -4, 0, color);
        drawAxisGlyph(graphics, axis, 4, 0, color);
        fill(graphics, 0, -6, 0, 6, accentColor);
        fill(graphics, -1, -6, 1, -5, accentColor);
        fill(graphics, -1, 5, 1, 6, accentColor);
    }

    /** A clockwise orbit around the Y-axis mark. */
    private static void drawRotateY(GuiGraphics graphics, int color, int accentColor) {
        line(graphics, -3, -5, 3, -5, color);
        line(graphics, 3, -5, 5, -3, color);
        line(graphics, 5, -3, 5, 2, color);
        line(graphics, 5, 2, 3, 4, color);
        line(graphics, 5, 2, 2, 2, color);
        line(graphics, 5, 2, 5, -1, color);

        line(graphics, 2, 5, -3, 5, color);
        line(graphics, -3, 5, -5, 3, color);
        line(graphics, -5, 3, -5, -1, color);
        drawAxisGlyph(graphics, Axis.Y, 0, 0, accentColor);
    }

    /** A bidirectional arrow paired with an axis mark. */
    private static void drawOffset(GuiGraphics graphics, Axis axis, int color, int accentColor) {
        drawAxisGlyph(graphics, axis, -4, 0, accentColor);
        switch (axis) {
            case X -> {
                line(graphics, 0, 0, 6, 0, color);
                line(graphics, 0, 0, 2, -2, color);
                line(graphics, 0, 0, 2, 2, color);
                line(graphics, 6, 0, 4, -2, color);
                line(graphics, 6, 0, 4, 2, color);
            }
            case Y -> {
                line(graphics, 4, -6, 4, 6, color);
                line(graphics, 4, -6, 2, -4, color);
                line(graphics, 4, -6, 6, -4, color);
                line(graphics, 4, 6, 2, 4, color);
                line(graphics, 4, 6, 6, 4, color);
            }
            case Z -> {
                line(graphics, 0, 5, 6, -5, color);
                line(graphics, 0, 5, 0, 2, color);
                line(graphics, 0, 5, 3, 5, color);
                line(graphics, 6, -5, 3, -5, color);
                line(graphics, 6, -5, 6, -2, color);
            }
        }
    }

    /** Universal restore/reset symbol: a counter-clockwise return arrow. */
    private static void drawReset(GuiGraphics graphics, int color, int accentColor) {
        line(graphics, -5, -1, -5, -4, accentColor);
        line(graphics, -5, -4, -2, -4, accentColor);
        line(graphics, -5, -4, -3, -6, accentColor);
        line(graphics, -5, -4, -3, -2, accentColor);

        line(graphics, -2, -5, 2, -5, color);
        line(graphics, 2, -5, 5, -2, color);
        line(graphics, 5, -2, 5, 2, color);
        line(graphics, 5, 2, 2, 5, color);
        line(graphics, 2, 5, -2, 5, color);
        line(graphics, -2, 5, -4, 3, color);
    }

    /** Two overlapping selection frames with a small transfer arrow. */
    private static void drawCopySelection(GuiGraphics graphics, int color, int accentColor) {
        outline(graphics, -5, -5, 2, 2, accentColor);
        outline(graphics, -2, -2, 5, 5, color);

        line(graphics, -4, 5, 0, 5, accentColor);
        line(graphics, 0, 5, -2, 3, accentColor);
        line(graphics, 0, 5, -2, 6, accentColor);
    }

    /** Classic floppy-disk silhouette without relying on a texture atlas. */
    private static void drawSave(GuiGraphics graphics, int color, int accentColor) {
        line(graphics, -5, -6, 3, -6, color);
        line(graphics, 3, -6, 6, -3, color);
        line(graphics, 6, -3, 6, 6, color);
        line(graphics, 6, 6, -5, 6, color);
        line(graphics, -5, 6, -5, -6, color);

        outline(graphics, -2, -5, 3, -1, accentColor);
        fill(graphics, 1, -4, 2, -2, accentColor);
        outline(graphics, -3, 2, 3, 6, color);
        fill(graphics, -1, 3, 2, 3, accentColor);
    }

    private static void drawAxisGlyph(GuiGraphics graphics, Axis axis, int x, int y, int color) {
        switch (axis) {
            case X -> {
                line(graphics, x - 2, y - 3, x + 2, y + 3, color);
                line(graphics, x + 2, y - 3, x - 2, y + 3, color);
            }
            case Y -> {
                line(graphics, x - 2, y - 3, x, y, color);
                line(graphics, x + 2, y - 3, x, y, color);
                line(graphics, x, y, x, y + 3, color);
            }
            case Z -> {
                line(graphics, x - 2, y - 3, x + 2, y - 3, color);
                line(graphics, x + 2, y - 3, x - 2, y + 3, color);
                line(graphics, x - 2, y + 3, x + 2, y + 3, color);
            }
        }
    }

    private static void outline(GuiGraphics graphics, int left, int top, int right, int bottom,
                                int color) {
        fill(graphics, left, top, right, top, color);
        fill(graphics, left, bottom, right, bottom, color);
        fill(graphics, left, top, left, bottom, color);
        fill(graphics, right, top, right, bottom, color);
    }

    /** Inclusive integer line using a compact Bresenham rasterizer. */
    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int stepX = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int stepY = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            fill(graphics, x0, y0, x0, y0, color);
            if (x0 == x1 && y0 == y1) return;
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                x0 += stepX;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += stepY;
            }
        }
    }

    private static void fill(GuiGraphics graphics, int left, int top, int right, int bottom,
                             int color) {
        graphics.fill(left, top, right + 1, bottom + 1, color);
    }

    private enum Axis {
        X, Y, Z
    }
}
