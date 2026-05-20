package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.SetRiftActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RiftActionScreen extends Screen {
    private static final int BUTTON_WIDTH = 104;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RADIUS = 72;

    public RiftActionScreen() {
        super(Component.translatable("screen.mydimension.rift_actions"));
    }

    @Override
    protected void init() {
        RiftAction[] actions = RiftAction.values();
        int centerX = width / 2;
        int centerY = height / 2;

        for (int i = 0; i < actions.length; i++) {
            RiftAction action = actions[i];
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * i / actions.length);
            int x = centerX + (int) Math.round(Math.cos(angle) * RADIUS) - BUTTON_WIDTH / 2;
            int y = centerY + (int) Math.round(Math.sin(angle) * RADIUS) - BUTTON_HEIGHT / 2;
            addRenderableWidget(Button.builder(action.displayName(), button -> select(action))
                    .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void select(RiftAction action) {
        ModNetwork.CHANNEL.sendToServer(new SetRiftActionPacket(action));
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
