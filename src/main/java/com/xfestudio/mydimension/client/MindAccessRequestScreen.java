package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.TeamMindCommandPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.UUID;

public class MindAccessRequestScreen extends Screen {
    private final Screen parent;
    private final UUID requesterId;
    private final String requesterName;
    private final long openedAt = Util.getMillis();
    private boolean responded;

    public MindAccessRequestScreen(Screen parent, UUID requesterId, String requesterName) {
        super(Component.translatable("screen.mydimension.team.request_title"));
        this.parent = parent;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.team.accept"), button -> respond(true))
                .bounds(centerX - 106, centerY + 24, 98, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.team.deny"), button -> respond(false))
                .bounds(centerX + 8, centerY + 24, 98, 22).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        float progress = easeOut(Mth.clamp((Util.getMillis() - openedAt) / 220.0F, 0.0F, 1.0F));
        int centerX = width / 2;
        int centerY = height / 2;
        int offset = Math.round((1.0F - progress) * 8.0F);
        graphics.fill(centerX - 130, centerY - 45 + offset, centerX + 130, centerY + 56 + offset,
                multiplyAlpha(0xE0060A12, progress));
        graphics.renderOutline(centerX - 130, centerY - 45 + offset, 260, 101, multiplyAlpha(0xFF85D6F7, progress));
        graphics.drawCenteredString(font, title, centerX, centerY - 31 + offset, multiplyAlpha(0xFFEAFBFF, progress));
        Component prompt = Component.translatable("screen.mydimension.team.request_prompt", requesterName);
        String fittedPrompt = font.plainSubstrByWidth(prompt.getString(), 236);
        graphics.drawCenteredString(font, fittedPrompt, centerX, centerY - 7 + offset, multiplyAlpha(0xFFC5E7F3, progress));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!responded) {
            respond(false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void respond(boolean accepted) {
        if (responded) {
            return;
        }
        responded = true;
        ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.respond(requesterId, accepted));
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private static float easeOut(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static int multiplyAlpha(int color, float multiplier) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Mth.clamp(multiplier, 0.0F, 1.0F));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
