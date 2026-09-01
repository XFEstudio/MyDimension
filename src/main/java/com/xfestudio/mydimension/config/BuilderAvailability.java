package com.xfestudio.mydimension.config;

/** Client-visible copy of the server master switch used while rebuilding creative tabs. */
public final class BuilderAvailability {
    private static volatile boolean clientEnabled = true;
    private static volatile boolean serverValueKnown;

    private BuilderAvailability() {
    }

    public static boolean creativeEntryEnabled() {
        return clientEnabled && BuilderConfig.isEnabled();
    }

    /** Updates the client copy and reports whether creative entries can actually have changed. */
    public static synchronized boolean acceptServerValue(boolean enabled) {
        // The first packet of every connection must refresh even when its value equals the local
        // default.  This repairs creative tabs after leaving a server that disabled the builder.
        boolean changed = !serverValueKnown || clientEnabled != enabled;
        clientEnabled = enabled;
        serverValueKnown = true;
        return changed;
    }

    public static synchronized void resetClientValue() {
        clientEnabled = true;
        serverValueKnown = false;
    }
}
