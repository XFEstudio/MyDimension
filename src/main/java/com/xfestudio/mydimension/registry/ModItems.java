package com.xfestudio.mydimension.registry;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.item.RealmwrightScepterItem;
import com.xfestudio.mydimension.config.BuilderAvailability;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MyDimension.MOD_ID);

    public static final RegistryObject<Item> RIFT = ITEMS.register("rift", () -> new RiftItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> MIND_PORTAL_FRAME = ITEMS.register("mind_portal_frame",
            () -> new BlockItem(ModBlocks.MIND_PORTAL_FRAME.get(), new Item.Properties()));
    public static final RegistryObject<Item> REALMWRIGHT_SCEPTER = ITEMS.register("realmwright_scepter",
            () -> new RealmwrightScepterItem(new Item.Properties()));
    public static final RegistryObject<Item> RESONANT_SUPPLY_ANCHOR = ITEMS.register("resonant_supply_anchor",
            () -> new BlockItem(ModBlocks.RESONANT_SUPPLY_ANCHOR.get(), new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RIFT);
            if (BuilderAvailability.creativeEntryEnabled()) event.accept(REALMWRIGHT_SCEPTER);
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(MIND_PORTAL_FRAME);
            if (BuilderAvailability.creativeEntryEnabled()) event.accept(RESONANT_SUPPLY_ANCHOR);
        }
    }
}
