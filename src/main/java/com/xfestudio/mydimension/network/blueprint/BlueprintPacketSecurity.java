package com.xfestudio.mydimension.network.blueprint;

import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.item.RealmwrightScepterItem;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

final class BlueprintPacketSecurity {
    private BlueprintPacketSecurity() {
    }

    static boolean authorize(ServerPlayer player, UUID requestId) {
        String rejection = null;
        if (!BuilderConfig.isEnabled()) {
            rejection = "The builder subsystem is disabled";
        } else if (!(player.getMainHandItem().getItem() instanceof RealmwrightScepterItem)) {
            rejection = "The Realmwright Scepter must be held in the main hand";
        }
        if (rejection == null) return true;
        BlueprintServerService.get(player.getServer()).rejectRequest(player, requestId, rejection);
        return false;
    }
}
