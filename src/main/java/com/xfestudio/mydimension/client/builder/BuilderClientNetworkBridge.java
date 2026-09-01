package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.network.builder.BuilderClientPacketHooks;
import com.xfestudio.mydimension.network.builder.BuilderCommandPacket;
import com.xfestudio.mydimension.network.builder.BuilderPreviewPacket;
import com.xfestudio.mydimension.network.builder.BuilderSnapshotPacket;
import com.xfestudio.mydimension.config.BuilderAvailability;
import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintPlacementPlan;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransform;
import com.xfestudio.mydimension.builder.blueprint.client.ClientBlueprintTransfers;
import com.xfestudio.mydimension.client.builder.blueprint.BlueprintCaptureScreen;
import com.xfestudio.mydimension.client.builder.blueprint.ClientBlueprintLibrary;
import com.xfestudio.mydimension.network.blueprint.BlueprintCaptureRequestPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintPlaceRequestPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintSelectionStartPacket;
import com.xfestudio.mydimension.network.blueprint.BlueprintSelectionCancelPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Converts client UX intents to ordinary builder packets and applies S2C state. */
@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class BuilderClientNetworkBridge implements BuilderClientBridge,
        BuilderClientPacketHooks.Receiver {
    private static final BuilderClientNetworkBridge INSTANCE = new BuilderClientNetworkBridge();
    private static final ClientBlueprintTransfers BLUEPRINT_TRANSFERS = new ClientBlueprintTransfers();

    private volatile BuilderClientSnapshot snapshot = BuilderClientSnapshot.EMPTY;
    @Nullable private volatile UUID selectedBlueprintId;
    @Nullable private BlueprintData selectedBlueprint;
    @Nullable private UUID blueprintToken;
    @Nullable private UUID pendingUpload;
    @Nullable private UUID pendingCapture;
    @Nullable private UUID pendingSelectionCapture;
    @Nullable private UUID pendingPlacement;
    private BlueprintTransform transform = BlueprintTransform.NONE;
    private BlockPos offset = BlockPos.ZERO;
    @Nullable private BlockPos lastPreviewAnchor;
    private boolean previewDirty;
    private int blueprintTargetMissTicks;

    private BuilderClientNetworkBridge() {
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BuilderClientServices.install(INSTANCE);
            BuilderClientPacketHooks.install(INSTANCE);
            BLUEPRINT_TRANSFERS.install();
        });
    }

    @Override
    public BuilderClientSnapshot snapshot() {
        BuilderClientSnapshot value = snapshot;
        UUID selected = selectedBlueprintId;
        if (java.util.Objects.equals(value.selectedBlueprintId(), selected)) return value;
        return new BuilderClientSnapshot(value.enabled(), value.mode(), value.surfaceMatch(),
                value.historyRecording(),
                value.buildLimit(), value.demolishLimit(), value.maximumBuildLimit(),
                value.maximumDemolishLimit(), value.reach(), value.status(), value.activeJobId(),
                value.completedBlocks(), value.totalBlocks(), value.canUndo(), value.canRedo(),
                value.anchors(), value.history(), selected);
    }

    @Override
    public void requestSnapshot() {
        ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.requestSnapshot());
    }

    @Override
    public void send(BuilderClientCommand command) {
        if (command instanceof BuilderClientCommand.OpenMenu) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.openMenu());
        } else if (command instanceof BuilderClientCommand.SetMode value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setMode(value.mode()));
        } else if (command instanceof BuilderClientCommand.SetSurfaceMatch value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setMatch(value.mode()));
        } else if (command instanceof BuilderClientCommand.SetLimits value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setLimits(
                    value.buildLimit(), value.demolishLimit()));
        } else if (command instanceof BuilderClientCommand.SetHistoryRecording value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setHistoryRecording(value.enabled()));
        } else if (command instanceof BuilderClientCommand.UseTarget value) {
            switch (value.kind()) {
                case AUTO -> ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.use(
                        target(value.target()), value.interactionOverride()));
                case RESUME_MISSING -> ModNetwork.CHANNEL.sendToServer(
                        BuilderCommandPacket.resume(value.activeJobId()));
                case PLACE_BLUEPRINT -> placeBlueprint(value.target());
            }
        } else if (command instanceof BuilderClientCommand.CancelActive value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.cancel(value.activeJobId()));
        } else if (command instanceof BuilderClientCommand.CancelBlueprint) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.cancelBlueprint());
        } else if (command instanceof BuilderClientCommand.Undo) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.undo());
        } else if (command instanceof BuilderClientCommand.Redo) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.redo());
        } else if (command instanceof BuilderClientCommand.UnbindAnchor value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.unbindAnchor(value.anchorId()));
        } else if (command instanceof BuilderClientCommand.MoveAnchor value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.moveAnchor(value.anchorId(), value.delta()));
        } else if (command instanceof BuilderClientCommand.SetAnchorPublic value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setAnchorPublic(
                    value.anchorId(), value.publicAccess()));
        } else if (command instanceof BuilderClientCommand.SetAnchorPlayer value) {
            ModNetwork.CHANNEL.sendToServer(BuilderCommandPacket.setAnchorPlayer(
                    value.anchorId(), value.playerName(), value.authorize()));
        } else if (command instanceof BuilderClientCommand.SelectBlueprint value) {
            selectedBlueprintId = value.blueprintId();
            selectBlueprint(value.blueprintId());
        } else if (command instanceof BuilderClientCommand.SelectBlueprintPoint value) {
            selectPoint(value.target());
        } else if (command instanceof BuilderClientCommand.ExecuteBlueprintAction value) {
            executeBlueprintAction(value.action());
        } else if (command instanceof BuilderClientCommand.AdjustBlueprint value) {
            adjustBlueprint(value.action(), value.direction());
        } else if (command instanceof BuilderClientCommand.ConfirmBlueprintAdjustment) {
            previewDirty = true;
        }
    }

    @Override
    public void snapshot(BuilderSnapshotPacket packet) {
        availability(packet.enabled());
        List<BuilderClientSnapshot.AnchorView> anchors = packet.anchors().stream()
                .map(anchor -> new BuilderClientSnapshot.AnchorView(anchor.id(), anchor.name(),
                        anchor.dimension(), anchor.packedPos(),
                        BuilderClientSnapshot.AnchorStatus.valueOf(anchor.status().name()),
                        anchor.owner(), anchor.publicAccess()))
                .toList();
        List<BuilderClientSnapshot.HistoryView> history = packet.history().stream()
                .map(entry -> new BuilderClientSnapshot.HistoryView(entry.id(), entry.label(),
                        entry.dimension(), entry.changedBlocks(), entry.createdAt(),
                        BuilderClientSnapshot.HistoryStatus.valueOf(entry.status().name())))
                .toList();
        snapshot = new BuilderClientSnapshot(packet.enabled(), packet.mode(), packet.surfaceMatch(),
                packet.historyRecording(),
                packet.buildLimit(), packet.demolishLimit(), packet.maximumBuildLimit(),
                packet.maximumDemolishLimit(), packet.reach(), packet.status(), packet.activeJobId(),
                packet.completedBlocks(), packet.totalBlocks(), packet.canUndo(), packet.canRedo(),
                anchors, history, selectedBlueprintId);
    }

    @Override
    public void availability(boolean enabled) {
        // Every ordinary build result carries the same enabled bit. Rebuilding all creative tabs
        // for that unchanged bit caused a large client-frame hitch after every click.
        if (!BuilderAvailability.acceptServerValue(enabled)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        boolean operatorTab = minecraft.player.canUseGameMasterBlocks()
                && minecraft.options.operatorItemsTab().get();
        // Force the vanilla creative-tab cache through a distinct key. If the creative screen is
        // open, leave the opposite key cached so its next container tick performs the visible refresh.
        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                minecraft.player.connection.enabledFeatures(), !operatorTab,
                minecraft.player.level().registryAccess());
        if (!(minecraft.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)) {
            net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                    minecraft.player.connection.enabledFeatures(), operatorTab,
                    minecraft.player.level().registryAccess());
        }
    }

    @Override
    public void preview(BuilderPreviewPacket packet) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, packet.dimension());
        List<BuilderPreviewState.Cell> cells = packet.cells().stream()
                .map(cell -> new BuilderPreviewState.Cell(cell.pos(), cell.state(),
                        BuilderPreviewState.Kind.valueOf(cell.kind().name()), cell.ghost()))
                .toList();
        BuilderPreviewState.Snapshot current = BuilderPreviewState.get().snapshot();
        BuilderPreviewState.Selection selection = BuilderPreviewState.mergeSelection(
                dimension, packet.first(), packet.second(), current);
        boolean cancelable = BuilderPreviewState.mergeCancelable(packet.cancelable(),
                packet.blueprintPreview(), selection);
        BuilderPreviewState.Snapshot incoming = new BuilderPreviewState.Snapshot(
                dimension, cells, selection, packet.activeJobId(), packet.blueprintPreview(),
                cancelable, packet.revision());
        boolean emptyServerWorkflow = packet.cells().isEmpty() && packet.first() == null
                && packet.second() == null && packet.activeJobId() == null
                && !packet.blueprintPreview() && !packet.cancelable();
        BuilderPreviewState.Snapshot merged = BuilderPreviewState.mergeServerSnapshot(
                current, incoming, emptyServerWorkflow);
        if (merged != current) BuilderPreviewState.get().accept(merged);
    }

    @Override
    public void openMenu() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BuilderToolScreen)) {
            BuilderClientServices.openToolScreen();
        }
    }

    private static BuilderCommandPacket.Target target(BuilderClientCommand.Target value) {
        return new BuilderCommandPacket.Target(value.blockPos(), value.face(), value.inside());
    }

    private static void selectPoint(BuilderClientCommand.Target target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        ResourceKey<Level> dimension = minecraft.level.dimension();
        BuilderPreviewState.Snapshot current = BuilderPreviewState.get().snapshot();
        BuilderPreviewState.Selection selection;
        if (current != null && current.dimension().equals(dimension)
                && current.selection().first() != null && current.selection().second() == null) {
            selection = new BuilderPreviewState.Selection(dimension,
                    current.selection().first(), target.blockPos());
        } else {
            selection = new BuilderPreviewState.Selection(dimension, target.blockPos(), null);
            ModNetwork.CHANNEL.sendToServer(new BlueprintSelectionStartPacket(target.blockPos()));
        }
        int revision = current == null ? 1 : current.revision() + 1;
        boolean complete = selection.first() != null && selection.second() != null;
        BuilderPreviewState.get().accept(new BuilderPreviewState.Snapshot(dimension, List.of(), selection,
                null, complete, true, revision));
        if (complete) {
            UUID request = UUID.randomUUID();
            INSTANCE.pendingSelectionCapture = request;
            ModNetwork.CHANNEL.sendToServer(new BlueprintCaptureRequestPacket(request, selection.first(),
                    selection.second(), BlueprintSaveMode.BLOCKS_ONLY, "Unsaved Selection", false));
        }
    }

    public static boolean requestCapture(String name, BlueprintSaveMode mode) {
        BuilderPreviewState.Snapshot preview = BuilderPreviewState.get().snapshot();
        if (preview == null || preview.selection().first() == null
                || preview.selection().second() == null || INSTANCE.pendingCapture != null) return false;
        UUID request = UUID.randomUUID();
        INSTANCE.pendingCapture = request;
        ModNetwork.CHANNEL.sendToServer(new BlueprintCaptureRequestPacket(request,
                preview.selection().first(), preview.selection().second(), mode, name, true));
        return true;
    }

    /** Stops routing a late capture response into a dialog the player already closed. */
    public static void cancelPendingCapture() {
        INSTANCE.pendingCapture = null;
    }

    public static void clientTick() {
        BLUEPRINT_TRANSFERS.tick();
        INSTANCE.pollBlueprintTransfers();
        boolean force = INSTANCE.previewDirty;
        INSTANCE.previewDirty = false;
        INSTANCE.refreshBlueprintPreview(force);
    }

    static void requestBlueprintPreviewRefresh() {
        // Coalesce high-resolution wheel input into one rebuild per client tick.
        INSTANCE.previewDirty = true;
    }

    public static void clearBlueprintSession() {
        INSTANCE.selectedBlueprint = null;
        INSTANCE.blueprintToken = null;
        INSTANCE.pendingUpload = null;
        INSTANCE.pendingCapture = null;
        INSTANCE.pendingSelectionCapture = null;
        INSTANCE.pendingPlacement = null;
        INSTANCE.lastPreviewAnchor = null;
        INSTANCE.previewDirty = false;
        INSTANCE.blueprintTargetMissTicks = 0;
        INSTANCE.transform = BlueprintTransform.NONE;
        INSTANCE.offset = BlockPos.ZERO;
        BLUEPRINT_TRANSFERS.clear();
    }

    public static void cancelBlueprintWorkflow() {
        ModNetwork.CHANNEL.sendToServer(new BlueprintSelectionCancelPacket());
        clearBlueprintSession();
        BuilderPreviewState.get().clearLocalWorkflow();
    }

    /** Drops only the transformed placement and keeps a source selection available for saving. */
    public static void cancelPlacementPreview() {
        INSTANCE.selectedBlueprintId = null;
        INSTANCE.selectedBlueprint = null;
        INSTANCE.blueprintToken = null;
        INSTANCE.pendingUpload = null;
        INSTANCE.pendingPlacement = null;
        INSTANCE.lastPreviewAnchor = null;
        INSTANCE.previewDirty = false;
        INSTANCE.blueprintTargetMissTicks = 0;
        INSTANCE.transform = BlueprintTransform.NONE;
        INSTANCE.offset = BlockPos.ZERO;
        BuilderPreviewState.get().clearPlacementPreview();
    }

    /** Cancels only the source-corner workflow; an already copied deployment remains selected. */
    public static void cancelSourceSelection() {
        ModNetwork.CHANNEL.sendToServer(new BlueprintSelectionCancelPacket());
        INSTANCE.pendingSelectionCapture = null;
        BuilderPreviewState.get().clearSelection();
    }

    private void selectBlueprint(UUID id) {
        ClientBlueprintLibrary.get().blueprint(id).ifPresentOrElse(blueprint -> {
            selectedBlueprint = blueprint;
            blueprintToken = null;
            transform = BlueprintTransform.NONE;
            offset = BlockPos.ZERO;
            lastPreviewAnchor = null;
            try {
                pendingUpload = BLUEPRINT_TRANSFERS.upload(blueprint);
            } catch (java.io.IOException ignored) {
                pendingUpload = null;
            }
        }, () -> {
            selectedBlueprint = null;
            blueprintToken = null;
        });
    }

    private void pollBlueprintTransfers() {
        if (pendingUpload != null) {
            BLUEPRINT_TRANSFERS.takeResult(pendingUpload).ifPresent(result -> {
                if (result.success()) blueprintToken = result.cacheToken();
                else showBlueprintMessage(result.message());
                pendingUpload = null;
                lastPreviewAnchor = null;
            });
        }
        if (pendingCapture != null) {
            UUID captureId = pendingCapture;
            java.util.Optional<ClientBlueprintTransfers.Download> download =
                    BLUEPRINT_TRANSFERS.takeDownload(captureId);
            if (download.isPresent()) {
                pendingCapture = null;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.screen instanceof BlueprintCaptureScreen captureScreen) {
                    captureScreen.acceptCapture(download.get().blueprint());
                }
            } else BLUEPRINT_TRANSFERS.takeResult(captureId).ifPresent(result -> {
                if (!result.success()) {
                    pendingCapture = null;
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.screen instanceof BlueprintCaptureScreen captureScreen) {
                        captureScreen.captureFailed(result.message());
                    } else {
                        showBlueprintMessage(result.message());
                    }
                }
            });
        }
        if (pendingSelectionCapture != null) {
            UUID selectionId = pendingSelectionCapture;
            java.util.Optional<ClientBlueprintTransfers.Download> download =
                    BLUEPRINT_TRANSFERS.takeDownload(selectionId);
            if (download.isPresent()) {
                pendingSelectionCapture = null;
                selectedBlueprint = download.get().blueprint();
                blueprintToken = download.get().cacheToken();
                transform = BlueprintTransform.NONE;
                offset = BlockPos.ZERO;
                lastPreviewAnchor = null;
            } else BLUEPRINT_TRANSFERS.takeResult(selectionId).ifPresent(result -> {
                if (!result.success()) {
                    pendingSelectionCapture = null;
                    showBlueprintMessage(result.message());
                }
            });
        }
        if (pendingPlacement != null) {
            UUID placementId = pendingPlacement;
            BLUEPRINT_TRANSFERS.takeResult(placementId).ifPresent(result -> {
                pendingPlacement = null;
                showBlueprintMessage(result.message());
            });
        }
    }

    private static void showBlueprintMessage(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && message != null && !message.isBlank()) {
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
    }

    private void executeBlueprintAction(BlueprintAltActionController.Action action) {
        if (action == BlueprintAltActionController.Action.SAVE) {
            BuilderPreviewState.Snapshot preview = BuilderPreviewState.get().snapshot();
            if (preview != null && preview.selection() != null && preview.selection().complete()) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.setScreen(new BlueprintCaptureScreen(minecraft.screen));
            }
            return;
        }
        if (selectedBlueprint == null) return;
        transform = switch (action) {
            case FLIP_X -> new BlueprintTransform(!transform.flipX(), transform.flipY(), transform.flipZ(),
                    transform.rotationY());
            case FLIP_Y -> new BlueprintTransform(transform.flipX(), !transform.flipY(), transform.flipZ(),
                    transform.rotationY());
            case FLIP_Z -> new BlueprintTransform(transform.flipX(), transform.flipY(), !transform.flipZ(),
                    transform.rotationY());
            case RESET -> BlueprintTransform.NONE;
            default -> transform;
        };
        if (action == BlueprintAltActionController.Action.RESET) offset = BlockPos.ZERO;
        previewDirty = true;
    }

    private void adjustBlueprint(BlueprintAltActionController.Action action, int direction) {
        if (selectedBlueprint == null || direction == 0) return;
        switch (action) {
            case ROTATE_Y -> transform = new BlueprintTransform(transform.flipX(), transform.flipY(),
                    transform.flipZ(), rotate(transform.rotationY(), direction));
            case OFFSET_X -> offset = offset.offset(direction, 0, 0);
            case OFFSET_Y -> offset = offset.offset(0, direction, 0);
            case OFFSET_Z -> offset = offset.offset(0, 0, direction);
            default -> { return; }
        }
        previewDirty = true;
    }

    private static Rotation rotate(Rotation current, int direction) {
        Rotation[] clockwise = { Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
                Rotation.COUNTERCLOCKWISE_90 };
        int index = 0;
        for (int i = 0; i < clockwise.length; i++) if (clockwise[i] == current) index = i;
        return clockwise[Math.floorMod(index + direction, clockwise.length)];
    }

    private void refreshBlueprintPreview(boolean force) {
        if (selectedBlueprint == null || blueprintToken == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
                || !BuilderClientServices.isHoldingRealmwright(minecraft)) return;
        BuilderClientCommand.Target airTarget = BuilderClientEvents.airPlacementTarget();
        BlockPos anchor;
        if (airTarget != null && airTarget.direct()) {
            anchor = airTarget.blockPos().offset(offset);
        } else {
            HitResult picked = minecraft.player.pick(Math.max(1, snapshot().reach()), 1.0F, false);
            if (!(picked instanceof BlockHitResult hit) || picked.getType() != HitResult.Type.BLOCK) {
                if (lastPreviewAnchor != null) {
                    if (++blueprintTargetMissTicks >= 2) {
                        lastPreviewAnchor = null;
                        blueprintTargetMissTicks = 0;
                        clearBlueprintCells(minecraft);
                    }
                }
                return;
            }
            anchor = hit.getBlockPos().relative(hit.getDirection()).offset(offset);
        }
        blueprintTargetMissTicks = 0;
        BuilderPreviewState.Snapshot currentPreview = BuilderPreviewState.get().snapshot();
        boolean currentGenerationIntact = currentPreview != null
                && currentPreview.blueprintPreview()
                && currentPreview.cells().size() == selectedBlueprint.blocks().size();
        if (!force && anchor.equals(lastPreviewAnchor) && currentGenerationIntact) return;
        lastPreviewAnchor = anchor;
        BlueprintPlacementPlan plan = BlueprintPlacementPlan.create(selectedBlueprint, transform, anchor);
        List<BuilderPreviewState.Cell> cells = plan.blocks().stream().map(block -> {
            net.minecraft.world.level.block.state.BlockState current = minecraft.level.getBlockState(block.worldPos());
            BuilderPreviewState.Kind kind = current.equals(block.state())
                    ? BuilderPreviewState.Kind.BLUEPRINT
                    : current.canBeReplaced() ? BuilderPreviewState.Kind.BUILD : BuilderPreviewState.Kind.INVALID;
            // An already-matching BLUEPRINT cell is the real world block itself. Baking a
            // second translucent model at the exact same coordinates causes depth fighting
            // while the player moves; retain its blue outline only.
            return new BuilderPreviewState.Cell(block.worldPos(), block.state(), kind,
                    kind == BuilderPreviewState.Kind.BUILD);
        }).toList();
        BuilderPreviewState.Snapshot old = BuilderPreviewState.get().snapshot();
        int revision = old == null ? 1 : old.revision() + 1;
        BuilderPreviewState.Selection selection = old == null
                ? new BuilderPreviewState.Selection(minecraft.level.dimension(), null, null) : old.selection();
        BuilderPreviewState.get().accept(new BuilderPreviewState.Snapshot(minecraft.level.dimension(), cells,
                selection, null, true, true, revision));
    }

    private static void clearBlueprintCells(Minecraft minecraft) {
        BuilderPreviewState.Snapshot old = BuilderPreviewState.get().snapshot();
        BuilderPreviewState.Selection selection = old == null
                ? new BuilderPreviewState.Selection(minecraft.level.dimension(), null, null)
                : old.selection();
        int revision = old == null ? 1 : old.revision() + 1;
        BuilderPreviewState.get().accept(new BuilderPreviewState.Snapshot(
                minecraft.level.dimension(), List.of(), selection, null,
                true, true, revision));
    }

    private void placeBlueprint(BuilderClientCommand.Target target) {
        if (blueprintToken == null || selectedBlueprint == null) return;
        BlockPos anchor = (target.direct() ? target.blockPos()
                : target.blockPos().relative(target.face())).offset(offset);
        UUID requestId = UUID.randomUUID();
        pendingPlacement = requestId;
        ModNetwork.CHANNEL.sendToServer(new BlueprintPlaceRequestPacket(requestId, blueprintToken,
                anchor, transform));
    }
}
