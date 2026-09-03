package com.xfestudio.mydimension.network.builder;

import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.SurfaceMatchMode;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderSnapshotPacketTest {
    @Test
    void roundTripPreservesReplacementSetting() {
        BuilderSnapshotPacket original = new BuilderSnapshotPacket(
                true, BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK,
                false, true, 256, 64, 4_096, 1_024, 64,
                "", null, 0, 0, false, false, List.of(), List.of());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        BuilderSnapshotPacket.encode(original, buffer);
        BuilderSnapshotPacket decoded = BuilderSnapshotPacket.decode(buffer);

        assertEquals(original, decoded);
        assertTrue(decoded.allowReplacement());
    }
}
