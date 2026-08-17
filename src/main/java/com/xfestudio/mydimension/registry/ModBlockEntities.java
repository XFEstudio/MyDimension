package com.xfestudio.mydimension.registry;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.world.block.entity.MindPortalBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MyDimension.MOD_ID);

    public static final RegistryObject<BlockEntityType<MindPortalBlockEntity>> MIND_PORTAL = BLOCK_ENTITIES.register(
            "mind_portal",
            () -> BlockEntityType.Builder.of(MindPortalBlockEntity::new, ModBlocks.MIND_PORTAL.get()).build(null)
    );

    private ModBlockEntities() {
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
