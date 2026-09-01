package com.xfestudio.mydimension.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.xfestudio.mydimension.builder.BuilderMode;
import com.xfestudio.mydimension.builder.BuilderInteractionPolicy;
import com.xfestudio.mydimension.builder.BuilderNetworkBridge;
import com.xfestudio.mydimension.builder.BuilderOperationManager;
import com.xfestudio.mydimension.builder.BuilderRuntime;
import com.xfestudio.mydimension.builder.RealmwrightData;
import com.xfestudio.mydimension.builder.ResonantAnchorTarget;
import com.xfestudio.mydimension.client.builder.RealmwrightScepterClientExtensions;
import com.xfestudio.mydimension.config.BuilderConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class RealmwrightScepterItem extends Item {
    private static final UUID REACH_MODIFIER_ID = UUID.fromString("6db36f7a-5c3c-4e53-91d3-dced67201f45");

    public RealmwrightScepterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        RealmwrightScepterClientExtensions.initialize(consumer);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        boolean shiftDown = player.isShiftKeyDown();
        BlockEntity blockEntity = context.getLevel().getBlockEntity(context.getClickedPos());
        if (!shiftDown && blockEntity instanceof ResonantAnchorTarget) {
            if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
            return bindAnchor((ServerPlayer) player, context.getItemInHand(),
                    (ResonantAnchorTarget) blockEntity);
        }
        // Physical Shift overrides are sent by BuilderClientEvents with an explicit packet bit.
        // The ordinary vanilla Item#useOn packet carries only the remappable sneak state, so it
        // must never bypass an interaction-priority block on its own.
        if (BuilderInteractionPolicy.prioritizesBlock(
                context.getLevel().getBlockState(context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        ServerPlayer serverPlayer = (ServerPlayer) player;
        ItemStack scepter = context.getItemInHand();
        RealmwrightData.ensureId(scepter);
        if (!BuilderRuntime.settings().enabled()) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.disabled"), true);
            return InteractionResult.FAIL;
        }
        net.minecraft.world.phys.BlockHitResult authoritativeHit = new net.minecraft.world.phys.BlockHitResult(
                context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        BuilderOperationManager.Result result = BuilderOperationManager.executeSurface(serverPlayer, scepter,
                authoritativeHit);
        if (!result.accepted() && result.rejectionKey() != null) {
            player.displayClientMessage(Component.translatable(result.rejectionKey()), true);
        }
        if (result.shouldSynchronize()) BuilderNetworkBridge.sync(serverPlayer);
        return result.accepted() ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        return InteractionResultHolder.pass(stack);
    }

    private static InteractionResult bindAnchor(ServerPlayer player, ItemStack scepter,
                                                ResonantAnchorTarget target) {
        RealmwrightData.ensureId(scepter);
        if (!BuilderRuntime.settings().enabled()) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.disabled"), true);
            return InteractionResult.FAIL;
        }
        if (!target.mayUse(player)) {
            player.displayClientMessage(Component.translatable("message.mydimension.builder.anchor_forbidden"), true);
            return InteractionResult.FAIL;
        }
        boolean bound = RealmwrightData.bind(scepter, target.anchorId(), BuilderConfig.MAX_BOUND_ANCHORS.get());
        player.displayClientMessage(Component.translatable(bound
                ? "message.mydimension.builder.anchor_bound"
                : "message.mydimension.builder.anchor_already_bound"), true);
        BuilderNetworkBridge.sync(player);
        return InteractionResult.CONSUME;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity,
                              int slot, boolean selected) {
        if (!level.isClientSide()) RealmwrightData.ensureId(stack);
        super.inventoryTick(stack, level, entity, slot, selected);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        if (slot != EquipmentSlot.MAINHAND || !BuilderRuntime.settings().enabled()) {
            return super.getAttributeModifiers(slot, stack);
        }
        double amount = Math.max(0.0D, BuilderRuntime.settings().blockReach() - 4.5D);
        ImmutableMultimap.Builder<Attribute, AttributeModifier> result = ImmutableMultimap.builder();
        result.putAll(super.getAttributeModifiers(slot, stack));
        result.put(ForgeMod.BLOCK_REACH.get(), new AttributeModifier(REACH_MODIFIER_ID,
                "Realmwright block reach", amount, AttributeModifier.Operation.ADDITION));
        return result.build();
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true; // The scepter never extends or performs entity attacks.
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        BuilderMode mode = RealmwrightData.mode(stack);
        tooltip.add(Component.translatable("tooltip.mydimension.realmwright_scepter.mode",
                        Component.translatable(mode == BuilderMode.BUILD
                                ? "gui.mydimension.builder.mode.build" : "gui.mydimension.builder.mode.demolish"))
                .withStyle(mode == BuilderMode.BUILD ? ChatFormatting.AQUA : ChatFormatting.RED));
        tooltip.add(Component.translatable("tooltip.mydimension.realmwright_scepter.menu")
                .withStyle(ChatFormatting.GRAY));
        if (!BuilderRuntime.settings().enabled()) {
            tooltip.add(Component.translatable("tooltip.mydimension.realmwright_scepter.disabled")
                    .withStyle(ChatFormatting.RED));
        }
    }
}
