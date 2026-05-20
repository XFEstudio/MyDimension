package com.xfestudio.mydimension.world;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;
import java.util.Set;

public class ModDimensions {
    public static final ResourceKey<Level> ETHEREAL_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "ethereal_mind")
    );

    public static final ResourceKey<DimensionType> ETHEREAL_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "ethereal_mind")
    );

    public static final ResourceKey<Level> MIRROR_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "mirror_mind")
    );

    public static final ResourceKey<DimensionType> MIRROR_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "mirror_mind")
    );

    public static final ResourceKey<Level> WATER_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "water_mind")
    );

    public static final ResourceKey<DimensionType> WATER_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "water_mind")
    );

    public static final ResourceKey<Level> NATURE_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "nature_mind")
    );

    public static final ResourceKey<DimensionType> NATURE_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "nature_mind")
    );

    private static final Set<ResourceKey<Level>> MIND_DIMENSIONS = Set.of(
            ETHEREAL_MIND,
            MIRROR_MIND,
            WATER_MIND,
            NATURE_MIND
    );

    private static final Map<ResourceKey<Level>, Double> ENTRY_HEIGHTS = Map.of(
            ETHEREAL_MIND, 66.0D,
            MIRROR_MIND, 80.0D,
            WATER_MIND, 76.0D,
            NATURE_MIND, 61.0D
    );

    public static boolean isMindDimension(ResourceKey<Level> dimension) {
        return MIND_DIMENSIONS.contains(dimension);
    }

    public static double entryHeight(ResourceKey<Level> dimension) {
        return ENTRY_HEIGHTS.getOrDefault(dimension, 80.0D);
    }
}
