package com.xfestudio.mydimension.world;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

public class ModDimensions {
    public static final ResourceKey<Level> ETHEREAL_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "ethereal_mind")
    );

    public static final ResourceKey<DimensionType> ETHEREAL_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "ethereal_mind")
    );
}
