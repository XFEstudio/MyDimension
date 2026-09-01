package com.xfestudio.mydimension.client.builder;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

/** Global client-side entry point intentionally independent of ModNetwork. */
public final class BuilderClientServices {
    public static final ResourceLocation REALMWRIGHT_ID =
            new ResourceLocation(MyDimension.MOD_ID, "realmwright_scepter");

    private static volatile BuilderClientBridge bridge = BuilderClientBridge.NOOP;

    private BuilderClientServices() {
    }

    public static void install(BuilderClientBridge value) {
        bridge = Objects.requireNonNull(value, "value");
    }

    public static BuilderClientBridge bridge() {
        return bridge;
    }

    public static BuilderClientSnapshot snapshot() {
        return bridge.snapshot();
    }

    public static void send(BuilderClientCommand command) {
        bridge.send(command);
    }

    public static boolean isHoldingRealmwright(Minecraft minecraft) {
        return minecraft.player != null && bridge.isRealmwright(minecraft.player.getMainHandItem());
    }

    static boolean isRealmwrightByRegistry(ItemStack stack) {
        return !stack.isEmpty() && REALMWRIGHT_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    public static void openToolScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        bridge.requestSnapshot();
        minecraft.setScreen(new BuilderToolScreen());
    }
}
