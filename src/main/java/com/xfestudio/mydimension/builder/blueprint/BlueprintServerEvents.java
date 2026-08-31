package com.xfestudio.mydimension.builder.blueprint;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlueprintServerEvents {
    private BlueprintServerEvents() {
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BlueprintServerService.get(event.getServer()).tick();
            BlueprintTaskManager.get(event.getServer()).tick();
        }
    }

    @SubscribeEvent
    public static void playerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlueprintTaskManager.get(player.getServer()).pausePlayer(player);
            BlueprintServerService.get(player.getServer()).removePlayer(player);
        }
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        BlueprintTaskManager.shutdown(event.getServer());
        BlueprintServerService.shutdown(event.getServer());
    }
}
