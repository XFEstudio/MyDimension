package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xfestudio.mydimension.world.block.MindPortalBlock;
import com.xfestudio.mydimension.world.block.entity.MindPortalBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Renders every portal block as one continuous projection-driven surface. The custom shader
 * follows the End Portal rendering approach, so moving the camera reveals real parallax depth.
 */
public class MindPortalRenderer implements BlockEntityRenderer<MindPortalBlockEntity> {
    private static final float SURFACE_DEPTH = 0.5F;

    public MindPortalRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MindPortalBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(MindPortalRenderType.get());
        Direction.Axis axis = blockEntity.getBlockState().getValue(MindPortalBlock.AXIS);

        if (axis == Direction.Axis.X) {
            renderAxisX(matrix, consumer);
        } else {
            renderAxisZ(matrix, consumer);
        }
    }

    private static void renderAxisX(Matrix4f matrix, VertexConsumer consumer) {
        vertex(consumer, matrix, 0.0F, 0.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 1.0F, 0.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 1.0F, 1.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 0.0F, 1.0F, SURFACE_DEPTH);

        vertex(consumer, matrix, 0.0F, 1.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 1.0F, 1.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 1.0F, 0.0F, SURFACE_DEPTH);
        vertex(consumer, matrix, 0.0F, 0.0F, SURFACE_DEPTH);
    }

    private static void renderAxisZ(Matrix4f matrix, VertexConsumer consumer) {
        vertex(consumer, matrix, SURFACE_DEPTH, 0.0F, 0.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 1.0F, 0.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 1.0F, 1.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 0.0F, 1.0F);

        vertex(consumer, matrix, SURFACE_DEPTH, 0.0F, 1.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 1.0F, 1.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 1.0F, 0.0F);
        vertex(consumer, matrix, SURFACE_DEPTH, 0.0F, 0.0F);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z) {
        consumer.vertex(matrix, x, y, z).endVertex();
    }
}
