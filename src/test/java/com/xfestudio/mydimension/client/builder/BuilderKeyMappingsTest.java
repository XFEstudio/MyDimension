package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.client.settings.KeyModifier;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuilderKeyMappingsTest {
    @Test
    void menuDefaultsToShiftMiddleMouse() {
        assertEquals(KeyModifier.SHIFT, BuilderKeyMappings.OPEN_MENU.getDefaultKeyModifier());
        assertEquals(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
                BuilderKeyMappings.OPEN_MENU.getDefaultKey());
    }
}
