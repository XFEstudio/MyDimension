package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderAvailability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BuilderBlueprintClientEvents {
    @Nullable private static ClientLevel trackedLevel;
    @Nullable private static ResourceKey<Level> trackedDimension;

    private BuilderBlueprintClientEvents() { }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        ResourceKey<Level> currentDimension = currentLevel == null ? null : currentLevel.dimension();
        // Forge's PlayerChangedDimensionEvent is emitted for ServerPlayer, not LocalPlayer. Track
        // the actual client world instead so portal travel, respawn-created ClientLevels and
        // same-connection dimension changes cannot leave an old selection blocking previews.
        if (currentLevel != trackedLevel
                || !java.util.Objects.equals(currentDimension, trackedDimension)) {
            clearWorldSession();
            trackedLevel = currentLevel;
            trackedDimension = currentDimension;
        }
        BuilderClientNetworkBridge.clientTick();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        BuilderAvailability.resetClientValue();
        clearWorldSession();
        trackedLevel = null;
        trackedDimension = null;
    }

    private static void clearWorldSession() {
        BuilderClientNetworkBridge.clearBlueprintSession();
        BuilderPreviewState.get().clear();
        BuilderSurfacePreviewPlanner.reset();
        BuilderPreviewRenderer.clearCache();
        BuilderAnchorPreviewTracker.clear();
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ReloadEvents {
        private ReloadEvents() { }

        @SubscribeEvent
        public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resourceManager ->
                    BuilderPreviewRenderer.clearCache());
        }
    }
}
