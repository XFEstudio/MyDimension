package com.xfestudio.mydimension;

import com.mojang.logging.LogUtils;
import com.xfestudio.mydimension.compat.create.CreateTrainCompat;
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
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(MyDimension.MOD_ID)
public class MyDimension {
    public static final String MOD_ID = "mydimension";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MyDimension() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModChunkGenerators.CHUNK_GENERATORS.register(modEventBus);
        modEventBus.addListener(ModItems::addCreative);
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(new MyDimensionEvents());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModNetwork.register();
            CreateTrainCompat.register();
        });
    }
}
