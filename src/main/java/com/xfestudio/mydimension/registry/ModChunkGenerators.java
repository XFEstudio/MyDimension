package com.xfestudio.mydimension.registry;

import com.mojang.serialization.Codec;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.world.NoStructureNoiseChunkGenerator;
import com.xfestudio.mydimension.world.SoaringMindChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModChunkGenerators {
    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MyDimension.MOD_ID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> NO_STRUCTURE_NOISE =
            CHUNK_GENERATORS.register("no_structure_noise", () -> NoStructureNoiseChunkGenerator.CODEC);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> SOARING_ISLANDS =
            CHUNK_GENERATORS.register("soaring_islands", () -> SoaringMindChunkGenerator.CODEC);
}
