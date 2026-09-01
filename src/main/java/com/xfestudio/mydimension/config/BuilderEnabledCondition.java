package com.xfestudio.mydimension.config;

import com.google.gson.JsonObject;
import com.xfestudio.mydimension.MyDimension;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Recipe condition used to remove builder recipes when the server switch is disabled. */
public final class BuilderEnabledCondition implements ICondition {
    public static final ResourceLocation ID = new ResourceLocation(MyDimension.MOD_ID, "builder_enabled");
    public static final BuilderEnabledCondition INSTANCE = new BuilderEnabledCondition();
    public static final Serializer SERIALIZER = new Serializer();

    private BuilderEnabledCondition() {
    }

    @Override
    public ResourceLocation getID() {
        return ID;
    }

    @Override
    public boolean test(IContext context) {
        return BuilderConfig.isEnabled();
    }

    public static final class Serializer implements IConditionSerializer<BuilderEnabledCondition> {
        @Override
        public void write(JsonObject json, BuilderEnabledCondition value) {
        }

        @Override
        public BuilderEnabledCondition read(JsonObject json) {
            return INSTANCE;
        }

        @Override
        public ResourceLocation getID() {
            return ID;
        }
    }

    @Mod.EventBusSubscriber(modid = MyDimension.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private static boolean registered;

        private Registration() {
        }

        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> {
                if (!registered) {
                    CraftingHelper.register(SERIALIZER);
                    registered = true;
                }
            });
        }
    }
}
