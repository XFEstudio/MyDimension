package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.RealmwrightData;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Registers the model predicate used to swap build/demolish item models. */
@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BuilderItemProperties {
    public static final ResourceLocation MODE_PROPERTY =
            new ResourceLocation(MyDimension.MOD_ID, "builder_mode");

    private BuilderItemProperties() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Item item = ForgeRegistries.ITEMS.getValue(BuilderClientServices.REALMWRIGHT_ID);
            if (item != null) {
                register(item);
            }
        });
    }

    public static void register(Item item) {
        ItemProperties.register(item, MODE_PROPERTY,
                (stack, level, entity, seed) -> RealmwrightData.mode(stack) == BuilderMode.DEMOLISH ? 1.0F : 0.0F);
    }
}
