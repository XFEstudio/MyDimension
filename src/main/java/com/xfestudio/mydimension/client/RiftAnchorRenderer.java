package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.world.entity.RiftAnchorEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class RiftAnchorRenderer extends EntityRenderer<RiftAnchorEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(MyDimension.MOD_ID, "textures/entity/rift_anchor.png");

    public RiftAnchorRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.0F;
    }

    @Override
    public void render(RiftAnchorEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.9D + Math.sin((entity.tickCount + partialTick) * 0.08D) * 0.08D, 0.0D);
        poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
        poseStack.scale(1.05F, 1.05F, 1.05F);

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        vertex(consumer, matrix, normal, -0.5F, -0.5F, 0.0F, 0.0F, packedLight);
        vertex(consumer, matrix, normal, 0.5F, -0.5F, 1.0F, 0.0F, packedLight);
        vertex(consumer, matrix, normal, 0.5F, 0.5F, 1.0F, 1.0F, packedLight);
        vertex(consumer, matrix, normal, -0.5F, 0.5F, 0.0F, 1.0F, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(RiftAnchorEntity entity) {
        return TEXTURE;
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal, float x, float y, float u, float v, int packedLight) {
        consumer.vertex(matrix, x, y, 0.0F)
                .color(255, 255, 255, 220)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(normal, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }
}
