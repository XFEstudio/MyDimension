package com.xfestudio.mydimension.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xfestudio.mydimension.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public final class MindPortalOverlay {
    private static final ResourceLocation PORTAL_SPRITE = new ResourceLocation("minecraft", "block/nether_portal");
    private static final float CHARGE_STEP = 1.0F / 40.0F;
    private static final float FADE_STEP = 0.1F;

    public static final IGuiOverlay OVERLAY = MindPortalOverlay::render;

    private static float previousIntensity;
    private static float intensity;

    private MindPortalOverlay() {
    }

    public static void tick(Minecraft minecraft) {
        previousIntensity = intensity;
        if (minecraft.level == null || minecraft.player == null) {
            previousIntensity = 0.0F;
            intensity = 0.0F;
            return;
        }

        if (isInsidePortal(minecraft.player)) {
            intensity = Mth.clamp(intensity + CHARGE_STEP, 0.0F, 1.0F);
        } else {
            intensity = Mth.clamp(intensity - FADE_STEP, 0.0F, 1.0F);
        }
    }

    private static boolean isInsidePortal(Player player) {
        AABB bounds = player.getBoundingBox().deflate(0.001D);
        int minX = Mth.floor(bounds.minX);
        int minY = Mth.floor(bounds.minY);
        int minZ = Mth.floor(bounds.minZ);
        int maxX = Mth.floor(bounds.maxX);
        int maxY = Mth.floor(bounds.maxY);
        int maxZ = Mth.floor(bounds.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (player.level().getBlockState(pos.set(x, y, z)).is(ModBlocks.MIND_PORTAL.get())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
                               float partialTick, int screenWidth, int screenHeight) {
        float progress = Mth.lerp(partialTick, previousIntensity, intensity);
        if (progress <= 0.0F) {
            return;
        }

        float alpha = progress;
        if (alpha < 1.0F) {
            alpha *= alpha;
            alpha *= alpha;
            alpha = alpha * 0.8F + 0.2F;
        }

        Minecraft minecraft = gui.getMinecraft();
        float pulse = 0.96F;
        if (minecraft.level != null) {
            pulse += Mth.sin((minecraft.level.getGameTime() + partialTick) * 0.25F) * 0.04F;
        }
        alpha = Mth.clamp(alpha * pulse, 0.0F, 1.0F);

        TextureAtlasSprite sprite = minecraft.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(PORTAL_SPRITE);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(0.76F, 0.9F, 1.0F, alpha);
        graphics.blit(0, 0, -90, screenWidth, screenHeight, sprite);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
