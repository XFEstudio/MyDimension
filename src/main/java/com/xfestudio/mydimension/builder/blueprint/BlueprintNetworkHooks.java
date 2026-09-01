package com.xfestudio.mydimension.builder.blueprint;

import java.util.UUID;

/** Common-side bridge, populated by client setup without loading client classes on a dedicated server. */
public final class BlueprintNetworkHooks {
    public interface ClientReceiver {
        void begin(UUID transferId, UUID cacheToken, int byteLength, int chunkCount, byte[] sha256);
        void chunk(UUID transferId, int sequence, byte[] data);
        void end(UUID transferId);
        void result(UUID requestId, boolean success, UUID cacheToken, String message);
    }

    private static final ClientReceiver NOOP = new ClientReceiver() {
        public void begin(UUID transferId, UUID cacheToken, int byteLength, int chunkCount, byte[] sha256) { }
        public void chunk(UUID transferId, int sequence, byte[] data) { }
        public void end(UUID transferId) { }
        public void result(UUID requestId, boolean success, UUID cacheToken, String message) { }
    };

    private static volatile ClientReceiver clientReceiver = NOOP;

    private BlueprintNetworkHooks() {
    }

    public static void installClientReceiver(ClientReceiver receiver) {
        clientReceiver = receiver == null ? NOOP : receiver;
    }

    public static ClientReceiver clientReceiver() {
        return clientReceiver;
    }
}
