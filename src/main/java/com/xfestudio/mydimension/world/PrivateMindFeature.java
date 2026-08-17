package com.xfestudio.mydimension.world;

public final class PrivateMindFeature {
    private static final boolean ENABLED = PrivateMindFeature.class.getResource("/mydimension_private_minds_enabled.txt") != null;

    private PrivateMindFeature() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }
}
