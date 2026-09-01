package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.InputConstants;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.BuilderInteractionPolicy;
import com.xfestudio.mydimension.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Self-registering client input, preview, and HUD hooks. */
@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BuilderClientEvents {
    private static final BlueprintAltActionController ALT_ACTIONS = new BlueprintAltActionController();
    private static final BuilderControlTargetController CONTROL_TARGET =
            new BuilderControlTargetController();

    private BuilderClientEvents() {
    }

    public static BlueprintAltActionController altActions() {
        return ALT_ACTIONS;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !BuilderClientServices.isHoldingRealmwright(minecraft)) {
            return;
        }

        if (event.isPickBlock()) {
            if (!Screen.hasAltDown() && !Screen.hasControlDown() && Screen.hasShiftDown()) {
                drain(BuilderKeyMappings.OPEN_MENU);
                drain(BuilderKeyMappings.TOGGLE_MODE);
                openMenu();
                cancel(event);
            } else if (!Screen.hasAltDown() && !Screen.hasControlDown() && !Screen.hasShiftDown()) {
                drain(BuilderKeyMappings.OPEN_MENU);
                drain(BuilderKeyMappings.TOGGLE_MODE);
                toggleMode();
                cancel(event);
            }
            return;
        }

        if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND) {
            if (Screen.hasAltDown() && BuilderPreviewState.get().hasBlueprintWheelActions()) {
                ALT_ACTIONS.openNavigation();
                ALT_ACTIONS.activateOrConfirm();
                cancel(event);
                return;
            }

            BuilderPreviewState preview = BuilderPreviewState.get();
            BuilderClientCommand.Target commandTarget;
            if (Screen.hasControlDown()) {
                commandTarget = CONTROL_TARGET.target();
                if (commandTarget == null) {
                    BlockHitResult blockTarget = extendedBlockHit(minecraft);
                    if (blockTarget == null) return;
                    commandTarget = target(blockTarget, false);
                }
                if (CONTROL_TARGET.isAirMode() && preview.isBlueprintPreviewActive()) {
                    BuilderClientServices.send(new BuilderClientCommand.UseTarget(commandTarget,
                            BuilderClientCommand.UseKind.PLACE_BLUEPRINT, preview.activeJobId()));
                } else {
                    BuilderClientServices.send(new BuilderClientCommand.SelectBlueprintPoint(commandTarget));
                }
                CONTROL_TARGET.consumeCurrent();
            } else {
                BuilderPreviewState.Cell virtualTarget = preview.virtualTarget();
                if (virtualTarget != null) {
                    // Resuming is job based; a yellow cell is a real custom-ray target even when
                    // there is no vanilla block behind it for player.pick() to return.
                    commandTarget = new BuilderClientCommand.Target(virtualTarget.pos(),
                            net.minecraft.core.Direction.UP,
                            net.minecraft.world.phys.Vec3.atCenterOf(virtualTarget.pos()),
                            false, true);
                    BuilderClientServices.send(new BuilderClientCommand.UseTarget(commandTarget,
                            BuilderClientCommand.UseKind.RESUME_MISSING, preview.activeJobId()));
                    cancel(event);
                    return;
                }
                BlockHitResult blockTarget = extendedBlockHit(minecraft);
                if (blockTarget == null) return;
                commandTarget = target(blockTarget, false);
                BuilderClientCommand.UseKind kind = preview.isBlueprintPreviewActive()
                        ? BuilderClientCommand.UseKind.PLACE_BLUEPRINT
                        : BuilderClientCommand.UseKind.AUTO;
                if (kind == BuilderClientCommand.UseKind.AUTO && !Screen.hasShiftDown()) {
                    boolean sameVanillaTarget = sameVanillaBlockTarget(
                            minecraft.hitResult, blockTarget);
                    if (BuilderInteractionPolicy.prioritizesBlock(
                            minecraft.level.getBlockState(blockTarget.getBlockPos()))) {
                        // Let vanilla call the block first. If it returns PASS, the ordinary item
                        // policy below still refuses the scepter. During a one-frame reach mismatch,
                        // cancel instead of accidentally using a different cached vanilla target.
                        if (!sameVanillaTarget) cancel(event);
                        return;
                    }
                    if (sameVanillaTarget) {
                        // A non-interactive block returns PASS and naturally reaches Item#useOn.
                        return;
                    }
                    // The configured snapshot and the reach attribute normally agree. If they
                    // are briefly out of sync, retain extended-range use only for a non-interactive
                    // surface; an interaction-priority target is never sent through this fallback.
                }
                BuilderClientServices.send(new BuilderClientCommand.UseTarget(
                        commandTarget, kind, preview.activeJobId(), Screen.hasShiftDown()));
            }
            cancel(event);
            return;
        }

        if (event.isAttack()) {
            BuilderPreviewState preview = BuilderPreviewState.get();
            BuilderPreviewState.CancelTarget cancelTarget = preview.focusedCancelTarget();
            if (cancelTarget == null) return;
            switch (cancelTarget) {
                case DEPLOYMENT -> {
                    BuilderClientServices.send(new BuilderClientCommand.CancelBlueprint());
                    BuilderClientNetworkBridge.cancelPlacementPreview();
                }
                case SELECTION -> BuilderClientNetworkBridge.cancelSourceSelection();
                case MISSING -> {
                    BuilderClientServices.send(new BuilderClientCommand.CancelActive(preview.activeJobId()));
                    preview.clearMissingPreview();
                }
            }
            cancel(event);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !BuilderClientServices.isHoldingRealmwright(minecraft)) {
            return;
        }
        if (Screen.hasControlDown() && !Screen.hasAltDown()) {
            int reach = Math.max(1, BuilderClientServices.snapshot().reach());
            if (CONTROL_TARGET.scroll(minecraft, event.getScrollDelta(), reach)) {
                BuilderClientNetworkBridge.requestBlueprintPreviewRefresh();
                event.setCanceled(true);
            }
            return;
        }
        if (!Screen.hasAltDown() || !BuilderPreviewState.get().hasBlueprintWheelActions()) return;
        ALT_ACTIONS.openNavigation();
        if (ALT_ACTIONS.scroll(event.getScrollDelta())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        if ((event.getKey() == GLFW.GLFW_KEY_LEFT_ALT || event.getKey() == GLFW.GLFW_KEY_RIGHT_ALT)
                && event.getAction() == InputConstants.RELEASE) {
            ALT_ACTIONS.closeOnAltRelease();
        }
        if ((event.getKey() == GLFW.GLFW_KEY_LEFT_CONTROL
                || event.getKey() == GLFW.GLFW_KEY_RIGHT_CONTROL)
                && event.getAction() == InputConstants.RELEASE) {
            CONTROL_TARGET.reset();
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BuilderAnchorPreviewTracker.tick(minecraft);
        if (!BuilderClientServices.isHoldingRealmwright(minecraft)) {
            drain(BuilderKeyMappings.TOGGLE_MODE);
            drain(BuilderKeyMappings.OPEN_MENU);
            drain(BuilderKeyMappings.UNDO);
            drain(BuilderKeyMappings.REDO);
            ALT_ACTIONS.reset();
            CONTROL_TARGET.reset();
            BuilderPreviewState.get().clearHover();
            BuilderSurfacePreviewPlanner.reset();
            BuilderPreviewRenderer.clearCache();
            return;
        }
        if (minecraft.screen != null) {
            drain(BuilderKeyMappings.TOGGLE_MODE);
            drain(BuilderKeyMappings.OPEN_MENU);
            drain(BuilderKeyMappings.UNDO);
            drain(BuilderKeyMappings.REDO);
            ALT_ACTIONS.reset();
            CONTROL_TARGET.reset();
            BuilderPreviewState.get().clearHover();
            BuilderSurfacePreviewPlanner.reset();
            // Menus temporarily pause targeting, but the immutable preview and its GPU cache stay
            // alive behind the screen. Closing a menu therefore cannot restart from an empty VBO.
            BuilderPreviewRenderer.tick(minecraft);
            return;
        }

        while (BuilderKeyMappings.OPEN_MENU.consumeClick()) {
            // Forge records every mapping sharing a mouse button before applying modifiers.
            // Consume the Shift binding on every middle click, but act only for exact Shift.
            if (Screen.hasShiftDown() && !Screen.hasAltDown() && !Screen.hasControlDown()) {
                drain(BuilderKeyMappings.TOGGLE_MODE);
                openMenu();
            }
        }
        while (BuilderKeyMappings.TOGGLE_MODE.consumeClick()) {
            if (!Screen.hasShiftDown() && !Screen.hasAltDown() && !Screen.hasControlDown()) {
                toggleMode();
            }
        }
        while (BuilderKeyMappings.UNDO.consumeClick()) {
            BuilderClientServices.send(new BuilderClientCommand.Undo());
        }
        while (BuilderKeyMappings.REDO.consumeClick()) {
            BuilderClientServices.send(new BuilderClientCommand.Redo());
        }

        int reach = Math.max(1, BuilderClientServices.snapshot().reach());
        CONTROL_TARGET.tick(minecraft, reach);
        BuilderSurfacePreviewPlanner.update(minecraft);
        BuilderPreviewState.get().updateHoveredTarget(minecraft, 1.0F, reach);
        BuilderPreviewRenderer.tick(minecraft);
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        BuilderPreviewRenderer.render(event);
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            BuilderHudRenderer.render(event.getGuiGraphics(), event.getWindow(), ALT_ACTIONS);
        }
    }

    private static void toggleMode() {
        BuilderMode next = BuilderClientServices.snapshot().mode().toggle();
        BuilderClientServices.send(new BuilderClientCommand.SetMode(next));
    }

    private static void openMenu() {
        BuilderClientServices.send(new BuilderClientCommand.OpenMenu());
        BuilderClientServices.openToolScreen();
    }

    private static void cancel(InputEvent.InteractionKeyMappingTriggered event) {
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    private static void drain(net.minecraft.client.KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // Prevent a default mouse click handled above from toggling again on the next tick.
        }
    }

    private static BlockHitResult extendedBlockHit(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        HitResult result = minecraft.player.pick(Math.max(1, BuilderClientServices.snapshot().reach()), 1.0F, false);
        return result instanceof BlockHitResult blockHit && result.getType() == HitResult.Type.BLOCK
                ? blockHit : null;
    }

    static BuilderClientCommand.Target target(BlockHitResult result, boolean direct) {
        BlockPos pos = result.getBlockPos();
        return new BuilderClientCommand.Target(pos, result.getDirection(), result.getLocation(),
                result.isInside(), direct);
    }

    static boolean sameVanillaBlockTarget(HitResult vanillaTarget, BlockHitResult requested) {
        return vanillaTarget instanceof BlockHitResult blockTarget
                && vanillaTarget.getType() == HitResult.Type.BLOCK
                && blockTarget.getBlockPos().equals(requested.getBlockPos())
                && blockTarget.getDirection() == requested.getDirection()
                && blockTarget.isInside() == requested.isInside();
    }

    @javax.annotation.Nullable
    static BuilderClientCommand.Target airPlacementTarget() {
        return CONTROL_TARGET.isAirMode() ? CONTROL_TARGET.target() : null;
    }

    static void updateControlTargetForRender(Minecraft minecraft) {
        CONTROL_TARGET.tick(minecraft,
                Math.max(1, BuilderClientServices.snapshot().reach()));
    }
}
