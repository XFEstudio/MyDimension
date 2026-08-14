package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.SetRiftActionPacket;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.ModDimensions;
import com.xfestudio.mydimension.world.PrivateMindFeature;
import net.minecraft.Util;
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
import java.util.function.BooleanSupplier;

public class RiftActionScreen extends Screen {
    private static final int GRID_GAP = 10;
    private static final int TRAVEL_COLOR = 0xFF3E8DB8;
    private static final int MOB_COLOR = 0xFF8B58C8;
    private static final int ANCHOR_COLOR = 0xFFD6A23E;
    private static final int COPY_COLOR = 0xFF4EBA7A;
    private static final int TEAM_COLOR = 0xFF4FB5A5;
    private static final ResourceLocation RIFT_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/item/rift.png");
    private static final ResourceLocation PORTAL_ICON = new ResourceLocation("minecraft", "textures/block/nether_portal.png");
    private static final ResourceLocation ANCHOR_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/gui/rift_anchor_icon.png");

    private final long openedAt;
    private long contentAnimationAt;
    private Page page = Page.TRAVEL_PRIVATE;
    private boolean inMindDimension;
    private boolean creative;
    private boolean privateMindsEnabled;

    public RiftActionScreen() {
        super(Component.translatable("screen.mydimension.rift_actions"));
        openedAt = Util.getMillis();
        contentAnimationAt = openedAt;
    }

    @Override
    protected void init() {
        inMindDimension = minecraft != null && minecraft.player != null && ModDimensions.isMindDimension(minecraft.player.level().dimension());
        creative = minecraft != null && minecraft.player != null && minecraft.player.isCreative();
        privateMindsEnabled = PrivateMindFeature.isEnabled();
        normalizePage();

        Layout layout = createLayout();
        addModeTabs(layout);
        if (showsScopeSelector()) {
            addScopeTabs(layout);
        }
        addActionGrid(currentActions(), layout);
        if (inMindDimension) {
            addAnchorButton(layout);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        renderMotes(graphics);
        renderCenterPanel(graphics, createLayout());
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Layout createLayout() {
        int desiredHeight = inMindDimension ? 220 : 190;
        int panelHeight = Math.min(desiredHeight, Math.max(156, height - 12));
        int panelWidth = Math.min(470, Math.max(280, width - 24));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        return new Layout(left, top, left + panelWidth, top + panelHeight, panelWidth, panelHeight < 205);
    }

    private void addModeTabs(Layout layout) {
        List<MenuMode> modes = visibleModes();
        int height = layout.compact() ? 18 : 22;
        int gap = 7;
        int maxWidth = (layout.width() - 28 - gap * (modes.size() - 1)) / modes.size();
        int tabWidth = Mth.clamp(maxWidth, 74, 112);
        int totalWidth = modes.size() * tabWidth + (modes.size() - 1) * gap;
        int x = width / 2 - totalWidth / 2;
        int y = layout.top() + (layout.compact() ? 28 : 34);

        for (int i = 0; i < modes.size(); i++) {
            MenuMode mode = modes.get(i);
            addRenderableWidget(new SegmentButton(x, y, tabWidth, height, mode.title(), button -> switchMode(mode),
                    () -> currentMode() == mode, i));
            x += tabWidth + gap;
        }
    }

    private void addScopeTabs(Layout layout) {
        int tabWidth = Math.min(116, (layout.width() - 42) / 2);
        int gap = 6;
        int totalWidth = tabWidth * 2 + gap;
        int x = width / 2 - totalWidth / 2;
        int y = layout.top() + (layout.compact() ? 50 : 62);
        int height = layout.compact() ? 18 : 20;

        addRenderableWidget(new SegmentButton(x, y, tabWidth, height,
                Component.translatable("screen.mydimension.rift_actions.scope.private"), button -> switchScope(false),
                () -> !isSharedPage(), 3));
        addRenderableWidget(new SegmentButton(x + tabWidth + gap, y, tabWidth, height,
                Component.translatable("screen.mydimension.rift_actions.scope.shared"), button -> switchScope(true),
                this::isSharedPage, 4));
    }

    private void addActionGrid(RiftAction[] actions, Layout layout) {
        int columns = 3;
        int horizontalPadding = 18;
        int buttonWidth = (layout.width() - horizontalPadding * 2 - GRID_GAP * (columns - 1)) / columns;
        int buttonHeight = layout.compact() ? 24 : 30;
        int rowGap = layout.compact() ? 6 : 10;
        int left = layout.left() + horizontalPadding;
        int top = layout.top() + (showsScopeSelector() ? (layout.compact() ? 72 : 90) : (layout.compact() ? 52 : 68));
        RiftAction selectedAction = getSelectedAction();

        for (int i = 0; i < actions.length; i++) {
            int x = left + (i % columns) * (buttonWidth + GRID_GAP);
            int y = top + (i / columns) * (buttonHeight + rowGap);
            addRenderableWidget(new ActionButton(x, y, buttonWidth, buttonHeight, actions[i],
                    selectedAction == actions[i], i, actionLabel(actions[i])));
        }
    }

    private void addAnchorButton(Layout layout) {
        int buttonWidth = Math.min(210, layout.width() - 56);
        int buttonHeight = layout.compact() ? 24 : 28;
        int y = layout.bottom() - buttonHeight - (layout.compact() ? 7 : 10);
        addRenderableWidget(new ActionButton(width / 2 - buttonWidth / 2, y, buttonWidth, buttonHeight,
                RiftAction.SET_ANCHOR, getSelectedAction() == RiftAction.SET_ANCHOR, 6, RiftAction.SET_ANCHOR.displayName()));
    }

    private List<MenuMode> visibleModes() {
        List<MenuMode> modes = new ArrayList<>();
        modes.add(MenuMode.TRAVEL);
        if (!inMindDimension) {
            modes.add(MenuMode.MOB);
        }
        if (privateMindsEnabled && creative) {
            modes.add(MenuMode.COPY);
        }
        if (privateMindsEnabled) {
            modes.add(MenuMode.TEAM);
        }
        return modes;
    }

    private void normalizePage() {
        if (!privateMindsEnabled) {
            page = switch (page) {
                case MOB_PRIVATE, MOB_SHARED -> inMindDimension ? Page.TRAVEL_SHARED : Page.MOB_SHARED;
                default -> Page.TRAVEL_SHARED;
            };
            return;
        }

        if (!creative && page == Page.COPY_SHARED) {
            page = Page.TRAVEL_PRIVATE;
        }
        if (inMindDimension && (page == Page.MOB_PRIVATE || page == Page.MOB_SHARED)) {
            page = page == Page.MOB_SHARED ? Page.TRAVEL_SHARED : Page.TRAVEL_PRIVATE;
        }
    }

    private boolean showsScopeSelector() {
        return privateMindsEnabled && currentMode() != MenuMode.COPY;
    }

    private MenuMode currentMode() {
        return switch (page) {
            case TRAVEL_PRIVATE, TRAVEL_SHARED -> MenuMode.TRAVEL;
            case MOB_PRIVATE, MOB_SHARED -> MenuMode.MOB;
            case COPY_SHARED -> MenuMode.COPY;
        };
    }

    private boolean isSharedPage() {
        return page == Page.TRAVEL_SHARED || page == Page.MOB_SHARED || page == Page.COPY_SHARED;
    }

    private void switchMode(MenuMode mode) {
        if (mode == MenuMode.TEAM) {
            if (minecraft != null) {
                minecraft.setScreen(new TeamMindScreen(this));
            }
            return;
        }
        if (mode == currentMode()) {
            return;
        }

        boolean shared = !privateMindsEnabled || isSharedPage();
        page = switch (mode) {
            case TRAVEL -> shared ? Page.TRAVEL_SHARED : Page.TRAVEL_PRIVATE;
            case MOB -> shared ? Page.MOB_SHARED : Page.MOB_PRIVATE;
            case COPY -> Page.COPY_SHARED;
            case TEAM -> page;
        };
        animatePageChange();
    }

    private void switchScope(boolean shared) {
        if (shared == isSharedPage() || currentMode() == MenuMode.COPY) {
            return;
        }

        page = switch (currentMode()) {
            case TRAVEL -> shared ? Page.TRAVEL_SHARED : Page.TRAVEL_PRIVATE;
            case MOB -> shared ? Page.MOB_SHARED : Page.MOB_PRIVATE;
            case COPY -> Page.COPY_SHARED;
            case TEAM -> page;
        };
        animatePageChange();
    }

    private void animatePageChange() {
        contentAnimationAt = Util.getMillis();
        rebuildWidgets();
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

    private Component actionLabel(RiftAction action) {
        if (privateMindsEnabled) {
            return action.displayName();
        }

        RiftAction simplified = switch (action) {
            case SHARED_ETHEREAL_MIND -> RiftAction.ETHEREAL_MIND;
            case SHARED_MIRROR_MIND -> RiftAction.MIRROR_MIND;
            case SHARED_WATER_MIND -> RiftAction.WATER_MIND;
            case SHARED_NATURE_MIND -> RiftAction.NATURE_MIND;
            case SHARED_SOARING_MIND -> RiftAction.SOARING_MIND;
            case SEND_MOB_SHARED_ETHEREAL -> RiftAction.SEND_MOB_ETHEREAL;
            case SEND_MOB_SHARED_MIRROR -> RiftAction.SEND_MOB_MIRROR;
            case SEND_MOB_SHARED_WATER -> RiftAction.SEND_MOB_WATER;
            case SEND_MOB_SHARED_NATURE -> RiftAction.SEND_MOB_NATURE;
            case SEND_MOB_SHARED_SOARING -> RiftAction.SEND_MOB_SOARING;
            default -> action;
        };
        return simplified.displayName();
    }

    private void renderCenterPanel(GuiGraphics graphics, Layout layout) {
        float progress = openingProgress(0, 260);
        int titleOffset = Math.round((1.0F - easeOut(progress)) * 7.0F);
        int panelFill = multiplyAlpha(0xC0060A12, progress);
        int panelEdge = multiplyAlpha(0xFF85D6F7, progress);

        fillRoundedRect(graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), 8, panelFill);
        drawRoundedBorder(graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), 8, panelEdge);
        graphics.fill(layout.left() + 12, layout.top() + 1, layout.left() + 72, layout.top() + 3,
                multiplyAlpha(currentMode().accentColor(), progress));
        graphics.drawCenteredString(font, title, width / 2, layout.top() + (layout.compact() ? 9 : 13) + titleOffset,
                multiplyAlpha(0xFFEAFBFF, progress));

        if (!layout.compact()) {
            int footerY = inMindDimension ? layout.bottom() - 55 : layout.bottom() - 19;
            Component selected = Component.translatable("screen.mydimension.rift_actions.selected", actionLabel(getSelectedAction()));
            graphics.drawCenteredString(font, selected, width / 2, footerY, multiplyAlpha(0xFFA9C9D8, progress));
        }
    }

    private void renderMotes(GuiGraphics graphics) {
        long elapsed = Util.getMillis() - openedAt;
        int verticalSpan = Math.max(1, height + 36);
        int horizontalSpan = Math.max(1, width - 20);
        float progress = openingProgress(80, 420);

        for (int i = 0; i < 16; i++) {
            int x = 10 + Math.floorMod(i * 83 + (i % 3) * 29, horizontalSpan);
            int drift = (int) (elapsed * (0.006F + (i % 4) * 0.0015F));
            int y = Math.floorMod(i * 47 - drift, verticalSpan) - 18;
            int size = i % 5 == 0 ? 2 : 1;
            int color = i % 3 == 0 ? 0xFF9A6CE0 : 0xFF70D4F4;
            int alpha = 28 + (i % 4) * 9;
            graphics.fill(x, y, x + size, y + size, multiplyAlpha(withAlpha(color, alpha), progress));
        }
    }

    private RiftAction getSelectedAction() {
        if (minecraft == null || minecraft.player == null) {
            return privateMindsEnabled ? RiftAction.ETHEREAL_MIND : RiftAction.SHARED_ETHEREAL_MIND;
        }

        ItemStack mainHand = minecraft.player.getMainHandItem();
        if (mainHand.is(ModItems.RIFT.get())) {
            return RiftItem.getSelectedAction(mainHand);
        }

        ItemStack offHand = minecraft.player.getOffhandItem();
        return offHand.is(ModItems.RIFT.get()) ? RiftItem.getSelectedAction(offHand)
                : (privateMindsEnabled ? RiftAction.ETHEREAL_MIND : RiftAction.SHARED_ETHEREAL_MIND);
    }

    private void select(RiftAction action) {
        ModNetwork.CHANNEL.sendToServer(new SetRiftActionPacket(action));
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private float openingProgress(int delayMillis, int durationMillis) {
        return animationProgress(openedAt, delayMillis, durationMillis);
    }

    private float contentProgress(int order) {
        return easeOut(animationProgress(contentAnimationAt, order * 34, 210));
    }

    private float animationProgress(long startedAt, int delayMillis, int durationMillis) {
        float elapsed = Util.getMillis() - startedAt - delayMillis;
        return Mth.clamp(elapsed / durationMillis, 0.0F, 1.0F);
    }

    private static float easeOut(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static int multiplyAlpha(int color, float multiplier) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Mth.clamp(multiplier, 0.0F, 1.0F));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int mixColor(int from, int to, float amount) {
        float value = Mth.clamp(amount, 0.0F, 1.0F);
        int alpha = Math.round(Mth.lerp(value, (from >>> 24) & 0xFF, (to >>> 24) & 0xFF));
        int red = Math.round(Mth.lerp(value, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF));
        int green = Math.round(Mth.lerp(value, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF));
        int blue = Math.round(Mth.lerp(value, from & 0xFF, to & 0xFF));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int darken(int color) {
        int alpha = color & 0xFF000000;
        int red = (int) (((color >> 16) & 0xFF) * 0.58F);
        int green = (int) (((color >> 8) & 0xFF) * 0.58F);
        int blue = (int) ((color & 0xFF) * 0.58F);
        return alpha | (red << 16) | (green << 8) | blue;
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
        TRAVEL_PRIVATE,
        TRAVEL_SHARED,
        MOB_PRIVATE,
        MOB_SHARED,
        COPY_SHARED
    }

    private enum MenuMode {
        TRAVEL("screen.mydimension.rift_actions.travel", TRAVEL_COLOR),
        MOB("screen.mydimension.rift_actions.mob", MOB_COLOR),
        COPY("screen.mydimension.rift_actions.copy", COPY_COLOR),
        TEAM("screen.mydimension.rift_actions.team", TEAM_COLOR);

        private final String translationKey;
        private final int accentColor;

        MenuMode(String translationKey, int accentColor) {
            this.translationKey = translationKey;
            this.accentColor = accentColor;
        }

        private Component title() {
            return Component.translatable(translationKey);
        }

        private int accentColor() {
            return accentColor;
        }
    }

    private record Layout(int left, int top, int right, int bottom, int width, boolean compact) {
    }

    private class SegmentButton extends Button {
        private final BooleanSupplier selected;
        private final int animationOrder;
        private float hoverProgress;

        private SegmentButton(int x, int y, int width, int height, Component message, OnPress onPress,
                              BooleanSupplier selected, int animationOrder) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.selected = selected;
            this.animationOrder = animationOrder;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            hoverProgress = Mth.clamp(hoverProgress + (isHoveredOrFocused() ? 0.16F : -0.16F), 0.0F, 1.0F);
            float progress = easeOut(openingProgress(animationOrder * 24, 220));
            int offset = Math.round((1.0F - progress) * 5.0F);
            int left = getX();
            int top = getY() + offset;
            int right = left + getWidth();
            int bottom = top + getHeight();
            boolean active = selected.getAsBoolean();
            int activeColor = currentMode().accentColor();
            int fill = active ? withAlpha(activeColor, 218) : mixColor(0xA62A4354, 0xD0487894, hoverProgress);
            int edge = active ? 0xFFFFEEA3 : mixColor(0xFF7096AA, 0xFFC9F5FF, hoverProgress);

            fillRoundedRect(graphics, left, top, right, bottom, 5, multiplyAlpha(fill, progress));
            drawRoundedBorder(graphics, left, top, right, bottom, 5, multiplyAlpha(edge, progress));
            if (active) {
                int lineWidth = Math.max(12, getWidth() / 3);
                graphics.fill(left + (getWidth() - lineWidth) / 2, bottom - 2,
                        right - (getWidth() - lineWidth) / 2, bottom - 1, multiplyAlpha(0xFFFFF3B0, progress));
            }
            drawCenteredFittingText(graphics, Minecraft.getInstance().font, getMessage(), left, top, getWidth(), getHeight(),
                    multiplyAlpha(active || hoverProgress > 0.15F ? 0xFFFFFFFF : 0xFFD8E9F0, progress));
        }
    }

    private class ActionButton extends Button {
        private final RiftAction action;
        private final boolean selected;
        private final int animationOrder;
        private float hoverProgress;

        private ActionButton(int x, int y, int width, int height, RiftAction action, boolean selected,
                             int animationOrder, Component message) {
            super(x, y, width, height, message, button -> select(action), DEFAULT_NARRATION);
            this.action = action;
            this.selected = selected;
            this.animationOrder = animationOrder;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            hoverProgress = Mth.clamp(hoverProgress + (isHoveredOrFocused() ? 0.14F : -0.14F), 0.0F, 1.0F);
            float progress = contentProgress(animationOrder);
            if (progress <= 0.01F) {
                return;
            }

            int left = getX();
            int top = getY() + Math.round((1.0F - progress) * 9.0F);
            int right = left + getWidth();
            int bottom = top + getHeight();
            int base = action == RiftAction.SET_ANCHOR ? ANCHOR_COLOR
                    : (action.copiesSharedDimension() ? COPY_COLOR : (action.sendsMob() ? MOB_COLOR : TRAVEL_COLOR));
            int fill = withAlpha(base, Math.round(Mth.lerp(hoverProgress, 172.0F, 232.0F)));
            int edge = selected ? 0xFFFFF4A8
                    : (action == RiftAction.SET_ANCHOR ? 0xFFFFE0A3
                    : (action.copiesSharedDimension() ? 0xFFB8FFD2 : (action.sendsMob() ? 0xFFD8C1FF : 0xFFB8F0FF)));

            fillRoundedRect(graphics, left, top, right, bottom, 7, multiplyAlpha(darken(fill), progress));
            fillRoundedRect(graphics, left + 1, top + 1, right - 1, bottom - 1, 6, multiplyAlpha(fill, progress));
            drawRoundedBorder(graphics, left, top, right, bottom, 7, multiplyAlpha(edge, progress));

            if (selected) {
                float pulse = 0.72F + (Mth.sin(Util.getMillis() / 210.0F) + 1.0F) * 0.12F;
                fillRoundedRect(graphics, left + 4, top + 5, left + 7, bottom - 5, 2,
                        multiplyAlpha(0xFFFFF4A8, progress * pulse));
            }
            if (hoverProgress > 0.01F) {
                int glintWidth = Math.round((getWidth() - 18) * hoverProgress);
                graphics.fill(left + 9, bottom - 3, left + 9 + glintWidth, bottom - 2,
                        multiplyAlpha(edge, progress * hoverProgress * 0.85F));
            }

            renderIcon(graphics, left + 9 + Math.round(hoverProgress * 2.0F), top + (getHeight() - 16) / 2, progress);
            int textColor = mixColor(0xFFEAFBFF, 0xFFFFFFFF, hoverProgress);
            drawFittingText(graphics, Minecraft.getInstance().font, getMessage(), left + 32, top,
                    getWidth() - 38, getHeight(), multiplyAlpha(textColor, progress));
        }

        private void renderIcon(GuiGraphics graphics, int x, int y, float opacity) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, opacity);
            if (action == RiftAction.SET_ANCHOR) {
                graphics.blit(ANCHOR_ICON, x, y, 0, 0, 16, 16, 16, 16);
            } else if (action.sendsMob()) {
                graphics.blit(PORTAL_ICON, x, y, 0, 0, 16, 16, 16, 512);
            } else {
                graphics.blit(RIFT_ICON, x, y, 0, 0, 16, 16, 16, 16);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void drawCenteredFittingText(GuiGraphics graphics, Font font, Component text,
                                                int left, int top, int width, int height, int color) {
        String label = font.plainSubstrByWidth(text.getString(), width - 8);
        graphics.drawCenteredString(font, label, left + width / 2, top + (height - 8) / 2, color);
    }

    private static void drawFittingText(GuiGraphics graphics, Font font, Component text,
                                        int left, int top, int width, int height, int color) {
        String label = font.plainSubstrByWidth(text.getString(), width);
        graphics.drawString(font, label, left, top + (height - 8) / 2, color);
    }
}
