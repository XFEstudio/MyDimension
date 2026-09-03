package com.xfestudio.mydimension.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Locale;

/**
 * Server-owned limits for the Realmwright builder subsystem.
 *
 * <p>The fields are intentionally public so the item, menu, network, anchor and
 * transaction layers all read one authoritative set of limits. Client supplied
 * values must always be clamped to these values again on the logical server.</p>
 */
public final class BuilderConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue BLOCK_REACH;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_BYPASSES_COSTS;
    public static final ForgeConfigSpec.IntValue MAX_BUILD_LIMIT;
    public static final ForgeConfigSpec.IntValue MAX_DEMOLISH_LIMIT;
    public static final ForgeConfigSpec.IntValue EDITS_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_BOUND_ANCHORS;
    public static final ForgeConfigSpec.IntValue MAX_AUTHORIZED_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CROSS_DIMENSION_ANCHORS;
    public static final ForgeConfigSpec.BooleanValue TEMPORARILY_LOAD_ANCHOR_CHUNKS;
    public static final ForgeConfigSpec.IntValue MAX_TEMPORARY_CHUNKS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue ANCHOR_RESOLVE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_UNSIDED_ITEM_HANDLER_FALLBACK;
    public static final ForgeConfigSpec.IntValue UNDO_DEPTH;
    public static final ForgeConfigSpec.IntValue MAX_HISTORY_BYTES_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MAX_TRANSACTION_BYTES;
    public static final ForgeConfigSpec.ConfigValue<String> FULL_BLOCK_ENTITY_POLICY;
    public static final ForgeConfigSpec.IntValue MAX_BLUEPRINT_COMPRESSED_BYTES;
    public static final ForgeConfigSpec.IntValue MAX_BLUEPRINT_UNCOMPRESSED_BYTES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Real mwright construction tool and remote supply anchor settings.")
                .push("builder");

        ENABLED = builder
                .comment("Master switch. Disabled builders remain registered, but cannot operate or bind anchors.")
                .define("enabled", true);
        BLOCK_REACH = builder
                .comment("Maximum server-authoritative block interaction reach while the scepter is in the main hand.")
                .defineInRange("blockReach", 64.0D, 4.5D, 256.0D);
        CREATIVE_BYPASSES_COSTS = builder
                .comment("Creative players do not consume building materials or tool durability.")
                .define("creativeBypassesCosts", true);
        MAX_BUILD_LIMIT = builder
                .comment("Hard server cap for the configurable number of blocks in one build batch.")
                .defineInRange("maxBuildLimit", 4096, 1, 65536);
        MAX_DEMOLISH_LIMIT = builder
                .comment("Hard server cap for the configurable number of blocks in one demolition batch.")
                .defineInRange("maxDemolishLimit", 1024, 1, 65536);
        EDITS_PER_TICK = builder
                .comment("Legacy compatibility value retained for existing server configs. Construction, demolition and blueprint printing are no longer delayed by this value.")
                .defineInRange("editsPerTick", 64, 1, 4096);
        MAX_BOUND_ANCHORS = builder
                .comment("Maximum number of ordered remote supply anchors bound to one scepter.")
                .defineInRange("maxBoundAnchors", 16, 0, 256);
        MAX_AUTHORIZED_PLAYERS = builder
                .comment("Maximum explicit ACL entries stored by one supply anchor.")
                .defineInRange("maxAuthorizedPlayers", 32, 0, 1024);
        ALLOW_CROSS_DIMENSION_ANCHORS = builder
                .comment("Allow a scepter to use anchors in another dimension.")
                .define("allowCrossDimensionAnchors", true);
        TEMPORARILY_LOAD_ANCHOR_CHUNKS = builder
                .comment("Temporarily load an anchor and its attached container while an operation accesses them.")
                .define("temporarilyLoadAnchorChunks", true);
        MAX_TEMPORARY_CHUNKS_PER_PLAYER = builder
                .comment("Maximum unique chunks temporarily leased by one player at once.")
                .defineInRange("maxTemporaryChunksPerPlayer", 16, 0, 256);
        ANCHOR_RESOLVE_TIMEOUT_TICKS = builder
                .comment("Maximum time a queued remote anchor resolution may remain pending.")
                .defineInRange("anchorResolveTimeoutTicks", 40, 1, 1200);
        ALLOW_UNSIDED_ITEM_HANDLER_FALLBACK = builder
                .comment("After sided handlers and vanilla containers fail, query the target's unsided item capability.")
                .define("allowUnsidedItemHandlerFallback", true);
        UNDO_DEPTH = builder
                .comment("Maximum retained transaction steps per scepter.")
                .defineInRange("undoDepth", 20, 0, 1000);
        MAX_HISTORY_BYTES_PER_PLAYER = builder
                .comment("Maximum serialized transaction history retained for one player.")
                .defineInRange("maxHistoryBytesPerPlayer", 67_108_864, 0, Integer.MAX_VALUE);
        MAX_TRANSACTION_BYTES = builder
                .comment("Maximum serialized size of one complete builder transaction, including all blueprint batches.")
                .defineInRange("maxTransactionBytes", 33_554_432, 0, Integer.MAX_VALUE);
        FULL_BLOCK_ENTITY_POLICY = builder
                .comment("Who may place blueprint block-entity NBT: NEVER, CREATIVE_ONLY, OP_ONLY, or CREATIVE_OR_OP.")
                .define("fullBlockEntityPolicy", FullBlockEntityPolicy.CREATIVE_OR_OP.name(), BuilderConfig::validPolicy);
        MAX_BLUEPRINT_COMPRESSED_BYTES = builder
                .comment("Maximum compressed bytes accepted for one blueprint transfer (protocol memory-safety budget).")
                .defineInRange("maxBlueprintCompressedBytes", 8_388_608, 1024, Integer.MAX_VALUE);
        MAX_BLUEPRINT_UNCOMPRESSED_BYTES = builder
                .comment("Maximum decoded NBT bytes accepted for one blueprint transfer (allocation-safety budget).")
                .defineInRange("maxBlueprintUncompressedBytes", 16_777_216, 1024, Integer.MAX_VALUE);

        builder.pop();
        SPEC = builder.build();
    }

    private BuilderConfig() {
    }

    public static boolean isEnabled() {
        // Recipe conditions may be evaluated during the initial data-pack load,
        // before Forge has attached the per-world SERVER config.  Calling get()
        // in that window is an error in the development environment (and is
        // scheduled to become one in production Forge as well).  The declared
        // default is the only authoritative value available until the config is
        // loaded; later reloads use the actual world value.
        return SPEC.isLoaded() ? ENABLED.get() : ENABLED.getDefault();
    }

    /** Safe during early client item/model queries and the first server data-pack pass. */
    public static <T> T value(ForgeConfigSpec.ConfigValue<T> setting) {
        return SPEC.isLoaded() ? setting.get() : setting.getDefault();
    }

    public static int clampBuildLimit(int requested) {
        return Math.max(1, Math.min(requested, value(MAX_BUILD_LIMIT)));
    }

    public static int clampDemolishLimit(int requested) {
        return Math.max(1, Math.min(requested, value(MAX_DEMOLISH_LIMIT)));
    }

    public static FullBlockEntityPolicy fullBlockEntityPolicy() {
        return FullBlockEntityPolicy.valueOf(value(FULL_BLOCK_ENTITY_POLICY).toUpperCase(Locale.ROOT));
    }

    private static boolean validPolicy(Object candidate) {
        if (!(candidate instanceof String value)) {
            return false;
        }
        try {
            FullBlockEntityPolicy.valueOf(value.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public enum FullBlockEntityPolicy {
        NEVER,
        CREATIVE_ONLY,
        OP_ONLY,
        CREATIVE_OR_OP
    }
}
