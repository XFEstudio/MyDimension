package com.xfestudio.mydimension.world;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public enum MindType {
    ETHEREAL(ModDimensions.ETHEREAL_MIND, "dimension.mydimension.ethereal_mind"),
    MIRROR(ModDimensions.MIRROR_MIND, "dimension.mydimension.mirror_mind"),
    WATER(ModDimensions.WATER_MIND, "dimension.mydimension.water_mind"),
    NATURE(ModDimensions.NATURE_MIND, "dimension.mydimension.nature_mind"),
    SOARING(ModDimensions.SOARING_MIND, "dimension.mydimension.soaring_mind");

    private final ResourceKey<Level> baseDimension;
    private final String translationKey;

    MindType(ResourceKey<Level> baseDimension, String translationKey) {
        this.baseDimension = baseDimension;
        this.translationKey = translationKey;
    }

    public ResourceKey<Level> baseDimension() {
        return baseDimension;
    }

    public Component displayName() {
        return Component.translatable(translationKey);
    }

    public static MindType fromBaseDimension(ResourceKey<Level> dimension) {
        for (MindType type : values()) {
            if (type.baseDimension.equals(dimension)) {
                return type;
            }
        }
        return null;
    }
}
