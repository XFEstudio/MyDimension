package com.xfestudio.mydimension.builder;

/** Common runtime settings facade. Server config installs its provider after config registration. */
public final class BuilderRuntime {
    public interface Settings {
        boolean enabled();
        boolean creativeBypassesCosts();
        int maxBuildLimit();
        int maxDemolishLimit();
        int undoDepth();
        int maxHistoryBytesPerPlayer();
        int maxTransactionBytes();
        double blockReach();
        int editsPerTick();
    }

    private static final Settings DEFAULTS = new Settings() {
        public boolean enabled() { return true; }
        public boolean creativeBypassesCosts() { return true; }
        public int maxBuildLimit() { return 4096; }
        public int maxDemolishLimit() { return 1024; }
        public int undoDepth() { return 20; }
        public int maxHistoryBytesPerPlayer() { return 67_108_864; }
        public int maxTransactionBytes() { return 33_554_432; }
        public double blockReach() { return 64.0D; }
        public int editsPerTick() { return 64; }
    };

    private static volatile Settings settings = DEFAULTS;

    private BuilderRuntime() {
    }

    public static void install(Settings value) {
        settings = value == null ? DEFAULTS : value;
    }

    public static Settings settings() {
        return settings;
    }
}
