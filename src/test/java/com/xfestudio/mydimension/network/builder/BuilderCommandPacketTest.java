package com.xfestudio.mydimension.network.builder;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BuilderCommandPacketTest {
    @Test
    void usePacketRoundTripPreservesPhysicalShiftOverrideIntent() {
        BuilderCommandPacket.Target target = new BuilderCommandPacket.Target(
                new BlockPos(4, 5, 6), Direction.WEST, true);
        byte[] shifted = encode(BuilderCommandPacket.use(target, true));
        byte[] ordinary = encode(BuilderCommandPacket.use(target, false));

        BuilderCommandPacket decoded = BuilderCommandPacket.decode(
                new FriendlyByteBuf(Unpooled.wrappedBuffer(shifted)));

        assertArrayEquals(shifted, encode(decoded));
        assertFalse(java.util.Arrays.equals(shifted, ordinary));
    }

    private static byte[] encode(BuilderCommandPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BuilderCommandPacket.encode(packet, buffer);
        return ByteBufUtil.getBytes(buffer);
    }
}
