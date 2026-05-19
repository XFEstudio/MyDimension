package com.xfestudio.mydimension;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Locale;

public class MyDimensionEvents {
    private static final String IMPORTED_TO_ETHEREAL_MIND = "mydimension_imported_to_ethereal_mind";

    @SubscribeEvent
    public void preventMobsInEtherealMind(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND)
                && entity instanceof Mob
                && !entity.getPersistentData().getBoolean(IMPORTED_TO_ETHEREAL_MIND)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void sendMobToEtherealMind(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof LivingEntity target) || target instanceof ServerPlayer) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!stack.is(ModItems.RIFT.get())) {
            return;
        }

        ServerLevel targetLevel = player.getServer().getLevel(ModDimensions.ETHEREAL_MIND);
        if (targetLevel == null) {
            player.displayClientMessage(Component.translatable("message.mydimension.missing_dimension"), true);
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        target.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
        Entity moved = target.changeDimension(targetLevel);
        if (moved != null) {
            moved.getPersistentData().putBoolean(IMPORTED_TO_ETHEREAL_MIND, true);
            moved.moveTo(player.getX(), RiftItem.ETHEREAL_SURFACE_Y, player.getZ(), moved.getYRot(), moved.getXRot());
            targetLevel.playSound(null, BlockPos.containing(moved.position()), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            player.getCooldowns().addCooldown(stack.getItem(), 20);
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void protectPlayersFromOtherPlayerTeleports(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer sourcePlayer)) {
            return;
        }

        String input = event.getParseResults().getReader().getString().trim();
        String command = firstWord(input).toLowerCase(Locale.ROOT);
        if (!command.equals("tp") && !command.equals("teleport")) {
            return;
        }

        Collection<ServerPlayer> targets = parseExplicitPlayerTargets(source, input.substring(command.length()).trim());
        if (targets == null) {
            return;
        }

        for (ServerPlayer target : targets) {
            if (!target.getUUID().equals(sourcePlayer.getUUID()) && target.level().dimension().equals(ModDimensions.ETHEREAL_MIND)) {
                source.sendFailure(Component.translatable("message.mydimension.protected_tp", target.getDisplayName()));
                event.setCanceled(true);
                return;
            }
        }
    }

    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
    }

    private static Collection<ServerPlayer> parseExplicitPlayerTargets(CommandSourceStack source, String arguments) {
        if (arguments.isBlank()) {
            return null;
        }

        try {
            StringReader reader = new StringReader(arguments);
            EntitySelector selector = EntityArgument.players().parse(reader);
            return selector.findPlayers(source);
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }
}
