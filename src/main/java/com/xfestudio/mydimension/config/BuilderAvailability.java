package com.xfestudio.mydimension.config;

/** Client-visible copy of the server master switch used while rebuilding creative tabs. */
public final class BuilderAvailability {
    private static volatile boolean clientEnabled = true;

    private BuilderAvailability() {
    }

    public static boolean creativeEntryEnabled() {
        return clientEnabled && BuilderConfig.isEnabled();
    }

    public static void acceptServerValue(boolean enabled) {
        clientEnabled = enabled;
    }

    public static void resetClientValue() {
        clientEnabled = true;
    }
}
