package com.xfestudio.mydimension.client.builder.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintNames;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.client.builder.BuilderClientNetworkBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Collects name and NBT policy before the server captures the selected cuboid. */
public final class BlueprintCaptureScreen extends Screen {
    private final Screen parent;
    private final ClientBlueprintLibrary library = ClientBlueprintLibrary.get();
    private EditBox name;
    private BlueprintSaveMode mode = BlueprintSaveMode.BLOCKS_ONLY;
    private String status = "";
    private boolean saving;
    private Button modeButton;
    private Button saveButton;
    private Button cancelButton;
    private BlueprintData capturedBlueprint;
    private BlueprintData pendingReplacement;
    private String submittedName = "";
    private BlueprintSaveMode submittedMode = BlueprintSaveMode.BLOCKS_ONLY;

    public BlueprintCaptureScreen(Screen parent) {
        // This dialog is entered from the wheel's SAVE action. Although the server performs
        // a fresh capture after the player chooses the NBT policy, the user-facing operation
        // is saving the already selected cuboid, not selecting/copying it again.
        super(Component.translatable("screen.mydimension.realmwright.blueprint.save"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelTop = height / 2 - 91;
        int left = width / 2 - 150;
        String retainedName = name == null
                ? Component.translatable("screen.mydimension.realmwright.blueprint.default_name").getString()
                : name.getValue();
        name = new EditBox(font, left, panelTop + 34, 300, 22,
                Component.translatable("screen.mydimension.realmwright.blueprint.name"));
        name.setMaxLength(64);
        // Screen#init runs again after resize/fullscreen changes. Keep both the user's draft and
        // an in-progress conflict/replace state instead of silently restoring the default name.
        name.setValue(retainedName);
        name.setResponder(ignored -> invalidateReplacement(false));
        addRenderableWidget(name);

        // The save policy is one complete row: it is a setting, not a peer of the
        // primary Save action. Save and Cancel then form the second action row.
        modeButton = addRenderableWidget(Button.builder(modeLabel(), button -> {
            invalidateReplacement(true);
            mode = mode == BlueprintSaveMode.BLOCKS_ONLY ? BlueprintSaveMode.FULL : BlueprintSaveMode.BLOCKS_ONLY;
            button.setMessage(modeLabel());
        }).bounds(left, panelTop + 65, 300, 22).build());
        saveButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.mydimension.realmwright.blueprint.save"),
                button -> submit()).bounds(left, panelTop + 97, 146, 22).build());
        cancelButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 154, panelTop + 97, 146, 22).build());
        updateActiveState();
        setInitialFocus(name);
    }

    private void submit() {
        if (saving) return;
        try {
            String normalized = BlueprintNames.normalize(name.getValue());
            if (pendingReplacement != null
                    && normalized.equals(submittedName) && mode == submittedMode) {
                saveCaptured(pendingReplacement, ClientBlueprintLibrary.ConflictPolicy.REPLACE);
                return;
            }
            pendingReplacement = null;
            if (capturedBlueprint != null && mode == submittedMode) {
                // Renaming does not change any server-authoritative world data. Reuse the
                // capture already returned for this dialog instead of immediately hitting the
                // capture cooldown again after a name conflict.
                submittedName = normalized;
                BlueprintData renamed = capturedBlueprint.withIdentity(capturedBlueprint.id(), normalized);
                saveCaptured(renamed, ClientBlueprintLibrary.ConflictPolicy.FAIL);
                return;
            }
            capturedBlueprint = null;
            submittedName = normalized;
            submittedMode = mode;
            saving = true;
            status = Component.translatable(
                    "screen.mydimension.realmwright.blueprint.saving").getString();
            updateActiveState();
            if (!BuilderClientNetworkBridge.requestCapture(normalized, mode)) {
                captureFailed(Component.translatable(
                        "screen.mydimension.realmwright.blueprint.selection_missing").getString());
            }
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage() == null ? "Invalid blueprint name" : exception.getMessage();
        }
    }

    /** Receives the one server-authoritative capture and saves it without opening another dialog. */
    public void acceptCapture(BlueprintData captured) {
        if (!saving) return;
        capturedBlueprint = captured;
        saveCaptured(captured, ClientBlueprintLibrary.ConflictPolicy.FAIL);
    }

    private void saveCaptured(BlueprintData captured, ClientBlueprintLibrary.ConflictPolicy policy) {
        capturedBlueprint = captured;
        saving = true;
        status = Component.translatable(
                "screen.mydimension.realmwright.blueprint.saving").getString();
        updateActiveState();
        library.saveAsync(captured, policy)
                .whenComplete((entry, failure) -> Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(parent);
                        return;
                    }
                    Throwable root = rootCause(failure);
                    if (root instanceof ClientBlueprintLibrary.NameConflictException conflict) {
                        pendingReplacement = captured;
                        status = Component.translatable(
                                "screen.mydimension.realmwright.blueprint.name_conflict",
                                conflict.existingName()).getString();
                    } else {
                        status = root.getMessage() == null
                                ? root.getClass().getSimpleName() : root.getMessage();
                    }
                    saving = false;
                    updateActiveState();
                }));
    }

    /** Keeps transfer/capture failures visible in this same save dialog. */
    public void captureFailed(String message) {
        capturedBlueprint = null;
        pendingReplacement = null;
        status = message == null || message.isBlank()
                ? Component.translatable("screen.mydimension.realmwright.blueprint.save_failed").getString()
                : message;
        saving = false;
        updateActiveState();
    }

    private void updateActiveState() {
        if (name != null) name.setEditable(!saving);
        if (modeButton != null) modeButton.active = !saving;
        if (saveButton != null) {
            saveButton.active = !saving;
            saveButton.setMessage(Component.translatable(pendingReplacement == null
                    ? "screen.mydimension.realmwright.blueprint.save"
                    : "screen.mydimension.realmwright.blueprint.replace"));
        }
        if (cancelButton != null) cancelButton.active = !saving;
    }

    private void invalidateReplacement(boolean discardCapture) {
        if (saving) return;
        if (discardCapture) capturedBlueprint = null;
        if (pendingReplacement == null) return;
        pendingReplacement = null;
        status = "";
        updateActiveState();
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
        int top = height / 2 - 91;
        graphics.fill(left, top, left + 328, top + 182, 0xEE121822);
        graphics.renderOutline(left, top, 328, 182, 0xFF557A9F);
        graphics.drawCenteredString(font, title, width / 2, top + 9, 0xFFFFFFFF);
        int warningY = top + 127;
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.translatable("screen.mydimension.realmwright.blueprint.full_warning"), 300)) {
            graphics.drawString(font, line, left + 14, warningY, 0xFFFFB85C, false);
            warningY += font.lineHeight + 1;
        }
        if (!status.isBlank()) graphics.drawCenteredString(font,
                font.plainSubstrByWidth(status, 300), width / 2, top + 165, 0xFFFF6B63);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (saving) return;
        BuilderClientNetworkBridge.cancelPendingCapture();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
