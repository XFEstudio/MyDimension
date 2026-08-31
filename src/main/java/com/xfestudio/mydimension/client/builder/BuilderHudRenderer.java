package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
        if (minecraft.options.hideGui || !BuilderClientServices.isHoldingRealmwright(minecraft)) {
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

        if (controller.phase() != BlueprintAltActionController.Phase.CLOSED
                && BuilderPreviewState.get().isBlueprintPreviewActive()) {
            renderActionWheel(graphics, window, controller);
        }
    }

    private static void renderActionWheel(GuiGraphics graphics, Window window,
                                          BlueprintAltActionController controller) {
        Minecraft minecraft = Minecraft.getInstance();
        int centerX = window.getGuiScaledWidth() / 2;
        int centerY = window.getGuiScaledHeight() / 2;
        List<BlueprintAltActionController.Action> actions = controller.actions();
        int radius = 58;
        for (int index = 0; index < actions.size(); index++) {
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * index / actions.size());
            int x = centerX + Mth.floor(Math.cos(angle) * radius);
            int y = centerY + Mth.floor(Math.sin(angle) * radius);
            boolean selected = actions.get(index) == controller.highlighted();
            int color = selected ? 0xE0477ACB : 0xB018202C;
            graphics.fill(x - 7, y - 7, x + 8, y + 8, color);
            graphics.drawCenteredString(minecraft.font, Integer.toString(index + 1), x, y - 4,
                    selected ? 0xFFFFFFFF : 0xFFBFC9D8);
        }

        BlueprintAltActionController.Action active = controller.phase()
                == BlueprintAltActionController.Phase.ADJUSTING
                ? controller.adjusting() : controller.highlighted();
        Component label = Component.translatable(active.translationKey());
        int width = minecraft.font.width(label) + 16;
        graphics.fill(centerX - width / 2, centerY - 11, centerX + width / 2, centerY + 11, 0xD0101620);
        graphics.drawCenteredString(minecraft.font, label, centerX, centerY - 4, 0xFFFFFFFF);
        if (controller.phase() == BlueprintAltActionController.Phase.ADJUSTING) {
            Component hint = Component.translatable("hud.mydimension.realmwright.alt_adjust_hint");
            graphics.drawCenteredString(minecraft.font, hint, centerX, centerY + 18, 0xFFFFD66B);
        }
    }
}
