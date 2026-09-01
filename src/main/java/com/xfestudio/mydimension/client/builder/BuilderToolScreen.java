package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import com.xfestudio.mydimension.client.builder.blueprint.BlueprintRenameScreen;
import com.xfestudio.mydimension.client.builder.blueprint.ClientBlueprintLibrary;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.ControlsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.UUID;

/** Five-tab, non-pausing control surface for the Realmwright's Scepter. */
public class BuilderToolScreen extends Screen {
    private static final int PANEL_WIDTH = 540;
    private static final int PANEL_HEIGHT = 322;
    private static final int PAGE_ROWS = 6;

    private final ClientBlueprintLibrary library = ClientBlueprintLibrary.get();
    private Tab tab = Tab.OPERATIONS;
    private BuilderClientSnapshot snapshot = BuilderClientSnapshot.EMPTY;
    private BuilderMode localMode = BuilderMode.BUILD;
    private SurfaceMatchMode localMatch = SurfaceMatchMode.SAME_BLOCK;
    private boolean localHistoryRecording;
    private EditBox buildLimit;
    private EditBox demolishLimit;
    private EditBox aclPlayer;
    private int anchorOffset;
    private int blueprintOffset;
    private int historyOffset;
    private UUID selectedLocalBlueprint;
    private UUID deleteConfirmation;
    private UUID selectedManagedAnchor;
    private String notice = "";
    private int requestTicker;

    public BuilderToolScreen() {
        super(Component.translatable("screen.mydimension.realmwright.title"));
    }

    @Override
    protected void init() {
        snapshot = BuilderClientServices.snapshot();
        if (!snapshot.enabled() && tab != Tab.BLUEPRINTS && tab != Tab.SETTINGS) tab = Tab.BLUEPRINTS;
        localMode = snapshot.mode();
        localMatch = snapshot.surfaceMatch();
        localHistoryRecording = snapshot.historyRecording();
        addTabs();
        switch (tab) {
            case OPERATIONS -> initOperations();
            case SUPPLIES -> initSupplies();
            case BLUEPRINTS -> initBlueprints();
            case HISTORY -> initHistory();
            case SETTINGS -> initSettings();
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelRight() - 70, panelBottom() - 27, 58, 20).build());
        if (library.entries().isEmpty() && !library.refreshing()) {
            library.refreshAsync();
        }
    }

    private void addTabs() {
        int left = panelLeft() + 12;
        int width = (panelWidth() - 24) / Tab.values().length;
        for (int index = 0; index < Tab.values().length; index++) {
            Tab value = Tab.values()[index];
            Button button = Button.builder(Component.translatable(value.translationKey), clicked -> {
                        if (tab != value) {
                            tab = value;
                            rebuildWidgets();
                        }
                    }).bounds(left + index * width, panelTop() + 34, width - 3, 22).build();
            button.active = snapshot.enabled() ? tab != value
                    : (value == Tab.BLUEPRINTS || value == Tab.SETTINGS) && tab != value;
            addRenderableWidget(button);
        }
    }

    private void initOperations() {
        int left = contentLeft();
        int top = contentTop();
        Button mode = Button.builder(modeLabel(), button -> {
            localMode = localMode.toggle();
            button.setMessage(modeLabel());
            BuilderClientServices.send(new BuilderClientCommand.SetMode(localMode));
        }).bounds(left, top, 172, 24).build();
        addRenderableWidget(mode);

        Button match = Button.builder(matchLabel(), button -> {
            localMatch = localMatch.toggle();
            button.setMessage(matchLabel());
            BuilderClientServices.send(new BuilderClientCommand.SetSurfaceMatch(localMatch));
        }).bounds(left + 184, top, 184, 24).build();
        addRenderableWidget(match);

        buildLimit = numericBox(left, top + 55, snapshot.buildLimit(), 112);
        demolishLimit = numericBox(left + 132, top + 55, snapshot.demolishLimit(), 112);
        addRenderableWidget(buildLimit);
        addRenderableWidget(demolishLimit);
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.apply"), button -> applyLimits())
                .bounds(left + 264, top + 55, 104, 20).build());

        Button history = Button.builder(historyRecordingLabel(), button -> {
            localHistoryRecording = !localHistoryRecording;
            button.setMessage(historyRecordingLabel());
            BuilderClientServices.send(new BuilderClientCommand.SetHistoryRecording(localHistoryRecording));
        }).bounds(left, top + 82, 172, 20).build();
        addRenderableWidget(history);

        Button cancel = Button.builder(Component.translatable("screen.mydimension.realmwright.cancel_job"), button -> {
            BuilderClientServices.send(new BuilderClientCommand.CancelActive(snapshot.activeJobId()));
            BuilderPreviewState.get().clearLocalWorkflow();
        }).bounds(left, top + 108, 172, 20).build();
        cancel.active = snapshot.activeJobId() != null;
        addRenderableWidget(cancel);

        Button undo = Button.builder(Component.translatable("screen.mydimension.realmwright.undo"),
                button -> BuilderClientServices.send(new BuilderClientCommand.Undo()))
                .bounds(left + 184, top + 108, 86, 20).build();
        undo.active = snapshot.canUndo();
        addRenderableWidget(undo);
        Button redo = Button.builder(Component.translatable("screen.mydimension.realmwright.redo"),
                button -> BuilderClientServices.send(new BuilderClientCommand.Redo()))
                .bounds(left + 282, top + 108, 86, 20).build();
        redo.active = snapshot.canRedo();
        addRenderableWidget(redo);
    }

    private void initSupplies() {
        List<BuilderClientSnapshot.AnchorView> anchors = snapshot.anchors();
        anchorOffset = clampOffset(anchorOffset, anchors.size());
        int left = contentLeft();
        int top = contentTop();
        int shown = Math.min(PAGE_ROWS, anchors.size() - anchorOffset);
        for (int row = 0; row < shown; row++) {
            int index = anchorOffset + row;
            BuilderClientSnapshot.AnchorView anchor = anchors.get(index);
            int y = top + row * 30;
            Button anchorLine = Button.builder(anchorLabel(anchor), button -> {
                selectedManagedAnchor = anchor.id();
                rebuildWidgets();
            }).bounds(left, y, 250, 23).build();
            anchorLine.active = anchor.owner() && !anchor.id().equals(selectedManagedAnchor);
            addRenderableWidget(anchorLine);
            addRenderableWidget(Button.builder(Component.literal("↑"), button -> {
                BuilderClientServices.send(new BuilderClientCommand.MoveAnchor(anchor.id(), -1));
                BuilderClientServices.bridge().requestSnapshot();
            }).bounds(left + 256, y, 26, 23).build()).active = index > 0;
            addRenderableWidget(Button.builder(Component.literal("↓"), button -> {
                BuilderClientServices.send(new BuilderClientCommand.MoveAnchor(anchor.id(), 1));
                BuilderClientServices.bridge().requestSnapshot();
            }).bounds(left + 286, y, 26, 23).build()).active = index + 1 < anchors.size();
            addRenderableWidget(Button.builder(Component.literal("×"), button ->
                            BuilderClientServices.send(new BuilderClientCommand.UnbindAnchor(anchor.id())))
                    .bounds(left + 316, y, 26, 23).build());
            Button visibility = Button.builder(Component.literal(anchor.publicAccess() ? "🌐" : "🔒"), button ->
                            BuilderClientServices.send(new BuilderClientCommand.SetAnchorPublic(anchor.id(),
                                    !anchor.publicAccess())))
                    .bounds(left + 346, y, 28, 23).build();
            visibility.active = anchor.owner();
            addRenderableWidget(visibility);
        }
        BuilderClientSnapshot.AnchorView managed = anchors.stream()
                .filter(anchor -> anchor.id().equals(selectedManagedAnchor) && anchor.owner())
                .findFirst().orElse(null);
        int aclY = contentBottom() - 24;
        aclPlayer = new EditBox(font, left, aclY, 176, 20,
                Component.translatable("screen.mydimension.realmwright.anchor_player"));
        aclPlayer.setMaxLength(16);
        aclPlayer.setHint(Component.translatable("screen.mydimension.realmwright.anchor_player"));
        aclPlayer.setEditable(managed != null);
        addRenderableWidget(aclPlayer);
        Button allow = Button.builder(Component.translatable("screen.mydimension.realmwright.anchor_allow"), button ->
                        updateAnchorPlayer(true)).bounds(left + 184, aclY, 90, 20).build();
        allow.active = managed != null;
        addRenderableWidget(allow);
        Button revoke = Button.builder(Component.translatable("screen.mydimension.realmwright.anchor_revoke"), button ->
                        updateAnchorPlayer(false)).bounds(left + 282, aclY, 90, 20).build();
        revoke.active = managed != null;
        addRenderableWidget(revoke);
    }

    private void initBlueprints() {
        List<ClientBlueprintLibrary.Entry> entries = library.entries();
        blueprintOffset = clampOffset(blueprintOffset, entries.size());
        int left = contentLeft();
        int top = contentTop();
        int shown = Math.min(PAGE_ROWS, entries.size() - blueprintOffset);
        for (int row = 0; row < shown; row++) {
            ClientBlueprintLibrary.Entry entry = entries.get(blueprintOffset + row);
            int y = top + row * 28;
            Button button = Button.builder(blueprintLabel(entry), clicked -> {
                if (entry.valid()) {
                    selectedLocalBlueprint = entry.id();
                    BuilderClientServices.send(new BuilderClientCommand.SelectBlueprint(entry.id()));
                    rebuildWidgets();
                }
            }).bounds(left, y, 374, 22).build();
            button.active = entry.valid() && !entry.id().equals(selectedLocalBlueprint);
            addRenderableWidget(button);
        }

        int actionY = contentBottom() - 24;
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.import"), button -> importBlueprint())
                .bounds(left, actionY, 62, 20).build());
        Button export = Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.export"), button -> exportBlueprint())
                .bounds(left + 66, actionY, 62, 20).build();
        export.active = selectedLocalBlueprint != null;
        addRenderableWidget(export);
        Button rename = Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.rename"), button -> renameBlueprint())
                .bounds(left + 132, actionY, 62, 20).build();
        rename.active = selectedLocalBlueprint != null;
        addRenderableWidget(rename);
        Button delete = Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.delete"), button -> deleteBlueprint())
                .bounds(left + 198, actionY, 62, 20).build();
        delete.active = selectedLocalBlueprint != null;
        addRenderableWidget(delete);
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.refresh"), button ->
                        library.refreshAsync().thenRun(() -> executeOnClient(this::rebuildWidgets)))
                .bounds(left + 264, actionY, 62, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.blueprint.save_current"), button ->
                        BuilderClientServices.send(new BuilderClientCommand.ExecuteBlueprintAction(
                                BlueprintAltActionController.Action.SAVE)))
                .bounds(left + 330, actionY, 90, 20).build());
    }

    private void initHistory() {
        List<BuilderClientSnapshot.HistoryView> history = snapshot.history();
        historyOffset = clampOffset(historyOffset, history.size());
        int left = contentLeft();
        int top = contentTop();
        int shown = Math.min(PAGE_ROWS, history.size() - historyOffset);
        for (int row = 0; row < shown; row++) {
            BuilderClientSnapshot.HistoryView entry = history.get(historyOffset + row);
            int y = top + row * 28;
            Button line = Button.builder(historyLabel(entry), button -> {
            }).bounds(left, y, 374, 22).build();
            line.active = false;
            addRenderableWidget(line);
        }
        Button undo = Button.builder(Component.translatable("screen.mydimension.realmwright.undo"),
                button -> BuilderClientServices.send(new BuilderClientCommand.Undo()))
                .bounds(left, contentBottom() - 24, 96, 20).build();
        undo.active = snapshot.canUndo();
        addRenderableWidget(undo);
        Button redo = Button.builder(Component.translatable("screen.mydimension.realmwright.redo"),
                button -> BuilderClientServices.send(new BuilderClientCommand.Redo()))
                .bounds(left + 104, contentBottom() - 24, 96, 20).build();
        redo.active = snapshot.canRedo();
        addRenderableWidget(redo);
    }

    private void initSettings() {
        int left = contentLeft();
        int top = contentTop() + 116;
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.controls"), button -> {
                    if (minecraft != null) minecraft.setScreen(new ControlsScreen(this, minecraft.options));
                }).bounds(left, top, 178, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mydimension.realmwright.open_library"), button -> {
                    try {
                        java.nio.file.Files.createDirectories(library.directory());
                        Util.getPlatform().openFile(library.directory().toFile());
                    } catch (java.io.IOException exception) {
                        notice = exception.getMessage();
                    }
                }).bounds(left + 190, top, 178, 22).build());
    }

    @Override
    public void tick() {
        super.tick();
        BuilderClientSnapshot latest = BuilderClientServices.snapshot();
        if (latest != snapshot) snapshot = latest;
        if (++requestTicker >= 20) {
            requestTicker = 0;
            BuilderClientServices.bridge().requestSnapshot();
        }
        if (buildLimit != null) buildLimit.tick();
        if (demolishLimit != null) demolishLimit.tick();
        if (aclPlayer != null) aclPlayer.tick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0 ? -1 : delta < 0 ? 1 : 0;
        if (direction != 0) {
            switch (tab) {
                case SUPPLIES -> anchorOffset = scroll(anchorOffset, direction, snapshot.anchors().size());
                case BLUEPRINTS -> blueprintOffset = scroll(blueprintOffset, direction, library.entries().size());
                case HISTORY -> historyOffset = scroll(historyOffset, direction, snapshot.history().size());
                default -> {
                    return super.mouseScrolled(mouseX, mouseY, delta);
                }
            }
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(panelLeft(), panelTop(), panelRight(), panelBottom(), 0xE7121822);
        graphics.renderOutline(panelLeft(), panelTop(), panelWidth(), panelHeight(), 0xFF557A9F);
        graphics.drawString(font, title, panelLeft() + 14, panelTop() + 13, 0xFFFFFFFF, false);

        if (!snapshot.enabled()) {
            Component disabled = Component.translatable("screen.mydimension.realmwright.disabled");
            graphics.drawCenteredString(font, disabled, width / 2, contentTop() + 82, 0xFFFF6B63);
        } else {
            renderPageText(graphics);
        }
        if (!notice.isBlank()) {
            graphics.drawString(font, font.plainSubstrByWidth(notice, panelWidth() - 100),
                    panelLeft() + 14, panelBottom() - 22, 0xFFFFD66B, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPageText(GuiGraphics graphics) {
        int left = contentLeft();
        int top = contentTop();
        switch (tab) {
            case OPERATIONS -> {
                graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.build_limit"),
                        left, top + 42, 0xFFBFC9D8, false);
                graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.demolish_limit"),
                        left + 132, top + 42, 0xFFBFC9D8, false);
                String progress = snapshot.totalBlocks() <= 0 ? snapshot.status()
                        : snapshot.completedBlocks() + " / " + snapshot.totalBlocks() + "  " + snapshot.status();
                graphics.drawString(font, progress, left, top + 140, 0xFFE7EDF7, false);
            }
            case SUPPLIES -> {
                if (snapshot.anchors().isEmpty()) {
                    graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.no_anchors"),
                            left, top + 8, 0xFF9FAABC, false);
                }
            }
            case BLUEPRINTS -> {
                if (library.refreshing()) {
                    graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.blueprint.loading"),
                            left, top + 8, 0xFF9FAABC, false);
                } else if (library.entries().isEmpty()) {
                    graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.blueprint.empty"),
                            left, top + 8, 0xFF9FAABC, false);
                }
            }
            case HISTORY -> {
                if (snapshot.history().isEmpty()) {
                    graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.history.empty"),
                            left, top + 8, 0xFF9FAABC, false);
                }
            }
            case SETTINGS -> {
                graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.reach", snapshot.reach()),
                        left, top, 0xFFE7EDF7, false);
                graphics.drawString(font, BuilderKeyMappings.TOGGLE_MODE.getTranslatedKeyMessage(),
                        left, top + 24, 0xFFBFC9D8, false);
                graphics.drawString(font, BuilderKeyMappings.UNDO.getTranslatedKeyMessage(),
                        left, top + 44, 0xFFBFC9D8, false);
                graphics.drawString(font, BuilderKeyMappings.REDO.getTranslatedKeyMessage(),
                        left, top + 64, 0xFFBFC9D8, false);
                graphics.drawString(font, Component.translatable("screen.mydimension.realmwright.alt_help"),
                        left, top + 88, 0xFFBFC9D8, false);
            }
        }
    }

    private void applyLimits() {
        try {
            int build = Mth.clamp(Integer.parseInt(buildLimit.getValue()), 1, snapshot.maximumBuildLimit());
            int demolish = Mth.clamp(Integer.parseInt(demolishLimit.getValue()), 1, snapshot.maximumDemolishLimit());
            buildLimit.setValue(Integer.toString(build));
            demolishLimit.setValue(Integer.toString(demolish));
            BuilderClientServices.send(new BuilderClientCommand.SetLimits(build, demolish));
            notice = "";
        } catch (NumberFormatException exception) {
            notice = Component.translatable("screen.mydimension.realmwright.invalid_number").getString();
        }
    }

    private void updateAnchorPlayer(boolean authorize) {
        if (selectedManagedAnchor == null || aclPlayer == null || aclPlayer.getValue().isBlank()) return;
        BuilderClientServices.send(new BuilderClientCommand.SetAnchorPlayer(selectedManagedAnchor,
                aclPlayer.getValue(), authorize));
        aclPlayer.setValue("");
    }

    private void importBlueprint() {
        library.chooseImportFile().ifPresent(path -> {
            notice = Component.translatable("screen.mydimension.realmwright.blueprint.importing").getString();
            library.importAsync(path, ClientBlueprintLibrary.ConflictPolicy.FAIL)
                    .whenComplete((entry, failure) -> executeOnClient(() -> {
                        notice = failure == null ? entry.name() : rootMessage(failure);
                        library.refreshAsync().thenRun(() -> executeOnClient(this::rebuildWidgets));
                    }));
        });
    }

    private void exportBlueprint() {
        if (selectedLocalBlueprint == null) return;
        ClientBlueprintLibrary.Entry entry = library.entries().stream()
                .filter(value -> value.id().equals(selectedLocalBlueprint)).findFirst().orElse(null);
        if (entry == null) return;
        library.chooseExportFile(entry.name()).ifPresent(path ->
                library.exportAsync(entry.id(), path, false).whenComplete((output, failure) ->
                        executeOnClient(() -> notice = failure == null ? output.toString() : rootMessage(failure))));
    }

    private void renameBlueprint() {
        if (selectedLocalBlueprint == null || minecraft == null) return;
        ClientBlueprintLibrary.Entry entry = library.entries().stream()
                .filter(value -> value.valid() && value.id().equals(selectedLocalBlueprint))
                .findFirst().orElse(null);
        if (entry != null) minecraft.setScreen(new BlueprintRenameScreen(this, entry));
    }

    private void deleteBlueprint() {
        if (selectedLocalBlueprint == null) return;
        if (!selectedLocalBlueprint.equals(deleteConfirmation)) {
            deleteConfirmation = selectedLocalBlueprint;
            notice = Component.translatable("screen.mydimension.realmwright.blueprint.confirm_delete").getString();
            return;
        }
        UUID deleting = selectedLocalBlueprint;
        deleteConfirmation = null;
        library.deleteAsync(deleting).whenComplete((deleted, failure) -> executeOnClient(() -> {
            if (failure == null && Boolean.TRUE.equals(deleted)) selectedLocalBlueprint = null;
            notice = failure == null ? "" : rootMessage(failure);
            library.refreshAsync().thenRun(() -> executeOnClient(this::rebuildWidgets));
        }));
    }

    private EditBox numericBox(int x, int y, int value, int width) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.empty());
        box.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        box.setMaxLength(7);
        box.setValue(Integer.toString(value));
        return box;
    }

    private Component modeLabel() {
        return Component.translatable(localMode == BuilderMode.BUILD
                ? "screen.mydimension.realmwright.mode.build" : "screen.mydimension.realmwright.mode.demolish");
    }

    private Component matchLabel() {
        return Component.translatable(localMatch == SurfaceMatchMode.SAME_BLOCK
                ? "screen.mydimension.realmwright.match.same" : "screen.mydimension.realmwright.match.any");
    }

    private Component historyRecordingLabel() {
        return Component.translatable(localHistoryRecording
                ? "screen.mydimension.realmwright.history_recording.on"
                : "screen.mydimension.realmwright.history_recording.off");
    }

    private Component anchorLabel(BuilderClientSnapshot.AnchorView anchor) {
        BlockPos pos = BlockPos.of(anchor.packedPos());
        return Component.literal(anchor.name() + "  " + anchor.status().name().toLowerCase(java.util.Locale.ROOT)
                + "  " + anchor.dimension() + " " + pos.toShortString());
    }

    private Component blueprintLabel(ClientBlueprintLibrary.Entry entry) {
        if (!entry.valid()) return Component.literal("! " + entry.name());
        String selected = entry.id().equals(selectedLocalBlueprint) ? "✓ " : "";
        return Component.literal(selected + entry.name() + "  " + entry.sizeX() + "×" + entry.sizeY() + "×"
                + entry.sizeZ() + "  [" + entry.blocks() + "]");
    }

    private Component historyLabel(BuilderClientSnapshot.HistoryView entry) {
        return Component.literal(entry.label() + "  " + entry.changedBlocks() + "  "
                + entry.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private int scroll(int offset, int direction, int size) {
        return Mth.clamp(offset + direction, 0, Math.max(0, size - PAGE_ROWS));
    }

    private int clampOffset(int offset, int size) {
        return Mth.clamp(offset, 0, Math.max(0, size - PAGE_ROWS));
    }

    private void executeOnClient(Runnable action) {
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        client.execute(action);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private int panelWidth() { return Math.min(PANEL_WIDTH, width - 16); }
    private int panelHeight() { return Math.min(PANEL_HEIGHT, height - 16); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int panelRight() { return panelLeft() + panelWidth(); }
    private int panelBottom() { return panelTop() + panelHeight(); }
    private int contentLeft() { return panelLeft() + Math.max(14, (panelWidth() - 400) / 2); }
    private int contentTop() { return panelTop() + 70; }
    private int contentBottom() { return panelBottom() - 38; }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Tab {
        OPERATIONS("screen.mydimension.realmwright.tab.operations"),
        SUPPLIES("screen.mydimension.realmwright.tab.supplies"),
        BLUEPRINTS("screen.mydimension.realmwright.tab.blueprints"),
        HISTORY("screen.mydimension.realmwright.tab.history"),
        SETTINGS("screen.mydimension.realmwright.tab.settings");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
