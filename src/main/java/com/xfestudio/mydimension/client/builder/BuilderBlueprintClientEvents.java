package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderAvailability;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BuilderBlueprintClientEvents {
    private BuilderBlueprintClientEvents() { }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) BuilderClientNetworkBridge.clientTick();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        BuilderAvailability.resetClientValue();
        BuilderClientNetworkBridge.clearBlueprintSession();
        BuilderPreviewState.get().clear();
        BuilderPreviewRenderer.clearCache();
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
