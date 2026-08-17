package com.xfestudio.mydimension.client;

import com.xfestudio.mydimension.network.MindAccessPromptPacket;
import com.xfestudio.mydimension.network.TeamMindDataPacket;
import net.minecraft.client.Minecraft;

public class RiftClient {
    public static void openActionScreen() {
        Minecraft.getInstance().setScreen(new RiftActionScreen());
    }

    public static void handleTeamMindData(TeamMindDataPacket packet) {
        if (Minecraft.getInstance().screen instanceof TeamMindScreen screen) {
            screen.updateData(packet);
        }
    }

    public static void openMindAccessPrompt(MindAccessPromptPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new MindAccessRequestScreen(minecraft.screen, packet.requesterId(), packet.requesterName()));
    }
}
