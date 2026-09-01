package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModEntities;
import com.xfestudio.mydimension.registry.ModBlockEntities;
import com.xfestudio.mydimension.client.builder.BuilderPreviewRenderTypes;
import com.xfestudio.mydimension.client.builder.RealmwrightScepterRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModClientEvents {
    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        RealmwrightScepterRenderer.registerAdditionalModels(event);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RIFT_ANCHOR.get(), RiftAnchorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MIND_PORTAL.get(), MindPortalRenderer::new);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        MindPortalRenderType.registerShader(event);
        BuilderPreviewRenderTypes.registerShaders(event);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.PORTAL.id(), "mind_portal", MindPortalOverlay.OVERLAY);
    }
}
