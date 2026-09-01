package com.xfestudio.mydimension.network.builder;

import com.xfestudio.mydimension.builder.BuilderHistoryService;
import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.BuilderNetworkBridge;
import com.xfestudio.mydimension.builder.BuilderOperationManager;
import com.xfestudio.mydimension.builder.BuilderRuntime;
import com.xfestudio.mydimension.builder.BuilderReachValidator;
import com.xfestudio.mydimension.builder.PendingBuildData;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.ResonantAnchorTarget;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import com.xfestudio.mydimension.builder.anchor.AnchorBindings;
import com.xfestudio.mydimension.builder.blueprint.BlueprintServerService;
import com.xfestudio.mydimension.config.BuilderConfig;
import com.xfestudio.mydimension.network.ModNetwork;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Compact C2S intent packet for non-blueprint Realmwright operations. No item
 * or world state supplied by the client is trusted by the handler.
 */
public final class BuilderCommandPacket {
    private final Action action;
    @Nullable private final BuilderMode mode;
    @Nullable private final SurfaceMatchMode match;
    private final int first;
    private final int second;
    @Nullable private final UUID id;
    @Nullable private final Target target;
    @Nullable private final String text;

    private BuilderCommandPacket(Action action, @Nullable BuilderMode mode,
                                 @Nullable SurfaceMatchMode match, int first, int second,
                                 @Nullable UUID id, @Nullable Target target, @Nullable String text) {
        this.action = action;
        this.mode = mode;
        this.match = match;
        this.first = first;
        this.second = second;
        this.id = id;
        this.target = target;
        this.text = text;
    }

    public static BuilderCommandPacket requestSnapshot() {
        return simple(Action.REQUEST_SNAPSHOT);
    }

    public static BuilderCommandPacket openMenu() {
        return simple(Action.OPEN_MENU);
    }

    public static BuilderCommandPacket setMode(BuilderMode mode) {
        return new BuilderCommandPacket(Action.SET_MODE, mode, null, 0, 0, null, null, null);
    }

    public static BuilderCommandPacket setMatch(SurfaceMatchMode match) {
        return new BuilderCommandPacket(Action.SET_MATCH, null, match, 0, 0, null, null, null);
    }

    public static BuilderCommandPacket setLimits(int buildLimit, int demolishLimit) {
        return new BuilderCommandPacket(Action.SET_LIMITS, null, null,
                buildLimit, demolishLimit, null, null, null);
    }

    public static BuilderCommandPacket setHistoryRecording(boolean enabled) {
        return new BuilderCommandPacket(Action.SET_HISTORY_RECORDING, null, null,
                enabled ? 1 : 0, 0, null, null, null);
    }

    public static BuilderCommandPacket use(Target target) {
        return new BuilderCommandPacket(Action.USE, null, null, 0, 0, null, target, null);
    }

    public static BuilderCommandPacket resume(@Nullable UUID activeJobId) {
        return new BuilderCommandPacket(Action.RESUME, null, null, 0, 0, activeJobId, null, null);
    }

    public static BuilderCommandPacket cancel(@Nullable UUID activeJobId) {
        return new BuilderCommandPacket(Action.CANCEL, null, null, 0, 0, activeJobId, null, null);
    }

    public static BuilderCommandPacket cancelBlueprint() {
        return simple(Action.CANCEL_BLUEPRINT);
    }

    public static BuilderCommandPacket undo() {
        return simple(Action.UNDO);
    }

    public static BuilderCommandPacket redo() {
        return simple(Action.REDO);
    }

    public static BuilderCommandPacket unbindAnchor(UUID anchorId) {
        return new BuilderCommandPacket(Action.UNBIND_ANCHOR, null, null, 0, 0, anchorId, null, null);
    }

    public static BuilderCommandPacket moveAnchor(UUID anchorId, int delta) {
        return new BuilderCommandPacket(Action.MOVE_ANCHOR, null, null,
                Integer.compare(delta, 0), 0, anchorId, null, null);
    }

    public static BuilderCommandPacket setAnchorPublic(UUID anchorId, boolean value) {
        return new BuilderCommandPacket(Action.SET_ANCHOR_PUBLIC, null, null, value ? 1 : 0, 0,
                anchorId, null, null);
    }

    public static BuilderCommandPacket setAnchorPlayer(UUID anchorId, String playerName, boolean authorize) {
        return new BuilderCommandPacket(Action.SET_ANCHOR_PLAYER, null, null, authorize ? 1 : 0, 0,
                anchorId, null, playerName);
    }

    private static BuilderCommandPacket simple(Action action) {
        return new BuilderCommandPacket(action, null, null, 0, 0, null, null, null);
    }

    public static void encode(BuilderCommandPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
        switch (packet.action) {
            case SET_MODE -> buffer.writeEnum(packet.mode);
            case SET_MATCH -> buffer.writeEnum(packet.match);
            case SET_LIMITS -> {
                buffer.writeVarInt(packet.first);
                buffer.writeVarInt(packet.second);
            }
            case SET_HISTORY_RECORDING -> buffer.writeBoolean(packet.first != 0);
            case USE -> packet.target.write(buffer);
            case RESUME, CANCEL -> writeOptionalUuid(buffer, packet.id);
            case UNBIND_ANCHOR -> buffer.writeUUID(packet.id);
            case MOVE_ANCHOR -> {
                buffer.writeUUID(packet.id);
                buffer.writeByte(packet.first);
            }
            case SET_ANCHOR_PUBLIC -> {
                buffer.writeUUID(packet.id);
                buffer.writeBoolean(packet.first != 0);
            }
            case SET_ANCHOR_PLAYER -> {
                buffer.writeUUID(packet.id);
                buffer.writeUtf(packet.text == null ? "" : packet.text, 64);
                buffer.writeBoolean(packet.first != 0);
            }
            default -> {
            }
        }
    }

    public static BuilderCommandPacket decode(FriendlyByteBuf buffer) {
        Action action = buffer.readEnum(Action.class);
        return switch (action) {
            case REQUEST_SNAPSHOT, OPEN_MENU, UNDO, REDO, CANCEL_BLUEPRINT -> simple(action);
            case SET_MODE -> setMode(buffer.readEnum(BuilderMode.class));
            case SET_MATCH -> setMatch(buffer.readEnum(SurfaceMatchMode.class));
            case SET_LIMITS -> setLimits(buffer.readVarInt(), buffer.readVarInt());
            case SET_HISTORY_RECORDING -> setHistoryRecording(buffer.readBoolean());
            case USE -> use(Target.read(buffer));
            case RESUME -> resume(readOptionalUuid(buffer));
            case CANCEL -> cancel(readOptionalUuid(buffer));
            case UNBIND_ANCHOR -> unbindAnchor(buffer.readUUID());
            case MOVE_ANCHOR -> moveAnchor(buffer.readUUID(), buffer.readByte());
            case SET_ANCHOR_PUBLIC -> setAnchorPublic(buffer.readUUID(), buffer.readBoolean());
            case SET_ANCHOR_PLAYER -> setAnchorPlayer(buffer.readUUID(), buffer.readUtf(64), buffer.readBoolean());
        };
    }

    public static void handle(BuilderCommandPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(BuilderCommandPacket packet, @Nullable ServerPlayer player) {
        if (player == null) return;
        ItemStack scepter = player.getMainHandItem();
        // This check is deliberately first and applies even to read-only/menu intents.
        if (!scepter.is(ModItems.REALMWRIGHT_SCEPTER.get())) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.hold_scepter"), true);
            return;
        }
        RealmwrightData.ensureId(scepter);

        if (packet.action == Action.OPEN_MENU) {
            BuilderNetworkBridge.openMenu(player);
            return;
        }
        if (packet.action == Action.REQUEST_SNAPSHOT) {
            // The menu polls lightweight metadata; the potentially large cell
            // preview is pushed only when a workflow actually changes.
            ModNetwork.sendBuilderSnapshot(player);
            return;
        }

        if (packet.action.worldMutation) {
            if (player.getCooldowns().isOnCooldown(scepter.getItem())) {
                return;
            }
            player.getCooldowns().addCooldown(scepter.getItem(), 1);
        }

        if (packet.action == Action.USE) {
            if (useTarget(player, scepter, packet.target)) BuilderNetworkBridge.sync(player);
            return;
        }

        switch (packet.action) {
            case SET_MODE -> RealmwrightData.setMode(scepter, packet.mode);
            case SET_MATCH -> RealmwrightData.setMatchMode(scepter, packet.match);
            case SET_LIMITS -> {
                RealmwrightData.setBuildLimit(scepter, packet.first,
                        BuilderRuntime.settings().maxBuildLimit());
                RealmwrightData.setDemolishLimit(scepter, packet.second,
                        BuilderRuntime.settings().maxDemolishLimit());
            }
            case SET_HISTORY_RECORDING -> RealmwrightData.setRecordsHistory(scepter, packet.first != 0);
            case USE -> { }
            case RESUME -> resume(player, scepter, packet.id);
            case CANCEL -> cancel(player, scepter, packet.id);
            case CANCEL_BLUEPRINT -> cancelBlueprint(player, scepter);
            case UNDO -> {
                BuilderHistoryService.undo(player, scepter);
            }
            case REDO -> {
                BuilderHistoryService.redo(player, scepter);
            }
            case UNBIND_ANCHOR -> AnchorBindings.unbind(scepter, packet.id);
            case MOVE_ANCHOR -> moveAnchor(scepter, packet.id, packet.first);
            case SET_ANCHOR_PUBLIC -> updateAnchorAcl(player, packet.id, null, packet.first != 0);
            case SET_ANCHOR_PLAYER -> updateAnchorAcl(player, packet.id, packet.text, packet.first != 0);
            default -> {
            }
        }
        BuilderNetworkBridge.sync(player);
    }

    private static boolean useTarget(ServerPlayer player, ItemStack scepter, @Nullable Target requested) {
        if (!BuilderRuntime.settings().enabled()) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.disabled"), true);
            return false;
        }
        BlockHitResult hit = authoritativeHit(player, requested);
        if (hit == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.out_of_reach"), true);
            return false;
        }

        BlockEntity blockEntity = player.serverLevel().getBlockEntity(hit.getBlockPos());
        if (blockEntity instanceof ResonantAnchorTarget anchor) {
            if (!anchor.mayUse(player)) {
                player.displayClientMessage(Component.translatable(
                        "message.mydimension.builder.anchor_forbidden"), true);
                return false;
            }
            AnchorBindings.BindResult result = AnchorBindings.bind(scepter, anchor.anchorId(),
                    BuilderConfig.MAX_BOUND_ANCHORS.get());
            String key = switch (result) {
                case BOUND -> "message.mydimension.builder.anchor_bound";
                case ALREADY_BOUND -> "message.mydimension.builder.anchor_already_bound";
                case LIMIT_REACHED -> "message.mydimension.builder.anchor_limit_reached";
            };
            player.displayClientMessage(Component.translatable(key), true);
            return result == AnchorBindings.BindResult.BOUND;
        }

        BuilderOperationManager.Result result = BuilderOperationManager.executeSurface(player, scepter, hit);
        if (!result.accepted() && result.rejectionKey() != null) {
            player.displayClientMessage(Component.translatable(result.rejectionKey()), true);
        }
        return result.shouldSynchronize();
    }

    @Nullable
    private static BlockHitResult authoritativeHit(ServerPlayer player, @Nullable Target requested) {
        if (requested == null) return null;
        return BuilderReachValidator.validatedHit(player, requested.pos, requested.face, requested.inside);
    }

    private static void resume(ServerPlayer player, ItemStack scepter, @Nullable UUID requestedJob) {
        PendingBuildData.Task pending = matchingPending(player, scepter, requestedJob);
        if (pending == null) {
            player.displayClientMessage(Component.translatable(
                    "message.mydimension.builder.no_pending_task"), true);
            return;
        }
        BuilderOperationManager.Result result = BuilderOperationManager.resumePending(player, scepter);
        if (!result.accepted() && result.rejectionKey() != null) {
            player.displayClientMessage(Component.translatable(result.rejectionKey()), true);
        }
    }

    private static void cancel(ServerPlayer player, ItemStack scepter, @Nullable UUID requestedJob) {
        com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager manager =
                com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(player.getServer());
        if (requestedJob == null) {
            BlueprintServerService.get(player.getServer()).cancelQueuedPlacement(player);
            manager.cancel(player, scepter);
            PendingBuildData.Task pending = PendingBuildData.get(player.getServer()).get(player.getUUID());
            if (pending != null && pending.scepterId().equals(RealmwrightData.id(scepter))) {
                BuilderOperationManager.cancelPending(player, scepter);
            }
            return;
        }
        com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.Status running = manager.status(player);
        if (requestedJob != null && requestedJob.equals(running.transactionId())) {
            manager.cancel(player, scepter);
            return;
        }
        if (matchingPending(player, scepter, requestedJob) != null) {
            BuilderOperationManager.cancelPending(player, scepter);
        }
    }

    private static void cancelBlueprint(ServerPlayer player, ItemStack scepter) {
        BlueprintServerService.get(player.getServer()).cancelQueuedPlacement(player);
        com.xfestudio.mydimension.builder.blueprint.BlueprintTaskManager.get(player.getServer())
                .cancel(player, scepter);
    }

    @Nullable
    private static PendingBuildData.Task matchingPending(ServerPlayer player, ItemStack scepter,
                                                          @Nullable UUID requestedJob) {
        if (requestedJob == null) return null;
        PendingBuildData.Task pending = PendingBuildData.get(player.getServer()).get(player.getUUID());
        if (pending == null || !pending.scepterId().equals(RealmwrightData.id(scepter))) return null;
        return pending.transactionId().equals(requestedJob) ? pending : null;
    }

    private static void moveAnchor(ItemStack scepter, @Nullable UUID anchorId, int delta) {
        if (anchorId == null || delta == 0) return;
        List<UUID> anchors = AnchorBindings.read(scepter);
        int current = anchors.indexOf(anchorId);
        if (current >= 0) AnchorBindings.move(scepter, anchorId, current + Integer.signum(delta));
    }

    private static void updateAnchorAcl(ServerPlayer actor, @Nullable UUID anchorId,
                                        @Nullable String playerName, boolean value) {
        if (!BuilderRuntime.settings().enabled() || anchorId == null) return;
        var location = com.xfestudio.mydimension.builder.anchor.AnchorIndexSavedData.get(actor.getServer())
                .find(anchorId).orElse(null);
        if (location == null) return;
        var level = actor.getServer().getLevel(location.dimension());
        if (level == null || !level.hasChunkAt(location.position())
                || !(level.getBlockEntity(location.position()) instanceof
                com.xfestudio.mydimension.builder.anchor.ResonantSupplyAnchorBlockEntity anchor)
                || !anchor.anchorId().equals(anchorId) || !anchor.canManage(actor)) return;
        if (playerName == null) {
            anchor.setPublicAccess(actor, value);
            return;
        }
        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(playerName.strip());
        if (target == null) {
            actor.displayClientMessage(Component.translatable("message.mydimension.builder.anchor_player_offline"),
                    true);
        } else if (value) {
            anchor.authorize(actor, target.getUUID());
        } else {
            anchor.revoke(actor, target.getUUID());
        }
    }

    private static void writeOptionalUuid(FriendlyByteBuf buffer, @Nullable UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUUID(value);
    }

    @Nullable
    private static UUID readOptionalUuid(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUUID() : null;
    }

    public enum Action {
        REQUEST_SNAPSHOT(false),
        OPEN_MENU(false),
        SET_MODE(false),
        SET_MATCH(false),
        SET_LIMITS(false),
        SET_HISTORY_RECORDING(false),
        USE(true),
        RESUME(true),
        CANCEL(false),
        UNDO(true),
        REDO(true),
        UNBIND_ANCHOR(false),
        MOVE_ANCHOR(false),
        SET_ANCHOR_PUBLIC(false),
        SET_ANCHOR_PLAYER(false),
        CANCEL_BLUEPRINT(false);

        private final boolean worldMutation;

        Action(boolean worldMutation) {
            this.worldMutation = worldMutation;
        }
    }

    public record Target(BlockPos pos, Direction face, boolean inside) {
        public Target {
            pos = pos.immutable();
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeBlockPos(pos);
            buffer.writeEnum(face);
            buffer.writeBoolean(inside);
        }

        private static Target read(FriendlyByteBuf buffer) {
            return new Target(buffer.readBlockPos(), buffer.readEnum(Direction.class), buffer.readBoolean());
        }
    }
}
