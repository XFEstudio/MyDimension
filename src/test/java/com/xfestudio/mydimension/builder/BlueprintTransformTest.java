package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.blueprint.BlueprintTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueprintTransformTest {
    @Test
    void preservesNonCornerAnchorAcrossMirrorsAndRotation() {
        BlockPos anchor = new BlockPos(1, 2, 3);
        BlueprintTransform transform = new BlueprintTransform(true, true, false, Rotation.CLOCKWISE_90);
        assertEquals(new BlockPos(1, 2, 2), transform.transform(anchor, 4, 5, 5));
    }

    @Test
    void maskRoundTripsEverySupportedFlag() {
        BlueprintTransform expected = new BlueprintTransform(true, true, true, Rotation.COUNTERCLOCKWISE_90);
        assertEquals(expected, BlueprintTransform.fromMask(expected.mask()));
    }
}
