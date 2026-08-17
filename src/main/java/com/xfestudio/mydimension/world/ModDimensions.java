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

    public static final ResourceKey<Level> SOARING_MIND = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(MyDimension.MOD_ID, "soaring_mind")
    );

    public static final ResourceKey<DimensionType> SOARING_MIND_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            new ResourceLocation(MyDimension.MOD_ID, "soaring_mind")
    );

    private static final Set<ResourceKey<Level>> MIND_DIMENSIONS = Set.of(
            ETHEREAL_MIND,
            MIRROR_MIND,
            WATER_MIND,
            NATURE_MIND,
            SOARING_MIND
    );

    private static final Map<ResourceKey<Level>, Double> ENTRY_HEIGHTS = Map.of(
            ETHEREAL_MIND, 66.0D,
            MIRROR_MIND, 80.0D,
            WATER_MIND, 76.0D,
            NATURE_MIND, 61.0D,
            SOARING_MIND, 128.0D
    );

    private static final Map<String, ResourceKey<Level>> MIND_IDS = Map.of(
            "ethereal_mind", ETHEREAL_MIND,
            "mirror_mind", MIRROR_MIND,
            "water_mind", WATER_MIND,
            "nature_mind", NATURE_MIND,
            "soaring_mind", SOARING_MIND
    );

    private static final Map<String, ResourceKey<Level>> PRIVATE_MIND_CODES = Map.of(
            "e", ETHEREAL_MIND,
            "m", MIRROR_MIND,
            "w", WATER_MIND,
            "n", NATURE_MIND,
            "s", SOARING_MIND
    );

    private static final Map<ResourceKey<Level>, String> PRIVATE_MIND_IDS = Map.of(
            ETHEREAL_MIND, "e",
            MIRROR_MIND, "m",
            WATER_MIND, "w",
            NATURE_MIND, "n",
            SOARING_MIND, "s"
    );

    public static boolean isMindDimension(ResourceKey<Level> dimension) {
        return baseMindDimension(dimension) != null;
    }

    public static ResourceKey<Level> baseMindDimension(ResourceKey<Level> dimension) {
        if (MIND_DIMENSIONS.contains(dimension)) {
            return dimension;
        }

        ResourceLocation location = dimension.location();
        if (!MyDimension.MOD_ID.equals(location.getNamespace())) {
            return null;
        }

        String path = location.getPath();
        ResourceKey<Level> privateBase = basePrivateMindDimension(path);
        if (privateBase != null) {
            return privateBase;
        }

        String prefix = "player_minds/slot_";
        if (!path.startsWith(prefix)) {
            return null;
        }

        int slash = path.indexOf('/', prefix.length());
        if (slash < 0) {
            return null;
        }

        return MIND_IDS.get(path.substring(slash + 1));
    }

    private static ResourceKey<Level> basePrivateMindDimension(String path) {
        if (path.length() < 3 || path.charAt(0) != 'p') {
            return null;
        }

        int index = 1;
        while (index < path.length() && Character.isDigit(path.charAt(index))) {
            index++;
        }

        if (index == 1 || index != path.length() - 1) {
            return null;
        }

        return PRIVATE_MIND_CODES.get(path.substring(index));
    }

    public static ResourceKey<Level> playerDimension(ResourceKey<Level> baseDimension, int slot) {
        ResourceKey<Level> base = baseMindDimension(baseDimension);
        if (base == null) {
            return baseDimension;
        }

        return ResourceKey.create(
                Registries.DIMENSION,
                new ResourceLocation(MyDimension.MOD_ID, "p" + slot + PRIVATE_MIND_IDS.get(base))
        );
    }

    public static double entryHeight(ResourceKey<Level> dimension) {
        ResourceKey<Level> base = baseMindDimension(dimension);
        return ENTRY_HEIGHTS.getOrDefault(base, 80.0D);
    }
}
