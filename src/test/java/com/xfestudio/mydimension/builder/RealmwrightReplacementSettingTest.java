package com.xfestudio.mydimension.builder;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmwrightReplacementSettingTest {
    @Test
    void replacementIsOptInWhenThePersistedFieldIsAbsent() {
        assertFalse(RealmwrightData.allowsReplacement((CompoundTag) null));
        assertFalse(RealmwrightData.allowsReplacement(new CompoundTag()));
    }

    @Test
    void replacementChoiceUsesThePersistedBooleanValue() {
        CompoundTag persisted = new CompoundTag();
        persisted.putBoolean("AllowReplace", true);
        assertTrue(RealmwrightData.allowsReplacement(persisted));

        persisted.putBoolean("AllowReplace", false);
        assertFalse(RealmwrightData.allowsReplacement(persisted));
    }
}
