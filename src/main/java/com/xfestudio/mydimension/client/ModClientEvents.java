package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModEntities;
import com.xfestudio.mydimension.registry.ModBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModClientEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RIFT_ANCHOR.get(), RiftAnchorRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MIND_PORTAL.get(), MindPortalRenderer::new);
    }
}
