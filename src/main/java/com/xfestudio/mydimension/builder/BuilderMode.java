package com.xfestudio.mydimension.builder;

public enum BuilderMode {
    BUILD,
    DEMOLISH;

    public BuilderMode toggle() {
        return this == BUILD ? DEMOLISH : BUILD;
    }

    public static BuilderMode byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return BUILD;
        }
    }
}
