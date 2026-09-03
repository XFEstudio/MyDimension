package com.xfestudio.mydimension.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderReplacementPolicyTest {
    @Test
    void solidObstacleRequiresTheSetting() {
        assertFalse(BuilderReplacementPolicy.allowsDestructiveReplacement(false, false, 0.5F));
        assertTrue(BuilderReplacementPolicy.allowsDestructiveReplacement(true, false, 0.5F));
    }

    @Test
    void onlyCreativeBypassesSurvivalUnbreakableHardness() {
        assertFalse(BuilderReplacementPolicy.allowsDestructiveReplacement(true, false, -1.0F));
        assertTrue(BuilderReplacementPolicy.allowsDestructiveReplacement(true, true, -1.0F));
    }
}
