package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransferAssembler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueprintTransferAssemblerTest {
    @Test
    void assemblesOrderedChunksAndChecksHash() throws Exception {
        byte[] bytes = new byte[49_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        BlueprintTransferAssembler assembler = new BlueprintTransferAssembler(UUID.randomUUID(), bytes.length,
                2, BlueprintIo.sha256(bytes), 0);
        assembler.accept(0, Arrays.copyOfRange(bytes, 0, 24_576), 1);
        assembler.accept(1, Arrays.copyOfRange(bytes, 24_576, bytes.length), 2);
        assertArrayEquals(bytes, assembler.finish(3));
    }

    @Test
    void rejectsOutOfOrderChunks() {
        byte[] bytes = new byte[30_000];
        BlueprintTransferAssembler assembler = new BlueprintTransferAssembler(UUID.randomUUID(), bytes.length,
                2, BlueprintIo.sha256(bytes), 0);
        assertThrows(IOException.class,
                () -> assembler.accept(1, Arrays.copyOfRange(bytes, 24_576, bytes.length), 1));
    }
}
