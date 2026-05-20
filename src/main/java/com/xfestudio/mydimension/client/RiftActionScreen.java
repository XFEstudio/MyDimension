package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.SetRiftActionPacket;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

public class RiftActionScreen extends Screen {
    private static final int BUTTON_HEIGHT = 26;
    private static final int BUTTON_GAP = 22;
    private static final int ROW_GAP = 82;
    private static final int TRAVEL_COLOR = 0xFF3E8DB8;
    private static final int MOB_COLOR = 0xFF8B58C8;
    private static final int ANCHOR_COLOR = 0xFFD6A23E;
    private static final ResourceLocation RIFT_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/item/rift.png");
    private static final ResourceLocation PORTAL_ICON = new ResourceLocation("minecraft", "textures/block/nether_portal.png");
    private static final ResourceLocation ANCHOR_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/entity/rift_anchor.png");

    private boolean inMindDimension;

    public RiftActionScreen() {
        super(Component.translatable("screen.mydimension.rift_actions"));
    }

    @Override
    protected void init() {
        inMindDimension = minecraft != null && minecraft.player != null && ModDimensions.isMindDimension(minecraft.player.level().dimension());

        RiftAction[] travelActions = new RiftAction[] {
                RiftAction.ETHEREAL_MIND,
                RiftAction.MIRROR_MIND,
                RiftAction.WATER_MIND,
                RiftAction.NATURE_MIND
        };
        addActionRow(travelActions, height / 2 - ROW_GAP);

        if (inMindDimension) {
            addCenteredActionButton(RiftAction.SET_ANCHOR, height / 2 + ROW_GAP - BUTTON_HEIGHT / 2);
        } else {
            addActionRow(new RiftAction[] {
                    RiftAction.SEND_MOB_ETHEREAL,
                    RiftAction.SEND_MOB_MIRROR,
                    RiftAction.SEND_MOB_WATER,
                    RiftAction.SEND_MOB_NATURE
            }, height / 2 + ROW_GAP);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        renderCenterPanel(graphics);
        renderGuideLines(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addActionRow(RiftAction[] actions, int centerY) {
        int columns = actions.length;
        int buttonWidth = rowButtonWidth(columns);
        int totalWidth = columns * buttonWidth + (columns - 1) * BUTTON_GAP;
        int x = width / 2 - totalWidth / 2;
        int y = centerY - BUTTON_HEIGHT / 2;

        for (RiftAction action : actions) {
            addRenderableWidget(new ActionButton(x, y, buttonWidth, action, getSelectedAction() == action));
            x += buttonWidth + BUTTON_GAP;
        }
    }

    private void addCenteredActionButton(RiftAction action, int y) {
        int buttonWidth = Mth.clamp(width / 4, 132, 176);
        addRenderableWidget(new ActionButton(width / 2 - buttonWidth / 2, y, buttonWidth, action, getSelectedAction() == action));
    }

    private int rowButtonWidth(int columns) {
        int available = Math.max(96, width - 64);
        int maxByWidth = (available - (columns - 1) * BUTTON_GAP) / columns;
        return Mth.clamp(maxByWidth, 92, 150);
    }

    private void renderCenterPanel(GuiGraphics graphics) {
        int centerX = width / 2;
        int centerY = height / 2;
        int left = centerX - 90;
        int top = centerY - 37;
        int right = centerX + 90;
        int bottom = centerY + 37;

        fillRoundedRect(graphics, left, top, right, bottom, 7, 0xB0060A12);
        drawRoundedBorder(graphics, left, top, right, bottom, 7, 0xFF85D6F7);
        graphics.drawCenteredString(font, title, centerX, centerY - 25, 0xFFEAFBFF);

        Component mode = Component.translatable(inMindDimension ? "screen.mydimension.rift_actions.mode_mind" : "screen.mydimension.rift_actions.mode_outside");
        Component selected = Component.translatable("screen.mydimension.rift_actions.selected", getSelectedAction().displayName());
        graphics.drawCenteredString(font, mode, centerX, centerY - 4, inMindDimension ? 0xFFFFE0A3 : 0xFFD0B6FF);
        graphics.drawCenteredString(font, selected, centerX, centerY + 14, 0xFF9DDBFF);
    }

    private void renderGuideLines(GuiGraphics graphics) {
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.fill(centerX - 1, centerY - ROW_GAP + BUTTON_HEIGHT / 2 + 8, centerX + 1, centerY - 43, 0x4485D6F7);
        graphics.fill(centerX - 1, centerY + 43, centerX + 1, centerY + ROW_GAP - BUTTON_HEIGHT / 2 - 8, inMindDimension ? 0x55FFE0A3 : 0x447F53B9);
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

    private class ActionButton extends Button {
        private final RiftAction action;
        private final boolean selected;

        private ActionButton(int x, int y, int width, RiftAction action, boolean selected) {
            super(x, y, width, BUTTON_HEIGHT, action.displayName(), button -> select(action), DEFAULT_NARRATION);
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

            fillRoundedRect(graphics, left, top, right, bottom, 7, darken(fill));
            fillRoundedRect(graphics, left + 1, top + 1, right - 1, bottom - 1, 6, fill);
            drawRoundedBorder(graphics, left, top, right, bottom, 7, edge);
            if (selected) {
                fillRoundedRect(graphics, left + 4, top + 5, left + 8, bottom - 5, 2, 0xFFFFF4A8);
            }

            renderIcon(graphics, left + 10, top + 5);
            int textColor = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFEAFBFF;
            drawFittingText(graphics, Minecraft.getInstance().font, getMessage(), left + 32, top, getWidth() - 38, getHeight(), textColor);
        }

        private void renderIcon(GuiGraphics graphics, int x, int y) {
            if (action == RiftAction.SET_ANCHOR) {
                graphics.blit(ANCHOR_ICON, x, y, 0, 0, 16, 16, 32, 32);
            } else if (action.sendsMob()) {
                graphics.blit(PORTAL_ICON, x, y, 0, 0, 16, 16, 16, 512);
            } else {
                graphics.blit(RIFT_ICON, x, y, 0, 0, 16, 16, 16, 16);
            }
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

        private void drawFittingText(GuiGraphics graphics, Font font, Component text, int left, int top, int width, int height, int color) {
            String label = font.plainSubstrByWidth(text.getString(), width);
            graphics.drawString(font, label, left, top + (height - 8) / 2, color);
        }
    }
}
