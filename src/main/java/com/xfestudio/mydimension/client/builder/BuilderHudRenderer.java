package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
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
            drawGlyph(graphics, minecraft.font, actions.get(index).glyph(), x, y,
                    selected ? 1.18F : 0.82F, selected ? 0xFFFFFFFF : 0xFFD2CBE2);
        }

        // The wheel is lowered enough that its transparent center leaves the vanilla crosshair unobscured.
        Component label = Component.translatable(active.translationKey());
        int panelHalfWidth = Math.max(27, innerRadius - 3);
        graphics.fill(centerX - panelHalfWidth, centerY - 14,
                centerX + panelHalfWidth, centerY + 14, 0xC51A1128);
        graphics.fill(centerX - panelHalfWidth, centerY - 14,
                centerX + panelHalfWidth, centerY - 12, adjusting ? 0xD957E4D1 : 0xD39168E9);
        String modeMarker = adjusting ? "<  ALT  >" : "ALT  " + (selectedIndex + 1) + "/" + actions.size();
        graphics.drawCenteredString(minecraft.font, modeMarker, centerX, centerY - 11,
                adjusting ? 0xFF74EBD9 : 0xFFD5B9FF);
        drawScaledCentered(graphics, minecraft.font, label, centerX, centerY + 5,
                panelHalfWidth * 2 - 7, 0xFFFFFFFF);

        Component hint = Component.translatable(adjusting
                ? "hud.mydimension.realmwright.alt_adjust_hint"
                : "screen.mydimension.realmwright.alt_help");
        drawHint(graphics, minecraft.font, hint, centerX,
                Math.max(5, centerY - outerRadius - 17), Math.max(32, screenWidth - 20),
                adjusting ? 0xFFFFD977 : 0xFFE1D5F3);
    }

    private static void drawGlyph(GuiGraphics graphics, Font font, String glyph, int x, int y,
                                  float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, glyph, 0, -font.lineHeight / 2, color);
        graphics.pose().popPose();
    }

    private static void drawScaledCentered(GuiGraphics graphics, Font font, Component text,
                                           int x, int y, int maximumWidth, int color) {
        int naturalWidth = Math.max(1, font.width(text));
        float scale = Math.min(1.0F, maximumWidth / (float) naturalWidth);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, text, 0, -font.lineHeight / 2, color);
        graphics.pose().popPose();
    }

    private static void drawHint(GuiGraphics graphics, Font font, Component hint, int centerX,
                                 int y, int maximumWidth, int color) {
        int naturalWidth = Math.max(1, font.width(hint));
        float scale = Math.min(1.0F, (maximumWidth - 12) / (float) naturalWidth);
        int visibleWidth = Mth.ceil(naturalWidth * scale);
        graphics.fill(centerX - visibleWidth / 2 - 6, y - 2,
                centerX + visibleWidth / 2 + 6, y + 11, 0xA9140D21);
        graphics.fill(centerX - visibleWidth / 2 - 6, y - 2,
                centerX - visibleWidth / 2 - 3, y + 11, 0xB555DCCB);
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y + 2, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, hint, 0, 0, color);
        graphics.pose().popPose();
    }
}
