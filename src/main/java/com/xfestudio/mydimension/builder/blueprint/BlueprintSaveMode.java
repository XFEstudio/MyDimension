package com.xfestudio.mydimension.builder.blueprint;

import java.util.Locale;

public enum BlueprintSaveMode {
    BLOCKS_ONLY,
    FULL;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BlueprintSaveMode parse(String value) {
        for (BlueprintSaveMode mode : values()) {
            if (mode.serializedName().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown blueprint save mode: " + value);
    }
}
