package com.xfestudio.mydimension.world;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runtime check that the transformed mind level data no longer writes the overworld clock. */
@GameTestHolder(MyDimension.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MindTimeGameTests {
    private MindTimeGameTests() {
    }

    @GameTest(template = "empty")
    public static void mindClockIsIndependentFromOverworld(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel mind = helper.getLevel().getServer().getLevel(ModDimensions.ETHEREAL_MIND);
        helper.assertTrue(mind != null, "Ethereal mind level was not loaded");
        if (mind == null) {
            return;
        }

        MindTimeController.attach(mind);
        helper.assertTrue(MindTimeController.hasIndependentTime(mind),
                "Mind level did not receive independent level data");

        long overworldBefore = overworld.getDayTime();
        long mindBefore = mind.getDayTime();
        long mindTarget = mindBefore + 7_321L;
        mind.setDayTime(mindTarget);

        helper.assertTrue(mind.getDayTime() == mindTarget,
                "Mind level rejected an independent daylight update");
        helper.assertTrue(overworld.getDayTime() == overworldBefore,
                "Changing mind daylight also changed the overworld");

        mind.setDayTime(mindBefore);
        helper.succeed();
    }
}
