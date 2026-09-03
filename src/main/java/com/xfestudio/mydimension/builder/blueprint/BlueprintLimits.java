package com.xfestudio.mydimension.builder.blueprint;

/**
 * Hard protocol and allocation-safety budgets shared by local files and network transfers.
 * Blueprint geometry and non-air block counts are deliberately not capped here.
 */
public final class BlueprintLimits {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PALETTE = 8_192;
    public static final int MAX_BLOCK_ENTITIES = 65_536;
    public static final int MAX_BLOCK_ENTITY_BYTES = 256 * 1024;
    public static final int MAX_BLOCK_ENTITY_TOTAL_BYTES = 8 * 1024 * 1024;
    public static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    public static final int TRANSFER_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_TRANSFER_CHUNKS =
            (MAX_COMPRESSED_BYTES + TRANSFER_CHUNK_BYTES - 1) / TRANSFER_CHUNK_BYTES;
    public static final int MAX_NAME_CODE_POINTS = 64;
    public static final int TRANSFER_IDLE_TICKS = 20 * 30;
    public static final int CACHE_IDLE_TICKS = 20 * 60 * 10;
    public static final int MAX_CACHE_ENTRIES_PER_PLAYER = 4;
    public static final int MAX_CACHE_BYTES_PER_PLAYER = 32 * 1024 * 1024;
    public static final int MAX_CACHE_BYTES_GLOBAL = 128 * 1024 * 1024;
    public static final int MAX_QUEUED_PLANS_PER_PLAYER = 2;

    private BlueprintLimits() {
    }
}
