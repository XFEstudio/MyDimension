package com.xfestudio.mydimension.client.builder.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintNames;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.client.builder.BuilderClientNetworkBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Collects name and NBT policy before the server captures the selected cuboid. */
public final class BlueprintCaptureScreen extends Screen {
    private final Screen parent;
    private EditBox name;
    private BlueprintSaveMode mode = BlueprintSaveMode.BLOCKS_ONLY;
    private String status = "";

    public BlueprintCaptureScreen(Screen parent) {
        super(Component.translatable("screen.mydimension.realmwright.blueprint.capture"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int top = height / 2 - 60;
        name = new EditBox(font, left, top + 24, 300, 22,
                Component.translatable("screen.mydimension.realmwright.blueprint.name"));
        name.setMaxLength(64);
        name.setValue(Component.translatable("screen.mydimension.realmwright.blueprint.default_name").getString());
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(modeLabel(), button -> {
            mode = mode == BlueprintSaveMode.BLOCKS_ONLY ? BlueprintSaveMode.FULL : BlueprintSaveMode.BLOCKS_ONLY;
            button.setMessage(modeLabel());
        }).bounds(left, top + 55, 190, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.capture"),
                button -> submit()).bounds(left + 198, top + 55, 102, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 198, top + 80, 102, 20).build());
        setInitialFocus(name);
    }

    private void submit() {
        try {
            String normalized = BlueprintNames.normalize(name.getValue());
            BuilderClientNetworkBridge.requestCapture(normalized, mode);
            if (minecraft != null) minecraft.setScreen(parent);
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage() == null ? "Invalid blueprint name" : exception.getMessage();
        }
    }

    private Component modeLabel() {
        return Component.translatable(mode == BlueprintSaveMode.BLOCKS_ONLY
                ? "screen.mydimension.realmwright.blueprint.blocks_only"
                : "screen.mydimension.realmwright.blueprint.full");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 164;
        int top = height / 2 - 76;
        graphics.fill(left, top, left + 328, top + 150, 0xEE121822);
        graphics.renderOutline(left, top, 328, 150, 0xFF557A9F);
        graphics.drawCenteredString(font, title, width / 2, top + 9, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.blueprint.full_warning"),
                left + 14, top + 112, 0xFFFFB85C, false);
        if (!status.isBlank()) graphics.drawCenteredString(font, status, width / 2, top + 132, 0xFFFF6B63);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
