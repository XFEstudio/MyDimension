package com.xfestudio.mydimension.network.builder;

import com.xfestudio.mydimension.builder.PendingBuildData;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.BuilderSurfaceTaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Immutable server preview. At present this primarily carries material-waiting cells. */
public record BuilderPreviewPacket(ResourceLocation dimension, List<Cell> cells,
                                   @Nullable BlockPos first, @Nullable BlockPos second,
                                   @Nullable UUID activeJobId, boolean blueprintPreview,
                                   boolean cancelable, int revision) {
    private static final int MAX_CELLS = 65_536;

    public BuilderPreviewPacket {
        cells = List.copyOf(cells);
        first = first == null ? null : first.immutable();
        second = second == null ? null : second.immutable();
    }

    public static BuilderPreviewPacket from(ServerPlayer player, ItemStack scepter) {
        BuilderSurfaceTaskManager.Status running = BuilderSurfaceTaskManager.get(player.getServer()).status(player);
        if (running.transactionId() != null) {
            // An empty authoritative task snapshot freezes client-side surface replanning while the world
            // changes underneath the queued operation. No per-tick full preview packet is necessary.
            return new BuilderPreviewPacket(player.level().dimension().location(), List.of(),
                    null, null, running.transactionId(), false, true, revision(player));
        }
        PendingBuildData.Task task = PendingBuildData.get(player.getServer()).get(player.getUUID());
        if (task == null || !task.scepterId().equals(RealmwrightData.id(scepter))) {
            return new BuilderPreviewPacket(player.level().dimension().location(), List.of(),
                    null, null, null, false, false, revision(player));
        }
        List<Cell> cells = task.missing().stream().limit(MAX_CELLS)
                .map(entry -> new Cell(entry.pos(), entry.state(), Kind.MISSING, true))
                .toList();
        return new BuilderPreviewPacket(task.dimension().location(), cells, null, null,
                task.transactionId(), false, true, revision(player));
    }

    public static void encode(BuilderPreviewPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.dimension);
        buffer.writeVarInt(packet.cells.size());
        for (Cell cell : packet.cells) {
            buffer.writeBlockPos(cell.pos);
            buffer.writeVarInt(Block.getId(cell.state));
            buffer.writeEnum(cell.kind);
            buffer.writeBoolean(cell.ghost);
        }
        writeOptionalPos(buffer, packet.first);
        writeOptionalPos(buffer, packet.second);
        writeOptionalUuid(buffer, packet.activeJobId);
        buffer.writeBoolean(packet.blueprintPreview);
        buffer.writeBoolean(packet.cancelable);
        buffer.writeVarInt(packet.revision);
    }

    public static BuilderPreviewPacket decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        int count = boundedCount(buffer.readVarInt(), MAX_CELLS, "builder preview cells");
        List<Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buffer.readBlockPos();
            BlockState state = Block.stateById(buffer.readVarInt());
            cells.add(new Cell(pos, state, buffer.readEnum(Kind.class), buffer.readBoolean()));
        }
        return new BuilderPreviewPacket(dimension, cells, readOptionalPos(buffer), readOptionalPos(buffer),
                readOptionalUuid(buffer), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt());
    }

    public static void handle(BuilderPreviewPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BuilderClientPacketHooks.receiver().preview(packet));
        context.setPacketHandled(true);
    }

    private static int revision(ServerPlayer player) {
        return (int) (player.serverLevel().getGameTime() & Integer.MAX_VALUE);
    }

    private static int boundedCount(int value, int maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Invalid " + label + " count: " + value);
        }
        return value;
    }

    private static void writeOptionalPos(FriendlyByteBuf buffer, @Nullable BlockPos value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeBlockPos(value);
    }

    @Nullable
    private static BlockPos readOptionalPos(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readBlockPos() : null;
    }

    private static void writeOptionalUuid(FriendlyByteBuf buffer, @Nullable UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUUID(value);
    }

    @Nullable
    private static UUID readOptionalUuid(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUUID() : null;
    }

    public record Cell(BlockPos pos, BlockState state, Kind kind, boolean ghost) {
        public Cell {
            pos = pos.immutable();
        }
    }

    public enum Kind {
        BUILD,
        DEMOLISH,
        MISSING,
        INVALID,
        BLUEPRINT
    }
}
