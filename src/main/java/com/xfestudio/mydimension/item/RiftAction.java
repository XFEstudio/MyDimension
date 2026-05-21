package com.xfestudio.mydimension.item;

import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

public enum RiftAction {
    ETHEREAL_MIND("ethereal_mind", "action.mydimension.ethereal_mind", ModDimensions.ETHEREAL_MIND, false, false, false),
    MIRROR_MIND("mirror_mind", "action.mydimension.mirror_mind", ModDimensions.MIRROR_MIND, false, false, false),
    WATER_MIND("water_mind", "action.mydimension.water_mind", ModDimensions.WATER_MIND, false, false, false),
    NATURE_MIND("nature_mind", "action.mydimension.nature_mind", ModDimensions.NATURE_MIND, false, false, false),
    SHARED_ETHEREAL_MIND("shared_ethereal_mind", "action.mydimension.shared_ethereal_mind", ModDimensions.ETHEREAL_MIND, false, true, false),
    SHARED_MIRROR_MIND("shared_mirror_mind", "action.mydimension.shared_mirror_mind", ModDimensions.MIRROR_MIND, false, true, false),
    SHARED_WATER_MIND("shared_water_mind", "action.mydimension.shared_water_mind", ModDimensions.WATER_MIND, false, true, false),
    SHARED_NATURE_MIND("shared_nature_mind", "action.mydimension.shared_nature_mind", ModDimensions.NATURE_MIND, false, true, false),
    SEND_MOB_ETHEREAL("send_mob_ethereal", "action.mydimension.send_mob_ethereal", ModDimensions.ETHEREAL_MIND, true, false, false),
    SEND_MOB_MIRROR("send_mob_mirror", "action.mydimension.send_mob_mirror", ModDimensions.MIRROR_MIND, true, false, false),
    SEND_MOB_WATER("send_mob_water", "action.mydimension.send_mob_water", ModDimensions.WATER_MIND, true, false, false),
    SEND_MOB_NATURE("send_mob_nature", "action.mydimension.send_mob_nature", ModDimensions.NATURE_MIND, true, false, false),
    SEND_MOB_SHARED_ETHEREAL("send_mob_shared_ethereal", "action.mydimension.send_mob_shared_ethereal", ModDimensions.ETHEREAL_MIND, true, true, false),
    SEND_MOB_SHARED_MIRROR("send_mob_shared_mirror", "action.mydimension.send_mob_shared_mirror", ModDimensions.MIRROR_MIND, true, true, false),
    SEND_MOB_SHARED_WATER("send_mob_shared_water", "action.mydimension.send_mob_shared_water", ModDimensions.WATER_MIND, true, true, false),
    SEND_MOB_SHARED_NATURE("send_mob_shared_nature", "action.mydimension.send_mob_shared_nature", ModDimensions.NATURE_MIND, true, true, false),
    COPY_SHARED_ETHEREAL("copy_shared_ethereal", "action.mydimension.copy_shared_ethereal", ModDimensions.ETHEREAL_MIND, false, false, true),
    COPY_SHARED_MIRROR("copy_shared_mirror", "action.mydimension.copy_shared_mirror", ModDimensions.MIRROR_MIND, false, false, true),
    COPY_SHARED_WATER("copy_shared_water", "action.mydimension.copy_shared_water", ModDimensions.WATER_MIND, false, false, true),
    COPY_SHARED_NATURE("copy_shared_nature", "action.mydimension.copy_shared_nature", ModDimensions.NATURE_MIND, false, false, true),
    SET_ANCHOR("set_anchor", "action.mydimension.set_anchor", null, false, false, false);

    private final String id;
    private final String translationKey;
    private final ResourceKey<Level> targetDimension;
    private final boolean sendsMob;
    private final boolean sharedDimension;
    private final boolean copiesSharedDimension;

    RiftAction(String id, String translationKey, ResourceKey<Level> targetDimension, boolean sendsMob, boolean sharedDimension, boolean copiesSharedDimension) {
        this.id = id;
        this.translationKey = translationKey;
        this.targetDimension = targetDimension;
        this.sendsMob = sendsMob;
        this.sharedDimension = sharedDimension;
        this.copiesSharedDimension = copiesSharedDimension;
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
        return targetDimension != null && !sendsMob && !copiesSharedDimension;
    }

    public boolean sendsMob() {
        return sendsMob;
    }

    public boolean usesSharedDimension() {
        return sharedDimension;
    }

    public boolean copiesSharedDimension() {
        return copiesSharedDimension;
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
