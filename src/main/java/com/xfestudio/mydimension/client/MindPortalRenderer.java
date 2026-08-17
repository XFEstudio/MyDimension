package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xfestudio.mydimension.world.block.MindPortalBlock;
import com.xfestudio.mydimension.world.block.entity.MindPortalBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class MindPortalRenderer implements BlockEntityRenderer<MindPortalBlockEntity> {
    private static final ResourceLocation PORTAL_SPRITE = new ResourceLocation("minecraft", "block/nether_portal");

    public MindPortalRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MindPortalBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (blockEntity.linkId() == null || !blockEntity.getBlockState().getValue(MindPortalBlock.CORE)) {
            return;
        }

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(PORTAL_SPRITE);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();
        float pulse = (float) Math.sin((gameTime + partialTick) * 0.055D);
        Direction.Axis axis = blockEntity.getBlockState().getValue(MindPortalBlock.AXIS);

        poseStack.pushPose();
        renderLayer(poseStack, consumer, sprite, axis, 0.486F + pulse * 0.012F,
                82, 214, 255, 205, false);
        renderLayer(poseStack, consumer, sprite, axis, 0.514F - pulse * 0.009F,
                160, 92, 255, 135, true);
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(MindPortalBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    private static void renderLayer(PoseStack poseStack, VertexConsumer consumer, TextureAtlasSprite sprite,
                                    Direction.Axis axis, float depth, int red, int green, int blue, int alpha,
                                    boolean flipUv) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        float u0 = flipUv ? sprite.getU1() : sprite.getU0();
        float u1 = flipUv ? sprite.getU0() : sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        if (axis == Direction.Axis.X) {
            vertex(consumer, matrix, normal, 0.0F, 0.0F, depth, u0, v1, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
            vertex(consumer, matrix, normal, 3.0F, 0.0F, depth, u1, v1, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
            vertex(consumer, matrix, normal, 3.0F, 4.0F, depth, u1, v0, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
            vertex(consumer, matrix, normal, 0.0F, 4.0F, depth, u0, v0, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);

            vertex(consumer, matrix, normal, 0.0F, 4.0F, depth, u0, v0, red, green, blue, alpha, 0.0F, 0.0F, -1.0F);
            vertex(consumer, matrix, normal, 3.0F, 4.0F, depth, u1, v0, red, green, blue, alpha, 0.0F, 0.0F, -1.0F);
            vertex(consumer, matrix, normal, 3.0F, 0.0F, depth, u1, v1, red, green, blue, alpha, 0.0F, 0.0F, -1.0F);
            vertex(consumer, matrix, normal, 0.0F, 0.0F, depth, u0, v1, red, green, blue, alpha, 0.0F, 0.0F, -1.0F);
        } else {
            vertex(consumer, matrix, normal, depth, 0.0F, 0.0F, u0, v1, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 4.0F, 0.0F, u0, v0, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 4.0F, 3.0F, u1, v0, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 0.0F, 3.0F, u1, v1, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);

            vertex(consumer, matrix, normal, depth, 0.0F, 3.0F, u1, v1, red, green, blue, alpha, -1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 4.0F, 3.0F, u1, v0, red, green, blue, alpha, -1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 4.0F, 0.0F, u0, v0, red, green, blue, alpha, -1.0F, 0.0F, 0.0F);
            vertex(consumer, matrix, normal, depth, 0.0F, 0.0F, u0, v1, red, green, blue, alpha, -1.0F, 0.0F, 0.0F);
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float z, float u, float v,
                               int red, int green, int blue, int alpha,
                               float normalX, float normalY, float normalZ) {
        consumer.vertex(matrix, x, y, z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, normalX, normalY, normalZ)
                .endVertex();
    }
}
