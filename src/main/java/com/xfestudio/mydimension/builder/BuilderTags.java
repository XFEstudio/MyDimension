package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.MyDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class BuilderTags {
    public static final TagKey<Block> CONSTRUCTION_PROTECTED = TagKey.create(
            Registries.BLOCK, new ResourceLocation(MyDimension.MOD_ID, "construction_protected"));
    public static final TagKey<Block> TRANSACTION_UNSAFE = TagKey.create(
            Registries.BLOCK, new ResourceLocation(MyDimension.MOD_ID, "transaction_unsafe"));

    private BuilderTags() {
    }
}
