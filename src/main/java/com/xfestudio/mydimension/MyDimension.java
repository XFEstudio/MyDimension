package com.xfestudio.mydimension;

import com.mojang.logging.LogUtils;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
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

        ModItems.register(modEventBus);
        modEventBus.addListener(ModItems::addCreative);
        MinecraftForge.EVENT_BUS.register(new MyDimensionEvents());
    }
}
