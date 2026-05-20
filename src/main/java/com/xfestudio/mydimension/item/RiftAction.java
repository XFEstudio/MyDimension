package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

public enum RiftAction {
    ETHEREAL_MIND("ethereal_mind", "action.mydimension.ethereal_mind", ModDimensions.ETHEREAL_MIND, false),
    MIRROR_MIND("mirror_mind", "action.mydimension.mirror_mind", ModDimensions.MIRROR_MIND, false),
    WATER_MIND("water_mind", "action.mydimension.water_mind", ModDimensions.WATER_MIND, false),
    NATURE_MIND("nature_mind", "action.mydimension.nature_mind", ModDimensions.NATURE_MIND, false),
    SEND_MOB_ETHEREAL("send_mob_ethereal", "action.mydimension.send_mob_ethereal", ModDimensions.ETHEREAL_MIND, true),
    SEND_MOB_MIRROR("send_mob_mirror", "action.mydimension.send_mob_mirror", ModDimensions.MIRROR_MIND, true),
    SEND_MOB_WATER("send_mob_water", "action.mydimension.send_mob_water", ModDimensions.WATER_MIND, true),
    SEND_MOB_NATURE("send_mob_nature", "action.mydimension.send_mob_nature", ModDimensions.NATURE_MIND, true),
    SET_ANCHOR("set_anchor", "action.mydimension.set_anchor", null, false);

    private final String id;
    private final String translationKey;
    private final ResourceKey<Level> targetDimension;
    private final boolean sendsMob;

    RiftAction(String id, String translationKey, ResourceKey<Level> targetDimension, boolean sendsMob) {
        this.id = id;
        this.translationKey = translationKey;
        this.targetDimension = targetDimension;
        this.sendsMob = sendsMob;
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
        return targetDimension != null && !sendsMob;
    }

    public boolean sendsMob() {
        return sendsMob;
    }

    public static RiftAction byId(String id) {
        if ("send_mob".equals(id)) {
            return SEND_MOB_ETHEREAL;
        }

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
