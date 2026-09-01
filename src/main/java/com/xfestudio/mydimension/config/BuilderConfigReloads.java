package com.xfestudio.mydimension.config;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import com.xfestudio.mydimension.network.ModNetwork;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-evaluates conditional builder recipes after the per-world SERVER config
 * becomes available or changes at runtime.
 *
 * <p>The first data-pack pass necessarily happens before Forge attaches a
 * world's server config.  {@link BuilderConfig#isEnabled()} therefore uses the
 * declared default for that pass.  A configured {@code enabled=false} is
 * applied by one normal resource reload as soon as the server is ready.</p>
 */
public final class BuilderConfigReloads {
    private static final AtomicBoolean RELOAD_PENDING = new AtomicBoolean();
    private static volatile boolean lastEnabled = BuilderConfig.ENABLED.getDefault();

    private BuilderConfigReloads() {
    }

    private static void requestRecipeReload(MinecraftServer server) {
        if (server == null || !RELOAD_PENDING.compareAndSet(false, true)) {
            return;
        }
        server.execute(() -> server.reloadResources(server.getPackRepository().getSelectedIds())
                .whenComplete((ignored, failure) -> {
                    RELOAD_PENDING.set(false);
                    if (failure != null) {
                        MyDimension.LOGGER.error("Failed to reload builder conditional recipes", failure);
                    }
                }));
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void serverStarted(ServerStartedEvent event) {
            boolean enabled = BuilderConfig.isEnabled();
            lastEnabled = enabled;
            if (!enabled) {
                requestRecipeReload(event.getServer());
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void configReloaded(ModConfigEvent.Reloading event) {
            if (event.getConfig().getSpec() != BuilderConfig.SPEC) {
                return;
            }
            boolean enabled = BuilderConfig.isEnabled();
            if (enabled == lastEnabled) return;
            lastEnabled = enabled;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(() -> server.getPlayerList().getPlayers()
                        .forEach(ModNetwork::sendBuilderAvailability));
            }
            requestRecipeReload(server);
        }
    }
}
