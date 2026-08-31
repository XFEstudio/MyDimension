package com.xfestudio.mydimension.client.builder.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** Name, save-mode, and explicit collision-policy dialog for a captured blueprint. */
public final class BlueprintSaveScreen extends Screen {
    private final Screen parent;
    private final BlueprintData source;
    private final ClientBlueprintLibrary library = ClientBlueprintLibrary.get();
    private EditBox name;
    private BlueprintSaveMode mode;
    private ClientBlueprintLibrary.ConflictPolicy conflictPolicy = ClientBlueprintLibrary.ConflictPolicy.FAIL;
    private String status = "";
    private boolean saving;

    public BlueprintSaveScreen(Screen parent, BlueprintData source) {
        super(Component.translatable("screen.mydimension.realmwright.blueprint.save"));
        this.parent = parent;
        this.source = source;
        this.mode = source.saveMode();
    }

    public static void open(BlueprintData data) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new BlueprintSaveScreen(minecraft.screen, data));
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int top = height / 2 - 76;
        name = new EditBox(font, left, top + 25, 300, 22,
                Component.translatable("screen.mydimension.realmwright.blueprint.name"));
        name.setMaxLength(96);
        name.setValue(source.name());
        addRenderableWidget(name);

        addRenderableWidget(Button.builder(modeLabel(), button -> {
            mode = mode == BlueprintSaveMode.BLOCKS_ONLY ? BlueprintSaveMode.FULL : BlueprintSaveMode.BLOCKS_ONLY;
            button.setMessage(modeLabel());
        }).bounds(left, top + 58, 145, 22).build());
        addRenderableWidget(Button.builder(conflictLabel(), button -> {
            conflictPolicy = switch (conflictPolicy) {
                case FAIL -> ClientBlueprintLibrary.ConflictPolicy.REPLACE;
                case REPLACE -> ClientBlueprintLibrary.ConflictPolicy.KEEP_BOTH;
                case KEEP_BOTH -> ClientBlueprintLibrary.ConflictPolicy.FAIL;
            };
            button.setMessage(conflictLabel());
        }).bounds(left + 155, top + 58, 145, 22).build());

        Button save = Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.save"),
                button -> save()).bounds(left + 82, top + 94, 100, 22).build();
        save.active = !saving;
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 192, top + 94, 100, 22).build());
    }

    private void save() {
        if (saving) return;
        BlueprintData value;
        try {
            value = copyWithSettings(name.getValue(), mode);
        } catch (RuntimeException exception) {
            status = exception.getMessage();
            return;
        }
        saving = true;
        rebuildWidgets();
        library.saveAsync(value, conflictPolicy).whenComplete((entry, failure) ->
                Minecraft.getInstance().execute(() -> {
                    saving = false;
                    if (failure == null) {
                        library.refreshAsync();
                        Minecraft.getInstance().setScreen(parent);
                    } else {
                        status = rootMessage(failure);
                        rebuildWidgets();
                    }
                }));
    }

    private BlueprintData copyWithSettings(String newName, BlueprintSaveMode newMode) {
        List<BlueprintData.BlockEntry> blocks = newMode == BlueprintSaveMode.BLOCKS_ONLY
                ? source.blocks().stream()
                .map(entry -> new BlueprintData.BlockEntry(entry.pos(), entry.stateIndex(), null)).toList()
                : source.blocks();
        return new BlueprintData(UUID.randomUUID(), newName, source.author(), source.authorUuid(),
                System.currentTimeMillis(), newMode, source.sizeX(), source.sizeY(), source.sizeZ(),
                source.anchor(), source.palette(), blocks);
    }

    private Component modeLabel() {
        return Component.translatable(mode == BlueprintSaveMode.BLOCKS_ONLY
                ? "screen.mydimension.realmwright.blueprint.blocks_only"
                : "screen.mydimension.realmwright.blueprint.full");
    }

    private Component conflictLabel() {
        return Component.translatable("screen.mydimension.realmwright.blueprint.conflict."
                + conflictPolicy.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 164;
        int top = height / 2 - 90;
        graphics.fill(left, top, left + 328, top + 150, 0xEE121822);
        graphics.renderOutline(left, top, 328, 150, 0xFF557A9F);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFFFF);
        if (!status.isBlank()) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(status, 300), width / 2,
                    top + 130, 0xFFFF6B63);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!saving && minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
