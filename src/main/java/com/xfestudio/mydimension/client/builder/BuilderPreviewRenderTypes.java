package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xfestudio.mydimension.MyDimension;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/**
 * Render states used by the builder preview.
 *
 * <p>In particular, outlines are real quads written to the main target. They
 * deliberately do not use {@link RenderType#lines()}, whose line shader and
 * item-entity target are commonly replaced or omitted by shader packs.</p>
 */
public final class BuilderPreviewRenderTypes {
    private static final ResourceLocation WAVE_SHADER = ResourceLocation.fromNamespaceAndPath(
            MyDimension.MOD_ID, "builder_projection_wave");
    private static final ResourceLocation WAVE_FALLBACK_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MyDimension.MOD_ID, "textures/block/mind_portal.png");
    private static final long TIME_ORIGIN = System.nanoTime();

    private static ShaderInstance waveShader;

    private static final RenderStateShard.TransparencyStateShard TRANSLUCENT =
            new RenderStateShard.TransparencyStateShard(
                    "mydimension_builder_translucency",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
    private static final RenderStateShard.DepthTestStateShard LEQUAL =
            new RenderStateShard.DepthTestStateShard("mydimension_builder_lequal", GL11.GL_LEQUAL);
    private static final RenderStateShard.DepthTestStateShard OCCLUDED =
            new RenderStateShard.DepthTestStateShard("mydimension_builder_occluded", GL11.GL_GREATER);
    private static final RenderStateShard.CullStateShard NO_CULL =
            new RenderStateShard.CullStateShard(false);
    private static final RenderStateShard.CullStateShard CULL =
            new RenderStateShard.CullStateShard(true);
    private static final RenderStateShard.WriteMaskStateShard COLOR_ONLY =
            new RenderStateShard.WriteMaskStateShard(true, false);
    private static final RenderStateShard.WriteMaskStateShard COLOR_AND_DEPTH =
            new RenderStateShard.WriteMaskStateShard(true, true);

    private static final RenderType OUTLINE = createOutline("mydimension_builder_outline", LEQUAL);
    private static final RenderType OUTLINE_XRAY = createOutline(
            "mydimension_builder_outline_xray", OCCLUDED);

    private static final RenderType GHOST_MODEL = RenderType.create(
            "mydimension_builder_ghost_model",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            262_144,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(
                            GameRenderer::getRendertypeEntityTranslucentShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, false, false))
                    .setTransparencyState(TRANSLUCENT)
                    .setDepthTestState(LEQUAL)
                    .setCullState(CULL)
                    .setLightmapState(new RenderStateShard.LightmapStateShard(true))
                    .setOverlayState(new RenderStateShard.OverlayStateShard(true))
                    // A depth-writing translucent model is intentionally used for stable
                    // projection silhouettes. It prevents coplanar/internal model quads from
                    // changing blend order whenever the camera crosses a section boundary.
                    .setWriteMaskState(COLOR_AND_DEPTH)
                    .createCompositeState(false));

    private static final RenderType PROJECTION_WAVE = RenderType.create(
            "mydimension_builder_projection_wave",
            DefaultVertexFormat.POSITION_COLOR_TEX,
            VertexFormat.Mode.QUADS,
            262_144,
            false,
            false,
            RenderType.CompositeState.builder()
                    // The vanilla shader is a deliberate compatibility fallback while resources
                    // are reloading, or if a third-party pack rejects the custom core shader.
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> waveShader != null
                            ? waveShader : GameRenderer.getPositionColorTexShader()))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            WAVE_FALLBACK_TEXTURE, false, false))
                    .setTransparencyState(TRANSLUCENT)
                    .setDepthTestState(LEQUAL)
                    // Both sides matter when the player stands inside a blueprint volume;
                    // this also avoids face loss from shader packs that invert winding.
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_ONLY)
                    .createCompositeState(false));

    private BuilderPreviewRenderTypes() {
    }

    public static RenderType outline() {
        return OUTLINE;
    }

    public static RenderType outlineXray() {
        return OUTLINE_XRAY;
    }

    public static RenderType ghostModel() {
        return GHOST_MODEL;
    }

    public static RenderType projectionWave() {
        return PROJECTION_WAVE;
    }

    /** Updates animation uniforms immediately before the wave VBOs are drawn. */
    public static void updateWaveUniforms() {
        ShaderInstance shader = waveShader;
        if (shader == null) return;
        Uniform time = shader.getUniform("PreviewTime");
        if (time != null) {
            time.set((System.nanoTime() - TIME_ORIGIN) / 1_000_000_000.0F);
        }
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), WAVE_SHADER,
                        DefaultVertexFormat.POSITION_COLOR_TEX),
                shader -> waveShader = shader);
    }

    private static RenderType createOutline(String name,
                                            RenderStateShard.DepthTestStateShard depthTest) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                262_144,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(
                                GameRenderer::getPositionColorShader))
                        .setTransparencyState(TRANSLUCENT)
                        .setDepthTestState(depthTest)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_ONLY)
                        .createCompositeState(false));
    }
}
