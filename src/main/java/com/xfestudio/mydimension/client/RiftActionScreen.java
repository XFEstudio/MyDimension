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

import java.util.ArrayList;
import java.util.List;

public class RiftActionScreen extends Screen {
    private static final int BUTTON_HEIGHT = 26;
    private static final int BUTTON_GAP = 18;
    private static final int TRAVEL_COLOR = 0xFF3E8DB8;
    private static final int MOB_COLOR = 0xFF8B58C8;
    private static final int ANCHOR_COLOR = 0xFFD6A23E;
    private static final int COPY_COLOR = 0xFF4EBA7A;
    private static final ResourceLocation RIFT_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/item/rift.png");
    private static final ResourceLocation PORTAL_ICON = new ResourceLocation("minecraft", "textures/block/nether_portal.png");
    private static final ResourceLocation ANCHOR_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/gui/rift_anchor_icon.png");

    private Page page = Page.TRAVEL_PRIVATE;
    private boolean inMindDimension;
    private boolean creative;

    public RiftActionScreen() {
        super(Component.translatable("screen.mydimension.rift_actions"));
    }

    @Override
    protected void init() {
        inMindDimension = minecraft != null && minecraft.player != null && ModDimensions.isMindDimension(minecraft.player.level().dimension());
        creative = minecraft != null && minecraft.player != null && minecraft.player.isCreative();

        int centerX = width / 2;
        int centerY = height / 2;
        addTabs(centerX, centerY - 108);
        addActionGrid(currentActions(), centerY - 36);
        if (inMindDimension) {
            addCenteredActionButton(RiftAction.SET_ANCHOR, centerY + 88);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        renderCenterPanel(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addTabs(int centerX, int y) {
        List<Page> pages = visiblePages();
        int tabWidth = Math.max(68, Math.min(94, (width - 48 - (pages.size() - 1) * 8) / pages.size()));
        int totalWidth = pages.size() * tabWidth + (pages.size() - 1) * 8;
        int x = centerX - totalWidth / 2;
        for (Page tab : pages) {
            addRenderableWidget(new PageButton(x, y, tabWidth, tab));
            x += tabWidth + 8;
        }
    }

    private List<Page> visiblePages() {
        List<Page> pages = new ArrayList<>(List.of(Page.TRAVEL_PRIVATE, Page.TRAVEL_SHARED, Page.MOB_PRIVATE, Page.MOB_SHARED));
        if (creative) {
            pages.add(Page.COPY_SHARED);
        } else if (page == Page.COPY_SHARED) {
            page = Page.TRAVEL_PRIVATE;
        }
        return pages;
    }

    private RiftAction[] currentActions() {
        return switch (page) {
            case TRAVEL_PRIVATE -> new RiftAction[] {RiftAction.ETHEREAL_MIND, RiftAction.MIRROR_MIND, RiftAction.WATER_MIND, RiftAction.NATURE_MIND, RiftAction.SOARING_MIND};
            case TRAVEL_SHARED -> new RiftAction[] {RiftAction.SHARED_ETHEREAL_MIND, RiftAction.SHARED_MIRROR_MIND, RiftAction.SHARED_WATER_MIND, RiftAction.SHARED_NATURE_MIND, RiftAction.SHARED_SOARING_MIND};
            case MOB_PRIVATE -> new RiftAction[] {RiftAction.SEND_MOB_ETHEREAL, RiftAction.SEND_MOB_MIRROR, RiftAction.SEND_MOB_WATER, RiftAction.SEND_MOB_NATURE, RiftAction.SEND_MOB_SOARING};
            case MOB_SHARED -> new RiftAction[] {RiftAction.SEND_MOB_SHARED_ETHEREAL, RiftAction.SEND_MOB_SHARED_MIRROR, RiftAction.SEND_MOB_SHARED_WATER, RiftAction.SEND_MOB_SHARED_NATURE, RiftAction.SEND_MOB_SHARED_SOARING};
            case COPY_SHARED -> new RiftAction[] {RiftAction.COPY_SHARED_ETHEREAL, RiftAction.COPY_SHARED_MIRROR, RiftAction.COPY_SHARED_WATER, RiftAction.COPY_SHARED_NATURE, RiftAction.COPY_SHARED_SOARING};
        };
    }

    private void addActionGrid(RiftAction[] actions, int top) {
        int columns = 3;
        int buttonWidth = rowButtonWidth(columns);
        int totalWidth = buttonWidth * columns + BUTTON_GAP * (columns - 1);
        int left = width / 2 - totalWidth / 2;
        for (int i = 0; i < actions.length; i++) {
            int x = left + (i % columns) * (buttonWidth + BUTTON_GAP);
            int y = top + (i / columns) * 42;
            addRenderableWidget(new ActionButton(x, y, buttonWidth, actions[i], getSelectedAction() == actions[i]));
        }
    }

    private void addCenteredActionButton(RiftAction action, int y) {
        int buttonWidth = Mth.clamp(width / 4, 140, 190);
        addRenderableWidget(new ActionButton(width / 2 - buttonWidth / 2, y, buttonWidth, action, getSelectedAction() == action));
    }

    private int rowButtonWidth(int columns) {
        int available = Math.max(96, width - 64);
        int maxByWidth = (available - (columns - 1) * BUTTON_GAP) / columns;
        return Mth.clamp(maxByWidth, 64, 150);
    }

    private void renderCenterPanel(GuiGraphics graphics) {
        int centerX = width / 2;
        int centerY = height / 2;
        int left = centerX - 180;
        int top = centerY - 72;
        int right = centerX + 180;
        int bottom = centerY + 72;

        fillRoundedRect(graphics, left, top, right, bottom, 7, 0x99060A12);
        drawRoundedBorder(graphics, left, top, right, bottom, 7, 0xFF85D6F7);
        graphics.drawCenteredString(font, title, centerX, centerY - 61, 0xFFEAFBFF);
        graphics.drawCenteredString(font, page.title(), centerX, centerY + 52, page == Page.COPY_SHARED ? 0xFFB8FFD2 : 0xFF9DDBFF);
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

    private enum Page {
        TRAVEL_PRIVATE("screen.mydimension.rift_actions.page.travel_private"),
        TRAVEL_SHARED("screen.mydimension.rift_actions.page.travel_shared"),
        MOB_PRIVATE("screen.mydimension.rift_actions.page.mob_private"),
        MOB_SHARED("screen.mydimension.rift_actions.page.mob_shared"),
        COPY_SHARED("screen.mydimension.rift_actions.page.copy_shared");

        private final String translationKey;

        Page(String translationKey) {
            this.translationKey = translationKey;
        }

        private Component title() {
            return Component.translatable(translationKey);
        }
    }

    private class PageButton extends Button {
        private final Page targetPage;

        private PageButton(int x, int y, int width, Page targetPage) {
            super(x, y, width, 20, targetPage.title(), button -> {
                page = targetPage;
                rebuildWidgets();
            }, DEFAULT_NARRATION);
            this.targetPage = targetPage;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int fill = targetPage == page ? 0xD6D6A23E : (isHoveredOrFocused() ? 0xC83E8DB8 : 0x963E8DB8);
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, fill);
            drawRoundedBorder(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 5, targetPage == page ? 0xFFFFF4A8 : 0xFFB8F0FF);
            drawCenteredFittingText(graphics, Minecraft.getInstance().font, getMessage(), getX(), getY(), getWidth(), getHeight(), 0xFFEAFBFF);
        }
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
            int base = action == RiftAction.SET_ANCHOR ? ANCHOR_COLOR : (action.copiesSharedDimension() ? COPY_COLOR : (action.sendsMob() ? MOB_COLOR : TRAVEL_COLOR));
            int fill = withAlpha(base, isHoveredOrFocused() ? 226 : 174);
            int edge = selected ? 0xFFFFF4A8 : (action == RiftAction.SET_ANCHOR ? 0xFFFFE0A3 : (action.copiesSharedDimension() ? 0xFFB8FFD2 : (action.sendsMob() ? 0xFFD8C1FF : 0xFFB8F0FF)));

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
                graphics.blit(ANCHOR_ICON, x, y, 0, 0, 16, 16, 16, 16);
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
    }

    private static void drawCenteredFittingText(GuiGraphics graphics, Font font, Component text, int left, int top, int width, int height, int color) {
        String label = font.plainSubstrByWidth(text.getString(), width - 8);
        graphics.drawCenteredString(font, label, left + width / 2, top + (height - 8) / 2, color);
    }

    private static void drawFittingText(GuiGraphics graphics, Font font, Component text, int left, int top, int width, int height, int color) {
        String label = font.plainSubstrByWidth(text.getString(), width);
        graphics.drawString(font, label, left, top + (height - 8) / 2, color);
    }
}
