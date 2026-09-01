package com.xfestudio.mydimension.builder;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderSurfaceRateLimiterTest {
    @Test
    void twoHundredFiftySixCandidatesAtSixtyFourPerTickReserveFourTicks() {
        assertEquals(4, BuilderSurfaceRateLimiter.delayTicks(256, 64));
        assertEquals(1, BuilderSurfaceRateLimiter.delayTicks(1, 64));
        assertEquals(1, BuilderSurfaceRateLimiter.delayTicks(0, 0));
    }

    @Test
    void sixtyFourDemolitionsReserveFourTicksAtTheWeightedBudget() {
        assertEquals(4, BuilderSurfaceRateLimiter.delayTicks(BuilderMode.DEMOLISH, 64, 64));
        assertEquals(1, BuilderSurfaceRateLimiter.delayTicks(BuilderMode.DEMOLISH, 16, 64));
    }

    @Test
    void firstClickIsImmediateAndRepeatedPacketsDoNotEnterExecutionWindow() {
        BuilderSurfaceRateLimiter.PermitWindow window = new BuilderSurfaceRateLimiter.PermitWindow();

        assertTrue(window.tryAcquire(100, 256, 64));
        assertEquals(104, window.nextEligibleTick());
        assertFalse(window.tryAcquire(100, 256, 64));
        assertFalse(window.tryAcquire(103, 256, 64));
        assertTrue(window.tryAcquire(104, 256, 64));
    }

    @Test
    void twentyRapidPacketsAreReducedToTheConfiguredAverageBudget() {
        BuilderSurfaceRateLimiter.PermitWindow window = new BuilderSurfaceRateLimiter.PermitWindow();
        int admitted = 0;
        for (int tick = 0; tick < 20; tick++) {
            if (window.tryAcquire(tick, 256, 64)) admitted++;
        }
        // One immediate operation, then one every four ticks: 5 * 256 / 20 == 64 edits per tick.
        assertEquals(5, admitted);
    }

    @Test
    void changingModeOrScepterCannotBypassThePlayersWindow() {
        BuilderSurfaceRateLimiter.PlayerWindows windows = new BuilderSurfaceRateLimiter.PlayerWindows();
        UUID player = UUID.randomUUID();
        UUID firstScepter = UUID.randomUUID();
        UUID secondScepter = UUID.randomUUID();

        assertTrue(windows.tryAcquire(player, firstScepter, BuilderMode.BUILD, 200, 256, 64));
        assertFalse(windows.tryAcquire(player, firstScepter, BuilderMode.DEMOLISH, 200, 64, 64));
        assertFalse(windows.tryAcquire(player, secondScepter, BuilderMode.BUILD, 201, 256, 64));
        assertTrue(windows.tryAcquire(player, secondScepter, BuilderMode.DEMOLISH, 204, 64, 64));
    }

    @Test
    void differentPlayersRetainIndependentImmediateFirstClicks() {
        BuilderSurfaceRateLimiter.PlayerWindows windows = new BuilderSurfaceRateLimiter.PlayerWindows();
        UUID scepter = UUID.randomUUID();

        assertTrue(windows.tryAcquire(UUID.randomUUID(), scepter, BuilderMode.BUILD, 300, 256, 64));
        assertTrue(windows.tryAcquire(UUID.randomUUID(), scepter, BuilderMode.BUILD, 300, 256, 64));
    }

    @Test
    void veryLargeValuesAreCalculatedWithoutIntegerOverflow() {
        assertEquals(1, BuilderSurfaceRateLimiter.delayTicks(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE,
                BuilderSurfaceRateLimiter.delayTicks(Integer.MAX_VALUE, 1));
    }
}
