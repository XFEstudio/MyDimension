package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintLimits;
import com.xfestudio.mydimension.builder.blueprint.BlueprintPlacementPlan;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransform;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueprintUnboundedSizeTest {
    @Test
    void acceptsDimensionsBeyondFormerAxisAndVolumeLimits() {
        BlueprintData blueprint = emptyBlueprint(20_000, 384, 20_000,
                new BlockPos(10_000, 192, 10_000));

        assertEquals(153_600_000_000L, blueprint.volume());
    }

    @Test
    void acceptsProductsLargerThanLongWithoutRejectingDimensions() {
        BlueprintData blueprint = emptyBlueprint(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                BlockPos.ZERO);

        assertEquals(Long.MAX_VALUE, blueprint.volume());
    }

    @Test
    void savesLoadsAndPlansGeometryBeyondTheFormerLimits() throws Exception {
        SharedConstants.tryDetectVersion();
        BlueprintData source = emptyBlueprint(20_000, 384, 20_000,
                new BlockPos(10_000, 192, 10_000));

        BlueprintData decoded = BlueprintIo.decode(BlueprintIo.encode(source));
        BlueprintPlacementPlan plan = BlueprintPlacementPlan.create(decoded, BlueprintTransform.NONE,
                new BlockPos(10, 64, 10));

        assertEquals(20_000, decoded.sizeX());
        assertEquals(153_600_000_000L, decoded.volume());
        assertEquals(0, plan.blocks().size());
    }

    @Test
    void formerGeometryAndBlockCountLimitConstantsStayRemoved() {
        assertThrows(NoSuchFieldException.class, () -> BlueprintLimits.class.getDeclaredField("MAX_AXIS"));
        assertThrows(NoSuchFieldException.class, () -> BlueprintLimits.class.getDeclaredField("MAX_VOLUME"));
        assertThrows(NoSuchFieldException.class, () -> BlueprintLimits.class.getDeclaredField("MAX_BLOCKS"));
        assertThrows(NoSuchFieldException.class, () -> BuilderConfig.class.getDeclaredField("MAX_BLUEPRINT_AXIS"));
        assertThrows(NoSuchFieldException.class, () -> BuilderConfig.class.getDeclaredField("MAX_BLUEPRINT_VOLUME"));
        assertThrows(NoSuchFieldException.class, () -> BuilderConfig.class.getDeclaredField("MAX_BLUEPRINT_BLOCKS"));
    }

    @Test
    void stillRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> emptyBlueprint(0, 1, 1, BlockPos.ZERO));
    }

    private static BlueprintData emptyBlueprint(int sizeX, int sizeY, int sizeZ, BlockPos anchor) {
        return new BlueprintData(UUID.randomUUID(), "Large Empty", "test", null, 0L,
                BlueprintSaveMode.BLOCKS_ONLY, sizeX, sizeY, sizeZ, anchor, List.of(), List.of());
    }
}
