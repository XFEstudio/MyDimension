package com.xfestudio.mydimension.world;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent daylight clock stored independently in each concrete mind dimension. */
final class MindTimeSavedData extends SavedData {
    static final String DATA_NAME = MyDimension.MOD_ID + "_mind_time";
    private static final String DAY_TIME_TAG = "DayTime";

    private long dayTime;

    private MindTimeSavedData(long dayTime, boolean dirty) {
        this.dayTime = dayTime;
        if (dirty) {
            setDirty();
        }
    }

    static MindTimeSavedData get(ServerLevel level, long initialDayTime) {
        return level.getDataStorage().computeIfAbsent(
                MindTimeSavedData::load,
                () -> new MindTimeSavedData(initialDayTime, true),
                DATA_NAME
        );
    }

    static MindTimeSavedData load(CompoundTag tag) {
        return new MindTimeSavedData(tag.getLong(DAY_TIME_TAG), false);
    }

    long dayTime() {
        return dayTime;
    }

    void setDayTime(long dayTime) {
        if (this.dayTime == dayTime) {
            return;
        }

        this.dayTime = dayTime;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong(DAY_TIME_TAG, dayTime);
        return tag;
    }
}
