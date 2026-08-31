package com.xfestudio.mydimension;

import com.mojang.logging.LogUtils;
import com.xfestudio.mydimension.compat.create.CreateTrainCompat;
import com.xfestudio.mydimension.builder.BuilderDropCapture;
import com.xfestudio.mydimension.builder.BuilderMaterials;
import com.xfestudio.mydimension.builder.BuilderRuntime;
import com.xfestudio.mydimension.builder.anchor.AnchorRemoteBridge;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.registry.ModChunkGenerators;
import com.xfestudio.mydimension.registry.ModBlockEntities;
import com.xfestudio.mydimension.registry.ModBlocks;
import com.xfestudio.mydimension.registry.ModEntities;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MyDimension.MOD_ID)
public class MyDimension {
    public static final String MOD_ID = "mydimension";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MyDimension() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BuilderConfig.SPEC);
        BuilderRuntime.install(new BuilderRuntime.Settings() {
            public boolean enabled() { return BuilderConfig.value(BuilderConfig.ENABLED); }
            public boolean creativeBypassesCosts() { return BuilderConfig.value(BuilderConfig.CREATIVE_BYPASSES_COSTS); }
            public int maxBuildLimit() { return BuilderConfig.value(BuilderConfig.MAX_BUILD_LIMIT); }
            public int maxDemolishLimit() { return BuilderConfig.value(BuilderConfig.MAX_DEMOLISH_LIMIT); }
            public int undoDepth() { return BuilderConfig.value(BuilderConfig.UNDO_DEPTH); }
            public int maxHistoryBytesPerPlayer() { return BuilderConfig.value(BuilderConfig.MAX_HISTORY_BYTES_PER_PLAYER); }
            public int maxTransactionBytes() { return BuilderConfig.value(BuilderConfig.MAX_TRANSACTION_BYTES); }
            public double blockReach() { return BuilderConfig.value(BuilderConfig.BLOCK_REACH); }
            public int editsPerTick() { return BuilderConfig.value(BuilderConfig.EDITS_PER_TICK); }
        });
        BuilderMaterials.installRemoteBridge(AnchorRemoteBridge.INSTANCE);

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModChunkGenerators.CHUNK_GENERATORS.register(modEventBus);
        modEventBus.addListener(ModItems::addCreative);
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(new MyDimensionEvents());
        MinecraftForge.EVENT_BUS.register(new BuilderDropCapture());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModNetwork.register();
            CreateTrainCompat.register();
        });
    }
}
