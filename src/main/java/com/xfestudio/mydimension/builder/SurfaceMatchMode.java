package com.xfestudio.mydimension.builder;

public enum SurfaceMatchMode {
    SAME_BLOCK,
    ANY_BLOCK;

    public SurfaceMatchMode toggle() {
        return this == SAME_BLOCK ? ANY_BLOCK : SAME_BLOCK;
    }

    public static SurfaceMatchMode byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return SAME_BLOCK;
        }
    }
}
