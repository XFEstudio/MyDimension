package com.xfestudio.mydimension.client;

import net.minecraft.client.Minecraft;

public class RiftClient {
    public static void openActionScreen() {
        Minecraft.getInstance().setScreen(new RiftActionScreen());
    }
}
