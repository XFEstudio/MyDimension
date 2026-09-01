package com.xfestudio.mydimension.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderAvailabilityTest {
    @BeforeEach
    @AfterEach
    void resetConnectionState() {
        BuilderAvailability.resetClientValue();
    }

    @Test
    void firstPacketRefreshesTabsButRepeatedSnapshotsDoNot() {
        assertTrue(BuilderAvailability.acceptServerValue(true));
        assertFalse(BuilderAvailability.acceptServerValue(true));
        assertTrue(BuilderAvailability.acceptServerValue(false));
        assertFalse(BuilderAvailability.acceptServerValue(false));
    }

    @Test
    void newConnectionRefreshesEvenWhenItMatchesTheDefault() {
        assertTrue(BuilderAvailability.acceptServerValue(false));
        BuilderAvailability.resetClientValue();
        assertTrue(BuilderAvailability.acceptServerValue(true));
        assertFalse(BuilderAvailability.acceptServerValue(true));
    }
}
