package com.xfestudio.mydimension.registry;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.anchor.ResonantSupplyAnchorBlock;
import com.xfestudio.mydimension.world.block.MindPortalBlock;
import com.xfestudio.mydimension.world.block.MindPortalFrameBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MyDimension.MOD_ID);

    public static final RegistryObject<Block> MIND_PORTAL_FRAME = BLOCKS.register("mind_portal_frame",
            () -> new MindPortalFrameBlock(BlockBehaviour.Properties.copy(Blocks.CRYING_OBSIDIAN)
                    .strength(25.0F, 1200.0F)
                    .lightLevel(state -> 7)));

    public static final RegistryObject<Block> MIND_PORTAL = BLOCKS.register("mind_portal",
            () -> new MindPortalBlock(BlockBehaviour.Properties.of()
                    .noCollission()
                    .noOcclusion()
                    .strength(-1.0F, 3600000.0F)
                    .lightLevel(state -> 12)
                    .sound(SoundType.GLASS)));

    public static final RegistryObject<Block> RESONANT_SUPPLY_ANCHOR = BLOCKS.register(
            "resonant_supply_anchor",
            () -> new ResonantSupplyAnchorBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE)
                    .noOcclusion()
                    .strength(5.0F, 1200.0F)));

    private ModBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
