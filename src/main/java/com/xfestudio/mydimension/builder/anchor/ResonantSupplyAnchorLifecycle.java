package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.registry.ModBlocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Precise, non-scanning wake-up for anchors whose target container chunk loads later. */
@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ResonantSupplyAnchorLifecycle {
    private ResonantSupplyAnchorLifecycle() {
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !BuilderConfig.isEnabled()) {
            return;
        }
        ChunkPos loadedChunk = event.getChunk().getPos();
        MinecraftServer server = level.getServer();
        Runnable validation = () -> wakeAnchorsTargeting(level, loadedChunk);
        if (server.isSameThread()) {
            validation.run();
        } else {
            server.execute(validation);
        }
    }

    static void wakeAnchorsTargeting(ServerLevel level, ChunkPos loadedChunk) {
        if (!BuilderConfig.isEnabled() || !level.getChunkSource().hasChunk(loadedChunk.x, loadedChunk.z)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server.getLevel(Level.OVERWORLD) == null) {
            return;
        }
        for (AnchorIndexSavedData.AnchorLocation location : AnchorIndexSavedData.get(server)
                .findTargetingChunk(level.dimension(), loadedChunk)) {
            // Never load the adjacent anchor chunk merely to validate it.
            if (!level.isLoaded(location.position())) {
                continue;
            }
            BlockState state = level.getBlockState(location.position());
            if (state.is(ModBlocks.RESONANT_SUPPLY_ANCHOR.get())
                    && state.getValue(ResonantSupplyAnchorBlock.FACING) == location.facing()) {
                level.scheduleTick(location.position(), ModBlocks.RESONANT_SUPPLY_ANCHOR.get(), 1);
            }
        }
    }
}
