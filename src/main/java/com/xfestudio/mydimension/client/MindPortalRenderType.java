package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xfestudio.mydimension.MyDimension;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

public final class MindPortalRenderType {
    private static final ResourceLocation SHADER = ResourceLocation.fromNamespaceAndPath(
            MyDimension.MOD_ID, "mind_portal");
    private static final ResourceLocation OVERLAY_SHADER = ResourceLocation.fromNamespaceAndPath(
            MyDimension.MOD_ID, "mind_portal_overlay");
    private static final ResourceLocation VOID_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MyDimension.MOD_ID, "textures/entity/mind_portal_void.png");
    private static final long OVERLAY_TIME_ORIGIN = System.nanoTime();

    private static ShaderInstance shaderInstance;
    private static ShaderInstance overlayShaderInstance;

    private static final RenderStateShard.MultiTextureStateShard PORTAL_TEXTURES =
            RenderStateShard.MultiTextureStateShard.builder()
                    .add(VOID_TEXTURE, false, false)
                    .add(VOID_TEXTURE, false, false)
                    .build();

    private static final RenderType PORTAL = RenderType.create(
            "mydimension_mind_portal",
            DefaultVertexFormat.POSITION,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> shaderInstance))
                    .setTextureState(PORTAL_TEXTURES)
                    .createCompositeState(false));

    private static final RenderType OVERLAY = RenderType.create(
            "mydimension_mind_portal_overlay",
            DefaultVertexFormat.POSITION,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> overlayShaderInstance))
                    .setTextureState(PORTAL_TEXTURES)
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                            "mydimension_mind_portal_overlay_transparency",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.defaultBlendFunc();
                            },
                            () -> {
                                RenderSystem.disableBlend();
                                RenderSystem.defaultBlendFunc();
                            }))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard(
                            "mydimension_mind_portal_overlay_depth", GL11.GL_ALWAYS))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                    .createCompositeState(false));

    private MindPortalRenderType() {
    }

    public static RenderType get() {
        return PORTAL;
    }

    public static RenderType overlay() {
        return OVERLAY;
    }

    public static boolean setOverlayState(float alpha) {
        if (overlayShaderInstance == null) {
            return false;
        }
        Uniform alphaUniform = overlayShaderInstance.getUniform("PortalAlpha");
        Uniform timeUniform = overlayShaderInstance.getUniform("PortalTime");
        if (alphaUniform == null || timeUniform == null) {
            return false;
        }
        alphaUniform.set(alpha);
        float elapsedSeconds = (System.nanoTime() - OVERLAY_TIME_ORIGIN) / 1_000_000_000.0F;
        timeUniform.set(elapsedSeconds);
        return true;
    }

    public static void resetOverlayAlpha() {
        if (overlayShaderInstance == null) {
            return;
        }
        Uniform uniform = overlayShaderInstance.getUniform("PortalAlpha");
        if (uniform != null) {
            uniform.set(1.0F);
        }
    }

    public static void registerShader(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), SHADER, DefaultVertexFormat.POSITION),
                shader -> shaderInstance = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), OVERLAY_SHADER, DefaultVertexFormat.POSITION),
                shader -> overlayShaderInstance = shader);
    }
}
