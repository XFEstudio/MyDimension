package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.RealmwrightData;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ModelEvent;

/** Renders the static scepter body plus lightweight, independently animated model components. */
public final class RealmwrightScepterRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ResourceLocation BUILD_MODEL = model("realmwright_scepter_build");
    private static final ResourceLocation DEMOLISH_MODEL = model("realmwright_scepter_demolish");
    private static final ResourceLocation LOWER_RING_MODEL = model("realmwright_scepter_ring_lower");
    private static final ResourceLocation UPPER_RING_MODEL = model("realmwright_scepter_ring_upper");
    private static final ResourceLocation FLOATING_MODEL = model("realmwright_scepter_floating");
    private static final ResourceLocation FLOATING_SECONDARY_MODEL = model("realmwright_scepter_floating_secondary");

    // Both orbit rings live on the upper shaft so neither is hidden by the hand in first person.
    // The former lower ring is now the higher, slightly smaller orbit.
    private static final float LOWER_RING_PIVOT_Y = 12.5F / 16.0F;
    private static final float UPPER_RING_PIVOT_Y = 8.25F / 16.0F;

    public RealmwrightScepterRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(BUILD_MODEL);
        event.register(DEMOLISH_MODEL);
        event.register(LOWER_RING_MODEL);
        event.register(UPPER_RING_MODEL);
        event.register(FLOATING_MODEL);
        event.register(FLOATING_SECONDARY_MODEL);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        long animationMillis = Util.getMillis();
        float ringTicks = (animationMillis % 3_600_000L) / 50.0F;
        // Keep bobbing phase in double precision instead of sharing the hourly ring wrap. The
        // ring speeds complete an integral number of turns at that wrap, while a sine wave would
        // otherwise make the floating fragments visibly jump once per long play session.
        double bobPhase = animationMillis * (0.14D / 50.0D);

        ResourceLocation bodyModel = RealmwrightData.mode(stack) == BuilderMode.DEMOLISH
                ? DEMOLISH_MODEL : BUILD_MODEL;
        renderModel(minecraft, bodyModel, stack, pose, buffers, packedLight, packedOverlay);

        pose.pushPose();
        rotateAroundShaft(pose, LOWER_RING_PIVOT_Y, ringTicks * 4.0F, -5.0F);
        renderModel(minecraft, LOWER_RING_MODEL, stack, pose, buffers, packedLight, packedOverlay);
        pose.popPose();

        pose.pushPose();
        rotateAroundShaft(pose, UPPER_RING_PIVOT_Y, ringTicks * -5.5F, 7.0F);
        renderModel(minecraft, UPPER_RING_MODEL, stack, pose, buffers, packedLight, packedOverlay);
        pose.popPose();

        pose.pushPose();
        pose.translate(0.0D, Math.sin(bobPhase) * 0.08D, 0.0D);
        renderModel(minecraft, FLOATING_MODEL, stack, pose, buffers, packedLight, packedOverlay);
        pose.popPose();

        pose.pushPose();
        pose.translate(0.0D, Math.sin(bobPhase + 2.2D) * 0.065D, 0.0D);
        renderModel(minecraft, FLOATING_SECONDARY_MODEL, stack, pose, buffers, packedLight, packedOverlay);
        pose.popPose();
    }

    private static void rotateAroundShaft(PoseStack pose, float pivotY, float degrees, float tiltDegrees) {
        pose.translate(0.5D, pivotY, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(degrees));
        pose.mulPose(Axis.ZP.rotationDegrees(tiltDegrees));
        pose.translate(-0.5D, -pivotY, -0.5D);
    }

    private static void renderModel(Minecraft minecraft, ResourceLocation location, ItemStack stack,
                                    PoseStack pose, MultiBufferSource buffers,
                                    int packedLight, int packedOverlay) {
        BakedModel model = minecraft.getModelManager().getModel(location);
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType renderType : pass.getRenderTypes(stack, true)) {
                VertexConsumer vertexConsumer = ItemRenderer.getFoilBufferDirect(
                        buffers, renderType, true, stack.hasFoil());
                itemRenderer.renderModelLists(pass, stack, packedLight, packedOverlay, pose, vertexConsumer);
            }
        }
    }

    private static ResourceLocation model(String name) {
        return new ResourceLocation(MyDimension.MOD_ID, "item/" + name);
    }
}
