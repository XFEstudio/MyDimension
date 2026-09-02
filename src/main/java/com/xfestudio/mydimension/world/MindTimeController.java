package com.xfestudio.mydimension.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;

/** Installs and advances an independent daylight clock for every concrete mind level. */
public final class MindTimeController {
    private MindTimeController() {
    }

    public static void attach(ServerLevel level) {
        if (!ModDimensions.isMindDimension(level.dimension())
                || level.serverLevelData instanceof MindServerLevelData) {
            return;
        }

        ServerLevelData original = level.serverLevelData;
        MindTimeSavedData savedTime = MindTimeSavedData.get(level, original.getDayTime());
        MindServerLevelData independent = new MindServerLevelData(original, savedTime);
        level.serverLevelData = independent;
        level.levelData = independent;
    }

    public static void tick(ServerLevel level) {
        if (!ModDimensions.isMindDimension(level.dimension())) {
            return;
        }

        attach(level);
        if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            level.setDayTime(level.getDayTime() + 1L);
        }
    }

    static boolean hasIndependentTime(ServerLevel level) {
        return level.serverLevelData instanceof MindServerLevelData
                && level.levelData == level.serverLevelData;
    }
}
