package com.xfestudio.mydimension.network.builder;

/**
 * Common-side indirection for builder S2C packets. The client installs a
 * receiver during client setup, keeping dedicated servers free of client
 * class references.
 */
public final class BuilderClientPacketHooks {
    public interface Receiver {
        void snapshot(BuilderSnapshotPacket packet);

        void preview(BuilderPreviewPacket packet);

        void openMenu();

        void availability(boolean enabled);
    }

    private static final Receiver NOOP = new Receiver() {
        @Override
        public void snapshot(BuilderSnapshotPacket packet) {
        }

        @Override
        public void preview(BuilderPreviewPacket packet) {
        }

        @Override
        public void openMenu() {
        }

        @Override
        public void availability(boolean enabled) {
        }
    };

    private static volatile Receiver receiver = NOOP;

    private BuilderClientPacketHooks() {
    }

    public static void install(Receiver value) {
        receiver = value == null ? NOOP : value;
    }

    public static Receiver receiver() {
        return receiver;
    }
}
