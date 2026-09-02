package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/** Compact mode/progress HUD plus the Alt blueprint action wheel. */
public final class BuilderHudRenderer {
    private BuilderHudRenderer() {
    }

    public static void render(GuiGraphics graphics, Window window,
                              BlueprintAltActionController controller) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean holdingScepter = BuilderClientServices.isHoldingRealmwright(minecraft);
        boolean blueprintWorkflow = BuilderPreviewState.get().hasBlueprintWheelActions();
        controller.updateVisibility(Screen.hasAltDown(), minecraft.screen == null
                && holdingScepter && blueprintWorkflow);
        if (minecraft.options.hideGui || !holdingScepter) {
            return;
        }
        BuilderClientSnapshot snapshot = BuilderClientServices.snapshot();
        int modeColor = snapshot.mode() == com.xfestudio.mydimension.builder.BuilderMode.BUILD
                ? 0xFF39D46A : 0xFFF04B45;
        Component mode = Component.translatable(snapshot.mode()
                == com.xfestudio.mydimension.builder.BuilderMode.BUILD
                ? "hud.mydimension.realmwright.build" : "hud.mydimension.realmwright.demolish");
        graphics.fill(7, 7, 15 + minecraft.font.width(mode), 23, 0xA0101620);
        graphics.fill(7, 7, 10, 23, modeColor);
        graphics.drawString(minecraft.font, mode, 13, 11, 0xFFFFFFFF, false);

        if (snapshot.totalBlocks() > 0) {
            String progress = snapshot.completedBlocks() + " / " + snapshot.totalBlocks();
            graphics.drawString(minecraft.font, progress, 8, 27, 0xFFE7EDF7, true);
        }

        if (controller.phase() != BlueprintAltActionController.Phase.CLOSED && blueprintWorkflow) {
            renderActionWheel(graphics, window, controller);
        }
    }

    private static void renderActionWheel(GuiGraphics graphics, Window window,
                                          BlueprintAltActionController controller) {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = window.getGuiScaledWidth();
        int screenHeight = window.getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int minimumDimension = Math.min(screenWidth, screenHeight);
        int outerRadius = Mth.clamp(minimumDimension / 3, 44, 76);
        outerRadius = Math.min(outerRadius, Math.max(32, (screenHeight - 24) / 2));
        int innerRadius = Mth.clamp(Mth.floor(outerRadius * 0.46F), 22, 35);
        int crosshairClearance = Math.min(26, Math.max(16, innerRadius - 7));
        int centerY = Mth.clamp(screenHeight / 2 + crosshairClearance,
                outerRadius + 7, Math.max(outerRadius + 7, screenHeight - outerRadius - 7));
        List<BlueprintAltActionController.Action> actions = controller.actions();
        BlueprintAltActionController.Action active = controller.phase()
                == BlueprintAltActionController.Phase.ADJUSTING
                ? controller.adjusting() : controller.highlighted();
        int selectedIndex = Math.max(0, actions.indexOf(active));
        boolean adjusting = controller.phase() == BlueprintAltActionController.Phase.ADJUSTING;

        // Rift-like outer aura and an enlarged selected wedge sit behind the opaque wheel sectors.
        BuilderRadialWheelPainter.drawRing(graphics, centerX, centerY, outerRadius + 1,
                outerRadius + 4, 0x284FE6D2);
        BuilderRadialWheelPainter.drawRing(graphics, centerX, centerY, outerRadius - 1,
                outerRadius + 1, 0x7A8A61DC);
        BuilderRadialWheelPainter.drawSector(graphics, centerX, centerY, innerRadius - 2,
                outerRadius + 3, actions.size(), selectedIndex, adjusting ? 0x664DE7D1 : 0x5A9469EF);
        BuilderRadialWheelPainter.drawSectors(graphics, centerX, centerY, innerRadius, outerRadius,
                actions.size(), index -> {
                    if (index == selectedIndex) return adjusting ? 0xE03C7F91 : 0xDF594AA0;
                    return (index & 1) == 0 ? 0xBD21152F : 0xBD29183A;
                });
        BuilderRadialWheelPainter.drawSector(graphics, centerX, centerY, innerRadius + 2,
                outerRadius - 2, actions.size(), selectedIndex, adjusting ? 0x6B61E9D4 : 0x5F9C72F2);
        BuilderRadialWheelPainter.drawRing(graphics, centerX, centerY, innerRadius - 1,
                innerRadius + 1, adjusting ? 0xB855E4D1 : 0xA68D69E8);

        int labelRadius = (innerRadius + outerRadius) / 2;
        int pulse = 1 + Mth.floor((Math.sin(System.nanoTime() / 180_000_000.0D) + 1.0D) * 0.75D);
        for (int index = 0; index < actions.size(); index++) {
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * index / actions.size());
            boolean selected = index == selectedIndex;
            int radialOffset = selected ? 3 : 0;
            int x = centerX + Mth.floor(Math.cos(angle) * (labelRadius + radialOffset));
            int y = centerY + Mth.floor(Math.sin(angle) * (labelRadius + radialOffset));
            int badgeRadius = selected ? 11 + pulse : 8;
            if (selected) {
                BuilderRadialWheelPainter.drawDisc(graphics, x, y, badgeRadius + 4,
                        adjusting ? 0x4559E6D2 : 0x42966EF0);
            }
            BuilderRadialWheelPainter.drawDisc(graphics, x, y, badgeRadius,
                    selected ? 0xE0192036 : 0xC0181324);
            BuilderRadialWheelPainter.drawRing(graphics, x, y, badgeRadius - 1, badgeRadius + 1,
                    selected ? (adjusting ? 0xFF67E9D5 : 0xFFC09AFF) : 0xA35D5077);
            BuilderWheelIconPainter.draw(graphics, actions.get(index), x, y,
                    selected ? 0.90F : 0.72F,
                    selected ? 0xFFFFFFFF : 0xFFD2CBE2,
                    selected ? (adjusting ? 0xFF6EF5DE : 0xFFD2AFFF) : 0xFFA58EC5);
        }

        // No action labels: the centre repeats the active pictogram at a larger scale.  In
        // adjustment mode, opposing chevrons communicate that the wheel changes its value.
        BuilderRadialWheelPainter.drawDisc(graphics, centerX, centerY, innerRadius - 4, 0xB0181027);
        BuilderRadialWheelPainter.drawRing(graphics, centerX, centerY, innerRadius - 5,
                innerRadius - 3, adjusting ? 0xB861E9D4 : 0x9A8D69E8);
        BuilderWheelIconPainter.draw(graphics, active, centerX, centerY - 2, 1.12F,
                0xFFFFFFFF, adjusting ? 0xFF67E9D5 : 0xFFC9A8FF);
        if (adjusting) {
            drawChevron(graphics, centerX - innerRadius + 7, centerY - 2, false, 0xFF73EAD7);
            drawChevron(graphics, centerX + innerRadius - 7, centerY - 2, true, 0xFF73EAD7);
        }

        // One dot per action preserves position feedback without textual page numbers.
        int indicatorY = centerY + innerRadius - 9;
        int indicatorSpacing = 3;
        int indicatorStart = centerX - (actions.size() - 1) * indicatorSpacing / 2;
        for (int index = 0; index < actions.size(); index++) {
            int dotX = indicatorStart + index * indicatorSpacing;
            int dotRadius = index == selectedIndex ? 1 : 0;
            BuilderRadialWheelPainter.drawDisc(graphics, dotX, indicatorY, dotRadius,
                    index == selectedIndex
                            ? (adjusting ? 0xFF65E9D5 : 0xFFC5A1FF)
                            : 0x8E78688E);
        }

        // Keep the sectors pictogram-only, but identify the one focused action in a compact
        // tooltip outside the wheel.  This is intentionally not drawn in the centre: the icon
        // and adjustment chevrons remain readable, and the crosshair area stays unobstructed.
        renderSelectedActionHint(graphics, minecraft, screenWidth, screenHeight, centerX, centerY,
                outerRadius, active, adjusting);
    }

    private static void renderSelectedActionHint(GuiGraphics graphics, Minecraft minecraft,
                                                  int screenWidth, int screenHeight,
                                                  int centerX, int centerY, int outerRadius,
                                                  BlueprintAltActionController.Action active,
                                                  boolean adjusting) {
        Component label = Component.translatable(active.translationKey());
        int textWidth = minecraft.font.width(label);
        int panelWidth = Math.min(screenWidth - 12, Math.max(72, textWidth + 24));
        int panelHeight = 17;
        int preferredTop = centerY + outerRadius + 8;
        int top = preferredTop + panelHeight <= screenHeight - 4
                ? preferredTop
                : centerY - outerRadius - panelHeight - 8;
        int left = Mth.clamp(centerX - panelWidth / 2, 6, Math.max(6, screenWidth - panelWidth - 6));
        int right = left + panelWidth;

        int border = adjusting ? 0xD85DE6D2 : 0xD2A77BF2;
        int innerAccent = adjusting ? 0x6056D9C8 : 0x606D48A1;
        graphics.fill(left + 3, top, right - 3, top + panelHeight, 0xC8140E20);
        graphics.fill(left + 1, top + 3, right - 1, top + panelHeight - 3, 0xC8140E20);
        graphics.fill(left + 4, top, right - 4, top + 1, border);
        graphics.fill(left + 4, top + panelHeight - 1, right - 4, top + panelHeight, border);
        graphics.fill(left, top + 4, left + 1, top + panelHeight - 4, border);
        graphics.fill(right - 1, top + 4, right, top + panelHeight - 4, border);
        graphics.fill(left + 5, top + 2, left + 7, top + panelHeight - 2, innerAccent);
        graphics.fill(right - 7, top + 2, right - 5, top + panelHeight - 2, innerAccent);

        graphics.drawCenteredString(minecraft.font, label, left + panelWidth / 2, top + 5,
                adjusting ? 0xFFB6FFF1 : 0xFFF1E8FF);
    }

    private static void drawChevron(GuiGraphics graphics, int x, int y, boolean pointsRight,
                                    int color) {
        int direction = pointsRight ? 1 : -1;
        for (int offset = -2; offset <= 2; offset++) {
            int horizontal = x + direction * (2 - Math.abs(offset));
            graphics.fill(horizontal, y + offset, horizontal + 1, y + offset + 1, color);
        }
    }
}
