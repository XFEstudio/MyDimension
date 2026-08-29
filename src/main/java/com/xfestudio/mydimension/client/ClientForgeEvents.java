package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModEntities;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientForgeEvents {
    @SubscribeEvent
    public static void updateAnchorGlow(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MindPortalOverlay.tick(minecraft);
        if (minecraft.level == null) {
            return;
        }

        boolean holdingRift = isHoldingRift(minecraft.player);
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getType() == ModEntities.RIFT_ANCHOR.get()) {
                entity.setGlowingTag(holdingRift);
            }
        }
    }

    private static boolean isHoldingRift(Player player) {
        return player != null && (player.getMainHandItem().is(ModItems.RIFT.get()) || player.getOffhandItem().is(ModItems.RIFT.get()));
    }
}
