package com.xfestudio.mydimension.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindTimeSavedDataTest {
    @Test
    void roundTripsIndependentDayTime() {
        MindTimeSavedData data = MindTimeSavedData.load(tagWithDayTime(18_750L));
        assertEquals(18_750L, data.dayTime());
        assertFalse(data.isDirty());

        data.setDayTime(24_000L);
        assertTrue(data.isDirty());

        CompoundTag saved = data.save(new CompoundTag());
        MindTimeSavedData reloaded = MindTimeSavedData.load(saved);
        assertEquals(24_000L, reloaded.dayTime());
        assertFalse(reloaded.isDirty());
    }

    @Test
    void separateMindDataObjectsNeverShareTheirClock() {
        MindTimeSavedData first = MindTimeSavedData.load(tagWithDayTime(1_000L));
        MindTimeSavedData second = MindTimeSavedData.load(tagWithDayTime(13_000L));

        first.setDayTime(6_000L);

        assertEquals(6_000L, first.dayTime());
        assertEquals(13_000L, second.dayTime());
    }

    @Test
    void levelDataWrapperReadsAndWritesOnlyTheMindClock() {
        AtomicLong delegatedDayTime = new AtomicLong(Long.MIN_VALUE);
        ServerLevelData delegate = (ServerLevelData) Proxy.newProxyInstance(
                ServerLevelData.class.getClassLoader(),
                new Class<?>[]{ServerLevelData.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDayTime" -> 2_000L;
                    case "setDayTime" -> {
                        delegatedDayTime.set((long) arguments[0]);
                        yield null;
                    }
                    case "getGameTime" -> 99L;
                    default -> throw new AssertionError("Unexpected delegate call: " + method.getName());
                }
        );
        MindTimeSavedData time = MindTimeSavedData.load(tagWithDayTime(14_000L));
        MindServerLevelData wrapped = new MindServerLevelData(delegate, time);

        assertEquals(14_000L, wrapped.getDayTime());
        assertEquals(99L, wrapped.getGameTime());
        wrapped.setDayTime(24_000L);

        assertEquals(24_000L, wrapped.getDayTime());
        assertEquals(Long.MIN_VALUE, delegatedDayTime.get());
    }

    private static CompoundTag tagWithDayTime(long dayTime) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("DayTime", dayTime);
        return tag;
    }
}
