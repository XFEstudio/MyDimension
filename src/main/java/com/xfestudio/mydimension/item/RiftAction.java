package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

public enum RiftAction {
    ETHEREAL_MIND("ethereal_mind", "action.mydimension.ethereal_mind", ModDimensions.ETHEREAL_MIND),
    MIRROR_MIND("mirror_mind", "action.mydimension.mirror_mind", ModDimensions.MIRROR_MIND),
    WATER_MIND("water_mind", "action.mydimension.water_mind", ModDimensions.WATER_MIND),
    NATURE_MIND("nature_mind", "action.mydimension.nature_mind", ModDimensions.NATURE_MIND),
    SEND_MOB("send_mob", "action.mydimension.send_mob", null);

    private final String id;
    private final String translationKey;
    private final ResourceKey<Level> targetDimension;

    RiftAction(String id, String translationKey, ResourceKey<Level> targetDimension) {
        this.id = id;
        this.translationKey = translationKey;
        this.targetDimension = targetDimension;
    }

    public String id() {
        return id;
    }

    public Component displayName() {
        return Component.translatable(translationKey);
    }

    public ResourceKey<Level> targetDimension() {
        return targetDimension;
    }

    public boolean teleportsPlayer() {
        return targetDimension != null;
    }

    public static RiftAction byId(String id) {
        for (RiftAction action : values()) {
            if (action.id.equals(id)) {
                return action;
            }
        }

        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ETHEREAL_MIND;
        }
    }
}
