package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.platform.InputConstants;
import com.xfestudio.mydimension.MyDimension;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BuilderKeyMappings {
    public static final String CATEGORY = "key.categories.mydimension.realmwright";

    public static final KeyMapping TOGGLE_MODE = new KeyMapping(
            "key.mydimension.realmwright.toggle_mode",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            CATEGORY
    );
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.mydimension.realmwright.open_menu",
            KeyConflictContext.IN_GAME,
            KeyModifier.SHIFT,
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            CATEGORY
    );
    public static final KeyMapping UNDO = new KeyMapping(
            "key.mydimension.realmwright.undo",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
    );
    public static final KeyMapping REDO = new KeyMapping(
            "key.mydimension.realmwright.redo",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            CATEGORY
    );

    private BuilderKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_MODE);
        event.register(OPEN_MENU);
        event.register(UNDO);
        event.register(REDO);
    }
}
