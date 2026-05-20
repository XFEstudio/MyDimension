package com.xfestudio.mydimension.registry;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.world.entity.RiftAnchorEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MyDimension.MOD_ID);

    public static final RegistryObject<EntityType<RiftAnchorEntity>> RIFT_ANCHOR = ENTITIES.register(
            "rift_anchor",
            () -> EntityType.Builder.<RiftAnchorEntity>of(RiftAnchorEntity::new, MobCategory.MISC)
                    .sized(0.8F, 1.2F)
                    .clientTrackingRange(64)
                    .updateInterval(20)
                    .noSummon()
                    .noSave()
                    .build("rift_anchor")
    );

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}
