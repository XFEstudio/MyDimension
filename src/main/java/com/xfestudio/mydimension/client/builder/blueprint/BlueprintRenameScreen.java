package com.xfestudio.mydimension.client.builder.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit fail-first rename dialog. Replacing a name conflict always requires a separate click. */
public final class BlueprintRenameScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 154;

    private final Screen parent;
    private final ClientBlueprintLibrary.Entry entry;
    private final ClientBlueprintLibrary library = ClientBlueprintLibrary.get();
    private EditBox name;
    private String draftName;
    private String status = "";
    private boolean conflict;
    private boolean renaming;

    public BlueprintRenameScreen(Screen parent, ClientBlueprintLibrary.Entry entry) {
        super(Component.translatable("screen.mydimension.realmwright.blueprint.rename_title"));
        this.parent = parent;
        this.entry = entry;
        this.draftName = entry.name();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        name = new EditBox(font, left + 24, top + 39, PANEL_WIDTH - 48, 22,
                Component.translatable("screen.mydimension.realmwright.blueprint.name"));
        name.setMaxLength(96);
        name.setValue(draftName);
        name.setResponder(value -> draftName = value);
        name.setEditable(!renaming);
        addRenderableWidget(name);
        setInitialFocus(name);

        if (conflict) {
            Button retry = Button.builder(
                    Component.translatable("screen.mydimension.realmwright.blueprint.rename"),
                    button -> rename(ClientBlueprintLibrary.ConflictPolicy.FAIL))
                    .bounds(left + 24, top + 78, 92, 22).build();
            retry.active = !renaming;
            addRenderableWidget(retry);

            Button replace = Button.builder(
                    Component.translatable("screen.mydimension.realmwright.blueprint.replace"),
                    button -> rename(ClientBlueprintLibrary.ConflictPolicy.REPLACE))
                    .bounds(left + 134, top + 78, 92, 22).build();
            replace.active = !renaming;
            addRenderableWidget(replace);
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(left + 244, top + 78, 92, 22).build()).active = !renaming;
        } else {
            Button rename = Button.builder(
                    Component.translatable("screen.mydimension.realmwright.blueprint.rename"),
                    button -> rename(ClientBlueprintLibrary.ConflictPolicy.FAIL))
                    .bounds(left + 78, top + 78, 96, 22).build();
            rename.active = !renaming;
            addRenderableWidget(rename);
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(left + 186, top + 78, 96, 22).build()).active = !renaming;
        }
    }

    private void rename(ClientBlueprintLibrary.ConflictPolicy policy) {
        if (renaming) return;
        draftName = name.getValue();
        renaming = true;
        status = Component.translatable("screen.mydimension.realmwright.blueprint.renaming").getString();
        rebuildWidgets();
        library.renameAsync(entry.id(), draftName, policy).whenComplete((renamed, failure) ->
                Minecraft.getInstance().execute(() -> {
                    renaming = false;
                    if (failure == null) {
                        Minecraft.getInstance().setScreen(parent);
                        return;
                    }
                    ClientBlueprintLibrary.NameConflictException nameConflict = findConflict(failure);
                    conflict = nameConflict != null;
                    status = conflict
                            ? Component.translatable("screen.mydimension.realmwright.blueprint.rename_conflict",
                                    nameConflict.existingName()).getString()
                            : rootMessage(failure);
                    rebuildWidgets();
                }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            rename(ClientBlueprintLibrary.ConflictPolicy.FAIL);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xEE121822);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF557A9F);
        graphics.drawCenteredString(font, title, width / 2, top + 13, 0xFFFFFFFF);
        if (!status.isBlank()) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(status, PANEL_WIDTH - 34), width / 2,
                    top + 119, conflict ? 0xFFFFD66B : 0xFFFF6B63);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!renaming && minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static ClientBlueprintLibrary.NameConflictException findConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ClientBlueprintLibrary.NameConflictException conflict) return conflict;
            current = current.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
