package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.SetRiftActionPacket;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

public class RiftActionScreen extends Screen {
    private static final int BUTTON_WIDTH = 104;
    private static final int BUTTON_HEIGHT = 22;
    private static final int INNER_RADIUS = 76;
    private static final int OUTER_RADIUS = 122;
    private static final int TRAVEL_COLOR = 0xFF3E8DB8;
    private static final int MOB_COLOR = 0xFF8B58C8;
    private static final int ANCHOR_COLOR = 0xFFD6A23E;

    public RiftActionScreen() {
        super(Component.translatable("screen.mydimension.rift_actions"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        addActionButton(RiftAction.ETHEREAL_MIND, centerX, centerY, -90.0D, INNER_RADIUS);
        addActionButton(RiftAction.MIRROR_MIND, centerX, centerY, 0.0D, INNER_RADIUS);
        addActionButton(RiftAction.WATER_MIND, centerX, centerY, 90.0D, INNER_RADIUS);
        addActionButton(RiftAction.NATURE_MIND, centerX, centerY, 180.0D, INNER_RADIUS);

        addActionButton(RiftAction.SEND_MOB_ETHEREAL, centerX, centerY, -135.0D, OUTER_RADIUS);
        addActionButton(RiftAction.SEND_MOB_MIRROR, centerX, centerY, -45.0D, OUTER_RADIUS);
        addActionButton(RiftAction.SEND_MOB_WATER, centerX, centerY, 45.0D, OUTER_RADIUS);
        addActionButton(RiftAction.SEND_MOB_NATURE, centerX, centerY, 135.0D, OUTER_RADIUS);

        addRenderableWidget(new ActionButton(centerX - BUTTON_WIDTH / 2, centerY + 33, RiftAction.SET_ANCHOR, getSelectedAction() == RiftAction.SET_ANCHOR));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        renderCenterPanel(graphics);
        renderRingHints(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addActionButton(RiftAction action, int centerX, int centerY, double degrees, int radius) {
        double radians = Math.toRadians(degrees);
        int x = centerX + (int) Math.round(Math.cos(radians) * radius) - BUTTON_WIDTH / 2;
        int y = centerY + (int) Math.round(Math.sin(radians) * radius) - BUTTON_HEIGHT / 2;
        addRenderableWidget(new ActionButton(x, y, action, getSelectedAction() == action));
    }

    private void renderCenterPanel(GuiGraphics graphics) {
        int centerX = width / 2;
        int centerY = height / 2;
        fillRoundedRect(graphics, centerX - 56, centerY - 40, centerX + 56, centerY + 62, 5, 0xAA070A10);
        drawRoundedBorder(graphics, centerX - 56, centerY - 40, centerX + 56, centerY + 62, 5, 0xFF85D6F7);
        graphics.drawCenteredString(font, title, centerX, centerY - 23, 0xFFEAFBFF);
        graphics.drawCenteredString(font, Component.translatable("screen.mydimension.rift_actions.travel"), centerX, centerY - 5, 0xFF9DDBFF);
        graphics.drawCenteredString(font, Component.translatable("screen.mydimension.rift_actions.mob"), centerX, centerY + 10, 0xFFD0B6FF);
        graphics.drawCenteredString(font, Component.translatable("screen.mydimension.rift_actions.anchor"), centerX, centerY + 25, 0xFFFFE0A3);
    }

    private void renderRingHints(GuiGraphics graphics) {
        int centerX = width / 2;
        int centerY = height / 2;
        drawDiamond(graphics, centerX, centerY, INNER_RADIUS + 30, 0x4485D6F7);
        drawDiamond(graphics, centerX, centerY, OUTER_RADIUS + 30, 0x447F53B9);
    }

    private RiftAction getSelectedAction() {
        if (minecraft == null || minecraft.player == null) {
            return RiftAction.ETHEREAL_MIND;
        }

        ItemStack mainHand = minecraft.player.getMainHandItem();
        if (mainHand.is(ModItems.RIFT.get())) {
            return RiftItem.getSelectedAction(mainHand);
        }

        ItemStack offHand = minecraft.player.getOffhandItem();
        return offHand.is(ModItems.RIFT.get()) ? RiftItem.getSelectedAction(offHand) : RiftAction.ETHEREAL_MIND;
    }

    private void select(RiftAction action) {
        ModNetwork.CHANNEL.sendToServer(new SetRiftActionPacket(action));
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private static void drawBorder(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private static void fillRoundedRect(GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color) {
        graphics.fill(left + radius, top, right - radius, bottom, color);
        graphics.fill(left, top + radius, right, bottom - radius, color);
        graphics.fill(left + 1, top + 1, right - 1, top + radius, color);
        graphics.fill(left + 1, bottom - radius, right - 1, bottom - 1, color);
        graphics.fill(left + radius - 1, top + 1, right - radius + 1, bottom - 1, color);
    }

    private static void drawRoundedBorder(GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color) {
        graphics.fill(left + radius, top, right - radius, top + 1, color);
        graphics.fill(left + radius, bottom - 1, right - radius, bottom, color);
        graphics.fill(left, top + radius, left + 1, bottom - radius, color);
        graphics.fill(right - 1, top + radius, right, bottom - radius, color);
        graphics.fill(left + 1, top + 1, left + radius, top + 2, color);
        graphics.fill(right - radius, top + 1, right - 1, top + 2, color);
        graphics.fill(left + 1, bottom - 2, left + radius, bottom - 1, color);
        graphics.fill(right - radius, bottom - 2, right - 1, bottom - 1, color);
    }

    private static void drawDiamond(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        graphics.fill(centerX - 1, centerY - radius, centerX + 1, centerY - radius + 2, color);
        graphics.fill(centerX + radius - 1, centerY - 1, centerX + radius + 1, centerY + 1, color);
        graphics.fill(centerX - 1, centerY + radius - 2, centerX + 1, centerY + radius, color);
        graphics.fill(centerX - radius, centerY - 1, centerX - radius + 2, centerY + 1, color);
    }

    private class ActionButton extends Button {
        private final RiftAction action;
        private final boolean selected;

        private ActionButton(int x, int y, RiftAction action, boolean selected) {
            super(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, action.displayName(), button -> select(action), DEFAULT_NARRATION);
            this.action = action;
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + getWidth();
            int bottom = top + getHeight();
            int base = action == RiftAction.SET_ANCHOR ? ANCHOR_COLOR : (action.sendsMob() ? MOB_COLOR : TRAVEL_COLOR);
            int fill = withAlpha(base, isHoveredOrFocused() ? 226 : 174);
            int edge = selected ? 0xFFFFF4A8 : (action == RiftAction.SET_ANCHOR ? 0xFFFFE0A3 : (action.sendsMob() ? 0xFFD8C1FF : 0xFFB8F0FF));

            fillRoundedRect(graphics, left, top, right, bottom, 5, darken(fill));
            fillRoundedRect(graphics, left + 1, top + 1, right - 1, bottom - 1, 4, fill);
            drawRoundedBorder(graphics, left, top, right, bottom, 5, edge);
            if (selected) {
                fillRoundedRect(graphics, left + 3, top + 4, left + 7, bottom - 4, 2, 0xFFFFF4A8);
            }

            int textColor = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFEAFBFF;
            drawCenteredFittingText(graphics, Minecraft.getInstance().font, getMessage(), left, top, getWidth(), getHeight(), textColor);
        }

        private int withAlpha(int color, int alpha) {
            return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
        }

        private int darken(int color) {
            int alpha = color & 0xFF000000;
            int red = (int) (((color >> 16) & 0xFF) * 0.58F);
            int green = (int) (((color >> 8) & 0xFF) * 0.58F);
            int blue = (int) ((color & 0xFF) * 0.58F);
            return alpha | (red << 16) | (green << 8) | blue;
        }

        private void drawCenteredFittingText(GuiGraphics graphics, Font font, Component text, int left, int top, int width, int height, int color) {
            String label = font.plainSubstrByWidth(text.getString(), width - 10);
            graphics.drawCenteredString(font, label, left + width / 2, top + (height - 8) / 2, color);
        }
    }
}
