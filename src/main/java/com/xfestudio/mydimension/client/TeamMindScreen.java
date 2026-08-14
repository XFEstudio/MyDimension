package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.TeamMindCommandPacket;
import com.xfestudio.mydimension.network.TeamMindDataPacket;
import com.xfestudio.mydimension.world.MindType;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public class TeamMindScreen extends Screen {
    private static final int ENTRANCE_COLOR = 0xFF3E8DB8;
    private static final int ADD_COLOR = 0xFF4EBA7A;
    private static final int MANAGE_COLOR = 0xFF8B58C8;
    private static final ResourceLocation RIFT_ICON = new ResourceLocation(MyDimension.MOD_ID, "textures/item/rift.png");

    private final Screen parent;
    private final long openedAt = Util.getMillis();
    private final Set<UUID> requestedPlayers = new HashSet<>();
    private TeamTab tab = TeamTab.ENTRANCES;
    private List<TeamMindDataPacket.PlayerInfo> entrances = List.of();
    private List<TeamMindDataPacket.PlayerInfo> candidates = List.of();
    private List<TeamMindDataPacket.PlayerInfo> guests = List.of();
    private UUID selectedOwner;
    private int listPage;
    private boolean requestedData;

    public TeamMindScreen(Screen parent) {
        super(Component.translatable("screen.mydimension.team.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Layout layout = createLayout();
        addTabButtons(layout);
        switch (tab) {
            case ENTRANCES -> addEntranceWidgets(layout);
            case ADD -> addPlayerRows(layout, candidates, RowAction.REQUEST);
            case MANAGE -> addPlayerRows(layout, guests, RowAction.REVOKE);
        }
        addFooterButtons(layout);

        if (!requestedData) {
            requestedData = true;
            ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.refresh());
        }
    }

    public void updateData(TeamMindDataPacket packet) {
        entrances = packet.entrances();
        candidates = packet.candidates();
        guests = packet.guests();
        requestedPlayers.clear();
        if (selectedOwner == null || entrances.stream().noneMatch(player -> player.id().equals(selectedOwner))) {
            selectedOwner = entrances.isEmpty() ? null : entrances.get(0).id();
        }
        clampPage();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xD0101218, 0xE0181026);
        Layout layout = createLayout();
        float progress = easeOut(Mth.clamp((Util.getMillis() - openedAt) / 260.0F, 0.0F, 1.0F));
        fillRoundedRect(graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), 8,
                multiplyAlpha(0xD0060A12, progress));
        drawRoundedBorder(graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), 8,
                multiplyAlpha(0xFF85D6F7, progress));
        graphics.fill(layout.left() + 12, layout.top() + 1, layout.left() + 78, layout.top() + 3,
                multiplyAlpha(tab.color(), progress));
        graphics.drawCenteredString(font, title, width / 2, layout.top() + 13, multiplyAlpha(0xFFEAFBFF, progress));

        renderContentLabels(graphics, layout, progress);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Layout createLayout() {
        int panelWidth = Math.min(500, Math.max(300, width - 24));
        int panelHeight = Math.min(252, Math.max(190, height - 12));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        return new Layout(left, top, left + panelWidth, top + panelHeight, panelWidth, panelHeight);
    }

    private void addTabButtons(Layout layout) {
        TeamTab[] tabs = TeamTab.values();
        int gap = 7;
        int tabWidth = Math.min(118, (layout.width() - 36 - gap * (tabs.length - 1)) / tabs.length);
        int totalWidth = tabWidth * tabs.length + gap * (tabs.length - 1);
        int x = width / 2 - totalWidth / 2;
        int y = layout.top() + 34;

        for (int i = 0; i < tabs.length; i++) {
            TeamTab target = tabs[i];
            addRenderableWidget(new TeamButton(x, y, tabWidth, 22, target.title(), target.color(),
                    () -> tab == target, i, button -> {
                tab = target;
                listPage = 0;
                rebuildWidgets();
            }));
            x += tabWidth + gap;
        }
    }

    private void addEntranceWidgets(Layout layout) {
        int contentTop = layout.top() + 66;
        int leftWidth = Math.min(156, Math.max(112, layout.width() / 3));
        int left = layout.left() + 16;
        int from = listPage * rowsPerPage();
        int to = Math.min(entrances.size(), from + rowsPerPage());

        for (int index = from; index < to; index++) {
            TeamMindDataPacket.PlayerInfo owner = entrances.get(index);
            int y = contentTop + (index - from) * 25;
            addRenderableWidget(new TeamButton(left, y, leftWidth, 23, Component.literal(owner.name()), ENTRANCE_COLOR,
                    () -> owner.id().equals(selectedOwner), index - from + 3, button -> {
                selectedOwner = owner.id();
                rebuildWidgets();
            }));
        }

        if (selectedOwner == null) {
            return;
        }

        int right = left + leftWidth + 16;
        int availableWidth = layout.right() - 16 - right;
        int gap = 8;
        int buttonWidth = (availableWidth - gap) / 2;
        MindType[] mindTypes = MindType.values();
        for (int i = 0; i < mindTypes.length; i++) {
            MindType mindType = mindTypes[i];
            int x = right + (i % 2) * (buttonWidth + gap);
            int y = contentTop + (i / 2) * 31;
            addRenderableWidget(new MindButton(x, y, buttonWidth, 25, mindType, i + 5));
        }
        addPaginationButtons(layout, entrances);
    }

    private void addPlayerRows(Layout layout, List<TeamMindDataPacket.PlayerInfo> players, RowAction action) {
        int rowsPerPage = rowsPerPage();
        int from = listPage * rowsPerPage;
        int to = Math.min(players.size(), from + rowsPerPage);
        int left = layout.left() + 24;
        int right = layout.right() - 24;
        int top = layout.top() + 66;
        int actionWidth = 78;

        for (int index = from; index < to; index++) {
            TeamMindDataPacket.PlayerInfo player = players.get(index);
            int row = index - from;
            int y = top + row * 25;
            addRenderableWidget(new TeamButton(left, y, right - left - actionWidth - 8, 23,
                    Component.literal(player.name()), tab.color(), () -> false, row + 3, button -> {
            }));

            boolean pending = action == RowAction.REQUEST && requestedPlayers.contains(player.id());
            Component label = pending
                    ? Component.translatable("screen.mydimension.team.requested")
                    : Component.translatable(action.translationKey());
            TeamButton actionButton = new TeamButton(right - actionWidth, y, actionWidth, 23, label,
                    action == RowAction.REQUEST ? ADD_COLOR : 0xFFC95D66, () -> false, row + 4, button -> {
                if (action == RowAction.REQUEST) {
                    requestedPlayers.add(player.id());
                    ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.requestAccess(player.id()));
                    rebuildWidgets();
                } else {
                    ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.revoke(player.id()));
                }
            });
            actionButton.active = !pending;
            addRenderableWidget(actionButton);
        }

        addPaginationButtons(layout, players);
    }

    private void addFooterButtons(Layout layout) {
        int y = layout.bottom() - 29;
        addRenderableWidget(new TeamButton(layout.left() + 12, y, 62, 20,
                Component.translatable("gui.back"), 0xFF47677A, () -> false, 10, button -> onClose()));
        addRenderableWidget(new TeamButton(layout.right() - 74, y, 62, 20,
                Component.translatable("screen.mydimension.team.refresh"), 0xFF47677A, () -> false, 10, button -> {
            ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.refresh());
        }));
    }

    private void renderContentLabels(GuiGraphics graphics, Layout layout, float progress) {
        int color = multiplyAlpha(0xFFA9C9D8, progress);
        if (tab == TeamTab.ENTRANCES) {
            if (entrances.isEmpty()) {
                graphics.drawCenteredString(font, Component.translatable("screen.mydimension.team.no_entrances"),
                        width / 2, layout.top() + 119, color);
                return;
            }
            TeamMindDataPacket.PlayerInfo owner = selectedOwnerInfo();
            if (owner != null) {
                int leftWidth = Math.min(156, Math.max(112, layout.width() / 3));
                int center = layout.left() + 16 + leftWidth + 16 + (layout.right() - 16 - (layout.left() + 16 + leftWidth + 16)) / 2;
                graphics.drawCenteredString(font, Component.translatable("screen.mydimension.team.owner_minds", owner.name()),
                        center, layout.top() + 62, color);
            }
            renderPageNumber(graphics, layout, entrances, color);
        } else {
            List<TeamMindDataPacket.PlayerInfo> players = currentList();
            if (players.isEmpty()) {
                Component empty = Component.translatable(tab == TeamTab.ADD
                        ? "screen.mydimension.team.no_candidates"
                        : "screen.mydimension.team.no_guests");
                graphics.drawCenteredString(font, empty, width / 2, layout.top() + 119, color);
            }
            renderPageNumber(graphics, layout, players, color);
        }
    }

    private TeamMindDataPacket.PlayerInfo selectedOwnerInfo() {
        return entrances.stream().filter(player -> player.id().equals(selectedOwner)).findFirst().orElse(null);
    }

    private List<TeamMindDataPacket.PlayerInfo> currentList() {
        return switch (tab) {
            case ENTRANCES -> entrances;
            case ADD -> candidates;
            case MANAGE -> guests;
        };
    }

    private void addPaginationButtons(Layout layout, List<?> values) {
        int pages = pageCount(values);
        if (pages <= 1) {
            return;
        }

        int y = layout.bottom() - 39;
        TeamButton previous = new TeamButton(width / 2 - 54, y, 24, 20, Component.literal("<"), tab.color(),
                () -> false, 9, button -> changePage(-1));
        previous.active = listPage > 0;
        addRenderableWidget(previous);
        TeamButton next = new TeamButton(width / 2 + 30, y, 24, 20, Component.literal(">"), tab.color(),
                () -> false, 9, button -> changePage(1));
        next.active = listPage + 1 < pages;
        addRenderableWidget(next);
    }

    private void renderPageNumber(GuiGraphics graphics, Layout layout, List<?> values, int color) {
        int pages = pageCount(values);
        if (pages > 1) {
            graphics.drawCenteredString(font, Component.literal((listPage + 1) + " / " + pages),
                    width / 2, layout.bottom() - 34, color);
        }
    }

    private void changePage(int amount) {
        listPage = Mth.clamp(listPage + amount, 0, Math.max(0, pageCount(currentList()) - 1));
        if (tab == TeamTab.ENTRANCES && !entrances.isEmpty()) {
            selectedOwner = entrances.get(Math.min(entrances.size() - 1, listPage * rowsPerPage())).id();
        }
        rebuildWidgets();
    }

    private void clampPage() {
        listPage = Mth.clamp(listPage, 0, Math.max(0, pageCount(currentList()) - 1));
    }

    private static int pageCount(List<?> values) {
        int rowsPerPage = rowsPerPage();
        return Math.max(1, (values.size() + rowsPerPage - 1) / rowsPerPage);
    }

    private static int rowsPerPage() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getWindow().getGuiScaledHeight() < 232 ? 3 : 5;
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

    private static void fillRoundedRect(GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color) {
        graphics.fill(left + radius, top, right - radius, bottom, color);
        graphics.fill(left, top + radius, right, bottom - radius, color);
        graphics.fill(left + 1, top + 1, right - 1, top + radius, color);
        graphics.fill(left + 1, bottom - radius, right - 1, bottom - 1, color);
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

    private enum TeamTab {
        ENTRANCES("screen.mydimension.team.tab.entrances", ENTRANCE_COLOR),
        ADD("screen.mydimension.team.tab.add", ADD_COLOR),
        MANAGE("screen.mydimension.team.tab.manage", MANAGE_COLOR);

        private final String translationKey;
        private final int color;

        TeamTab(String translationKey, int color) {
            this.translationKey = translationKey;
            this.color = color;
        }

        private Component title() {
            return Component.translatable(translationKey);
        }

        private int color() {
            return color;
        }
    }

    private enum RowAction {
        REQUEST("screen.mydimension.team.request"),
        REVOKE("screen.mydimension.team.revoke");

        private final String translationKey;

        RowAction(String translationKey) {
            this.translationKey = translationKey;
        }

        private String translationKey() {
            return translationKey;
        }
    }

    private record Layout(int left, int top, int right, int bottom, int width, int height) {
    }

    private class MindButton extends TeamButton {
        private final MindType mindType;

        private MindButton(int x, int y, int width, int height, MindType mindType, int animationOrder) {
            super(x, y, width, height, mindType.displayName(), ENTRANCE_COLOR, () -> false, animationOrder,
                    button -> TeamMindScreen.this.selectMind(mindType));
            this.mindType = mindType;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            int y = getY() + (getHeight() - 16) / 2;
            graphics.blit(RIFT_ICON, getX() + 8, y, 0, 0, 16, 16, 16, 16);
        }

    }

    private void selectMind(MindType mindType) {
        if (selectedOwner == null || minecraft == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(TeamMindCommandPacket.select(selectedOwner, mindType));
        minecraft.setScreen(null);
    }

    private class TeamButton extends Button {
        private final int color;
        private final BooleanSupplier selected;
        private final int animationOrder;
        private float hoverProgress;

        private TeamButton(int x, int y, int width, int height, Component message, int color,
                           BooleanSupplier selected, int animationOrder, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.color = color;
            this.selected = selected;
            this.animationOrder = animationOrder;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            hoverProgress = Mth.clamp(hoverProgress + (isHoveredOrFocused() ? 0.15F : -0.15F), 0.0F, 1.0F);
            float progress = easeOut(Mth.clamp((Util.getMillis() - openedAt - animationOrder * 28L) / 220.0F, 0.0F, 1.0F));
            int top = getY() + Math.round((1.0F - progress) * 7.0F);
            int fillAlpha = active ? Math.round(Mth.lerp(hoverProgress, 150.0F, 220.0F)) : 75;
            int edge = selected.getAsBoolean() ? 0xFFFFF4A8 : (active ? 0xFFB8F0FF : 0xFF6A747B);

            fillRoundedRect(graphics, getX(), top, getX() + getWidth(), top + getHeight(), 6,
                    multiplyAlpha(withAlpha(color, fillAlpha), progress));
            drawRoundedBorder(graphics, getX(), top, getX() + getWidth(), top + getHeight(), 6,
                    multiplyAlpha(edge, progress));
            int textLeft = this instanceof MindButton ? getX() + 29 : getX();
            int textWidth = this instanceof MindButton ? getWidth() - 34 : getWidth();
            drawCenteredFittingText(graphics, Minecraft.getInstance().font, getMessage(), textLeft, top,
                    textWidth, getHeight(), multiplyAlpha(active ? 0xFFEAFBFF : 0xFF8A969C, progress));
        }
    }

    private static void drawCenteredFittingText(GuiGraphics graphics, Font font, Component text,
                                                int left, int top, int width, int height, int color) {
        String label = font.plainSubstrByWidth(text.getString(), Math.max(4, width - 8));
        graphics.drawCenteredString(font, label, left + width / 2, top + (height - 8) / 2, color);
    }
}
